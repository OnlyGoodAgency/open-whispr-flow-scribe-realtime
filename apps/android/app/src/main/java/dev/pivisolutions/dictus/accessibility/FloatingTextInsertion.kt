package dev.pivisolutions.dictus.accessibility

/** The complete replacement text and cursor position for Accessibility ACTION_SET_TEXT. */
internal data class FloatingTextInsertion(
    val text: String,
    val cursor: Int,
)

/**
 * Accessibility implementations sometimes expose an empty editor's visual hint
 * through `text`. Treat it as a hint when Android marks it that way, or when the
 * reported text and hint are equal and there is no real text selection.
 */
internal fun resolveExistingEditorText(
    text: CharSequence?,
    hintText: CharSequence?,
    isShowingHintText: Boolean,
    selectionStart: Int,
    selectionEnd: Int,
): String {
    val value = text?.toString().orEmpty()
    val hint = hintText?.toString()
    val matchesUnselectedHint =
        !hint.isNullOrEmpty() && value == hint && selectionStart <= 0 && selectionEnd <= 0
    return if (isShowingHintText || matchesUnselectedHint) "" else value
}

/**
 * Inserts speech at the editor selection while preserving all existing text.
 * Spaces are added only when two word characters would otherwise run together.
 */
internal fun composeFloatingTextInsertion(
    existing: String,
    selectionStart: Int,
    selectionEnd: Int,
    spoken: String,
): FloatingTextInsertion {
    val hasSelection = selectionStart >= 0 && selectionEnd >= 0
    val start = if (hasSelection) {
        minOf(selectionStart, selectionEnd).coerceIn(0, existing.length)
    } else {
        existing.length
    }
    val end = if (hasSelection) {
        maxOf(selectionStart, selectionEnd).coerceIn(start, existing.length)
    } else {
        start
    }
    val cleanSpoken = spoken.trim(' ', '\t', '\r')
    if (cleanSpoken.isEmpty()) return FloatingTextInsertion(existing, start)

    val prefix = existing.substring(0, start)
    val suffix = existing.substring(end)
    val leadingSpace = prefix.lastOrNull()?.isLetterOrDigit() == true &&
        cleanSpoken.firstOrNull()?.isLetterOrDigit() == true
    val trailingSpace = suffix.firstOrNull()?.isLetterOrDigit() == true &&
        cleanSpoken.lastOrNull()?.isLetterOrDigit() == true
    val inserted = buildString {
        if (leadingSpace) append(' ')
        append(cleanSpoken)
        if (trailingSpace) append(' ')
    }
    return FloatingTextInsertion(
        text = prefix + inserted + suffix,
        cursor = prefix.length + inserted.length,
    )
}
