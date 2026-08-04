package com.example.memory.data

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiEmbeddingClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        return if (savedKey.isNotEmpty()) savedKey else BuildConfig.GEMINI_API_KEY
    }

    fun generateEmbedding(text: String, apiKey: String): FloatArray? {
        if (text.isBlank() || apiKey.isBlank()) return null
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent?key=$apiKey"
            
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
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
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
        } catch (e: Exception) {
            Log.e("GeminiEmbeddingClient", "Error generating embedding for text", e)
            return null
        }
    }
}
