package com.example.automation.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback UI Automation Agent provider using Google Gemini REST API.
 */
class GeminiUiAgentProvider : UiAgentProvider {

    companion object {
        const val PROVIDER_ID = "gemini"
        const val DEFAULT_MODEL = "gemini-2.0-flash"
        private const val TAG = "GeminiUiAgentProvider"
        private const val TIMEOUT_MS = 25_000
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Google Gemini (Fallback)"

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
                model = model.ifBlank { DEFAULT_MODEL },
                errorCode = "MISSING_KEY"
            )
        }

        val targetModel = model.ifBlank { DEFAULT_MODEL }
        val startTime = System.currentTimeMillis()

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$effectiveKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
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
                UiAgentTestResult(
                    success = true,
                    message = "Connected to Gemini ($targetModel) in ${latency}ms",
                    provider = id,
                    model = targetModel,
                    latencyMs = latency
                )
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                UiAgentTestResult(
                    success = false,
                    message = "Gemini API error ($responseCode): $errorBody",
                    provider = id,
                    model = targetModel,
                    latencyMs = latency,
                    errorCode = "HTTP_$responseCode"
                )
            }
        } catch (e: Exception) {
            UiAgentTestResult(
                success = false,
                message = "Network error: ${e.localizedMessage ?: "Failed to reach Gemini"}",
                provider = id,
                model = targetModel,
                latencyMs = System.currentTimeMillis() - startTime,
                errorCode = "NETWORK_ERROR"
            )
        }
    }

    override suspend fun getCompatibleModels(context: Context, apiKey: String): List<UiAgentModelInfo> = withContext(Dispatchers.IO) {
        listOf(
            UiAgentModelInfo(
                id = "gemini-2.0-flash",
                name = "Gemini 2.0 Flash",
                description = "Fast multimodal agentic reasoning",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 1000000,
                isPreferred = true
            ),
            UiAgentModelInfo(
                id = "gemini-1.5-flash",
                name = "Gemini 1.5 Flash",
                description = "Lightweight multimodal execution",
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
        val targetModel = model.ifBlank { DEFAULT_MODEL }

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$effectiveKey"
            val url = URL(endpoint)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }

            val requestJson = buildGeminiPayload(task, observation, stepHistory)
            OutputStreamWriter(connection.outputStream).use { it.write(requestJson.toString()) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseGeminiResponse(responseText)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.w(TAG, "Gemini reasoning request failed with HTTP $responseCode: $errorBody")
                UiAgentReasoningResult.Error("Gemini error ($responseCode): $errorBody", errorCode = "HTTP_$responseCode", retryable = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini reasoning network error", e)
            UiAgentReasoningResult.Error("Network error: ${e.localizedMessage ?: "Failed to reach Gemini"}", errorCode = "NETWORK_ERROR", retryable = true)
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
                action = UiAction(type = UiAction.Type.TAP, x = args.optInt("x", 500), y = args.optInt("y", 1000)),
                reasoning = "Gemini tap at (${args.optInt("x")}, ${args.optInt("y")})"
            )
            "swipe" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.SWIPE,
                    x = args.optInt("start_x", 500),
                    y = args.optInt("start_y", 1200),
                    endX = args.optInt("end_x", 500),
                    endY = args.optInt("end_y", 400)
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
}
