package dev.pivisolutions.dictus.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingTextInsertionTest {
    @Test
    fun `ignores WhatsApp message placeholder exposed as editor text`() {
        assertEquals(
            "",
            resolveExistingEditorText(
                text = "Message",
                hintText = "Message",
                isShowingHintText = true,
                selectionStart = -1,
                selectionEnd = -1,
            ),
        )
    }

    @Test
    fun `ignores matching Notes placeholder when app omits showing-hint flag`() {
        assertEquals(
            "",
            resolveExistingEditorText(
                text = "Note",
                hintText = "Note",
                isShowingHintText = false,
                selectionStart = 0,
                selectionEnd = 0,
            ),
        )
    }

    @Test
    fun `preserves typed text even when it equals the editor hint`() {
        assertEquals(
            "Message",
            resolveExistingEditorText(
                text = "Message",
                hintText = "Message",
                isShowingHintText = false,
                selectionStart = 7,
                selectionEnd = 7,
            ),
        )
    }

    @Test
    fun `inserts speech at cursor without replacing existing text`() {
        assertEquals(
            FloatingTextInsertion("Hello brave world", 12),
            composeFloatingTextInsertion("Hello world", 6, 6, "brave"),
        )
    }

    @Test
    fun `replaces selected text and separates adjacent words`() {
        assertEquals(
            FloatingTextInsertion("Hello kind world", 10),
            composeFloatingTextInsertion("Hello cruel world", 6, 11, "kind"),
        )
    }

    @Test
    fun `does not add a space before punctuation`() {
        assertEquals(
            FloatingTextInsertion("Hello, world", 6),
            composeFloatingTextInsertion("Hello world", 5, 5, ","),
        )
    }

    @Test
    fun `clamps a missing accessibility selection to the end`() {
        assertEquals(
            FloatingTextInsertion("Hello world", 11),
            composeFloatingTextInsertion("Hello", -1, -1, "world"),
        )
    }

    @Test
    fun `blank speech leaves editor untouched`() {
        assertEquals(
            FloatingTextInsertion("Hello", 2),
            composeFloatingTextInsertion("Hello", 2, 4, "  "),
        )
    }
}
