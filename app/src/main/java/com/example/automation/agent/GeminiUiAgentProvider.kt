package com.example.automation.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.network.model.GeminiModelResolver
import com.example.network.model.UiAgentModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * UI Automation Agent provider using Google Gemini REST generateContent API.
 * Uses gemini-2.5-flash by default with gemini-3.5-flash fallback.
 */
class GeminiUiAgentProvider : UiAgentProvider {

    companion object {
        const val PROVIDER_ID = "gemini"
        const val DEFAULT_MODEL = UiAgentModels.GEMINI_UI_AGENT_DEFAULT
        const val FALLBACK_MODEL = UiAgentModels.GEMINI_UI_AGENT_FALLBACK
        private const val TAG = "GeminiUiAgentProvider"
        private const val TIMEOUT_MS = 25_000

        internal fun buildGenerateContentUrl(model: String): String =
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        internal fun configureGeminiRequest(connection: HttpURLConnection, apiKey: String) {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
        }
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Google Gemini (UI Reasoning)"

    override suspend fun isConfigured(context: Context): Boolean {
        val settings = SettingsManager(context)
        return settings.geminiApiKey.isNotBlank()
    }

    override suspend fun testConnection(context: Context, apiKey: String, model: String): UiAgentTestResult = withContext(Dispatchers.IO) {
        val effectiveKey = apiKey.ifBlank { SettingsManager(context).geminiApiKey }
        if (effectiveKey.isBlank()) {
            return@withContext UiAgentTestResult(
                success = false,
                message = "Gemini API Key is missing",
                provider = id,
                model = UiAgentModels.sanitizeModelName(model),
                errorCode = "MISSING_KEY"
            )
        }

        val targetModel = UiAgentModels.sanitizeModelName(model)
        val startTime = System.currentTimeMillis()
        var connection: HttpURLConnection? = null

        try {
            val url = URL(buildGenerateContentUrl(targetModel))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                configureGeminiRequest(this, effectiveKey)
                doOutput = true
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", "Ping test. Respond with OK."))
                        })
                    })
                })
            }

            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

            val responseCode = connection.responseCode
            val latency = System.currentTimeMillis() - startTime

            if (responseCode in 200..299) {
                connection.inputStream.use { }
                UiAgentTestResult(
                    success = true,
                    message = "Connected to Gemini ($targetModel) in ${latency}ms",
                    provider = id,
                    model = targetModel,
                    latencyMs = latency
                )
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Gemini test connection failed with HTTP $responseCode ($targetModel)")
                val friendlyMessage = when (responseCode) {
                    404 -> "Model '$targetModel' is unavailable on Gemini API."
                    429 -> "Gemini API rate limit reached."
                    401, 403 -> "Invalid Gemini API Key or authentication failed."
                    else -> "Gemini API returned error ($responseCode)."
                }
                UiAgentTestResult(
                    success = false,
                    message = friendlyMessage,
                    provider = id,
                    model = targetModel,
                    latencyMs = latency,
                    errorCode = "HTTP_$responseCode"
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini test connection error: ${e.javaClass.simpleName}")
            UiAgentTestResult(
                success = false,
                message = "Network error: Failed to reach Gemini",
                provider = id,
                model = targetModel,
                latencyMs = System.currentTimeMillis() - startTime,
                errorCode = "NETWORK_ERROR"
            )
        } finally {
            // HttpURLConnection is not Closeable; disconnect releases its socket and streams.
            connection?.disconnect()
        }
    }

    override suspend fun getCompatibleModels(context: Context, apiKey: String): List<UiAgentModelInfo> = withContext(Dispatchers.IO) {
        val effectiveKey = apiKey.ifBlank { SettingsManager(context).geminiApiKey }
        val dynamicallyDiscovered = if (effectiveKey.isNotBlank()) {
            GeminiModelResolver.getAvailableGenerateContentModels(effectiveKey)
        } else {
            emptyList()
        }

        if (dynamicallyDiscovered.isNotEmpty()) {
            return@withContext dynamicallyDiscovered.map { modelId ->
                val isDefault = (modelId == UiAgentModels.GEMINI_UI_AGENT_DEFAULT)
                UiAgentModelInfo(
                    id = modelId,
                    name = when (modelId) {
                        "gemini-3.8-flash" -> "Gemini 3.8 Flash"
                        "gemini-3.5-flash" -> "Gemini 3.5 Flash"
                        "gemini-2.5-pro" -> "Gemini 2.5 Pro"
                        else -> modelId.replace("-", " ").replaceFirstChar { it.uppercase() }
                    },
                    description = if (isDefault) "Primary multimodal agentic reasoning model" else "Discovered active account model",
                    supportsVision = true,
                    supportsToolCalling = true,
                    contextLength = 1000000,
                    isPreferred = isDefault
                )
            }.sortedByDescending { if (it.isPreferred) 1 else 0 }
        }

        listOf(
            UiAgentModelInfo(
                id = UiAgentModels.GEMINI_UI_AGENT_DEFAULT,
                name = "Gemini 3.8 Flash",
                description = "Fast, state-of-the-art multimodal agentic reasoning and visual grounding",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 1000000,
                isPreferred = true
            ),
            UiAgentModelInfo(
                id = UiAgentModels.GEMINI_UI_AGENT_FALLBACK,
                name = "Gemini 3.5 Flash",
                description = "High-efficiency multimodal execution and reasoning fallback",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 1000000,
                isPreferred = false
            )
        )
    }

    override suspend fun reasonNextAction(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>,
        apiKey: String,
        model: String
    ): UiAgentReasoningResult = withContext(Dispatchers.IO) {
        val effectiveKey = apiKey.ifBlank {
            return@withContext UiAgentReasoningResult.Error("Gemini API key is missing", errorCode = "MISSING_KEY", retryable = false)
        }
        val targetModel = GeminiModelResolver.resolveBestUiModel(effectiveKey, model)
        var connection: HttpURLConnection? = null

        try {
            val url = URL(buildGenerateContentUrl(targetModel))
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                configureGeminiRequest(this, effectiveKey)
                doOutput = true
            }

            val requestJson = buildGeminiPayload(task, observation, stepHistory)
            OutputStreamWriter(connection.outputStream).use { it.write(requestJson.toString()) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseGeminiResponse(responseText)
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Gemini reasoning request failed with HTTP $responseCode ($targetModel)")
                
                // If 404 (model not available) and targetModel wasn't already the fallback, attempt fallback model
                if (responseCode == 404 && targetModel != UiAgentModels.GEMINI_UI_AGENT_FALLBACK) {
                    Log.i(TAG, "Attempting automatic fallback to ${UiAgentModels.GEMINI_UI_AGENT_FALLBACK}...")
                    return@withContext executeReasoningRequest(
                        task, observation, stepHistory, effectiveKey, UiAgentModels.GEMINI_UI_AGENT_FALLBACK
                    )
                }

                val userFriendlyMessage = when (responseCode) {
                    404 -> "UI Agent model ($targetModel) is temporarily unavailable. Reconnecting with fallback..."
                    429 -> "UI Agent reasoning rate limit reached. Retrying..."
                    401, 403 -> "UI Agent authentication failed. Please verify API key."
                    in 500..599 -> "UI Agent reasoning service is experiencing high load. Retrying..."
                    else -> "UI Agent is temporarily unavailable. Reconnecting..."
                }

                UiAgentReasoningResult.Error(
                    message = userFriendlyMessage,
                    errorCode = "HTTP_$responseCode",
                    retryable = (responseCode != 401 && responseCode != 403)
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini reasoning network error: ${e.javaClass.simpleName}")
            UiAgentReasoningResult.Error(
                message = "UI Agent network connection issue. Retrying...",
                errorCode = "NETWORK_ERROR",
                retryable = true
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun executeReasoningRequest(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>,
        apiKey: String,
        targetModel: String
    ): UiAgentReasoningResult {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(buildGenerateContentUrl(targetModel)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                configureGeminiRequest(this, apiKey)
                doOutput = true
            }

            val requestJson = buildGeminiPayload(task, observation, stepHistory)
            OutputStreamWriter(connection.outputStream).use { it.write(requestJson.toString()) }

            val responseCode = connection.responseCode
            return if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseGeminiResponse(responseText)
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Fallback Gemini reasoning request failed with HTTP $responseCode ($targetModel)")
                UiAgentReasoningResult.Error("UI Agent is temporarily unavailable. Reconnecting...", errorCode = "HTTP_$responseCode")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return UiAgentReasoningResult.Error("UI Agent network connection issue.", errorCode = "NETWORK_ERROR")
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildGeminiPayload(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>
    ): JSONObject {
        val payload = JSONObject()

        val contents = JSONArray()
        val userContent = JSONObject().apply { put("role", "user") }
        val parts = JSONArray()

        val promptText = StringBuilder().apply {
            append("You are MAX UI Automation Agent.\n")
            append("TASK GOAL: ${task.goal}\n")
            task.targetPackage?.let { append("TARGET APP: $it\n") }
            append("FOREGROUND APP: ${observation.foregroundPackage ?: "Unknown"}\n")
            append("CURRENT STEP: ${stepHistory.size + 1} of ${task.maxSteps}\n\n")

            if (stepHistory.isNotEmpty()) {
                append("PREVIOUS ACTIONS:\n")
                for (rec in stepHistory) {
                    append(" - Step ${rec.stepIndex}: ${rec.action.type} -> verified=${rec.verified}\n")
                }
                append("\n")
            }

            if (observation.visibleText.isNotBlank()) {
                append("VISIBLE TEXT ON SCREEN:\n")
                append(observation.visibleText.take(1500))
                append("\n\n")
            }
        }.toString()

        parts.put(JSONObject().put("text", promptText))

        observation.screenshotJpeg?.let { jpegBytes ->
            parts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
                })
            })
        }

        userContent.put("parts", parts)
        contents.put(userContent)
        payload.put("contents", contents)

        val tools = JSONArray().apply {
            put(JSONObject().apply {
                put("function_declarations", JSONArray().apply {
                    put(createFuncDecl("tap", "Tap coordinate", listOf("x", "y")))
                    put(createFuncDecl("swipe", "Swipe screen", listOf("start_x", "start_y", "end_x", "end_y")))
                    put(createFuncDecl("type_text", "Type text", listOf("text")))
                    put(createFuncDecl("press_back", "Back button", emptyList()))
                    put(createFuncDecl("press_home", "Home button", emptyList()))
                    put(createFuncDecl("launch_app", "Launch app", listOf("package_name")))
                    put(createFuncDecl("finish_task", "Complete task", listOf("success", "message")))
                })
            })
        }
        payload.put("tools", tools)

        return payload
    }

    private fun createFuncDecl(name: String, description: String, requiredParams: List<String>): JSONObject {
        val props = JSONObject()
        for (p in requiredParams) {
            props.put(p, JSONObject().put("type", if (p in listOf("x", "y", "start_x", "start_y", "end_x", "end_y")) "INTEGER" else if (p == "success") "BOOLEAN" else "STRING"))
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (requiredParams.isNotEmpty()) {
                put("required", JSONArray(requiredParams))
            }
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    private fun parseGeminiResponse(responseText: String): UiAgentReasoningResult {
        try {
            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates") ?: return UiAgentReasoningResult.Error("No candidates in response")
            if (candidates.length() == 0) return UiAgentReasoningResult.Error("Empty candidates array")

            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return UiAgentReasoningResult.Error("No content in candidate")
            val parts = content.optJSONArray("parts") ?: return UiAgentReasoningResult.Error("No parts in content")

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val funcCall = part.optJSONObject("functionCall")
                if (funcCall != null) {
                    val name = funcCall.getString("name")
                    val args = funcCall.optJSONObject("args") ?: JSONObject()
                    return mapGeminiAction(name, args)
                }

                // Check text for JSON
                val text = part.optString("text", "")
                val extracted = extractJson(text)
                if (extracted != null) {
                    val actName = extracted.optString("action").ifEmpty { extracted.optString("name") }
                    val args = extracted.optJSONObject("arguments") ?: extracted.optJSONObject("args") ?: extracted
                    if (actName.isNotEmpty()) {
                        return mapGeminiAction(actName, args)
                    }
                }
            }

            val text = parts.getJSONObject(0).optString("text", "")
            return UiAgentReasoningResult.Error("Gemini returned text without action: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gemini response", e)
            return UiAgentReasoningResult.Error("Gemini parse error: ${e.message}")
        }
    }

    private fun extractJson(text: String): JSONObject? {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            return try {
                JSONObject(trimmed.substring(start, end + 1))
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun mapGeminiAction(name: String, args: JSONObject): UiAgentReasoningResult {
        return when (name.lowercase()) {
            "tap" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.TAP, x = optionalCoordinate(args, "x"), y = optionalCoordinate(args, "y")),
                reasoning = "Gemini tap at (${optionalCoordinate(args, "x")}, ${optionalCoordinate(args, "y")})"
            )
            "swipe" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.SWIPE,
                    x = optionalCoordinate(args, "start_x"),
                    y = optionalCoordinate(args, "start_y"),
                    endX = optionalCoordinate(args, "end_x"),
                    endY = optionalCoordinate(args, "end_y")
                ),
                reasoning = "Gemini swipe"
            )
            "type_text" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.TYPE, text = args.optString("text", "")),
                reasoning = "Gemini type text: ${args.optString("text")}"
            )
            "press_back" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.KEY_BACK),
                reasoning = "Gemini press back"
            )
            "press_home" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.KEY_HOME),
                reasoning = "Gemini press home"
            )
            "launch_app" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.LAUNCH_APP, packageName = args.optString("package_name")),
                reasoning = "Gemini launch ${args.optString("package_name")}"
            )
            "finish_task" -> UiAgentReasoningResult.FinishTask(
                success = args.optBoolean("success", true),
                message = args.optString("message", "Task completed"),
                reasoning = "Gemini finished"
            )
            else -> UiAgentReasoningResult.Error("Unknown function call: $name")
        }
    }

    private fun optionalCoordinate(args: JSONObject, key: String): Int? {
        val value = args.opt(key)
        if (value !is Number) return null
        val doubleValue = value.toDouble()
        if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0) return null
        if (doubleValue < Int.MIN_VALUE || doubleValue > Int.MAX_VALUE) return null
        return doubleValue.toInt()
    }
}
