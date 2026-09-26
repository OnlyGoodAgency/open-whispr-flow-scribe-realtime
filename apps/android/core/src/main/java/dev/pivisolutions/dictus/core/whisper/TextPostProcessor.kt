package dev.pivisolutions.dictus.core.whisper

/**
 * Post-processes transcribed text before insertion into the text field.
 *
 * Matches iOS behavior from DictationCoordinator.swift lines 368-372:
 * - Trim leading/trailing whitespace (Whisper sometimes adds extra spaces)
 * - If text ends with sentence-ending punctuation (. ! ? ...), append just a space
 * - Otherwise, append ". " (period + space)
 * This prevents chained dictations from sticking together.
 */
object TextPostProcessor {

    private val SENTENCE_ENDERS = setOf('.', '!', '?')
    private val FILLER_WORD = Regex(
        pattern = "(?i)(?<![\\p{L}\\p{N}])(?:uh+|um+|uhm+|hmm+|mmm+)(?![\\p{L}\\p{N}])[,;:]?[ \\t]*",
    )
    // Final cloud cleanup can contain paragraph breaks; keep them on insertion.
    private val REPEATED_SPACES = Regex("[ \\t]{2,}")
    private val LINE_END_SPACES = Regex("[ \\t]+(?=\\r?\\n)")

    /** Cloud final text has already been formatted; don't punctuate signatures or lists again. */
    fun processCloud(rawText: String): String {
        val text = rawText.trim(' ', '\t', '\r')
        if (text.isBlank()) return ""
        return if (text.endsWith('\n')) text else "$text "
    }

    fun process(rawText: String): String {
        val trimmed = rawText
            .replace(FILLER_WORD, "")
            .replace(REPEATED_SPACES, " ")
            .replace(LINE_END_SPACES, "")
            .trim()
        if (trimmed.isEmpty()) return ""

        val lastChar = trimmed.last()
        return if (lastChar in SENTENCE_ENDERS || trimmed.endsWith("...")) {
            "$trimmed "
        } else {
            "$trimmed. "
        }
    }
}
