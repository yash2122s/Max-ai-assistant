package com.example.telegram

import android.content.Context
import android.util.Log
import com.example.core.InputSource
import com.example.core.JarvisCore
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudSocketManager(private val context: Context) {
    private val TAG = "CloudSocketManager"
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val settings = SettingsManager(context)
    private var isRunning = false

    // Using a scope to allow auto-reconnects
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var reconnectJob: Job? = null

    // We keep a static reference so JarvisCore can reply directly
    companion object {
        private var instance: CloudSocketManager? = null
        
        fun sendReply(text: String) {
            instance?.webSocket?.send(text)
        }
        
        fun sendMessage(jsonString: String) {
            instance?.webSocket?.send(jsonString)
        }
    }

    init {
        instance = this
    }

    fun start() {
        Log.d(TAG, "start() called. Enabled=${settings.isTelegramBotEnabled}, URL='${settings.cloudBotUrl}', Secret='${if (settings.cloudAppSecret.isNotBlank()) "SET" else "EMPTY"}'")
        if (!settings.isTelegramBotEnabled) {
            Log.w(TAG, "Telegram bot is NOT enabled. Skipping.")
            return
        }
        if (settings.cloudBotUrl.isBlank() || settings.cloudAppSecret.isBlank()) {
            Log.w(TAG, "Cloud URL or Secret is blank. Skipping.")
            return
        }

        isRunning = true
        Log.d(TAG, "Starting Cloud Socket Manager...")
        connectWebSocket()
    }

    private fun connectWebSocket() {
        try {
            val url = "${settings.cloudBotUrl}?secret=${settings.cloudAppSecret}"
            Log.d(TAG, "Connecting to: ${settings.cloudBotUrl}")
            val request = Request.Builder().url(url).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected successfully!")
                    // Cancel any pending reconnects
                    reconnectJob?.cancel()
                    
                    // Send Handshake
                    val handshake = JSONObject().apply {
                        put("type", "handshake")
                        put("client_type", "android")
                        put("user_id", "default_user")
                        put("device_id", "android_device")
                        put("auth_token", settings.cloudAppSecret)
                        put("capabilities", JSONArray(listOf("flashlight", "calls", "whatsapp", "accessibility", "tts", "reminders", "system_navigation", "wifi")))
                    }
                    webSocket.send(handshake.toString())
                    
                    val getSettings = JSONObject().apply {
                        put("type", "get_settings")
                    }
                    webSocket.send(getSettings.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Received message from Cloud Bot: $text")
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type")
                        
                        if (type == "final_response") {
                            val payload = json.optJSONObject("payload")
                            val reply = payload?.optString("text", "") ?: json.optString("text", "")
                            if (reply.isNotBlank()) {
                                JarvisCore.processServerResponse(context, reply)
                            }
                        } else if (type == "action_request") {
                            Log.d(TAG, "Received action_request: $text")
                            com.example.automation.engine.ServerActionExecutor.execute(context, json)
                        } else if (type == "settings_sync") {
                            val payload = json.optJSONObject("payload")
                            val model = payload?.optString("preferred_model")
                            if (model != null) {
                                val settings = SettingsManager(context)
                                settings.cloudAiModel = model
                                Log.d(TAG, "Synced preferred model from server: $model")
                            }
                        } else {
                            Log.w(TAG, "Unknown message type: $type")
                        }
                    } catch (e: Exception) {
                        // Fallback to legacy raw string processing
                        JarvisCore.processCommand(context, text, InputSource.TELEGRAM)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket closed: $reason (Code: $code)")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure: ${t.message}")
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.d(TAG, "Attempting to reconnect in 5 seconds...")
            delay(5000)
            if (isRunning) {
                connectWebSocket()
            }
        }
    }

    fun updateSettings(model: String) {
        val json = JSONObject().apply {
            put("type", "update_settings")
            put("payload", JSONObject().apply {
                put("preferred_model", model)
            })
        }
        webSocket?.send(json.toString())
    }

    fun stop() {
        isRunning = false
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service stopped")
        webSocket = null
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Cloud Socket Manager stopped.")
    }
}
