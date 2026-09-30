use crate::managers::history::HistoryManager;
use crate::settings::AppSettings;
use anyhow::{anyhow, bail, Context, Result};
use reqwest::multipart::{Form, Part};
use reqwest::StatusCode;
use serde::Deserialize;
use std::io::Cursor;
use std::time::Duration;

#[derive(Deserialize)]
struct TranscriptionResponse {
    text: String,
}

const DEFAULT_GATEWAY_URL: &str = "https://uhqgd4qlmep8pnndo8j893bc.187.52.126.169.sslip.io";

pub async fn transcribe(
    settings: &AppSettings,
    samples: &[f32],
    history: &HistoryManager,
    screen_context: &str,
) -> Result<String> {
    let gateway_url =
        std::env::var("CLOUD_GATEWAY_URL").unwrap_or_else(|_| DEFAULT_GATEWAY_URL.to_string());
    let endpoint = normalize_endpoint(&gateway_url)?;

    let wav = encode_wav(samples)?;
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(135))
        .build()?;

    for force_refresh in [false, true] {
        let token = history
            .cloud_access_token(force_refresh)
            .await
            .context("Could not authenticate cloud transcription")?;
        let audio = Part::bytes(wav.clone())
            .file_name("dictation.wav")
            .mime_str("audio/wav")?;
        let mut form = Form::new().part("file", audio);
        if settings.selected_language != "auto" && !settings.selected_language.trim().is_empty() {
            form = form.text("language", settings.selected_language.clone());
        }
        if !settings.custom_words.is_empty() {
            form = form.text("prompt", settings.custom_words.join(", "));
        }
        let learned_vocabulary: &[crate::settings::PersonalVocabularyEntry] =
            if settings.learning_from_edits_enabled {
                &settings.learned_vocabulary
            } else {
                &[]
            };
        if !settings.personal_vocabulary.is_empty()
            || !learned_vocabulary.is_empty()
            || !screen_context.is_empty()
            || settings.filter_profanity
        {
            form = form.text(
                "cleanup_options",
                serde_json::json!({
                    "vocabulary": &settings.personal_vocabulary,
                    "learned_vocabulary": learned_vocabulary,
                    "context": screen_context,
                    "filter_profanity": settings.filter_profanity
                })
                .to_string(),
            );
        }

        let response = client
            .post(endpoint.clone())
            .bearer_auth(token)
            .multipart(form)
            .send()
            .await
            .context("Could not connect to the shared transcription server")?;
        let status = response.status();
        if status == StatusCode::UNAUTHORIZED && !force_refresh {
            continue;
        }
        if !status.is_success() {
            bail!(
                "Shared transcription server returned HTTP {}",
                status.as_u16()
            );
        }
        let result: TranscriptionResponse = response
            .json()
            .await
            .context("Shared transcription server returned invalid JSON")?;
        let text = result.text.trim().to_string();
        if text.is_empty() {
            bail!("Shared transcription server returned an empty transcription");
        }
        return Ok(text);
    }
    bail!("Cloud transcription authorization failed")
}

fn normalize_endpoint(value: &str) -> Result<reqwest::Url> {
    let trimmed = value.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        bail!("The shared server URL is missing");
    }
    let parsed = reqwest::Url::parse(trimmed).context("The shared server URL is invalid")?;
    let local_development = matches!(parsed.host_str(), Some("localhost" | "127.0.0.1"));
    if parsed.scheme() != "https" && !(local_development && parsed.scheme() == "http") {
        bail!("The shared server URL must use HTTPS");
    }
    let endpoint = if parsed.path().ends_with("/v1/audio/transcriptions") {
        trimmed.to_string()
    } else {
        format!("{trimmed}/v1/audio/transcriptions")
    };
    reqwest::Url::parse(&endpoint).map_err(|error| anyhow!(error))
}

fn encode_wav(samples: &[f32]) -> Result<Vec<u8>> {
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: 16_000,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut cursor = Cursor::new(Vec::with_capacity(44 + samples.len() * 2));
    {
        let mut writer = hound::WavWriter::new(&mut cursor, spec)?;
        for sample in samples {
            let value = (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16;
            writer.write_sample(value)?;
        }
        writer.finalize()?;
    }
    Ok(cursor.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_https_base_url() {
        assert_eq!(
            normalize_endpoint("https://speech.example.com/")
                .unwrap()
                .as_str(),
            "https://speech.example.com/v1/audio/transcriptions"
        );
    }

    #[test]
    fn rejects_insecure_public_url() {
        assert!(normalize_endpoint("http://speech.example.com").is_err());
    }

    #[test]
    fn creates_valid_wav() {
        let wav = encode_wav(&[-1.0, 0.0, 1.0]).unwrap();
        assert_eq!(&wav[0..4], b"RIFF");
        assert_eq!(&wav[8..12], b"WAVE");
        assert_eq!(&wav[36..40], b"data");
        assert_eq!(wav.len(), 50);
    }
}
