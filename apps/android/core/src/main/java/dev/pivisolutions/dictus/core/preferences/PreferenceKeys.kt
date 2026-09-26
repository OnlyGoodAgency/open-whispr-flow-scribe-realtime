package dev.pivisolutions.dictus.core.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * DataStore preference keys for Dictus settings.
 *
 * DataStore is Android's type-safe replacement for SharedPreferences.
 * Each key is typed (string, int, boolean, etc.) which prevents
 * type-mismatch bugs at compile time. These keys define what
 * settings are persisted across app restarts.
 *
 * Phase 4 additions: onboarding status, haptics, and sound toggles.
 */
object PreferenceKeys {
    // --- Keyboard settings (Phase 1-2) ---
    val KEYBOARD_LAYOUT = stringPreferencesKey("keyboard_layout")
    /** Keyboard correction language. Deliberately independent from ASR language. */
    val KEYBOARD_LANGUAGE = stringPreferencesKey("keyboard_language")

    // --- Model settings (Phase 3) ---
    val ACTIVE_MODEL = stringPreferencesKey("active_model")
    val TRANSCRIPTION_LANGUAGE = stringPreferencesKey("transcription_language")

    // --- Shared OpenWhisperFlow server ---
    val REMOTE_STT_ENABLED = booleanPreferencesKey("remote_stt_enabled")
    val REMOTE_STT_URL = stringPreferencesKey("remote_stt_url")
    val REMOTE_STT_API_KEY = stringPreferencesKey("remote_stt_api_key")
    val REMOTE_STT_MODEL = stringPreferencesKey("remote_stt_model")
    val REMOTE_STT_FALLBACK_LOCAL = booleanPreferencesKey("remote_stt_fallback_local")

    // --- Onboarding (Phase 4) ---
    val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")

    /** User accepted the separate Accessibility API disclosure for the floating mic. */
    val FLOATING_MIC_DISCLOSURE_ACCEPTED =
        booleanPreferencesKey("floating_mic_disclosure_accepted")

    // --- Audio feedback settings (Phase 4 + Phase 5) ---
    val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
    val KEY_SOUNDS_ENABLED = booleanPreferencesKey("key_sounds_enabled")
    val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    val SOUND_VOLUME = floatPreferencesKey("sound_volume")
    val RECORD_START_SOUND = stringPreferencesKey("record_start_sound")
    val RECORD_STOP_SOUND = stringPreferencesKey("record_stop_sound")
    val RECORD_CANCEL_SOUND = stringPreferencesKey("record_cancel_sound")

    // --- Appearance settings (Phase 4) ---
    val THEME = stringPreferencesKey("theme")

    // --- Last transcription (Phase 4) ---
    val LAST_TRANSCRIPTION = stringPreferencesKey("last_transcription")
    val LAST_TRANSCRIPTION_SAVED_AT = longPreferencesKey("last_transcription_saved_at")

    // --- Keyboard mode (Phase 5) ---
    /** Default keyboard opening mode: "abc" (letters) or "123" (numbers). */
    val KEYBOARD_MODE = stringPreferencesKey("keyboard_mode")

    // --- Suggestions (Phase 8) ---
    /** Whether the built-in suggestion bar is enabled. Users can disable if they
     *  experience performance issues or prefer typing without suggestions. */
    val SUGGESTIONS_ENABLED = booleanPreferencesKey("suggestions_enabled")

    /** Whether automatic replacement on word boundaries is enabled. Independent of the bar. */
    val AUTOCORRECT_ENABLED = booleanPreferencesKey("autocorrect_enabled")

    // --- Personal dictionary (Phase 8) ---
    /** Set of words the user has typed at least twice, persisted across restarts. */
    val PERSONAL_DICTIONARY = stringSetPreferencesKey("personal_dictionary")
    /** Dictation spelling overrides, preserving exact user casing. */
    val DICTATION_VOCABULARY = stringPreferencesKey("dictation_vocabulary")
    val DICTATION_LEARNED_TERMS = stringSetPreferencesKey("dictation_learned_terms")
    val DICTATION_LEARNED_ALIASES = stringPreferencesKey("dictation_learned_aliases")
    val DICTATION_TERM_COUNTS = stringPreferencesKey("dictation_term_counts")
    val DICTATION_CONTEXT_ENABLED = booleanPreferencesKey("dictation_context_enabled")
    val DICTATION_LEARNING_ENABLED = booleanPreferencesKey("dictation_learning_enabled")
    val DICTATION_FILTER_PROFANITY = booleanPreferencesKey("dictation_filter_profanity")
}
