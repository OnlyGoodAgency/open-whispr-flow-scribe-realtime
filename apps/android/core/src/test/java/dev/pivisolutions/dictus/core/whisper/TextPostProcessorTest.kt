package dev.pivisolutions.dictus.core.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPostProcessorTest {

    @Test
    fun `empty input returns empty string`() {
        assertEquals("", TextPostProcessor.process(""))
    }

    @Test
    fun `whitespace-only input returns empty string`() {
        assertEquals("", TextPostProcessor.process("   "))
    }

    @Test
    fun `trims whitespace and appends period-space when no trailing punctuation`() {
        assertEquals("hello world. ", TextPostProcessor.process("  hello world  "))
    }

    @Test
    fun `text without punctuation gets period-space appended`() {
        assertEquals("Bonjour. ", TextPostProcessor.process("Bonjour"))
    }

    @Test
    fun `text ending with period gets space appended`() {
        assertEquals("Bonjour. ", TextPostProcessor.process("Bonjour."))
    }

    @Test
    fun `text ending with question mark gets space appended`() {
        assertEquals("Vraiment? ", TextPostProcessor.process("Vraiment?"))
    }

    @Test
    fun `text ending with exclamation gets space appended`() {
        assertEquals("Super! ", TextPostProcessor.process("Super!"))
    }

    @Test
    fun `text ending with ellipsis gets space appended`() {
        assertEquals("Etc... ", TextPostProcessor.process("Etc..."))
    }

    @Test
    fun `removes conservative English and Taglish filler sounds`() {
        assertEquals(
            "Kumusta, this is a test. ",
            TextPostProcessor.process("Um, Kumusta, uh this is a test"),
        )
    }

    @Test
    fun `preserves paragraphs from final cloud cleanup`() {
        assertEquals(
            "Hello there.\n\nI paid $45 for 37kg. ",
            TextPostProcessor.process("Hello there.\n\nI paid $45 for 37kg."),
        )
    }

    @Test
    fun `removing a filler does not join separate paragraphs`() {
        assertEquals("Hello.\n\nNext topic. ", TextPostProcessor.process("Hello. Um\n\nNext topic."))
    }

    @Test
    fun `keeps meaningful tics idioms and language switching`() {
        val text = "Right, it works like a charm. I mean, the real issue is cost. Kumusta!"
        assertEquals("$text ", TextPostProcessor.process(text))
    }
}
