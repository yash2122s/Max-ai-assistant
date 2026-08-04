package com.example.voice.assistant

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.memory.data.PermanentMemory
import com.example.memory.data.MemoryRepository
import com.example.network.ConnectionState
import com.example.network.GeminiWebSocketClient
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import kotlinx.coroutines.*

class ConnectionManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (ConnectionState) -> Unit,
    private val onExecuteAutomation: (String) -> String
) {
    private var webSocketClient: GeminiWebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1000L
    private val MAX_RECONNECT_DELAY_MS = 30000L
    private val memoryRepository = MemoryRepository(context)
    private var currentMemoriesMarkdown: String = ""

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    private var reconnectInProgress = false
    private var isExplicitlyDisconnected = true
    private var isNetworkCallbackRegistered = false
    private var connectionGeneration = 0

    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d("ConnectionManager", "Network restored. Checking reconnection...")
            synchronized(this@ConnectionManager) {
                if ((connectionState == ConnectionState.FAILED || connectionState == ConnectionState.DISCONNECTED) &&
                    !isExplicitlyDisconnected &&
                    !reconnectInProgress) {
                    Log.d("ConnectionManager", "Network back online. Fast-tracking reconnection...")
                    cancelReconnectJob()
                    createNewConnection()
                }
            }
        }
    }

    init {
        scope.launch {
            memoryRepository.memoriesMarkdownFlow.collect { latestMarkdown ->
                if (connectionState == ConnectionState.CONNECTED && currentMemoriesMarkdown != latestMarkdown) {
                    Log.d("ConnectionManager", "Memories updated, reconnecting voice assistant client...")
                    forceReconnect()
                }
            }
        }
    }

    @Synchronized
    fun forceReconnect() {
        createNewConnection()
    }

    @Synchronized
    fun connect() {
        ServiceRegistry.register(ServiceType.VOICE, this)
        isExplicitlyDisconnected = false
        registerNetworkCallback()
        createNewConnection()
    }

    @Synchronized
    private fun createNewConnection() {
        destroyOldConnection()

        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedVoice = prefs.getString("voice_name", "Aoede") ?: "Aoede"
        val savedLanguage = prefs.getString("response_language", "Tenglish") ?: "Tenglish"
        val apiKey = if (savedKey.isNotEmpty()) savedKey else BuildConfig.GEMINI_API_KEY

        if (apiKey.isEmpty()) {
            Log.e("ConnectionManager", "API Key is empty, cannot connect")
            handleConnectionStateChange(ConnectionState.FAILED)
            return
        }

        handleConnectionStateChange(ConnectionState.CONNECTING)

        scope.launch {
            val markdown = memoryRepository.getMemoriesMarkdown()
            currentMemoriesMarkdown = markdown

            synchronized(this@ConnectionManager) {
                // Ensure we only proceed if another connection wasn't started in the meantime
                if (webSocketClient != null) return@launch

                val currentGen = ++connectionGeneration
                webSocketClient = GeminiWebSocketClient(
                    apiKey = apiKey,
                    voiceName = savedVoice,
                    responseLanguage = savedLanguage,
                    memoriesMarkdown = markdown,
                    onMessageReceived = { msg -> 
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                onMessageReceived(msg)
                            }
                        }
                    },
                    onAudioReceived = { audio -> 
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                onAudioReceived(audio)
                            }
                        }
                    },
                    onConnectionError = { err ->
                        Log.e("ConnectionManager", "Connection error: ${err.message}")
                    },
                    onConnectionStateChanged = { state ->
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                handleConnectionStateChange(state)
                            }
                        }
                    },
                    onExecuteAutomation = onExecuteAutomation
                )
                webSocketClient?.connect()
            }
        }
    }

    @Synchronized
    private fun destroyOldConnection() {
        webSocketClient?.disconnect()
        webSocketClient = null
    }

    @Synchronized
    private fun handleConnectionStateChange(state: ConnectionState) {
        if (this.connectionState == state) return
        this.connectionState = state
        onConnectionStateChanged(state)

        if (state == ConnectionState.CONNECTED) {
            reconnectDelayMs = 1000L
            reconnectInProgress = false
            cancelReconnectJob()
        } else if (state == ConnectionState.FAILED || state == ConnectionState.DISCONNECTED) {
            if (!isExplicitlyDisconnected) {
                startReconnectJob()
            }
        }
    }

    private fun startReconnectJob() {
        synchronized(this) {
            if (reconnectInProgress) return
            reconnectInProgress = true
        }

        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            Log.d("ConnectionManager", "Attempting automatic reconnect...")
            
            synchronized(this@ConnectionManager) {
                if (isExplicitlyDisconnected) return@launch
                // Double backoff
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                // Unlock the guard right before connection creation
                reconnectInProgress = false
                createNewConnection()
            }
        }
    }

    private fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
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
    fun sendVideoFrame(jpegBytes: ByteArray) {
        webSocketClient?.sendVideoFrame(jpegBytes)
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
        isExplicitlyDisconnected = true
        cancelReconnectJob()
        reconnectInProgress = false
        destroyOldConnection()
        unregisterNetworkCallback()
        handleConnectionStateChange(ConnectionState.DISCONNECTED)
    }

    private fun registerNetworkCallback() {
        if (isNetworkCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isNetworkCallbackRegistered = true
            Log.d("ConnectionManager", "ConnectivityManager.NetworkCallback registered")
        } catch (e: Exception) {
            Log.e("ConnectionManager", "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isNetworkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
            Log.d("ConnectionManager", "ConnectivityManager.NetworkCallback unregistered")
        } catch (e: Exception) {
            Log.e("ConnectionManager", "Failed to unregister network callback", e)
        }
    }

    fun isConnected(): Boolean {
        return connectionState == ConnectionState.CONNECTED
    }
}
