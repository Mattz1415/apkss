package com.example

import android.content.Context
import android.content.SharedPreferences

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /**
     * Mengembalikan API Key dari input pengguna (SharedPreferences),
     * atau fallback ke BuildConfig.GEMINI_API_KEY bila telah dikonfigurasi lewat Secrets.
     */
    fun getEffectiveApiKey(): String {
        val localKey = apiKey
        if (localKey.isNotEmpty()) return localKey
        val buildConfigKey = BuildConfig.GEMINI_API_KEY.trim()
        return if (buildConfigKey.isEmpty() || buildConfigKey == PLACEHOLDER_API_KEY) "" else buildConfigKey
    }

    fun hasValidApiKey(): Boolean = getEffectiveApiKey().isNotEmpty()

    var modelId: String
        get() = prefs.getString(KEY_MODEL_ID, DEFAULT_MODEL_ID)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: DEFAULT_MODEL_ID
        set(value) {
            val cleaned = value.trim().ifEmpty { DEFAULT_MODEL_ID }
            prefs.edit().putString(KEY_MODEL_ID, cleaned).apply()
        }

    var cachedModels: List<String>
        get() {
            val serialized = prefs.getString(KEY_CACHED_MODELS, null).orEmpty()
            val parsed = serialized.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val base = parsed.ifEmpty { DEFAULT_AVAILABLE_MODELS }
            return (base + PINNED_MODELS).distinct()
        }
        set(value) {
            val cleaned = (value + PINNED_MODELS).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (cleaned.isNotEmpty()) {
                prefs.edit().putString(KEY_CACHED_MODELS, cleaned.joinToString("\n")).apply()
            }
        }

    var systemInstruction: String
        get() = prefs.getString(KEY_SYSTEM_INSTRUCTION, DEFAULT_SYSTEM_INSTRUCTION)
            ?: DEFAULT_SYSTEM_INSTRUCTION
        set(value) = prefs.edit().putString(KEY_SYSTEM_INSTRUCTION, value.trim()).apply()

    var isTripleTapEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRIPLE_TAP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_TRIPLE_TAP_ENABLED, value).apply()

    var isTripleTapVibrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRIPLE_TAP_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_TRIPLE_TAP_VIBRATE, value).apply()

    var lastOverlayX: Int
        get() = prefs.getInt(KEY_LAST_X, DEFAULT_OVERLAY_X)
        set(value) = prefs.edit().putInt(KEY_LAST_X, value).apply()

    var lastOverlayY: Int
        get() = prefs.getInt(KEY_LAST_Y, DEFAULT_OVERLAY_Y)
        set(value) = prefs.edit().putInt(KEY_LAST_Y, value).apply()

    companion object {
        private const val PREFS_NAME = "floating_gemini_ai_prefs"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL_ID = "gemini_model_id"
        private const val KEY_CACHED_MODELS = "gemini_cached_models"
        private const val KEY_SYSTEM_INSTRUCTION = "gemini_system_instruction"
        private const val KEY_TRIPLE_TAP_ENABLED = "triple_tap_enabled"
        private const val KEY_TRIPLE_TAP_VIBRATE = "triple_tap_vibrate"
        private const val KEY_LAST_X = "last_overlay_x"
        private const val KEY_LAST_Y = "last_overlay_y"

        private const val PLACEHOLDER_API_KEY = "MY_GEMINI_API_KEY"

        const val DEFAULT_MODEL_ID = "gemini-2.5-flash"
        val PINNED_MODELS: List<String> = listOf("hydrogen")
        val DEFAULT_AVAILABLE_MODELS: List<String> = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "hydrogen"
        )
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "Kamu adalah asisten AI pintar. Jawab pertanyaan, chat, atau soal di layar secara singkat, akurat, dan langsung ke intinya dalam bahasa Indonesia."
        const val DEFAULT_OVERLAY_X = 24
        const val DEFAULT_OVERLAY_Y = 220
    }
}
