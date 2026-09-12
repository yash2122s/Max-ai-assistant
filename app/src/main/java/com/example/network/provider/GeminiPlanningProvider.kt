package com.example.network.provider

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.automation.state.ActionStep
import com.example.data.preferences.SettingsManager
import com.example.network.PromptBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiPlanningProvider(
    private val context: Context? = null,
    private val apiKeyProvider: (() -> String)? = null
) : LLMPlanningProvider {

    override val providerId: String = "gemini"
    override val defaultModel: String = "gemini-2.5-flash"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun resolveApiKey(): String {
        apiKeyProvider?.invoke()?.let {
            if (it.isNotBlank()) return it
        }

        if (context != null) {
            val secureKey = try {
                SettingsManager(context).geminiApiKey.trim()
            } catch (e: Exception) {
                ""
            }
            if (secureKey.isNotBlank()) return secureKey
        }

        return ""
    }

    override suspend fun generatePlan(request: PlanningRequest): PlanningResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val apiKey = resolveApiKey()
        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank. Cannot generate plan.")
            return@withContext PlanningResponse(
                success = false,
                errorMessage = "Missing or blank API key for Gemini Planning.",
                durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
            )
        }
        if (request.goal.isBlank()) {
            Log.e(TAG, "Goal is blank. Cannot generate plan.")
            return@withContext PlanningResponse(
                success = false,
                errorMessage = "Empty user goal.",
                durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
            )
        }

        val targetModel = request.modelName?.takeIf { it.isNotBlank() } ?: defaultModel

        try {
            val systemInstructionText = PromptBuilder.buildPlanningSystemInstruction()
            val userContentText = PromptBuilder.buildPlanningUserContent(request.goal, request.screenContext)

            // Build Gemini REST request payload with systemInstruction and structured JSON output schema
            val requestJson = JsonObject().apply {
                val systemInstructionObj = JsonObject().apply {
                    val parts = JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("text", systemInstructionText)
                        })
                    }
                    add("parts", parts)
                }
                add("systemInstruction", systemInstructionObj)

                val contents = JsonArray().apply {
                    add(JsonObject().apply {
                        val parts = JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("text", userContentText)
                            })
                        }
                        add("parts", parts)
                    })
                }
                add("contents", contents)

                val generationConfig = JsonObject().apply {
                    addProperty("responseMimeType", "application/json")
                    val responseSchema = JsonObject().apply {
                        addProperty("type", "ARRAY")
                        val items = JsonObject().apply {
                            addProperty("type", "OBJECT")
                            val properties = JsonObject().apply {
                                add("stepId", JsonObject().apply { addProperty("type", "STRING") })
                                add("actionType", JsonObject().apply { addProperty("type", "STRING") })
                                add("targetText", JsonObject().apply { addProperty("type", "STRING") })
                                add("resourceId", JsonObject().apply { addProperty("type", "STRING") })
                                add("contentDescription", JsonObject().apply { addProperty("type", "STRING") })
                            }
                            add("properties", properties)
                            val required = JsonArray().apply {
                                add("stepId")
                                add("actionType")
                            }
                            add("required", required)
                        }
                        add("items", items)
                    }
                    add("responseSchema", responseSchema)
                }
                add("generationConfig", generationConfig)
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent"
            val body = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)

            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(body)
                .build()


            Log.d(TAG, "Sending REST planning request for goal: '${request.goal}' to model '$targetModel'...")
            val response = client.newCall(httpRequest).execute()
            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP planning error ${response.code}: $errBody")
                return@withContext PlanningResponse(
                    success = false,
                    errorMessage = "HTTP ${response.code} error from Gemini API.",
                    durationMs = durationMs
                )
            }

            val responseBodyStr = response.body?.string() ?: ""
            if (responseBodyStr.isBlank()) {
                Log.e(TAG, "Empty response body received from Gemini API.")
                return@withContext PlanningResponse(
                    success = false,
                    errorMessage = "Empty response body from Gemini API.",
                    durationMs = durationMs
                )
            }

            val responseJson = JsonParser.parseString(responseBodyStr).asJsonObject
            
            // Extract usageMetadata telemetry safely if returned by Gemini API
            val tokenUsage = responseJson.getAsJsonObject("usageMetadata")?.let { usage ->
                val promptTokens = usage.get("promptTokenCount")?.asInt ?: 0
                val candidateTokens = usage.get("candidatesTokenCount")?.asInt ?: 0
                val totalTokens = usage.get("totalTokenCount")?.asInt ?: (promptTokens + candidateTokens)
                TokenUsage(
                    promptTokens = promptTokens,
                    candidateTokens = candidateTokens,
                    totalTokens = totalTokens
                )
            }

            if (tokenUsage != null) {
                Log.i(TAG, "Plan generated in ${durationMs}ms (Prompt: ${tokenUsage.promptTokens}, Output: ${tokenUsage.candidateTokens}, Total: ${tokenUsage.totalTokens} tokens)")
            } else {
                Log.i(TAG, "Plan generated in ${durationMs}ms (usageMetadata unavailable)")
            }

            val candidates = responseJson.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                Log.e(TAG, "No candidates returned in Gemini API response.")
                return@withContext PlanningResponse(
                    success = false,
                    errorMessage = "No response candidates returned.",
                    tokenUsage = tokenUsage,
                    durationMs = durationMs
                )
            }

            val firstCandidate = candidates.get(0).asJsonObject
            val parts = firstCandidate.getAsJsonObject("content")?.getAsJsonArray("parts")
            val textContent = parts?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

            if (textContent.isBlank()) {
                Log.e(TAG, "No text content in candidate part.")
                return@withContext PlanningResponse(
                    success = false,
                    errorMessage = "No text payload returned.",
                    tokenUsage = tokenUsage,
                    durationMs = durationMs
                )
            }

            Log.d(TAG, "Raw planning JSON response: $textContent")

            val stepsJsonArray = JsonParser.parseString(textContent).asJsonArray
            val stepsList = mutableListOf<ActionStep>()

            for (i in 0 until stepsJsonArray.size()) {
                val itemObj = stepsJsonArray.get(i).asJsonObject
                val stepId = itemObj.get("stepId")?.asString ?: "step_${i + 1}"
                val actionType = itemObj.get("actionType")?.asString ?: continue
                val targetText = itemObj.get("targetText")?.takeIf { !it.isJsonNull }?.asString
                val resourceId = itemObj.get("resourceId")?.takeIf { !it.isJsonNull }?.asString
                val contentDescription = itemObj.get("contentDescription")?.takeIf { !it.isJsonNull }?.asString
                val fallbackX = itemObj.get("fallbackX")?.takeIf { !it.isJsonNull }?.asFloat
                val fallbackY = itemObj.get("fallbackY")?.takeIf { !it.isJsonNull }?.asFloat

                stepsList.add(
                    ActionStep(
                        stepId = stepId,
                        actionType = actionType,
                        targetText = targetText,
                        resourceId = resourceId,
                        contentDescription = contentDescription,
                        fallbackX = fallbackX,
                        fallbackY = fallbackY
                    )
                )
            }

            PlanningResponse(
                success = true,
                plan = stepsList,
                rawResponse = textContent,
                tokenUsage = tokenUsage,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            val durationMs = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
            Log.e(TAG, "Exception during plan generation: ${e.message}", e)
            PlanningResponse(
                success = false,
                errorMessage = "Planning exception: ${e.message}",
                durationMs = durationMs
            )
        }
    }

    companion object {
        private const val TAG = "GeminiPlanningProvider"
    }
}
