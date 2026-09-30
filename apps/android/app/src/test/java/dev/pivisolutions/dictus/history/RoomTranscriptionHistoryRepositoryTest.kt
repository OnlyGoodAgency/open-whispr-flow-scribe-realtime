package dev.pivisolutions.dictus.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomTranscriptionHistoryRepositoryTest {
    private lateinit var database: TranscriptionHistoryDatabase
    private lateinit var repository: RoomTranscriptionHistoryRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TranscriptionHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTranscriptionHistoryRepository(database.transcriptionHistoryDao())
    }

    @After fun tearDown() = database.close()

    @Test
    fun `insert and delete re-emit and unknown id is false`() = runBlocking {
        val emissions = Channel<List<TranscriptionHistoryEntry>>(Channel.UNLIMITED)
        val observer = launch {
            repository.observeAll().collect { emissions.send(it) }
        }
        try {
            assertTrue(withTimeout(5_000) { emissions.receive() }.isEmpty())
            val id = repository.insert(entry())
            assertEquals(1, withTimeout(5_000) { emissions.receive() }.size)
            assertTrue(repository.deleteById(id))
            assertTrue(withTimeout(5_000) { emissions.receive() }.isEmpty())
            assertFalse(repository.deleteById(id))
            assertFalse(repository.deleteById(Long.MAX_VALUE))
        } finally {
            observer.cancel()
        }
    }

    private fun entry() = TranscriptionHistoryEntry(
        text = "private",
        requestedLanguage = "auto",
        durationMillis = 500,
        modelKey = "model",
        provider = "provider",
        createdAtEpochMillis = 100,
    )
}
