package dev.pivisolutions.dictus.service

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Uses the fixed gateway and the installation's short-lived Supabase access token. */
internal class CloudSttClient(
    private val gatewayUrl: String,
    private val accessToken: suspend (forceRefresh: Boolean) -> String,
    private val remoteClient: RemoteSttClient = RemoteSttClient(),
) {
    suspend fun transcribe(samples: FloatArray, language: String?): String {
        val started = System.nanoTime()
        Timber.tag("CloudDictation").i("started audio_ms=%d", samples.size * 1_000L / 16_000)
        try {
            var bearerToken = token(forceRefresh = false)
            for (attempt in 0..1) {
                val requestStarted = System.nanoTime()
                try {
                    val result = remoteClient.transcribe(
                        samples,
                        language,
                        RemoteSttConfig(url = gatewayUrl, apiKey = bearerToken, model = null),
                    )
                    Timber.tag("CloudDictation").i(
                        "completed status=200 request_ms=%d total_ms=%d",
                        elapsedMs(requestStarted), elapsedMs(started),
                    )
                    return result
                } catch (failure: RemoteSttException) {
                    Timber.tag("CloudDictation").w(
                        "gateway_failed status=%s request_ms=%d",
                        failure.statusCode?.toString() ?: "invalid_response", elapsedMs(requestStarted),
                    )
                    if (failure.statusCode != 401 || attempt == 1) throw failure
                    bearerToken = token(forceRefresh = true)
                }
            }
            error("Cloud transcription retry exhausted")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Exceptions can contain URLs or provider bodies. Log only the type and timing.
            Timber.tag("CloudDictation").w(
                "failed type=%s total_ms=%d", failure::class.java.simpleName, elapsedMs(started),
            )
            throw failure
        }
    }

    private suspend fun token(forceRefresh: Boolean): String {
        val started = System.nanoTime()
        try {
            return accessToken(forceRefresh).also {
                Timber.tag("CloudDictation").i(
                    "auth_ready refresh=%s duration_ms=%d", forceRefresh, elapsedMs(started),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Timber.tag("CloudDictation").w("auth_failed duration_ms=%d", elapsedMs(started))
            throw CloudAuthenticationException(failure)
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}

internal class CloudAuthenticationException(cause: Throwable) :
    Exception("Could not authenticate cloud dictation", cause)
