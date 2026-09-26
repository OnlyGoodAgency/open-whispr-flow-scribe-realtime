package dev.pivisolutions.dictus.core.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationVocabularyTest {
    @Test fun `plain names and aliases preserve preferred spelling`() {
        assertEquals(listOf(DictationVocabularyTerm("ClickUp", "ClickUp"), DictationVocabularyTerm("akme", "ACME")), DictationVocabulary.parse(" ClickUp\n\nakme => ACME "))
        assertTrue(DictationVocabulary.isValid("ClickUp\nakme => ACME"))
    }

    @Test fun `empty dictionary is valid`() {
        assertTrue(DictationVocabulary.isValid(" \n "))
        assertTrue(DictationVocabulary.parse("").isEmpty())
    }

    @Test fun `duplicate aliases invalid entries and excessive counts cannot be saved`() {
        for (value in listOf("Acme\nACME", "Akme => ", "=> Acme", "x".repeat(81), "Akme\tName", (1..51).joinToString("\n") { "Name$it" })) {
            assertFalse(value, DictationVocabulary.isValid(value))
        }
    }

    @Test fun `parser bounds stored terms even if old preferences are invalid`() {
        assertEquals(50, DictationVocabulary.parse((1..100).joinToString("\n") { "Name$it" }).size)
        assertEquals(1, DictationVocabulary.parse("Acme\nACME\nBroken => ").size)
    }
}
