package dev.pivisolutions.dictus.service

import androidx.datastore.preferences.core.Preferences
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys
import dev.pivisolutions.dictus.core.whisper.DictationVocabulary
import org.json.JSONArray
import org.json.JSONObject

internal object DictationCleanupOptions {
    fun from(preferences: Preferences, context: String): JSONObject {
        val vocabulary = DictationVocabulary.parse(preferences[PreferenceKeys.DICTATION_VOCABULARY].orEmpty())
        val learned = if (preferences[PreferenceKeys.DICTATION_LEARNING_ENABLED] != false) {
            (preferences[PreferenceKeys.DICTATION_LEARNED_TERMS] ?: emptySet())
                .filter { it.isNotBlank() && it.length <= 80 && it.none { c -> c.code < 32 } }
                .sorted().take(100)
        } else emptyList()
        return JSONObject()
            .put("supports_discard", true)
            .put("vocabulary", JSONArray().apply {
                vocabulary.forEach { put(JSONObject().put("spoken", it.spoken).put("written", it.written)) }
            })
            .put("learned_terms", JSONArray(learned))
            .put("context", if (preferences[PreferenceKeys.DICTATION_CONTEXT_ENABLED] == true) context.take(1000) else "")
            .put("filter_profanity", preferences[PreferenceKeys.DICTATION_FILTER_PROFANITY] ?: false)
    }
}
