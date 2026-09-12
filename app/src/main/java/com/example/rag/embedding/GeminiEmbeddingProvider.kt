package com.example.rag.embedding

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud embedding provider using Google's text-embedding-004 (768 dimensions).
 * Strictly opt-in via Settings; falls back safely on connection/API issues.
 */
class GeminiEmbeddingProvider(
    private val context: Context
) : RagEmbeddingProvider {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val fallbackLocal = LocalEmbeddingProvider(768)

    override fun modelName(): String = "models/text-embedding-004"

    override fun dimensions(): Int = 768

    private fun getApiKey(): String {
        return try { SettingsManager(context).geminiApiKey.trim() } catch (e: Exception) { "" }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return@withContext FloatArray(dimensions()) { 0.0f }
        }

        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext fallbackLocal.embed(cleanText)
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent"
            val jsonPayload = JSONObject().apply {
                put("model", "models/text-embedding-004")
                put("content", JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", cleanText)
                        })
                    })
                })
            }.toString()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonPayload.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("GeminiEmbeddingProvider", "Embedding API call failed with code=${response.code}, using fallback")
                    return@withContext fallbackLocal.embed(cleanText)
                }

                val responseStr = response.body?.string() ?: return@withContext fallbackLocal.embed(cleanText)
                val responseObj = JSONObject(responseStr)
                val embeddingObj = responseObj.optJSONObject("embedding") ?: return@withContext fallbackLocal.embed(cleanText)
                val valuesArray = embeddingObj.optJSONArray("values") ?: return@withContext fallbackLocal.embed(cleanText)

                val result = FloatArray(valuesArray.length())
                for (i in 0 until valuesArray.length()) {
                    result[i] = valuesArray.getDouble(i).toFloat()
                }
                result
            }
        } catch (e: Exception) {
            Log.w("GeminiEmbeddingProvider", "Exception generating cloud embedding, using fallback: ${e.message}")
            fallbackLocal.embed(cleanText)
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        texts.map { embed(it) }
    }
}
