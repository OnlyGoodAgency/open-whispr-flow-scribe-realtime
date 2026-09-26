package dev.pivisolutions.dictus.service

import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudSttClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `sends session bearer and lets the gateway choose the model`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"Hello from cloud"}"""))
        val refreshes = mutableListOf<Boolean>()
        val client = CloudSttClient(localUrl(), { refresh ->
            refreshes += refresh
            "session-access"
        })

        assertEquals("Hello from cloud", client.transcribe(floatArrayOf(0f), "en"))
        val request = nextRequest()
        assertEquals("Bearer session-access", request.getHeader("Authorization"))
        assertEquals("/v1/audio/transcriptions", request.path)
        val body = request.body.readUtf8()
        assertFalse(body.contains("name=\"model\""))
        assertTrue(body.contains("name=\"language\""))
        assertEquals(listOf(false), refreshes)
    }

    @Test
    fun `unauthorized request refreshes once and retries with new token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"text":"Recovered"}"""))
        val refreshes = mutableListOf<Boolean>()
        val client = CloudSttClient(localUrl(), { refresh ->
            refreshes += refresh
            if (refresh) "new-token" else "old-token"
        })

        assertEquals("Recovered", client.transcribe(floatArrayOf(0f), null))
        assertEquals("Bearer old-token", nextRequest().getHeader("Authorization"))
        assertEquals("Bearer new-token", nextRequest().getHeader("Authorization"))
        assertEquals(listOf(false, true), refreshes)
    }

    @Test
    fun `repeated unauthorized response stops after one refresh`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        val refreshes = mutableListOf<Boolean>()
        val client = CloudSttClient(localUrl(), { refresh ->
            refreshes += refresh
            "token"
        })

        val failure = runCatching { client.transcribe(floatArrayOf(0f), null) }.exceptionOrNull()
        assertEquals(401, (failure as RemoteSttException).statusCode)
        assertEquals(listOf(false, true), refreshes)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `provider failure is reported without retry or token refresh`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502))
        val refreshes = mutableListOf<Boolean>()
        val client = CloudSttClient(localUrl(), { refresh ->
            refreshes += refresh
            "token"
        })

        val failure = runCatching { client.transcribe(floatArrayOf(0f), null) }.exceptionOrNull()
        assertEquals(502, (failure as RemoteSttException).statusCode)
        assertEquals(listOf(false), refreshes)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `authentication failure sends no audio request`() = runBlocking {
        val client = CloudSttClient(localUrl(), { throw IOException("Unavailable") })
        val failure = runCatching { client.transcribe(floatArrayOf(0f), null) }.exceptionOrNull()
        assertTrue(failure is CloudAuthenticationException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancelled authentication stays cancelled and sends no audio`() = runBlocking {
        val client = CloudSttClient(localUrl(), { throw CancellationException() })
        val failure = runCatching { client.transcribe(floatArrayOf(0f), null) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, server.requestCount)
    }

    private fun localUrl() = server.url("/").newBuilder().host("127.0.0.1").build().toString()

    private fun nextRequest() = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
        "Expected a cloud request within five seconds"
    }
}
