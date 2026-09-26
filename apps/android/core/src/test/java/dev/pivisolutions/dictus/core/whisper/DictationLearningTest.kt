package dev.pivisolutions.dictus.core.whisper

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DictationLearningTest {
    @Before fun reset() { RecentDictation.clear() }

    @Test fun `learns a close spelling edit and preserves exact casing`() {
        assertEquals(DictationVocabularyTerm("akme", "ACME"), DictationLearning.correction("Send to akme.", "Send to ACME."))
        assertEquals(DictationVocabularyTerm("click up", "ClickUp"), DictationLearning.correction("Use click up.", "Use ClickUp."))
        assertEquals(DictationVocabularyTerm("Patell", "Patel"), DictationLearning.correction("Hi Patell", "Hi Patel"))
    }

    @Test fun `does not learn semantic rewrites numbers homophones or deletions as spellings`() {
        for ((before, after) in listOf("Send report" to "Send invoice", "Use their report" to "Use there report", "Pay 45 dollars" to "Pay 50 dollars", "Call Alex" to "Call", "Hello" to "")) {
            assertNull(DictationLearning.correction(before, after))
        }
    }

    @Test fun `edit tracker follows only inserted span and expires`() {
        val tracker = DictationEditTracker()
        tracker.arm("field-a", "Before. Send to akme. After.", 8, 13, 0)
        assertEquals(DictationVocabularyTerm("akme", "ACME"), tracker.settle("field-a", "Before. Send to ACME. After.", 1500))
        assertNull(tracker.settle("field-a", "Before. Send to ACME. After.", 1600))
        assertNull(tracker.settle("field-b", "Before. Send to Acme. After.", 2000))
        tracker.arm("field-a", "Send to akme.", 0, 13, 0)
        assertNull(tracker.settle("field-a", "Send to ACME.", 120_001))
    }

    @Test fun `unrelated edits and clearing vocabulary invalidate pending learning`() {
        val tracker = DictationEditTracker()
        tracker.arm("field", "Intro. Send to akme.", 7, 13, 0)
        assertNull(tracker.settle("field", "Changed intro. Send to ACME.", 1500))
        tracker.arm("field", "Send to akme.", 0, 13, 0)
        RecentDictation.clear()
        assertNull(tracker.settle("field", "Send to ACME.", 1500))
    }

    @Test fun `recent output matches a new paste but not existing or ambiguous text`() {
        RecentDictation.publish("Send to akme. ", 0)
        assertEquals(7..19, RecentDictation.findInsertion("Intro. ", "Intro. Send to akme. ", 100))
        assertNull(RecentDictation.findInsertion("Send to akme.", "Send to akme.", 100))
        assertNull(RecentDictation.findInsertion("", "Send to akme. Send to akme.", 100))
        assertNull(RecentDictation.findInsertion("", "Send to akme.", 300_001))
    }

    @Test fun `candidate evidence is bounded and excludes common words and identifiers`() {
        assertEquals(listOf("WisprFlow", "Patel"), DictationLearning.candidates("I use WisprFlow. Call Patel. WisprFlow. 12345."))
        assertEquals(listOf("Patel"), DictationLearning.candidates("hello unusual Patel", screen = true))
        assertTrue(DictationLearning.candidates((1..100).joinToString(" ") { "Product$it" }).size <= 50)
        assertFalse(DictationLearning.validTerm("Name\ncommand"))
    }

    @Test fun `alias storage rejects corrupt data and keeps only bounded terms`() {
        val aliases = listOf(DictationVocabularyTerm("akme", "ACME"), DictationVocabularyTerm("click up", "ClickUp"))
        assertEquals(aliases, DictationLearning.decodeAliases(DictationLearning.encodeAliases(aliases)))
        assertTrue(DictationLearning.decodeAliases("bad\nold\tnew\textra").isEmpty())
        assertEquals(100, DictationLearning.decodeAliases(DictationLearning.encodeAliases((1..150).map { DictationVocabularyTerm("alias$it", "Name$it") })).size)
    }
}
