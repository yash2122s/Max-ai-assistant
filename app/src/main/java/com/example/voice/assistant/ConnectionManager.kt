package com.example.voice.assistant

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.network.ConnectionState
import com.example.network.GeminiWebSocketClient
import kotlinx.coroutines.*

class ConnectionManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onExecuteAutomation: (String) -> String
) {
    private var webSocketClient: GeminiWebSocketClient? = null
    private var isConnecting = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1000L
    private val MAX_RECONNECT_DELAY_MS = 30000L

    @Synchronized
    fun connect() {
        if (webSocketClient != null || isConnecting) return
        isConnecting = true

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedVoice = prefs.getString("voice_name", "Aoede") ?: "Aoede"
        val savedLanguage = prefs.getString("response_language", "Tenglish") ?: "Tenglish"
        val apiKey = if (savedKey.isNotEmpty()) savedKey else BuildConfig.GEMINI_API_KEY

        if (apiKey.isEmpty()) {
            Log.e("ConnectionManager", "API Key is empty, cannot connect")
            isConnecting = false
            onConnectionStateChanged(ConnectionState.FAILED)
            return
        }

        webSocketClient = GeminiWebSocketClient(
            apiKey = apiKey,
            voiceName = savedVoice,
            responseLanguage = savedLanguage,
            onMessageReceived = { msg ->
                onMessageReceived(msg)
            },
            onAudioReceived = { audio ->
                onAudioReceived(audio)
            },
            onConnectionError = { err ->
                Log.e("ConnectionManager", "Connection error: ${err.message}")
                handleDisconnect()
            },
            onConnectionStateChanged = { state ->
                onConnectionStateChanged(state)
                if (state == ConnectionState.CONNECTED) {
                    isConnecting = false
                    reconnectDelayMs = 1000L
                    reconnectJob?.cancel()
                } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) {
                    isConnecting = false
                    handleDisconnect()
                }
            },
            onExecuteAutomation = onExecuteAutomation
        )

        webSocketClient?.connect()
    }

    private fun handleDisconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            Log.d("ConnectionManager", "Attempting automatic reconnect...")
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            disconnect()
            connect()
        }
    }

    @Synchronized
    fun sendText(text: String) {
        webSocketClient?.sendText(text)
    }

    @Synchronized
    fun sendAudio(audio: ByteArray) {
        webSocketClient?.sendAudio(audio)
    }

    @Synchronized
    fun sendInitialTrigger() {
        webSocketClient?.sendInitialTrigger()
    }

    @Synchronized
    fun sendToolResponse(id: String, name: String, responseJsonStr: String) {
        webSocketClient?.sendToolResponse(id, name, responseJsonStr)
    }

    @Synchronized
    fun disconnect() {
        reconnectJob?.cancel()
        webSocketClient?.disconnect()
        webSocketClient = null
        isConnecting = false
    }

    fun isConnected(): Boolean {
        return webSocketClient != null
    }
}
