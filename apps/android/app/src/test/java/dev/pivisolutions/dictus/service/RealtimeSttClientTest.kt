package dev.pivisolutions.dictus.service

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RealtimeSttClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test fun `streams PCM and revises live text before returning one final result`() = runBlocking {
        val receivedAudio = AtomicReference<ByteArray>()
        val start = AtomicReference<JSONObject>()
        enqueueSuccess(receivedAudio, start)
        val updates = Channel<String>(Channel.UNLIMITED)
        val session = RealtimeSttClient(localUrl(), { "session-token" })
            .start(scope, "en") { updates.trySend(it) }
        try {
            val audio = ByteArray(6400) { (it % 127).toByte() }
            session.offerAudio(audio)
            withTimeout(5000) {
                assertEquals("Hello wor", updates.receive())
                assertEquals("Hello world", updates.receive())
                assertEquals("Hello world.", updates.receive())
            }
            assertEquals("Hello world.", session.finish())
            assertArrayEquals(audio, receivedAudio.get())
            assertEquals("en", start.get().getString("language"))
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/realtime/transcription", request.path)
            assertEquals("Bearer session-token", request.getHeader("Authorization"))
        } finally {
            session.cancel()
        }
    }

    @Test fun `expired authentication refreshes once without discarding queued audio`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"error","code":"unauthorized"}""")
                webSocket.close(4401, null)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        val receivedAudio = AtomicReference<ByteArray>()
        enqueueSuccess(receivedAudio)
        val refreshes = mutableListOf<Boolean>()
        val session = RealtimeSttClient(localUrl(), { refresh ->
            refreshes += refresh
            if (refresh) "new-token" else "old-token"
        }).start(scope, null) {}
        try {
            val audio = ByteArray(6400)
            session.offerAudio(audio)
            assertEquals("Hello world.", session.finish())
            assertEquals(listOf(false, true), refreshes)
            assertArrayEquals(audio, receivedAudio.get())
            assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))
        } finally {
            session.cancel()
        }
    }

    @Test fun `cleanup preferences are sent and explicit discard finishes without retry`() = runBlocking {
        val setup = CompletableDeferred<JSONObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                when (message.getString("type")) {
                    "start" -> { setup.complete(message); webSocket.send("""{"type":"ready"}""") }
                    "finish" -> webSocket.send("""{"type":"final","text":"","discarded":true,"cleaned":true}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        }))
        val cleanup = JSONObject().put("supports_discard", true).put("context", "Working with ClickUp")
        val session = RealtimeSttClient(localUrl(), { "token" }).start(scope, "en", cleanup) {}
        try {
            assertEquals("", session.finish())
            val sent = withTimeout(5000) { setup.await() }.getJSONObject("cleanup")
            assertTrue(sent.getBoolean("supports_discard"))
            assertEquals("Working with ClickUp", sent.getString("context"))
            assertEquals(1, server.requestCount)
        } finally { session.cancel() }
    }

    @Test fun `provider failure after ready rejects partial text as a final result`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).getString("type") == "start") {
                    webSocket.send("""{"type":"ready"}""")
                    webSocket.send("""{"type":"partial","text":"Unfinished"}""")
                    webSocket.send("""{"type":"error","code":"provider_limit"}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
        val session = RealtimeSttClient(localUrl(), { "token" }).start(scope, null) {}
        try {
            val failure = runCatching { session.finish() }.exceptionOrNull()
            assertEquals("provider_limit", (failure as RealtimeSttException).code)
            assertEquals(1, server.requestCount)
        } finally {
            session.cancel()
        }
    }

    @Test fun `cancel during authentication does not start a socket or upload audio`() = runBlocking {
        val authEntered = CompletableDeferred<Unit>()
        val token = CompletableDeferred<String>()
        val session = RealtimeSttClient(localUrl(), {
            authEntered.complete(Unit)
            token.await()
        }).start(scope, null) {}
        withTimeout(5000) { authEntered.await() }
        session.offerAudio(ByteArray(6400))
        session.cancel()
        assertTrue(runCatching { session.finish() }.exceptionOrNull() is CancellationException)
        assertEquals(0, server.requestCount)
    }

    @Test fun `slow authentication bounds queued audio and reports fallback reason`() = runBlocking {
        val token = CompletableDeferred<String>()
        val session = RealtimeSttClient(localUrl(), { token.await() }).start(scope, null) {}
        try {
            repeat(51) { session.offerAudio(ByteArray(6400)) }
            val failure = runCatching { session.finish() }.exceptionOrNull()
            assertEquals("connection_too_slow", (failure as RealtimeSttException).code)
            assertEquals(0, server.requestCount)
        } finally {
            session.cancel()
        }
    }

    private fun enqueueSuccess(
        audio: AtomicReference<ByteArray>,
        start: AtomicReference<JSONObject> = AtomicReference(),
    ) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                when (message.getString("type")) {
                    "start" -> {
                        start.set(message)
                        webSocket.send("""{"type":"ready","model":"scribe_v2_realtime"}""")
                    }
                    "finish" -> webSocket.send("""{"type":"final","text":"Hello world."}""")
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                audio.set(bytes.toByteArray())
                webSocket.send("""{"type":"partial","text":"Hello wor"}""")
                webSocket.send("""{"type":"partial","text":"Hello world"}""")
                webSocket.send("""{"type":"committed","text":"Hello world."}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }))
    }

    private fun localUrl() = server.url("/").newBuilder().host("127.0.0.1").build().toString()
}
