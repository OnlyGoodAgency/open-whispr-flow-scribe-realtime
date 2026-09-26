package dev.pivisolutions.dictus.service

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber

/** One recording, one bounded audio queue and one authenticated gateway socket. */
internal class RealtimeSttClient(
    private val gatewayUrl: String,
    private val accessToken: suspend (Boolean) -> String,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    fun start(scope: CoroutineScope, language: String?, onText: (String) -> Unit): Session =
        Session(scope, language, onText).also { it.start() }

    internal inner class Session(
        private val scope: CoroutineScope,
        private val language: String?,
        private val onText: (String) -> Unit,
    ) {
        private val chunks = Channel<ByteArray>(50)
        private val result = CompletableDeferred<String>()
        private var socket: WebSocket? = null
        private var sender: Job? = null
        @Volatile private var cancelled = false
        private val started = System.nanoTime()

        fun start() {
            sender = scope.launch {
                try {
                    var active: WebSocket? = null
                    for (attempt in 0..1) {
                        val token = try {
                            accessToken(attempt == 1)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            throw CloudAuthenticationException(failure)
                        }
                        val ready = CompletableDeferred<Unit>()
                        val text = RealtimeTranscript()
                        val listener = listener(ready, text)
                        active = httpClient.newWebSocket(
                            Request.Builder()
                                .url(normalizeEndpoint(gatewayUrl))
                                .header("Authorization", "Bearer $token")
                                .build(),
                            listener,
                        )
                        socket = active
                        try {
                            withTimeout(20_000L) { ready.await() }
                            break
                        } catch (failure: RealtimeSttException) {
                            active.cancel()
                            if (failure.code != "unauthorized" || attempt == 1) throw failure
                            Timber.tag("RealtimeDictation").i("refreshing_session")
                        }
                    }
                    val connected = requireNotNull(active)
                    for (audio in chunks) {
                        if (result.isCompleted) break
                        // OkHttp owns a second outbound queue; bound it too so a slow
                        // network never accumulates an entire recording in the socket.
                        if (connected.queueSize() > 160_000L || !connected.send(audio.toByteString())) {
                            throw RealtimeSttException("connection_too_slow")
                        }
                    }
                    if (!cancelled && !result.isCompleted && !connected.send("{\"type\":\"finish\"}")) {
                        throw RealtimeSttException("connection_lost")
                    }
                } catch (timeout: TimeoutCancellationException) {
                    fail(RealtimeSttException("connection_timeout"))
                } catch (cancellation: CancellationException) {
                    result.cancel(cancellation)
                    socket?.cancel()
                    throw cancellation
                } catch (failure: Exception) {
                    fail(failure)
                }
            }
        }

        fun offerAudio(audio: ByteArray) {
            if (!result.isCompleted && !chunks.trySend(audio).isSuccess) {
                fail(RealtimeSttException("connection_too_slow"))
            }
        }

        suspend fun finish(): String {
            chunks.close()
            return try {
                withTimeout(25_000L) { result.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw RealtimeSttException("finalization_timeout")
            } finally {
                sender?.cancel()
                socket?.cancel()
            }
        }

        fun cancel() {
            cancelled = true
            chunks.cancel()
            result.cancel()
            sender?.cancel()
            socket?.cancel()
        }

        private fun fail(failure: Exception) {
            if (result.completeExceptionally(failure)) {
                Timber.tag("RealtimeDictation").w(
                    "failed code=%s elapsed_ms=%d",
                    (failure as? RealtimeSttException)?.code ?: failure::class.java.simpleName,
                    elapsedMs(),
                )
            }
            chunks.cancel()
            socket?.cancel()
        }

        private fun listener(ready: CompletableDeferred<Unit>, text: RealtimeTranscript) =
            object : WebSocketListener() {
                private var firstPartial = true

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(JSONObject().put("type", "start").apply {
                        if (!language.isNullOrBlank() && language != "auto") put("language", language)
                    }.toString())
                }

                override fun onMessage(webSocket: WebSocket, payload: String) {
                    if (cancelled || result.isCompleted) return
                    try {
                        val event = JSONObject(payload)
                        when (event.getString("type")) {
                            "ready" -> {
                                Timber.tag("RealtimeDictation").i("ready connect_ms=%d", elapsedMs())
                                ready.complete(Unit)
                            }
                            "partial" -> {
                                if (firstPartial && event.optString("text").isNotBlank()) {
                                    Timber.tag("RealtimeDictation").i("first_partial elapsed_ms=%d", elapsedMs())
                                    firstPartial = false
                                }
                                onText(text.partial(event.optString("text")))
                            }
                            "committed" -> onText(text.commit(event.optString("text")))
                            "final" -> {
                                val final = event.optString("text").trim()
                                if (final.isBlank()) throw RealtimeSttException("empty_transcription")
                                result.complete(final)
                                Timber.tag("RealtimeDictation").i("completed total_ms=%d", elapsedMs())
                                webSocket.close(1000, null)
                            }
                            "error" -> {
                                val failure = RealtimeSttException(event.optString("code", "provider_failed"))
                                if (!ready.isCompleted) ready.completeExceptionally(failure)
                                else fail(failure)
                            }
                        }
                    } catch (failure: Exception) {
                        if (!ready.isCompleted) ready.completeExceptionally(failure)
                        fail(failure)
                    }
                }

                override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
                    val error = RealtimeSttException("connection_lost")
                    if (!ready.isCompleted || ready.isCancelled) ready.completeExceptionally(error)
                    else fail(error)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (result.isCompleted || cancelled) return
                    val error = RealtimeSttException("connection_lost")
                    if (!ready.isCompleted || ready.isCancelled) ready.completeExceptionally(error)
                    else fail(error)
                }
            }

        private fun elapsedMs(): Long = (System.nanoTime() - started) / 1_000_000L
    }

    companion object {
        internal fun normalizeEndpoint(url: String): String =
            RemoteSttClient.normalizeEndpoint(url).removeSuffix("/v1/audio/transcriptions") +
                "/realtime/transcription"
    }
}

internal class RealtimeSttException(val code: String) : IOException("Realtime transcription failed: $code")

/** Interim text replaces only the current phrase; committed phrases are retained once. */
internal class RealtimeTranscript {
    private val committed = mutableListOf<String>()
    private var pending = ""

    fun partial(value: String): String {
        pending = value.trim()
        return display()
    }

    fun commit(value: String): String {
        if (value.isNotBlank()) committed += value.trim()
        pending = ""
        return display()
    }

    private fun display(): String = (committed + pending).filter { it.isNotBlank() }.joinToString(" ")
}
