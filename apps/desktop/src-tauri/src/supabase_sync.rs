use crate::managers::history::HistoryEntry;
use anyhow::{Context, Result};
use chrono::{DateTime, Utc};
use log::warn;
use reqwest::{Client, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tauri::AppHandle;
use tokio::sync::Mutex;
use uuid::Uuid;

const DEFAULT_SUPABASE_URL: &str = "https://ytsxbnfrxkycmwnltqse.supabase.co";
const DEFAULT_SUPABASE_PUBLISHABLE_KEY: &str = "sb_publishable_MkmpxjZHkvi3sFedaQ7hgQ_tqeEUCWg";
const EXPIRY_SKEW_SECONDS: i64 = 60;

#[derive(Clone)]
struct SupabaseConfig {
    url: String,
    publishable_key: String,
}

impl SupabaseConfig {
    fn from_environment() -> Option<Self> {
        let url = std::env::var("SUPABASE_URL")
            .unwrap_or_else(|_| DEFAULT_SUPABASE_URL.to_string())
            .trim_end_matches('/')
            .to_string();
        let publishable_key = std::env::var("SUPABASE_PUBLISHABLE_KEY")
            .unwrap_or_else(|_| DEFAULT_SUPABASE_PUBLISHABLE_KEY.to_string());
        if !url.starts_with("https://") || publishable_key.trim().is_empty() {
            return None;
        }
        Some(Self {
            url,
            publishable_key,
        })
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
struct PersistedSyncState {
    device_id: String,
    access_token: Option<String>,
    refresh_token: Option<String>,
    user_id: Option<String>,
    expires_at: Option<i64>,
}

#[derive(Clone, Debug, Deserialize)]
struct AuthUser {
    id: String,
}

#[derive(Clone, Debug, Deserialize)]
struct AuthSessionResponse {
    access_token: String,
    refresh_token: String,
    expires_in: i64,
    user: AuthUser,
}

#[derive(Clone)]
struct ActiveSession {
    access_token: String,
    user_id: String,
}

pub struct SupabaseHistorySync {
    client: Client,
    config: SupabaseConfig,
    state_path: PathBuf,
    state: Mutex<PersistedSyncState>,
}

impl SupabaseHistorySync {
    pub fn new(app_handle: &AppHandle) -> Result<Option<Arc<Self>>> {
        let Some(config) = SupabaseConfig::from_environment() else {
            return Ok(None);
        };
        let state_path = crate::portable::app_data_dir(app_handle)?.join("supabase-session.json");
        let mut state = match fs::read_to_string(&state_path) {
            Ok(serialized) => serde_json::from_str(&serialized).unwrap_or_default(),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                PersistedSyncState::default()
            }
            Err(error) => return Err(error).context("Failed to read Supabase session state"),
        };
        if state.device_id.is_empty() {
            state.device_id = Uuid::new_v4().to_string();
        }

        let client = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .build()
            .context("Failed to build Supabase HTTP client")?;
        let sync = Arc::new(Self {
            client,
            config,
            state_path,
            state: Mutex::new(state),
        });

        // Persist the stable device ID before the first network request.
        sync.persist_state_blocking()?;
        Ok(Some(sync))
    }

    pub fn enqueue(self: &Arc<Self>, entry: HistoryEntry) {
        let sync = self.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(error) = sync.upsert_entry(&entry).await {
                // Do not log the error body or entry because either can contain dictated text.
                warn!("Supabase history sync failed: {}", error_type(&error));
            }
        });
    }

    pub fn enqueue_all(self: &Arc<Self>, entries: Vec<HistoryEntry>) {
        let sync = self.clone();
        tauri::async_runtime::spawn(async move {
            for entry in entries {
                if entry.transcription_text.is_empty() {
                    continue;
                }
                if let Err(error) = sync.upsert_entry(&entry).await {
                    warn!("Supabase history catch-up failed: {}", error_type(&error));
                    break;
                }
            }
        });
    }

    async fn upsert_entry(&self, entry: &HistoryEntry) -> Result<()> {
        if entry.id <= 0 || entry.transcription_text.trim().is_empty() {
            return Ok(());
        }

        let session = self.valid_session(false).await?;
        match self.upsert_entry_with_session(entry, &session).await {
            Err(error) if is_session_rejection(&error) => {
                self.clear_session().await?;
                let replacement = self.valid_session(false).await?;
                self.upsert_entry_with_session(entry, &replacement).await
            }
            result => result,
        }
    }

    async fn upsert_entry_with_session(
        &self,
        entry: &HistoryEntry,
        session: &ActiveSession,
    ) -> Result<()> {
        self.postgrest_upsert(
            "profiles?on_conflict=id",
            &session.access_token,
            &json!({ "id": session.user_id }),
        )
        .await?;

        let created_at = DateTime::<Utc>::from_timestamp(entry.timestamp, 0)
            .unwrap_or_else(Utc::now)
            .to_rfc3339();
        let final_text = entry
            .post_processed_text
            .as_deref()
            .unwrap_or(&entry.transcription_text);
        let payload = json!({
            "user_id": session.user_id,
            "client_entry_id": format!("desktop-sqlite-{}", entry.id),
            "device_id": self.state.lock().await.device_id.clone(),
            "platform": current_platform(),
            "transcription_text": entry.transcription_text,
            "post_processed_text": final_text,
            "post_process_prompt": entry.post_process_prompt,
            "post_process_requested": entry.post_process_requested,
            "requested_language": "auto",
            "duration_ms": 0,
            "model_key": "",
            "provider": "desktop",
            "created_at": created_at,
            "updated_at": Utc::now().to_rfc3339(),
        });
        self.postgrest_upsert(
            "transcription_history?on_conflict=user_id,device_id,client_entry_id",
            &session.access_token,
            &payload,
        )
        .await
    }

    async fn postgrest_upsert(
        &self,
        resource: &str,
        access_token: &str,
        payload: &serde_json::Value,
    ) -> Result<()> {
        let response = self
            .client
            .post(format!("{}/rest/v1/{}", self.config.url, resource))
            .header("apikey", &self.config.publishable_key)
            .bearer_auth(access_token)
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .json(payload)
            .send()
            .await
            .context("Supabase Data API request failed")?;
        if response.status() == StatusCode::UNAUTHORIZED
            || response.status() == StatusCode::FORBIDDEN
        {
            return Err(anyhow::anyhow!("Supabase session rejected"));
        }
        response
            .error_for_status()
            .context("Supabase Data API returned an error")?;
        Ok(())
    }

    /// Return a short-lived access token for the shared transcription gateway.
    /// A gateway 401 can request one forced refresh using the stored refresh token.
    pub async fn access_token(&self, force_refresh: bool) -> Result<String> {
        Ok(self.valid_session(force_refresh).await?.access_token)
    }

    async fn valid_session(&self, force_refresh: bool) -> Result<ActiveSession> {
        let mut state = self.state.lock().await;
        let now = Utc::now().timestamp();
        if !force_refresh && state.expires_at.unwrap_or_default() > now + EXPIRY_SKEW_SECONDS {
            if let (Some(access_token), Some(user_id)) =
                (state.access_token.clone(), state.user_id.clone())
            {
                return Ok(ActiveSession {
                    access_token,
                    user_id,
                });
            }
        }

        let session = if let Some(refresh_token) = state.refresh_token.clone() {
            match self.refresh_session(&refresh_token).await {
                Ok(session) => session,
                Err(_) => self.create_anonymous_session().await?,
            }
        } else {
            self.create_anonymous_session().await?
        };
        apply_session(&mut state, &session, now);
        self.persist_locked_state(&state)?;
        Ok(ActiveSession {
            access_token: session.access_token,
            user_id: session.user.id,
        })
    }

    async fn create_anonymous_session(&self) -> Result<AuthSessionResponse> {
        self.auth_request("signup", json!({})).await
    }

    async fn refresh_session(&self, refresh_token: &str) -> Result<AuthSessionResponse> {
        self.auth_request(
            "token?grant_type=refresh_token",
            json!({ "refresh_token": refresh_token }),
        )
        .await
    }

    async fn auth_request(
        &self,
        resource: &str,
        payload: serde_json::Value,
    ) -> Result<AuthSessionResponse> {
        self.client
            .post(format!("{}/auth/v1/{}", self.config.url, resource))
            .header("apikey", &self.config.publishable_key)
            .json(&payload)
            .send()
            .await
            .context("Supabase Auth request failed")?
            .error_for_status()
            .context("Supabase Auth returned an error")?
            .json::<AuthSessionResponse>()
            .await
            .context("Supabase Auth response was invalid")
    }

    async fn clear_session(&self) -> Result<()> {
        let mut state = self.state.lock().await;
        state.access_token = None;
        state.refresh_token = None;
        state.user_id = None;
        state.expires_at = None;
        self.persist_locked_state(&state)
    }

    fn persist_state_blocking(&self) -> Result<()> {
        let state = self
            .state
            .try_lock()
            .map_err(|_| anyhow::anyhow!("Supabase session state is busy"))?;
        self.persist_locked_state(&state)
    }

    fn persist_locked_state(&self, state: &PersistedSyncState) -> Result<()> {
        if let Some(parent) = self.state_path.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&self.state_path, serde_json::to_vec(state)?)?;
        Ok(())
    }
}

