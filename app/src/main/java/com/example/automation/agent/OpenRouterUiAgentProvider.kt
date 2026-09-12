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
 * Primary UI Automation Agent provider using OpenRouter.
 * Supports dynamic model selection with stealth/ox-alpha as preferred default.
 */
class OpenRouterUiAgentProvider : UiAgentProvider {

    companion object {
        const val PROVIDER_ID = "openrouter"
        const val DEFAULT_MODEL = "stealth/ox-alpha"
        private const val TAG = "OpenRouterUiAgent"
        private const val API_BASE_URL = "https://openrouter.ai/api/v1"
        private const val TIMEOUT_MS = 25_000

        val CURATED_COMPATIBLE_MODELS = listOf(
            UiAgentModelInfo(
                id = "stealth/ox-alpha",
                name = "Ox Alpha (Stealth)",
                description = "Primary agentic reasoning & vision model for MAX UI automation",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 128000,
                isPreferred = true
            ),
            UiAgentModelInfo(
                id = "anthropic/claude-3.5-sonnet",
                name = "Claude 3.5 Sonnet",
                description = "High precision multi-step visual reasoning and tool calling",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 200000,
                isPreferred = false
            ),
            UiAgentModelInfo(
                id = "openai/gpt-4o",
                name = "GPT-4o",
                description = "Fast visual grounding and robust function calling",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 128000,
                isPreferred = false
            ),
            UiAgentModelInfo(
                id = "google/gemini-2.0-flash-001",
                name = "Gemini 2.0 Flash",
                description = "Ultra-low latency multimodal execution",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 1000000,
                isPreferred = false
            ),
            UiAgentModelInfo(
                id = "qwen/qwen-2.5-vl-72b-instruct",
                name = "Qwen 2.5 VL 72B",
                description = "Open visual-language model with screen coordinate grounding",
                supportsVision = true,
                supportsToolCalling = true,
                contextLength = 128000,
                isPreferred = false
            )
        )
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "OpenRouter (Ox Alpha)"

    override suspend fun isConfigured(context: Context): Boolean {
        val settings = SettingsManager(context)
        return settings.uiAgentApiKey.isNotBlank()
    }

    override suspend fun testConnection(context: Context, apiKey: String, model: String): UiAgentTestResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext UiAgentTestResult(
                success = false,
                message = "OpenRouter API Key is missing",
                provider = id,
                model = model,
                errorCode = "MISSING_KEY"
            )
        }

        val targetModel = model.ifBlank { DEFAULT_MODEL }
        val startTime = System.currentTimeMillis()

