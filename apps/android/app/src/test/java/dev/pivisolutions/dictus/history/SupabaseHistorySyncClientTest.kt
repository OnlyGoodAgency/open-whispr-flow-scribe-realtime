package dev.pivisolutions.dictus.history

import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class SupabaseHistorySyncClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun `anonymous session creates profile and history row`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"id":"user-1"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        val store = FakeSessionStore()
        val client = SupabaseHistorySyncClient(
            config = SupabaseClientConfig(localUrl(), "publishable"),
            sessionStore = store,
            nowEpochSeconds = { 1_000L },
        )

        client.upsert(historyEntry(id = 7L))

        val auth = nextRequest()
        assertEquals("/auth/v1/signup", auth.path)
        assertEquals("publishable", auth.getHeader("apikey"))

        val profile = nextRequest()
        assertEquals("/rest/v1/profiles?on_conflict=id", profile.path)
        assertEquals("Bearer access", profile.getHeader("Authorization"))
        assertEquals("user-1", JSONObject(profile.body.readUtf8()).getString("id"))

        val history = nextRequest()
        assertEquals(
            "/rest/v1/transcription_history?on_conflict=user_id,device_id,client_entry_id",
            history.path,
        )
        val payload = JSONObject(history.body.readUtf8())
        assertEquals("user-1", payload.getString("user_id"))
        assertEquals("device-1", payload.getString("device_id"))
        assertEquals("android-room-7", payload.getString("client_entry_id"))
        assertEquals("hello", payload.getString("transcription_text"))
        assertNotNull(store.session)
    }

    @Test
    fun `valid stored session skips anonymous sign in`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        val store = FakeSessionStore().apply {
            session = SupabaseSession("cached-access", "cached-refresh", "cached-user", 9_999L)
        }
        val client = SupabaseHistorySyncClient(
            config = SupabaseClientConfig(localUrl(), "publishable"),
            sessionStore = store,
            nowEpochSeconds = { 1_000L },
        )

        client.upsert(historyEntry(id = 9L))

        val profile = nextRequest()
        assertEquals("/rest/v1/profiles?on_conflict=id", profile.path)
        assertEquals("Bearer cached-access", profile.getHeader("Authorization"))
    }

    @Test
    fun `cloud access reuses the unexpired history session without network calls`() = runBlocking {
        val store = FakeSessionStore().apply {
            session = SupabaseSession("cached-access", "cached-refresh", "cached-user", 9_999L)
        }
        assertEquals("cached-access", client(store).accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `expired cloud session refreshes while preserving its user`() = runBlocking {
        val store = FakeSessionStore().apply {
            session = SupabaseSession("old-access", "old-refresh", "same-user", 999L)
        }
        server.enqueue(MockResponse().setBody(
            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"user":{"id":"same-user"}}""",
        ))

        assertEquals("new-access", client(store).accessToken())
        val request = nextRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", request.path)
        assertEquals("old-refresh", JSONObject(request.body.readUtf8()).getString("refresh_token"))
        assertEquals("same-user", store.session?.userId)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `failed refresh retains existing identity without signing up another user`() = runBlocking {
        for (status in listOf(500, 401)) {
            val original = SupabaseSession("old-access", "old-refresh", "same-user", 999L)
            val store = FakeSessionStore().apply { session = original }
            server.enqueue(MockResponse().setResponseCode(status))
            val failure = runCatching { client(store).accessToken(forceRefresh = true) }.exceptionOrNull()

            org.junit.Assert.assertTrue(failure is IOException)
            assertEquals(original, store.session)
            assertEquals("/auth/v1/token?grant_type=refresh_token", nextRequest().path)
        }
        assertEquals(2, server.requestCount)
    }

    private fun client(store: FakeSessionStore) = SupabaseHistorySyncClient(
        config = SupabaseClientConfig(localUrl(), "publishable"),
        sessionStore = store,
        nowEpochSeconds = { 1_000L },
    )

    private fun localUrl() = server.url("/").newBuilder().host("127.0.0.1").build().toString()

    private fun historyEntry(id: Long) = TranscriptionHistoryEntry(
        id = id,
        text = "hello",
        requestedLanguage = "auto",
        durationMillis = 1_500L,
        modelKey = "whisper-large-v3-turbo",
        provider = "REMOTE",
        createdAtEpochMillis = 1_780_000_000_000L,
    )

    private fun nextRequest() = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) {
        "Expected a Supabase request within five seconds"
    }

    private class FakeSessionStore : SupabaseSessionStore {
        var session: SupabaseSession? = null

        override fun loadSession(): SupabaseSession? = session
        override fun saveSession(session: SupabaseSession) { this.session = session }
        override fun clearSession() { session = null }
        override fun deviceId(): String = "device-1"
    }
}
