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
    private val onExecuteAutomation: suspend (String) -> String,
    private val onUserTranscription: ((String) -> Unit)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    private val onThoughtSummaryDelta: ((String, String) -> Unit)? = null,
    private val onInteractionStatusChanged: ((String) -> Unit)? = null,
    private val emotionEngine: com.example.emotion.engine.EmotionEngine? = null
) {
    private var webSocketClient: GeminiWebSocketClient? = null
    private val liveSessionState = com.example.network.LiveSessionState()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = 1000L
    private val MAX_RECONNECT_DELAY_MS = 30000L
    private val memoryRepository = MemoryRepository.getInstance(context)
    @Volatile private var currentMemoriesMarkdown: String = ""

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
            memoryRepository.memoriesMarkdownFlow.collect { freshMarkdown ->
                currentMemoriesMarkdown = freshMarkdown
            }
        }
    }

    private var isNewSession = true

    @Synchronized
    fun forceReconnect() {
        createNewConnection(isNew = false)
    }

    private val modelRouter: com.example.network.router.ModelRouter = com.example.network.router.DefaultModelRouter()
    private var activeHandoffContext: com.example.network.model.ModelHandoffContext? = null
    var currentModelMode: com.example.network.model.GeminiModelMode = com.example.network.model.GeminiModelMode.AUTO
        private set

    fun connect() {
        liveSessionState.reset()
        ServiceRegistry.register(ServiceType.VOICE, this)
        isExplicitlyDisconnected = false
        isNewSession = true
        registerNetworkCallback()
        createNewConnection(isNew = true)
    }

    @Synchronized
    fun switchModeAndReconnect(targetMode: com.example.network.model.GeminiModelMode, handoff: com.example.network.model.ModelHandoffContext? = null) {
        Log.i("ConnectionManager", "Switching Gemini Model Mode to: $targetMode | Handoff task: ${handoff?.taskId}")
        currentModelMode = targetMode
        activeHandoffContext = handoff
        val settings = com.example.data.preferences.SettingsManager(context)
        val targetModel = modelRouter.modelFor(targetMode)
        settings.liveVoiceModelName = targetModel
        createNewConnection(isNew = false, forcedModel = targetModel, handoff = handoff)
    }

    @Synchronized
    private fun createNewConnection(
        isNew: Boolean = isNewSession,
        forcedModel: String? = null,
        handoff: com.example.network.model.ModelHandoffContext? = null
    ) {
        destroyOldConnection()
        val currentGen = connectionGeneration

        val settings = com.example.data.preferences.SettingsManager(context)
        val savedKey = settings.geminiApiKey.trim()
        val savedVoice = settings.liveVoiceName
        val savedLanguage = settings.languagePreference
        val routingPreference = com.example.network.model.GeminiModelMode.fromString(settings.modelRoutingMode)
        val resolvedModel = forcedModel ?: modelRouter.resolveModel(routingPreference, "", null)
        val apiKey = savedKey

        val keyPresent = apiKey.isNotEmpty()
        Log.d("GeminiFlow", "[GeminiFlow] key_present=$keyPresent | model=$resolvedModel | mode=$routingPreference")

        if (!keyPresent) {
            Log.w("ConnectionManager", "[SECURITY_GATE] Gemini API Key is missing. Connection blocked. User must configure key in Settings or Onboarding.")
            Log.w("GeminiFlow", "[GeminiFlow] error=API key is missing")
            handleConnectionStateChange(ConnectionState.DISCONNECTED)
            return
        }

        handleConnectionStateChange(ConnectionState.CONNECTING)

        scope.launch {
            // Memory refresh is independent of the connection/audio startup critical path.
            val markdown = currentMemoriesMarkdown

            synchronized(this@ConnectionManager) {
                // Ensure we only proceed if another connection wasn't started in the meantime
                if (webSocketClient != null || currentGen != connectionGeneration || isExplicitlyDisconnected) return@launch

                val currentEmotion = emotionEngine?.getCurrentState()
                webSocketClient = GeminiWebSocketClient(
                    apiKey = apiKey,
                    voiceName = savedVoice,
                    responseLanguage = savedLanguage,
                    preferredModel = resolvedModel,
                    thinkingLevel = settings.liveThinkingLevel,
                    sessionState = liveSessionState,
                    memoriesMarkdown = markdown,
                    isNewSession = isNew,
                    emotionState = currentEmotion,
                    handoffContext = handoff ?: activeHandoffContext,
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
                    onExecuteAutomation = onExecuteAutomation,
                    onUserTranscription = { transcription ->
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                // Dynamic task classification and explicit override check
                                val explicitOverride = modelRouter.detectExplicitModeOverride(transcription)
                                if (explicitOverride != null) {
                                    val targetModelName = modelRouter.modelFor(explicitOverride)
                                    if (targetModelName != resolvedModel) {
                                        Log.i("ConnectionManager", "Voice command triggered mode transition: $explicitOverride ($targetModelName)")
                                        val handoffCtx = com.example.network.model.ModelHandoffContext(
                                            currentObjective = transcription,
                                            conversationSummary = "User requested mode change to $explicitOverride."
                                        )
                                        scope.launch {
                                            switchModeAndReconnect(explicitOverride, handoffCtx)
                                        }
                                    }
                                }
                                onUserTranscription?.invoke(transcription)
                            }
                        }
                    },
                    onTurnComplete = {
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                onTurnComplete?.invoke()
                            }
                        }
                    },
                    onThoughtSummaryDelta = { delta, fullSummary ->
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                onThoughtSummaryDelta?.invoke(delta, fullSummary)
                            }
                        }
                    },
                    onInteractionStatusChanged = { status ->
                        synchronized(this@ConnectionManager) {
                            if (currentGen == connectionGeneration) {
                                onInteractionStatusChanged?.invoke(status)
                            }
                        }
                    }
                )
                webSocketClient?.connect()
            }
        }
    }

    @Synchronized
    private fun destroyOldConnection() {
        // Invalidate callbacks before closing; close callbacks can be synchronous.
        connectionGeneration++
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

        val settings = com.example.data.preferences.SettingsManager(context)
        if (!settings.hasValidGeminiApiKey()) {
            reconnectInProgress = false
            Log.d("ConnectionManager", "[SECURITY_GATE] Reconnect aborted: No Gemini API Key configured.")
            handleConnectionStateChange(ConnectionState.DISCONNECTED)
            return
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
        if (webSocketClient == null) {
            Log.d("ConnectionManager", "WebSocket not connected. Initiating connection for sendText...")
            connect()
        }
        webSocketClient?.sendText(text)
    }

    @Synchronized
    fun sendAudio(audio: ByteArray) {
        webSocketClient?.sendAudio(audio)
    }

    @Synchronized
    fun commitAudioTurn() {
        webSocketClient?.commitAudioTurn()
    }

    @Synchronized
    fun sendInterruptionSignal() {
        webSocketClient?.sendInterruptionSignalToServer()
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

    @Synchronized
    fun suspendForWorkerMode() {
        disconnect()
        Log.i("ConnectionManager", "[WorkerMode] GEMINI_SUSPENDED")
    }

    @Synchronized
    fun resumeFromWorkerMode() {
        isExplicitlyDisconnected = false
        Log.i("ConnectionManager", "[WorkerMode] GEMINI_RESUMED")
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
