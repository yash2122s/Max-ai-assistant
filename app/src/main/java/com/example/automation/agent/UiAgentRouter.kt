package com.example.automation.agent

import android.content.Context
import android.util.Log
import com.example.data.preferences.SettingsManager

data class SelectedProvider(
    val provider: UiAgentProvider,
    val apiKey: String,
    val model: String
)

/**
 * Dynamically selects the UI Agent provider based on configuration, availability, and fallback priority.
 *
 * Priority:
 * 1. OpenRouter (Primary) -> stealth/ox-alpha or user-selected model
 * 2. Gemini Live (Fallback) -> gemini-2.0-flash
 */
class UiAgentRouter(private val context: Context) {

    companion object {
        private const val TAG = "UiAgentRouter"
    }

    private val openRouterProvider = OpenRouterUiAgentProvider()
    private val geminiProvider = GeminiUiAgentProvider()

    fun selectProvider(context: Context): SelectedProvider {
        val settings = SettingsManager(context)
        val openRouterKey = settings.uiAgentApiKey
        val openRouterModel = settings.uiAgentModel.ifBlank { OpenRouterUiAgentProvider.DEFAULT_MODEL }

        // 1. Primary: OpenRouter if configured
        if (openRouterKey.isNotBlank()) {
            Log.d(TAG, "Routing to OpenRouter with model '$openRouterModel'")
            return SelectedProvider(
                provider = openRouterProvider,
                apiKey = openRouterKey,
                model = openRouterModel
            )
        }

        // 2. Fallback: Gemini UI Agent
        val geminiKey = settings.geminiApiKey
        if (geminiKey.isNotBlank()) {
            Log.d(TAG, "OpenRouter unconfigured; Falling back to Gemini UI Agent")
            return SelectedProvider(
                provider = geminiProvider,
                apiKey = geminiKey,
                model = GeminiUiAgentProvider.DEFAULT_MODEL
            )
        }

        // 3. Unconfigured fallback
        Log.w(TAG, "No API keys configured for UI Agent; defaulting to OpenRouter provider")
        return SelectedProvider(
            provider = openRouterProvider,
            apiKey = "",
            model = openRouterModel
        )
    }

    fun getProvider(providerId: String): UiAgentProvider {
        return when (providerId.lowercase()) {
            "gemini" -> geminiProvider
            else -> openRouterProvider
        }
    }
}
