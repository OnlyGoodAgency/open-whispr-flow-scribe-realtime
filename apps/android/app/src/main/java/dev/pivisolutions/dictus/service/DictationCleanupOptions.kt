package dev.pivisolutions.dictus.service

import androidx.datastore.preferences.core.Preferences
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys
import dev.pivisolutions.dictus.core.whisper.DictationVocabulary
import dev.pivisolutions.dictus.core.whisper.DictationLearning
import org.json.JSONArray
import org.json.JSONObject

internal object DictationCleanupOptions {
    fun from(preferences: Preferences, context: String, learningAllowed: Boolean = true): JSONObject {
        val vocabulary = DictationVocabulary.parse(preferences[PreferenceKeys.DICTATION_VOCABULARY].orEmpty())
        val learned = if (learningAllowed && preferences[PreferenceKeys.DICTATION_LEARNING_ENABLED] != false) {
            (preferences[PreferenceKeys.DICTATION_LEARNED_TERMS] ?: emptySet())
                .filter { it.isNotBlank() && it.length <= 80 && it.none { c -> c.code < 32 } }
                .sorted().take(100)
        } else emptyList()
        val aliases = if (learningAllowed && preferences[PreferenceKeys.DICTATION_LEARNING_ENABLED] != false) {
            DictationLearning.decodeAliases(preferences[PreferenceKeys.DICTATION_LEARNED_ALIASES].orEmpty())
        } else emptyList()
        return JSONObject()
            .put("supports_discard", true)
            .put("vocabulary", JSONArray().apply {
                vocabulary.forEach { put(JSONObject().put("spoken", it.spoken).put("written", it.written)) }
            })
            .put("learned_terms", JSONArray(learned))
            .put("learned_vocabulary", JSONArray().apply {
                aliases.forEach { put(JSONObject().put("spoken", it.spoken).put("written", it.written)) }
            })
            .put("context", if (learningAllowed && preferences[PreferenceKeys.DICTATION_CONTEXT_ENABLED] == true) context.take(DictationLearning.MAX_CONTEXT) else "")
            .put("filter_profanity", preferences[PreferenceKeys.DICTATION_FILTER_PROFANITY] ?: false)
    }
}
