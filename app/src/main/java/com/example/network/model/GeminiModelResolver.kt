package com.example.network.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic Gemini model resolver for UI Agent workloads.
 *
 * Instead of relying exclusively on hardcoded model lists that inevitably become obsolete,
 * this resolver queries Google's `v1beta/models` endpoint for the configured API key,
 * verifies `supportedGenerationMethods` includes `generateContent`, excludes any retired/disallowed
 * models (Gemini 1.5/2.0), isolates Live voice models, and dynamically selects the best active model.
 */
object GeminiModelResolver {

    private const val TAG = "GeminiModelResolver"
    private const val TIMEOUT_MS = 10_000
    private const val CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes

    private data class CachedModelList(
        val models: List<String>,
        val timestamp: Long
    )

    private val cache = ConcurrentHashMap<String, CachedModelList>()

    /**
     * Resolves the best available Gemini model supporting generateContent for the given API key.
     */
    suspend fun resolveBestUiModel(
        apiKey: String,
        preferredModel: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext UiAgentModels.sanitizeModelName(preferredModel)
        }

        val availableModels = getAvailableGenerateContentModels(apiKey)

        // 1. If preferred model is explicitly requested, available, and not disallowed -> use it
        if (!preferredModel.isNullOrBlank()) {
            val sanitizedPreferred = UiAgentModels.sanitizeModelName(preferredModel)
            if (availableModels.contains(sanitizedPreferred)) {
                Log.d(TAG, "Resolved requested model: $sanitizedPreferred")
                return@withContext sanitizedPreferred
            }
        }

        // 2. Primary choice: gemini-2.5-flash
        if (availableModels.contains(UiAgentModels.GEMINI_UI_AGENT_DEFAULT)) {
            Log.d(TAG, "Resolved primary UI model: ${UiAgentModels.GEMINI_UI_AGENT_DEFAULT}")
            return@withContext UiAgentModels.GEMINI_UI_AGENT_DEFAULT
        }

        // 3. Secondary fallback: gemini-3.5-flash
        if (availableModels.contains(UiAgentModels.GEMINI_UI_AGENT_FALLBACK)) {
            Log.d(TAG, "Primary unavailable; resolved fallback UI model: ${UiAgentModels.GEMINI_UI_AGENT_FALLBACK}")
            return@withContext UiAgentModels.GEMINI_UI_AGENT_FALLBACK
        }

        // 4. Tertiary choice: gemini-2.5-pro or any available valid Flash model
        val dynamicFlash = availableModels.firstOrNull { it.contains("flash", ignoreCase = true) }
        if (dynamicFlash != null) {
            Log.d(TAG, "Resolved dynamic flash model: $dynamicFlash")
            return@withContext dynamicFlash
        }

        // 5. Any valid supported model from the account
        val anySupported = availableModels.firstOrNull()
        if (anySupported != null) {
            Log.d(TAG, "Resolved supported model: $anySupported")
            return@withContext anySupported
        }

        // 6. Hard-safe fallback
        Log.w(TAG, "No models dynamically discovered; defaulting to ${UiAgentModels.GEMINI_UI_AGENT_DEFAULT}")
        return@withContext UiAgentModels.GEMINI_UI_AGENT_DEFAULT
    }

    /**
     * Queries Google's v1beta/models endpoint and returns sanitized model IDs supporting generateContent.
     */
    suspend fun getAvailableGenerateContentModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()

        // Check in-memory cache
        val keyHash = apiKey.hashCode().toString()
        val cached = cache[keyHash]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS && cached.models.isNotEmpty()) {
            return@withContext cached.models
        }

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val modelsArray = json.optJSONArray("models")

                val supportedList = mutableListOf<String>()
                if (modelsArray != null) {
                    for (i in 0 until modelsArray.length()) {
                        val item = modelsArray.optJSONObject(i) ?: continue
                        val rawName = item.optString("name", "")
                        val modelId = rawName.removePrefix("models/").trim()

                        // Check if model is blacklisted/disallowed (Gemini 1.5, 2.0, or Live voice)
                        if (UiAgentModels.DISALLOWED_OBSOLETE_MODELS.contains(modelId.lowercase()) ||
                            modelId.contains("2.0-flash") ||
                            modelId.contains("1.5-flash") ||
                            modelId.contains("3.8-live")) {
                            continue
                        }

                        // Check if model supports generateContent
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        var supportsGenerateContent = false
                        if (methods != null) {
                            for (m in 0 until methods.length()) {
                                if (methods.getString(m).equals("generateContent", ignoreCase = true)) {
                                    supportsGenerateContent = true
                                    break
                                }
                            }
                        }

                        if (supportsGenerateContent) {
                            supportedList.add(modelId)
                        }
                    }
                }

                if (supportedList.isNotEmpty()) {
                    cache[keyHash] = CachedModelList(supportedList, now)
                    Log.i(TAG, "Discovered ${supportedList.size} valid generateContent models for API key: $supportedList")
                    return@withContext supportedList
                }
            } else {
                Log.w(TAG, "Failed to list models from Gemini API: HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during dynamic Gemini model discovery: ${e.message}")
        }

        // Return cached or empty
        return@withContext cached?.models ?: emptyList()
    }

    fun clearCache() {
        cache.clear()
    }
}
