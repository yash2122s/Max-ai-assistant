package com.example.automation.agent

import android.content.Context
import android.util.Log
import com.example.data.preferences.SettingsManager

import com.example.network.model.UiAgentModels

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
 * 2. Gemini Live / REST (Fallback) -> gemini-2.5-flash (centralized in UiAgentModels)
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
            val sanitizedModel = if (openRouterModel.contains("gemini", ignoreCase = true)) {
                UiAgentModels.sanitizeModelName(openRouterModel)
            } else {
                openRouterModel
            }
            Log.d(TAG, "Routing to OpenRouter with model '$sanitizedModel'")
            return SelectedProvider(
                provider = openRouterProvider,
                apiKey = openRouterKey,
                model = sanitizedModel
            )
        }

        // 2. Fallback: Gemini UI Agent
        val geminiKey = settings.geminiApiKey
        if (geminiKey.isNotBlank()) {
            Log.d(TAG, "OpenRouter unconfigured; Falling back to Gemini UI Agent (${UiAgentModels.GEMINI_UI_AGENT_DEFAULT})")
            return SelectedProvider(
                provider = geminiProvider,
                apiKey = geminiKey,
                model = UiAgentModels.GEMINI_UI_AGENT_DEFAULT
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
