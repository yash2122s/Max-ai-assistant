package com.example.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID

class SettingsManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val isTesting: Boolean by lazy {
        try {
            Class.forName("org.robolectric.Robolectric")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private val securePrefs: SharedPreferences by lazy {
        if (isTesting) {
            // Test fallback - isolated from production
            prefs
        } else {
            try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    "jarvis_secure_settings",
                    masterKeyAlias,
                    context.applicationContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                android.util.Log.e("SettingsManager", "Failed to initialize EncryptedSharedPreferences. Attempting recovery...", e)
                try {
                    // Try to delete corrupted shared prefs file and recreate
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        context.deleteSharedPreferences("jarvis_secure_settings")
                    } else {
                        context.getSharedPreferences("jarvis_secure_settings", Context.MODE_PRIVATE).edit().clear().apply()
                    }
                    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                    EncryptedSharedPreferences.create(
                        "jarvis_secure_settings",
                        masterKeyAlias,
                        context.applicationContext,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (recreateException: Exception) {
                    android.util.Log.e("SettingsManager", "Recovery failed. Falling back to standard SharedPreferences to prevent crash", recreateException)
                    prefs
                }
            }
        }
    }

    init {
        try {
            migrateSensitiveKey("gemini_api_key")
            migrateSensitiveKey("telegram_bot_token")
            migrateSensitiveKey("cloud_app_secret")
            migrateSensitiveKey("ui_agent_api_key")
        } catch (e: Exception) {
            android.util.Log.e("SettingsManager", "Error during sensitive key migration", e)
        }
    }

    private fun migrateSensitiveKey(key: String) {
        try {
            val legacyPrefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
            if (legacyPrefs.contains(key)) {
                val plaintextVal = legacyPrefs.getString(key, null)
                if (plaintextVal != null && plaintextVal.isNotEmpty()) {
                    securePrefs.edit().putString(key, plaintextVal).apply()
                }
                legacyPrefs.edit().remove(key).apply()
            }
            if (prefs.contains(key)) {
                val plaintextVal = prefs.getString(key, null)
                if (plaintextVal != null && plaintextVal.isNotEmpty()) {
                    securePrefs.edit().putString(key, plaintextVal).apply()
                }
                if (securePrefs !== prefs) {
                    prefs.edit().remove(key).apply()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsManager", "Could not migrate sensitive key '$key': ${e.message}")
        }
    }

    companion object {
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_LOCAL_AI_MODE = "local_ai_mode"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_MAX_ENABLED = "max_global_enabled"
        private const val KEY_UI_AGENT_PROVIDER = "ui_agent_provider"
        private const val KEY_UI_AGENT_API_KEY = "ui_agent_api_key"
        private const val KEY_UI_AGENT_MODEL = "ui_agent_model"

        private const val DEFAULT_BACKEND_URL = "https://your-jarvis-backend.onrender.com"
        const val DEFAULT_UI_AGENT_PROVIDER = "openrouter"
        const val DEFAULT_UI_AGENT_MODEL = "stealth/ox-alpha"
    }

    var backendUrl: String
        get() = prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value).apply()

    var userId: String
        get() {
            var id = prefs.getString(KEY_USER_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_USER_ID, id).commit()
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
        get() = securePrefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
        set(value) = securePrefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

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
        get() = prefs.getString("language_preference", "Tinglish") ?: "Tinglish"
        set(value) = prefs.edit().putString("language_preference", value).apply()

    var botPersona: String
        get() = prefs.getString("bot_persona", "Human Type Talking") ?: "Human Type Talking"
        set(value) = prefs.edit().putString("bot_persona", value).apply()

    enum class OnboardingStage {
        NOT_STARTED,
        PERMISSIONS_IN_PROGRESS,
        AI_CONFIG_IN_PROGRESS,
        COMPLETED
    }

    var onboardingStage: OnboardingStage
        get() {
            val stageName = prefs.getString("onboarding_stage_v3", null)
            if (stageName != null) {
                return try { OnboardingStage.valueOf(stageName) } catch (e: Exception) { OnboardingStage.NOT_STARTED }
            }
            // Migration from legacy flag
            return if (prefs.getBoolean("has_completed_onboarding", false)) {
                OnboardingStage.COMPLETED
            } else {
                OnboardingStage.NOT_STARTED
            }
        }
        set(value) = prefs.edit().putString("onboarding_stage_v3", value.name).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    var isTelegramBotEnabled: Boolean
        get() = prefs.getBoolean("telegram_bot_enabled", false)
        set(value) = prefs.edit().putBoolean("telegram_bot_enabled", value).apply()

    var telegramBotToken: String
        get() = securePrefs.getString("telegram_bot_token", "") ?: ""
        set(value) = securePrefs.edit().putString("telegram_bot_token", value).apply()

    var telegramChatId: String
        get() = prefs.getString("telegram_chat_id", "") ?: ""
        set(value) = prefs.edit().putString("telegram_chat_id", value).apply()
        
    var cloudBotUrl: String
        get() = prefs.getString("cloud_bot_url", "") ?: ""
        set(value) = prefs.edit().putString("cloud_bot_url", value).apply()

    var cloudAppSecret: String
        get() = securePrefs.getString("cloud_app_secret", "") ?: ""
        set(value) = securePrefs.edit().putString("cloud_app_secret", value).apply()

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

    // --- P1.2 Risk-aware Approval Settings ---

    /** Approval mode name: MANUAL | SAFE_AUTO | FULL_AUTO (default SAFE_AUTO). */
    var approvalMode: String
        get() = prefs.getString("approval_mode", "SAFE_AUTO") ?: "SAFE_AUTO"
        set(value) = prefs.edit().putString("approval_mode", value).apply()

    /** FULL_AUTO may auto-run CAUTION actions only when this is true (default false). */
    var isCautionAutoAllowedInFullAuto: Boolean
        get() = prefs.getBoolean("approval_caution_auto_full", false)
        set(value) = prefs.edit().putBoolean("approval_caution_auto_full", value).apply()

    /** Comma-separated allowlist of actions allowed to auto-run under SAFE_AUTO/FULL_AUTO. */
    var approvalAllowlist: String
        get() = prefs.getString("approval_allowlist", "") ?: ""
        set(value) = prefs.edit().putString("approval_allowlist", value).apply()

    // --- Native RAG Subsystem Settings ---

    var isRagEnabled: Boolean
        get() = prefs.getBoolean("rag_enabled", true)
        set(value) { prefs.edit().putBoolean("rag_enabled", value).commit() }

    var isSemanticSearchEnabled: Boolean
        get() = prefs.getBoolean("rag_semantic_search_enabled", true)
        set(value) { prefs.edit().putBoolean("rag_semantic_search_enabled", value).commit() }

    var isHybridSearchEnabled: Boolean
        get() = prefs.getBoolean("rag_hybrid_search_enabled", true)
        set(value) { prefs.edit().putBoolean("rag_hybrid_search_enabled", value).commit() }

    var ragTopK: Int
        get() = prefs.getInt("rag_top_k", 5)
        set(value) { prefs.edit().putInt("rag_top_k", value.coerceIn(1, 20)).commit() }

    var ragMinRelevance: Float
        get() = prefs.getFloat("rag_min_relevance", 0.35f)
        set(value) { prefs.edit().putFloat("rag_min_relevance", value.coerceIn(0.0f, 1.0f)).commit() }

    var ragMaxContextCharacters: Int
        get() = prefs.getInt("rag_max_context_chars", 6000)
        set(value) { prefs.edit().putInt("rag_max_context_chars", value.coerceIn(500, 20000)).commit() }

    var ragEmbeddingProvider: String
        get() = prefs.getString("rag_embedding_provider", "local") ?: "local"
        set(value) { prefs.edit().putString("rag_embedding_provider", value).commit() }

    var isAutoIndexMemoriesEnabled: Boolean
        get() = prefs.getBoolean("rag_auto_index_memories", true)
        set(value) { prefs.edit().putBoolean("rag_auto_index_memories", value).commit() }

    var ragLastIndexedTimestamp: Long
        get() = prefs.getLong("rag_last_indexed_timestamp", 0L)
        set(value) { prefs.edit().putLong("rag_last_indexed_timestamp", value).commit() }

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

    /** Voice persona for Gemini Live audio output. Options: Aoede, Kore, Fenrir, Puck, etc. */
    var liveVoiceName: String
        get() = prefs.getString("live_voice_name", "Aoede") ?: "Aoede"
        set(value) = prefs.edit().putString("live_voice_name", value).apply()

    fun setNotificationAppEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean("notif_app_enabled_$packageName", enabled).apply()
    }

    fun isNotificationAppEnabled(packageName: String): Boolean {
        // Default to false for privacy safety: ALL APPS DEFAULT TO OFF
        return prefs.getBoolean("notif_app_enabled_$packageName", false)
    }

    var isWhatsAppAutoReplyEnabled: Boolean
        get() = prefs.getBoolean("whatsapp_auto_reply_enabled", false)
        set(value) = prefs.edit().putBoolean("whatsapp_auto_reply_enabled", value).apply()

    // --- UI Automation Agent Settings (OpenRouter Primary / Ox Alpha / Gemini Fallback) ---

    var uiAgentProvider: String
        get() = prefs.getString(KEY_UI_AGENT_PROVIDER, DEFAULT_UI_AGENT_PROVIDER) ?: DEFAULT_UI_AGENT_PROVIDER
        set(value) = prefs.edit().putString(KEY_UI_AGENT_PROVIDER, value).apply()

    var uiAgentApiKey: String
        get() = securePrefs.getString(KEY_UI_AGENT_API_KEY, "")?.trim() ?: ""
        set(value) = securePrefs.edit().putString(KEY_UI_AGENT_API_KEY, value.trim()).apply()

    var uiAgentModel: String
        get() = prefs.getString(KEY_UI_AGENT_MODEL, DEFAULT_UI_AGENT_MODEL) ?: DEFAULT_UI_AGENT_MODEL
        set(value) = prefs.edit().putString(KEY_UI_AGENT_MODEL, value).apply()

    val isUiAgentConfigured: Boolean
        get() = uiAgentApiKey.isNotBlank() || geminiApiKey.isNotBlank()

    fun hasValidGeminiApiKey(): Boolean = geminiApiKey.isNotBlank()

    fun hasValidOpenRouterApiKey(): Boolean = uiAgentApiKey.isNotBlank()

    fun hasAnyValidAiKey(): Boolean = hasValidGeminiApiKey() || hasValidOpenRouterApiKey()

    // --- Global MAX Operating State (Developer Mode / Master Switch) ---

    var isMaxEnabled: Boolean
        get() = prefs.getBoolean(KEY_MAX_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_MAX_ENABLED, value).commit() }
}

