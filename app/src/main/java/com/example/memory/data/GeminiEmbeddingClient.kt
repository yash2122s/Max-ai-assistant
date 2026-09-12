package com.example.memory.data

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object GeminiEmbeddingClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun getApiKey(context: Context): String {
        return try { com.example.data.preferences.SettingsManager(context).geminiApiKey.trim() } catch (e: Exception) { "" }
    }

    /**
     * Suspend-compatible async embedding generation.
     * Uses OkHttp enqueue() + suspendCancellableCoroutine — does NOT block the calling thread.
     */
    suspend fun generateEmbeddingSuspend(text: String, apiKey: String): FloatArray? {
        if (text.isBlank() || apiKey.isBlank()) return null
        return try {
            val request = buildEmbeddingRequest(text, apiKey)
            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                continuation.invokeOnCancellation {
                    call.cancel()
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.w("GeminiEmbeddingClient", "Async embedding request failed: ${e.message}")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            parseEmbeddingResponse(response)
                        } catch (e: Exception) {
                            Log.w("GeminiEmbeddingClient", "Error parsing embedding response: ${e.message}")
                            null
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("GeminiEmbeddingClient", "Error in async embedding generation", e)
            null
        }
    }

    /**
     * Synchronous embedding generation — blocks the calling thread up to 16s.
     * Use generateEmbeddingSuspend() in coroutine contexts instead.
     */
    fun generateEmbedding(text: String, apiKey: String): FloatArray? {
        if (text.isBlank() || apiKey.isBlank()) return null
        try {
            val request = buildEmbeddingRequest(text, apiKey)
            httpClient.newCall(request).execute().use { response ->
                return parseEmbeddingResponse(response)
            }
        } catch (e: Exception) {
            Log.e("GeminiEmbeddingClient", "Error generating embedding for text", e)
            return null
        }
    }

    private fun buildEmbeddingRequest(text: String, apiKey: String): Request {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent"

        val jsonPayload = JSONObject().apply {
            put("model", "models/text-embedding-004")
            put("content", JSONObject().apply {
                put("parts", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", text)
                    })
                })
            })
        }.toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toRequestBody(mediaType)
        return Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(body)
            .build()
    }

    private fun parseEmbeddingResponse(response: Response): FloatArray? {
        if (!response.isSuccessful) {
            Log.w("GeminiEmbeddingClient", "Embedding request failed: code=${response.code}")
            return null
        }

        val responseStr = response.body?.string() ?: return null
        val responseObj = JSONObject(responseStr)
        val embeddingObj = responseObj.optJSONObject("embedding") ?: return null
        val valuesArray = embeddingObj.optJSONArray("values") ?: return null

        val result = FloatArray(valuesArray.length())
        for (i in 0 until valuesArray.length()) {
            result[i] = valuesArray.getDouble(i).toFloat()
        }
        return result
    }
}
