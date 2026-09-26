package dev.pivisolutions.dictus.core.whisper

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DictationLearningStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `three recordings learn a term once per recording and preserve spelling`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { temp.newFile("repeat.preferences_pb") })
        val store = DictationLearningStore(data)
        repeat(2) { store.observeTranscript("WisprFlow WisprFlow WisprFlow") }
        assertTrue(data.data.first()[PreferenceKeys.DICTATION_LEARNED_TERMS].isNullOrEmpty())
        store.observeTranscript("wisprflow")
        assertEquals(setOf("WisprFlow"), data.data.first()[PreferenceKeys.DICTATION_LEARNED_TERMS])
    }

    @Test fun `screen terms and observed correction are persisted as vocabulary not screen prose`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { temp.newFile("screen.preferences_pb") })
        val store = DictationLearningStore(data)
        repeat(3) { store.observeTranscript("hello unusual Patel", screen = true) }
        store.learnCorrection(DictationVocabularyTerm("akme", "ACME"))
        val prefs = data.data.first()
        assertEquals(setOf("Patel", "ACME"), prefs[PreferenceKeys.DICTATION_LEARNED_TERMS])
        assertEquals(listOf(DictationVocabularyTerm("akme", "ACME")), DictationLearning.decodeAliases(prefs[PreferenceKeys.DICTATION_LEARNED_ALIASES].orEmpty()))
        assertFalse(prefs.toString().contains("hello unusual"))
    }

    @Test fun `disabled learning persists neither speech screen nor edits`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { temp.newFile("off.preferences_pb") })
        data.edit { it[PreferenceKeys.DICTATION_LEARNING_ENABLED] = false }
        val store = DictationLearningStore(data)
        repeat(3) { store.observeTranscript("WisprFlow"); store.observeTranscript("Patel", screen = true) }
        store.learnCorrection(DictationVocabularyTerm("akme", "ACME"))
        val prefs = data.data.first()
        assertTrue(prefs[PreferenceKeys.DICTATION_LEARNED_TERMS].isNullOrEmpty())
        assertNull(prefs[PreferenceKeys.DICTATION_LEARNED_ALIASES])
        assertNull(prefs[PreferenceKeys.DICTATION_TERM_COUNTS])
    }

    @Test fun `later casing edit updates an earlier learned alias`() = runTest {
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { temp.newFile("casing.preferences_pb") })
        val store = DictationLearningStore(data)
        store.learnCorrection(DictationVocabularyTerm("akme", "Acme"))
        store.learnCorrection(DictationVocabularyTerm("Acme", "ACME"))
        val aliases = DictationLearning.decodeAliases(data.data.first()[PreferenceKeys.DICTATION_LEARNED_ALIASES].orEmpty())
        assertEquals("ACME", aliases.first { it.spoken == "akme" }.written)
    }
}
