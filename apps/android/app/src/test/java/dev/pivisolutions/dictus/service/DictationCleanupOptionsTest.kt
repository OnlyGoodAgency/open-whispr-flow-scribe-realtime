package dev.pivisolutions.dictus.service

import androidx.datastore.preferences.core.preferencesOf
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DictationCleanupOptionsTest {
    @Test fun `nearby text defaults off and deletion capability is advertised`() {
        val options = DictationCleanupOptions.from(preferencesOf(), "Private draft")
        assertEquals("", options.getString("context"))
        assertTrue(options.getBoolean("supports_discard"))
        assertFalse(options.getBoolean("filter_profanity"))
    }

    @Test fun `enabled context is bounded and disabled learning excludes stored terms`() {
        val preferences = preferencesOf(
            PreferenceKeys.DICTATION_CONTEXT_ENABLED to true,
            PreferenceKeys.DICTATION_LEARNING_ENABLED to false,
            PreferenceKeys.DICTATION_LEARNED_TERMS to setOf("ClickUp"),
            PreferenceKeys.DICTATION_LEARNED_ALIASES to "click up\tClickUp",
            PreferenceKeys.DICTATION_VOCABULARY to "akme => ACME",
        )
        val options = DictationCleanupOptions.from(preferences, "x".repeat(5000))
        assertEquals(4000, options.getString("context").length)
        assertEquals(0, options.getJSONArray("learned_terms").length())
        assertEquals(0, options.getJSONArray("learned_vocabulary").length())
        assertEquals("ACME", options.getJSONArray("vocabulary").getJSONObject(0).getString("written"))
    }

    @Test fun `learned aliases are sent when enabled but private editors exclude learning and context`() {
        val preferences = preferencesOf(
            PreferenceKeys.DICTATION_CONTEXT_ENABLED to true,
            PreferenceKeys.DICTATION_LEARNED_TERMS to setOf("ClickUp"),
            PreferenceKeys.DICTATION_LEARNED_ALIASES to "akme\tACME",
        )
        val enabled = DictationCleanupOptions.from(preferences, "ACME project")
        assertEquals("ACME", enabled.getJSONArray("learned_vocabulary").getJSONObject(0).getString("written"))
        val privateEditor = DictationCleanupOptions.from(preferences, "Private draft", learningAllowed = false)
        assertEquals(0, privateEditor.getJSONArray("learned_terms").length())
        assertEquals(0, privateEditor.getJSONArray("learned_vocabulary").length())
        assertEquals("", privateEditor.getString("context"))
    }
}
