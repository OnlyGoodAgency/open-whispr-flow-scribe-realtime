package dev.pivisolutions.dictus.service

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class RemoteSttClientTest {
    @Test
    fun `normalizes an HTTPS base URL`() {
        assertEquals(
            "https://speech.example.com/v1/audio/transcriptions",
            RemoteSttClient.normalizeEndpoint("https://speech.example.com/"),
        )
    }

    @Test
    fun `accepts v1 base URL and full transcription endpoint`() {
        val endpoint = "https://speech.example.com/v1/audio/transcriptions"
        assertEquals(endpoint, RemoteSttClient.normalizeEndpoint("https://speech.example.com/v1/"))
        assertEquals(endpoint, RemoteSttClient.normalizeEndpoint(endpoint))
    }

    @Test
    fun `rejects insecure public URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteSttClient.normalizeEndpoint("http://speech.example.com")
        }
    }

    @Test
    fun `encodes a valid PCM16 mono WAV`() {
        val wav = WavEncoder.encodePcm16Mono(floatArrayOf(-1f, 0f, 1f))
        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(6, ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(50, wav.size)
    }

    @Test
    fun `uploads authorization audio model and language`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\" Kumusta! \"}"))
            val client = RemoteSttClient(OkHttpClient())

            val result = client.transcribe(
                samples = floatArrayOf(0f, 0.25f),
                language = "tl",
                config = RemoteSttConfig(
                    url = server.url("/").newBuilder().host("127.0.0.1").build().toString(),
                    apiKey = "test-secret",
                ),
            )

            assertEquals("Kumusta!", result)
            val request = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", request.path)
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("openai/whisper-large-v3-turbo"))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("tl"))
            assertTrue(body.contains("filename=\"dictation.wav\""))
        }
    }
}
