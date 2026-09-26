package dev.pivisolutions.dictus.core.whisper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys

/** All persisted evidence is bounded to words/aliases. Full text is never persisted here. */
class DictationLearningStore(private val dataStore: DataStore<Preferences>) {
    suspend fun observeTranscript(text: String, screen: Boolean = false) {
        val candidates = DictationLearning.candidates(text.take(DictationLearning.MAX_EDITOR), screen)
        if (candidates.isEmpty()) return
        dataStore.edit { prefs ->
            if (prefs[PreferenceKeys.DICTATION_LEARNING_ENABLED] == false) return@edit
            val counts = prefs[PreferenceKeys.DICTATION_TERM_COUNTS].orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split('\t')
                val count = parts.firstOrNull()?.toIntOrNull()
                if (parts.size == 2 && count != null && count in 1..3 && DictationLearning.validTerm(parts[1])) parts[1] to count else null
            }.toMap().toMutableMap()
            val learned = (prefs[PreferenceKeys.DICTATION_LEARNED_TERMS] ?: emptySet()).toMutableSet()
            candidates.forEach { term ->
                val existing = counts.keys.firstOrNull { DictationLearning.canonical(it) == DictationLearning.canonical(term) }
                val count = ((existing?.let(counts::get) ?: 0) + 1).coerceAtMost(3)
                val preferred = if (existing != null && existing.any(Char::isUpperCase) && term.none(Char::isUpperCase)) existing else term
                if (existing != null) counts.remove(existing)
                counts[preferred] = count
                if (count >= 3) {
                    learned.removeAll { DictationLearning.canonical(it) == DictationLearning.canonical(preferred) }
                    learned.add(preferred)
                }
            }
            prefs[PreferenceKeys.DICTATION_TERM_COUNTS] = counts.entries.toList().takeLast(DictationLearning.MAX_CANDIDATES)
                .joinToString("\n") { "${it.value}\t${it.key}" }
            prefs[PreferenceKeys.DICTATION_LEARNED_TERMS] = learned.toList().takeLast(DictationLearning.MAX_TERMS).toSet()
        }
    }

    suspend fun learnCorrection(term: DictationVocabularyTerm) {
        if (!DictationLearning.validTerm(term.spoken) || !DictationLearning.validTerm(term.written)) return
        dataStore.edit { prefs ->
            if (prefs[PreferenceKeys.DICTATION_LEARNING_ENABLED] == false) return@edit
            val aliases = DictationLearning.decodeAliases(prefs[PreferenceKeys.DICTATION_LEARNED_ALIASES].orEmpty())
                .filterNot { DictationLearning.canonical(it.spoken) == DictationLearning.canonical(term.spoken) }
                .map { if (DictationLearning.canonical(it.written) == DictationLearning.canonical(term.spoken)) it.copy(written = term.written) else it } + term
            prefs[PreferenceKeys.DICTATION_LEARNED_ALIASES] = DictationLearning.encodeAliases(aliases)
            val terms = prefs[PreferenceKeys.DICTATION_LEARNED_TERMS] ?: emptySet()
            prefs[PreferenceKeys.DICTATION_LEARNED_TERMS] = (terms.filterNot {
                DictationLearning.canonical(it) == DictationLearning.canonical(term.written)
            } + term.written).takeLast(DictationLearning.MAX_TERMS).toSet()
        }
    }
}