fn apply_session(state: &mut PersistedSyncState, session: &AuthSessionResponse, now: i64) {
    state.access_token = Some(session.access_token.clone());
    state.refresh_token = Some(session.refresh_token.clone());
    state.user_id = Some(session.user.id.clone());
    state.expires_at = Some(now + session.expires_in);
}

fn is_session_rejection(error: &anyhow::Error) -> bool {
    error.to_string().contains("Supabase session rejected")
}

fn error_type(error: &anyhow::Error) -> &'static str {
    if is_session_rejection(error) {
        "session-rejected"
    } else {
        "request-error"
    }
}

#[cfg(target_os = "windows")]
fn current_platform() -> &'static str {
    "windows"
}

#[cfg(target_os = "macos")]
fn current_platform() -> &'static str {
    "macos"
}

#[cfg(target_os = "linux")]
fn current_platform() -> &'static str {
    "linux"
}

#[cfg(not(any(target_os = "windows", target_os = "macos", target_os = "linux")))]
fn current_platform() -> &'static str {
    "unknown"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn applying_session_preserves_device_identity() {
        let mut state = PersistedSyncState {
            device_id: "device-1".to_string(),
            ..Default::default()
        };
        let session = AuthSessionResponse {
            access_token: "access".to_string(),
            refresh_token: "refresh".to_string(),
            expires_in: 3_600,
            user: AuthUser {
                id: "user-1".to_string(),
            },
        };
        apply_session(&mut state, &session, 1_000);
        assert_eq!(state.device_id, "device-1");
        assert_eq!(state.user_id.as_deref(), Some("user-1"));
        assert_eq!(state.expires_at, Some(4_600));
    }
}