        try {
            val url = URL("$API_BASE_URL/chat/completions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("HTTP-Referer", "https://max.assistant.ai")
                setRequestProperty("X-Title", "MAX AI Agent")
                doOutput = true
            }

            val requestBody = JSONObject().apply {
                put("model", targetModel)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Test connection ping. Respond with 'PONG'.")
                    })
                })
                put("max_tokens", 10)
            }

            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

            val responseCode = connection.responseCode
            val latency = System.currentTimeMillis() - startTime

            if (responseCode in 200..299) {
                Log.d(TAG, "OpenRouter testConnection SUCCESS in ${latency}ms for model $targetModel")
                UiAgentTestResult(
                    success = true,
                    message = "Connected to $targetModel (${latency}ms)",
                    provider = id,
                    model = targetModel,
                    latencyMs = latency
                )
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val errorMsg = try {
                    JSONObject(errorText).optJSONObject("error")?.optString("message") ?: errorText
                } catch (_: Exception) {
                    errorText
                }
                Log.w(TAG, "OpenRouter testConnection failed with code $responseCode: $errorMsg")
                UiAgentTestResult(
                    success = false,
                    message = "OpenRouter error ($responseCode): $errorMsg",
                    provider = id,
                    model = targetModel,
                    latencyMs = latency,
                    errorCode = "HTTP_$responseCode"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter testConnection network exception", e)
            UiAgentTestResult(
                success = false,
                message = "Network error: ${e.localizedMessage ?: "Failed to reach OpenRouter"}",
                provider = id,
                model = targetModel,
                latencyMs = System.currentTimeMillis() - startTime,
                errorCode = "NETWORK_ERROR"
            )
        }
    }

    override suspend fun getCompatibleModels(context: Context, apiKey: String): List<UiAgentModelInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext CURATED_COMPATIBLE_MODELS
        }

        try {
            val url = URL("$API_BASE_URL/models")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            if (connection.responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val data = json.optJSONArray("data") ?: JSONArray()

                val remoteModels = mutableListOf<UiAgentModelInfo>()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val modelId = item.optString("id")
                    val modelName = item.optString("name", modelId)
                    val description = item.optString("description", "")
                    val contextLength = item.optInt("context_length", 128000)

                    val architecture = item.optJSONObject("architecture")
                    val modality = architecture?.optString("modality", "") ?: ""
                    val inputModalities = item.optJSONArray("input_modalities")

                    var supportsVision = modality.contains("image") || modality.contains("multimodal")
                    if (!supportsVision && inputModalities != null) {
                        for (m in 0 until inputModalities.length()) {
                            if (inputModalities.getString(m).contains("image")) {
                                supportsVision = true
                                break
                            }
                        }
                    }

                    val isKnownUiModel = modelId in listOf(
                        "stealth/ox-alpha",
                        "anthropic/claude-3.5-sonnet",
                        "anthropic/claude-3-5-sonnet",
                        "openai/gpt-4o",
                        "openai/gpt-4o-mini",
                        "google/gemini-2.0-flash-001",
                        "google/gemini-2.5-pro-preview-03-04",
                        "google/gemini-flash-1.5",
                        "qwen/qwen-2.5-vl-72b-instruct"
                    )

                    if (isKnownUiModel || supportsVision) {
                        remoteModels.add(
                            UiAgentModelInfo(
                                id = modelId,
                                name = modelName,
                                description = description,
                                supportsVision = true,
                                supportsToolCalling = true,
                                contextLength = contextLength,
                                isPreferred = (modelId == DEFAULT_MODEL)
                            )
                        )
                    }
                }

                if (remoteModels.isNotEmpty()) {
                    if (remoteModels.none { it.id == DEFAULT_MODEL }) {
                        remoteModels.add(0, CURATED_COMPATIBLE_MODELS.first { it.id == DEFAULT_MODEL })
                    }
                    return@withContext remoteModels.sortedByDescending { if (it.isPreferred) 1 else 0 }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic model discovery failed; falling back to curated list: ${e.message}")
        }

        return@withContext CURATED_COMPATIBLE_MODELS
    }

    override suspend fun reasonNextAction(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>,
        apiKey: String,
        model: String
    ): UiAgentReasoningResult = withContext(Dispatchers.IO) {
        val targetModel = model.ifBlank { DEFAULT_MODEL }
        val effectiveKey = apiKey.ifBlank {
            return@withContext UiAgentReasoningResult.Error("OpenRouter API key is empty", errorCode = "MISSING_KEY", retryable = false)
        }

        try {
            val url = URL("$API_BASE_URL/chat/completions")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $effectiveKey")
                setRequestProperty("HTTP-Referer", "https://max.assistant.ai")
                setRequestProperty("X-Title", "MAX AI Agent")
                doOutput = true
            }

            val requestJson = buildChatCompletionPayload(task, observation, stepHistory, targetModel)
            OutputStreamWriter(connection.outputStream).use { it.write(requestJson.toString()) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseToolCallResponse(responseText)
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.w(TAG, "OpenRouter reasoning request failed with HTTP $responseCode: $errorBody")
                UiAgentReasoningResult.Error("OpenRouter error ($responseCode): $errorBody", errorCode = "HTTP_$responseCode", retryable = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter reasoning network error", e)
            UiAgentReasoningResult.Error("Network error: ${e.localizedMessage ?: "Failed to reach OpenRouter"}", errorCode = "NETWORK_ERROR", retryable = true)
        }
    }

    private fun buildChatCompletionPayload(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>,
        model: String
    ): JSONObject {
        val payload = JSONObject()
        payload.put("model", model)
        payload.put("temperature", 0.1)

        val messages = JSONArray()

        val systemPrompt = JSONObject().apply {
            put("role", "system")
            put("content", """
                You are MAX UI Automation Agent. You control an Android phone to accomplish user tasks.
                
                RULES:
                1. Inspect the screen screenshot and accessibility elements.
                2. Call ONE structured tool to interact with the device.
                3. Choose precise pixel coordinates for tap/swipe or launch apps.
                4. If the goal has been achieved, call finish_task(success=true, message='...').
                5. If impossible or blocked, call finish_task(success=false, message='...').
                6. Do NOT invent arbitrary tools. Use ONLY the provided tool functions.
            """.trimIndent())
        }
        messages.put(systemPrompt)

        val userContent = JSONArray()

        val textPrompt = StringBuilder().apply {
            append("TASK GOAL: ${task.goal}\n")
            task.targetPackage?.let { append("TARGET APP: $it\n") }
            append("FOREGROUND APP: ${observation.foregroundPackage ?: "Unknown"}\n")
            append("CURRENT STEP: ${stepHistory.size + 1} of ${task.maxSteps}\n\n")

            if (stepHistory.isNotEmpty()) {
                append("PREVIOUS ACTIONS TAKEN:\n")
                for (rec in stepHistory) {
                    append(" - Step ${rec.stepIndex}: ${rec.action.type} -> verified=${rec.verified} (${rec.verificationNote ?: ""})\n")
                }
                append("\n")
            }

            if (observation.visibleText.isNotBlank()) {
                append("VISIBLE TEXT ON SCREEN:\n")
                append(observation.visibleText.take(1500))
                append("\n\n")
            }

            if (observation.prunedElements.isNotEmpty()) {
                append("KEY ACCESSIBILITY NODES (id/text/bounds):\n")
                for (node in observation.prunedElements.take(20)) {
                    val bounds = node.boundsInScreen
                    val label = node.text ?: node.contentDescription ?: node.resourceId ?: "Element"
                    append(" - [$label] at [${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}] clickable=${node.isClickable}\n")
                }
            }
        }.toString()

        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", textPrompt)
        })

        observation.screenshotJpeg?.let { jpegBytes ->
            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$b64")
                })
            })
        }

        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        payload.put("messages", messages)
        payload.put("tools", buildToolDeclarations())
        payload.put("tool_choice", "auto")

        return payload
    }

    private fun buildToolDeclarations(): JSONArray {
        val tools = JSONArray()

        tools.put(createToolObject("tap", "Tap on screen coordinates", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("x", JSONObject().put("type", "integer").put("description", "X coordinate in pixels"))
                put("y", JSONObject().put("type", "integer").put("description", "Y coordinate in pixels"))
                put("reason", JSONObject().put("type", "string").put("description", "Why this position is being tapped"))
            })
            put("required", JSONArray().put("x").put("y"))
        }))

        tools.put(createToolObject("long_press", "Long press on screen coordinates", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("x", JSONObject().put("type", "integer"))
                put("y", JSONObject().put("type", "integer"))
                put("duration_ms", JSONObject().put("type", "integer").put("default", 800))
            })
            put("required", JSONArray().put("x").put("y"))
        }))

        tools.put(createToolObject("swipe", "Swipe across the screen", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("start_x", JSONObject().put("type", "integer"))
                put("start_y", JSONObject().put("type", "integer"))
                put("end_x", JSONObject().put("type", "integer"))
                put("end_y", JSONObject().put("type", "integer"))
                put("duration_ms", JSONObject().put("type", "integer").put("default", 400))
            })
            put("required", JSONArray().put("start_x").put("start_y").put("end_x").put("end_y"))
        }))

        tools.put(createToolObject("scroll", "Scroll in a direction", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("UP").put("DOWN").put("LEFT").put("RIGHT")))
            })
            put("required", JSONArray().put("direction"))
        }))

        tools.put(createToolObject("type_text", "Type text into currently focused input field", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("text", JSONObject().put("type", "string").put("description", "The text to type"))
            })
            put("required", JSONArray().put("text"))
        }))

        tools.put(createToolObject("press_back", "Press the Android system Back button", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("reason", JSONObject().put("type", "string"))
            })
        }))

        tools.put(createToolObject("press_home", "Press the Android system Home button", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("reason", JSONObject().put("type", "string"))
            })
        }))

        tools.put(createToolObject("launch_app", "Launch an application by its package name", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("package_name", JSONObject().put("type", "string").put("description", "Android package name, e.g. com.whatsapp"))
            })
            put("required", JSONArray().put("package_name"))
        }))

        tools.put(createToolObject("finish_task", "Declare task completion or unresolvable failure", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("success", JSONObject().put("type", "boolean").put("description", "Whether goal was achieved"))
                put("message", JSONObject().put("type", "string").put("description", "User-facing summary of result"))
            })
            put("required", JSONArray().put("success").put("message"))
        }))

        return tools
    }

    private fun createToolObject(name: String, description: String, parameters: JSONObject): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            })
        }
    }

    private fun parseToolCallResponse(responseText: String): UiAgentReasoningResult {
        try {
            val json = JSONObject(responseText)
            val choices = json.optJSONArray("choices") ?: return UiAgentReasoningResult.Error("No choices in response")
            if (choices.length() == 0) return UiAgentReasoningResult.Error("Empty choices array")

            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return UiAgentReasoningResult.Error("No message object in choice")

            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val call = toolCalls.getJSONObject(0)
                val func = call.getJSONObject("function")
                val name = func.getString("name")
                val argsString = func.optString("arguments", "{}")
                val args = JSONObject(argsString)

                return mapFunctionToAction(name, args)
            }

            // Fallback: Check if model embedded structured JSON in content
            val content = message.optString("content", "")
            val parsedJson = extractJsonFromContent(content)
            if (parsedJson != null) {
                val actionName = parsedJson.optString("action")
                    .ifEmpty { parsedJson.optString("tool") }
                    .ifEmpty { parsedJson.optString("name") }
                val args = parsedJson.optJSONObject("arguments")
                    ?: parsedJson.optJSONObject("args")
                    ?: parsedJson

                if (actionName.isNotEmpty()) {
                    return mapFunctionToAction(actionName, args)
                }
            }

            return UiAgentReasoningResult.Error("Model returned text without structured action: $content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse tool call response", e)
            return UiAgentReasoningResult.Error("Response parse error: ${e.message}")
        }
    }

    private fun extractJsonFromContent(content: String): JSONObject? {
        val trimmed = content.trim()
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

    private fun mapFunctionToAction(name: String, args: JSONObject): UiAgentReasoningResult {
        return when (name.lowercase()) {
            "tap" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.TAP,
                    x = args.optInt("x", 500),
                    y = args.optInt("y", 1000),
                    reason = args.optString("reason", "Tap coordinates")
                ),
                reasoning = args.optString("reason", "Tap at (${args.optInt("x")}, ${args.optInt("y")})")
            )
            "long_press" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.LONG_PRESS,
                    x = args.optInt("x", 500),
                    y = args.optInt("y", 1000),
                    durationMs = args.optLong("duration_ms", 800L)
                ),
                reasoning = "Long press at (${args.optInt("x")}, ${args.optInt("y")})"
            )
            "swipe" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.SWIPE,
                    x = args.optInt("start_x", 500),
                    y = args.optInt("start_y", 1200),
                    endX = args.optInt("end_x", 500),
                    endY = args.optInt("end_y", 400),
                    durationMs = args.optLong("duration_ms", 400L)
                ),
                reasoning = "Swipe from (${args.optInt("start_x")}, ${args.optInt("start_y")}) to (${args.optInt("end_x")}, ${args.optInt("end_y")})"
            )
            "scroll" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.SCROLL,
                    direction = args.optString("direction", "DOWN")
                ),
                reasoning = "Scroll ${args.optString("direction", "DOWN")}"
            )
            "type_text" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.TYPE,
                    text = args.optString("text", "")
                ),
                reasoning = "Type text: '${args.optString("text")}'"
            )
            "press_back" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.KEY_BACK),
                reasoning = args.optString("reason", "Press back")
            )
            "press_home" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(type = UiAction.Type.KEY_HOME),
                reasoning = args.optString("reason", "Press home")
            )
            "launch_app" -> UiAgentReasoningResult.ActionDecision(
                action = UiAction(
                    type = UiAction.Type.LAUNCH_APP,
                    packageName = args.optString("package_name")
                ),
                reasoning = "Launch package: ${args.optString("package_name")}"
            )
            "finish_task" -> UiAgentReasoningResult.FinishTask(
                success = args.optBoolean("success", true),
                message = args.optString("message", "Task completed"),
                reasoning = args.optString("message", "Finished")
            )
            else -> UiAgentReasoningResult.Error("Unknown tool called: $name")
        }
    }
}
