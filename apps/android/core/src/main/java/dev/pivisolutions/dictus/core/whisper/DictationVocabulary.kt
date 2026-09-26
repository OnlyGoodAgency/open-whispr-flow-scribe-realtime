package dev.pivisolutions.dictus.core.whisper

data class DictationVocabularyTerm(val spoken: String, val written: String)

/** Explicit spelling preferences: one term or "heard spelling => preferred spelling" per line. */
object DictationVocabulary {
    const val MAX_TERMS = 50
    const val MAX_TERM_LENGTH = 80

    fun parse(value: String): List<DictationVocabularyTerm> = value.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            val parts = line.split("=>", limit = 2).map(String::trim)
            val spoken = parts.first()
            val written = parts.getOrElse(1) { spoken }
            if (listOf(spoken, written).any { it.isBlank() || it.length > MAX_TERM_LENGTH || it.any { c -> c.code < 32 } }) {
                null
            } else {
                DictationVocabularyTerm(spoken, written)
            }
        }
        .distinctBy { it.spoken.lowercase(java.util.Locale.ROOT) }
        .take(MAX_TERMS)
        .toList()

    fun isValid(value: String): Boolean {
        val lines = value.lineSequence().filter { it.isNotBlank() }.toList()
        return lines.size <= MAX_TERMS && parse(value).size == lines.size
    }
}
