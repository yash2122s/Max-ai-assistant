package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LOCAL_AI_MODE = "local_ai_mode"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"

        private const val DEFAULT_BACKEND_URL = "https://your-jarvis-backend.onrender.com"
    }

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value).apply()

    var userId: String
        get() {
            var id = prefs.getString(KEY_USER_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_USER_ID, id).apply()
            }
            return id
        }
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var fcmToken: String
        get() = prefs.getString(KEY_FCM_TOKEN, "simulated_fcm_token_vqzrtp") ?: "simulated_fcm_token_vqzrtp"
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var isLocalAiMode: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_AI_MODE, true) // Default to true so they can test out of the box with AI Studio!
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_AI_MODE, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var isAutoReplyEnabled: Boolean
        get() = prefs.getBoolean("auto_reply_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_reply_enabled", value).apply()

    var isAiFallbackEnabled: Boolean
        get() = prefs.getBoolean("ai_fallback_enabled", true)
        set(value) = prefs.edit().putBoolean("ai_fallback_enabled", value).apply()

    var aiAllowedContacts: String
        get() = prefs.getString("ai_allowed_contacts", "") ?: ""
        set(value) = prefs.edit().putString("ai_allowed_contacts", value).apply()

    var quickReplies: String
        get() = prefs.getString("quick_replies", "Yes,No,Can't talk now,Call you later") ?: "Yes,No,Can't talk now,Call you later"
        set(value) = prefs.edit().putString("quick_replies", value).apply()

    var cloudAiModel: String
        get() = prefs.getString("cloud_ai_model", "groq") ?: "groq"
        set(value) = prefs.edit().putString("cloud_ai_model", value).apply()

    var languagePreference: String
        get() = prefs.getString("language_preference", "English") ?: "English"
        set(value) = prefs.edit().putString("language_preference", value).apply()

    var botPersona: String
        get() = prefs.getString("bot_persona", "Human Type Talking") ?: "Human Type Talking"
        set(value) = prefs.edit().putString("bot_persona", value).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean("has_completed_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_completed_onboarding", value).apply()

    var isTelegramBotEnabled: Boolean
        get() = prefs.getBoolean("telegram_bot_enabled", false)
        set(value) = prefs.edit().putBoolean("telegram_bot_enabled", value).apply()

    var telegramBotToken: String
        get() = prefs.getString("telegram_bot_token", "") ?: ""
        set(value) = prefs.edit().putString("telegram_bot_token", value).apply()

    var telegramChatId: String
        get() = prefs.getString("telegram_chat_id", "") ?: ""
        set(value) = prefs.edit().putString("telegram_chat_id", value).apply()
        
    var cloudBotUrl: String
        get() = prefs.getString("cloud_bot_url", "") ?: ""
        set(value) = prefs.edit().putString("cloud_bot_url", value).apply()

    var cloudAppSecret: String
        get() = prefs.getString("cloud_app_secret", "") ?: ""
        set(value) = prefs.edit().putString("cloud_app_secret", value).apply()

    // --- Agentic Loop Settings ---

    var isAgenticModeEnabled: Boolean
        get() = prefs.getBoolean("agentic_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("agentic_mode_enabled", value).apply()

    var agenticMaxIterations: Int
        get() = prefs.getInt("agentic_max_iterations", 8)
        set(value) = prefs.edit().putInt("agentic_max_iterations", value.coerceIn(1, 15)).apply()

    var agenticDailyBudget: Int
        get() = prefs.getInt("agentic_daily_budget", 50)
        set(value) = prefs.edit().putInt("agentic_daily_budget", value.coerceIn(5, 200)).apply()

    /** Tracks how many agentic iterations have been used today. Resets daily. */
    var agenticIterationsUsedToday: Int
        get() {
            val lastDate = prefs.getString("agentic_budget_date", "") ?: ""
            val today = java.time.LocalDate.now().toString()
            if (lastDate != today) {
                // New day — reset counter
                prefs.edit()
                    .putString("agentic_budget_date", today)
                    .putInt("agentic_iterations_today", 0)
                    .apply()
                return 0
            }
            return prefs.getInt("agentic_iterations_today", 0)
        }
        set(value) {
            prefs.edit()
                .putString("agentic_budget_date", java.time.LocalDate.now().toString())
                .putInt("agentic_iterations_today", value)
                .apply()
        }

    /** Returns true if there's budget remaining for agentic iterations today. */
    fun hasAgenticBudget(): Boolean = agenticIterationsUsedToday < agenticDailyBudget

    /** Consume N iterations from today's budget. Returns false if over budget. */
    fun consumeAgenticBudget(iterations: Int): Boolean {
        val current = agenticIterationsUsedToday
        if (current + iterations > agenticDailyBudget) return false
        agenticIterationsUsedToday = current + iterations
        return true
    }

    // --- Gemini Live Voice Settings ---

    /** When true, wake word triggers a Gemini Live audio session instead of STT→AI→TTS. */
    var isLiveVoiceMode: Boolean
        get() = prefs.getBoolean("live_voice_mode", false)
        set(value) = prefs.edit().putBoolean("live_voice_mode", value).apply()

    /** The Gemini model to use for Live audio sessions. */
    var liveVoiceModelName: String
        get() = prefs.getString("live_voice_model_name", "gemini-3.1-flash-live-preview") ?: "gemini-3.1-flash-live-preview"
        set(value) = prefs.edit().putString("live_voice_model_name", value).apply()

    /** Voice persona for Gemini Live audio output. Options: Fenrir, Puck, Charon, etc. */
    var liveVoiceName: String
        get() = prefs.getString("live_voice_name", "Fenrir") ?: "Fenrir"
        set(value) = prefs.edit().putString("live_voice_name", value).apply()
}

