package dev.pivisolutions.dictus.history

import android.content.Context
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

data class SupabaseClientConfig(
    val url: String,
    val publishableKey: String,
) {
    val isConfigured: Boolean
        get() = (
            url.startsWith("https://") ||
                url.startsWith("http://localhost:") ||
                url.startsWith("http://127.0.0.1:")
            ) && publishableKey.isNotBlank()
}

internal data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresAtEpochSeconds: Long,
)

internal interface SupabaseSessionStore {
    fun loadSession(): SupabaseSession?
    fun saveSession(session: SupabaseSession)
    fun clearSession()
    fun deviceId(): String
}

internal class AndroidSupabaseSessionStore(
    context: Context,
) : SupabaseSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun loadSession(): SupabaseSession? {
        val accessToken = preferences.getString(ACCESS_TOKEN, null) ?: return null
        val refreshToken = preferences.getString(REFRESH_TOKEN, null) ?: return null
        val userId = preferences.getString(USER_ID, null) ?: return null
        val expiresAt = preferences.getLong(EXPIRES_AT, 0L)
        if (expiresAt <= 0L) return null
        return SupabaseSession(accessToken, refreshToken, userId, expiresAt)
    }

    override fun saveSession(session: SupabaseSession) {
        preferences.edit()
            .putString(ACCESS_TOKEN, session.accessToken)
            .putString(REFRESH_TOKEN, session.refreshToken)
            .putString(USER_ID, session.userId)
            .putLong(EXPIRES_AT, session.expiresAtEpochSeconds)
            .apply()
    }

    override fun clearSession() {
        preferences.edit()
            .remove(ACCESS_TOKEN)
            .remove(REFRESH_TOKEN)
            .remove(USER_ID)
            .remove(EXPIRES_AT)
            .apply()
    }

    override fun deviceId(): String {
        preferences.getString(DEVICE_ID, null)?.let { return it }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(DEVICE_ID, generated).apply()
        return generated
    }

    private companion object {
        const val PREFERENCES_NAME = "supabase-history-sync"
        const val ACCESS_TOKEN = "access-token"
        const val REFRESH_TOKEN = "refresh-token"
        const val USER_ID = "user-id"
        const val EXPIRES_AT = "expires-at"
        const val DEVICE_ID = "device-id"
    }
}

/**
 * Authenticates each installation as an anonymous Supabase user and mirrors local
 * history rows through the Data API. The publishable key is safe in a client;
 * row ownership is enforced by the database's auth.uid()-based RLS policies.
 */
class SupabaseHistorySyncClient internal constructor(
    private val config: SupabaseClientConfig,
    private val sessionStore: SupabaseSessionStore,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val authMutex = Mutex()

    val isConfigured: Boolean
        get() = config.isConfigured

    /** Shares the installation's history session with cloud dictation; never log this token. */
    suspend fun accessToken(forceRefresh: Boolean = false): String {
        check(isConfigured) { "Cloud authentication is not configured" }
        return validSession(forceRefresh).accessToken
    }

    suspend fun upsert(entry: TranscriptionHistoryEntry) {
        if (!isConfigured || entry.id <= 0L || entry.text.isBlank()) return

        try {
            upsertWithSession(entry, validSession())
        } catch (expired: SessionRejectedException) {
            upsertWithSession(entry, validSession(forceRefresh = true))
        }
    }

    private suspend fun upsertWithSession(
        entry: TranscriptionHistoryEntry,
        session: SupabaseSession,
    ) = withContext(Dispatchers.IO) {
        val profile = JSONObject().put("id", session.userId)
        executeJson(
            path = "/rest/v1/profiles?on_conflict=id",
            body = profile,
            accessToken = session.accessToken,
            prefer = "resolution=merge-duplicates,return=minimal",
        )

        val createdAt = Instant.ofEpochMilli(entry.createdAtEpochMillis).toString()
        val history = JSONObject()
            .put("user_id", session.userId)
            .put("client_entry_id", "android-room-${entry.id}")
            .put("device_id", sessionStore.deviceId())
            .put("platform", "android")
            .put("transcription_text", entry.text)
            .put("post_process_requested", false)
            .put("requested_language", entry.requestedLanguage)
            .put("duration_ms", entry.durationMillis.coerceAtLeast(0L))
            .put("model_key", entry.modelKey)
            .put("provider", entry.provider.lowercase())
            .put("created_at", createdAt)
            .put("updated_at", createdAt)
        executeJson(
            path = "/rest/v1/transcription_history" +
                "?on_conflict=user_id,device_id,client_entry_id",
            body = history,
            accessToken = session.accessToken,
            prefer = "resolution=merge-duplicates,return=minimal",
        )
    }

    private suspend fun validSession(forceRefresh: Boolean = false): SupabaseSession = authMutex.withLock {
        val current = sessionStore.loadSession()
        if (!forceRefresh && current != null && current.expiresAtEpochSeconds > nowEpochSeconds() + EXPIRY_SKEW_SECONDS) {
            return@withLock current
        }

        if (current != null) {
            // Preserve the existing identity on network errors or a rejected refresh.
            // Creating a replacement anonymous user would strand the old cloud history.
            return@withLock requestSession(
                path = "/auth/v1/token?grant_type=refresh_token",
                body = JSONObject().put("refresh_token", current.refreshToken),
            )
        }

        requestSession(path = "/auth/v1/signup", body = JSONObject())
    }

    private suspend fun requestSession(path: String, body: JSONObject): SupabaseSession =
        withContext(Dispatchers.IO) {
            val response = executeJson(path, body, accessToken = null, prefer = null)
            val json = JSONObject(response)
            val user = json.optJSONObject("user")
                ?: throw IOException("Supabase Auth response did not contain a user")
            val session = SupabaseSession(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                userId = user.getString("id"),
                expiresAtEpochSeconds = nowEpochSeconds() + json.optLong("expires_in", 3_600L),
            )
            sessionStore.saveSession(session)
            session
        }

    private fun executeJson(
        path: String,
        body: JSONObject,
        accessToken: String?,
        prefer: String?,
    ): String {
        val request = Request.Builder()
            .url(config.url.trimEnd('/') + path)
            .header("apikey", config.publishableKey)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .apply {
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (prefer != null) header("Prefer", prefer)
            }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SessionRejectedException()
            if (!response.isSuccessful) {
                throw IOException("Supabase request failed with HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    private class SessionRejectedException : IOException()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val EXPIRY_SKEW_SECONDS = 60L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/** Runs best-effort synchronization without delaying dictation or logging private text. */
class TranscriptionHistorySyncCoordinator(
    private val repository: TranscriptionHistoryRepository,
    private val client: SupabaseHistorySyncClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun start() {
        if (!client.isConfigured) return
        scope.launch {
            try {
                repository.getAll().forEach { client.upsert(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Timber.w("Supabase history catch-up failed (%s)", failure::class.java.simpleName)
            }
        }
    }

    fun enqueue(entry: TranscriptionHistoryEntry) {
        if (!client.isConfigured) return
        scope.launch {
            try {
                client.upsert(entry)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Timber.w("Supabase history sync failed (%s)", failure::class.java.simpleName)
            }
        }
    }
}
