package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.memory.data.MemoryRepository
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.Job
import com.example.network.ConnectionState
import com.example.utils.playAudioResponse
import com.example.automation.engine.ActionDispatcher
import com.example.voice.vision.ScreenCaptureProvider
import com.example.voice.assistant.*
import org.json.JSONObject
import org.json.JSONException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class ChatViewModel : ViewModel() {
    companion object {
        private var _instance: ChatViewModel? = null
        val instance: ChatViewModel
            get() {
                if (_instance == null) {
                    _instance = ChatViewModel()
                }
                return _instance!!
            }
        
        val rmsFlow = MutableStateFlow(0f)
        
        fun updateRms(value: Float) {
            rmsFlow.value = value
        }
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    
    var appContext: Context? = null
    private var memoryRepository: MemoryRepository? = null
    private var chatHistoryJob: Job? = null
    private var eventObservationJob: Job? = null
    private var captionClearJob: Job? = null

    private var engine: AssistantEngine? = null
    
    fun initialize(context: Context, apiKey: String, voiceName: String = "Aoede", responseLanguage: String = "Tenglish", preferredModel: String = "Auto") {
        appContext = context.applicationContext
        val appCtx = context.applicationContext
        memoryRepository = MemoryRepository.getInstance(appCtx)

        if (chatHistoryJob == null || chatHistoryJob?.isActive == false) {
            chatHistoryJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                com.example.data.local.AppDatabase.getDatabase(appCtx).chatMessageDao()
                    .getAllMessagesFlow().collect { roomMsgs ->
                        val viewMsgs = roomMsgs.map { 
                            ChatMessage(
                                role = if (it.sender == "user") "You" else "Gemini", 
                                content = it.text, 
                                timestamp = it.timestamp
                            ) 
                        }.reversed()
                        _uiState.update { state ->
                            if (state.messages.isEmpty()) {
                                state.copy(messages = viewMsgs)
                            } else {
                                state
                            }
                        }
                    }
            }
        }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryRepository?.backfillMissingEmbeddings()
        }

        // Connect to canonical AssistantEngine
        engine = AssistantLifecycleManager.getOrCreateEngine(appCtx)
        observeEngineEvents()

        // Observe audio playback state safely via StateFlow
        viewModelScope.launch {
            com.example.utils.AudioPlaybackManager.isPlayingFlow.collect { playing ->
                _uiState.update { state ->
                    state.copy(
                        isAiSpeaking = playing,
                        isThinking = if (playing) false else state.isThinking
                    )
                }
            }
        }
    }

    private fun observeEngineEvents() {
        // Sync immediate connection state from engine
        engine?.getConnectionState()?.let { currentConn ->
            _uiState.update { it.copy(connectionState = currentConn) }
        }

        if (eventObservationJob?.isActive == true) return
        eventObservationJob = viewModelScope.launch {
            launch {
                // Periodic connection state sync fallback
                while (isActive) {
                    engine?.getConnectionState()?.let { currentConn ->
                        if (_uiState.value.connectionState != currentConn) {
                            _uiState.update { it.copy(connectionState = currentConn) }
                        }
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }

            launch {
                AssistantEventBus.events.collect { event ->
                    when (event) {
                        is AssistantEvent.TextTranscribed -> {
                            persistChatMessage(event.role, event.text)
                            if (event.role == "Gemini") {
                                try {
                                    val jsonStart = event.text.indexOf("{")
                                    val jsonEnd = event.text.lastIndexOf("}")
                                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                                        val jsonStr = event.text.substring(jsonStart, jsonEnd + 1)
                                        val jsonObj = JSONObject(jsonStr)
                                        _uiState.update { state -> 
                                            state.copy(pendingAutomation = jsonObj)
                                        }
                                    }
                                } catch (e: JSONException) {
                                    // Not valid JSON
                                }
                                
                                _uiState.update { state ->
                                    val updatedAiText = if (state.liveAiCaption.isNotEmpty()) "${state.liveAiCaption} ${event.text}" else event.text
                                    state.copy(
                                        messages = listOf(ChatMessage("Gemini", event.text)) + state.messages,
                                        isThinking = false,
                                        liveUserCaption = "",
                                        liveAiCaption = updatedAiText,
                                        isAiSpeaking = true
                                    )
                                }
                                scheduleCaptionClear()
                            } else {
                                captionClearJob?.cancel()
                                _uiState.update { state ->
                                    state.copy(
                                        liveUserCaption = event.text,
                                        liveAiCaption = "",
                                        isAiSpeaking = false
                                    )
                                }
                            }
                        }
                        is AssistantEvent.ResponseChunk -> {
                            // Handled via TextTranscribed / streaming
                        }
                        is AssistantEvent.TurnComplete -> {
                            // Turn complete
                        }
                        is AssistantEvent.AudioRmsChanged -> {
                            updateRms(event.rms)
                        }
                        is AssistantEvent.ConnectionStateChanged -> {
                            _uiState.update { it.copy(connectionState = event.state) }
                        }
                        is AssistantEvent.ErrorOccurred -> {
                            _uiState.update { it.copy(error = event.message) }
                        }
                        is AssistantEvent.NeedsUserInput -> {
                            persistChatMessage("Gemini", event.question)
                            _uiState.update { it.copy(isThinking = false, liveAiCaption = event.question) }
                        }
                        is AssistantEvent.ToolProgress -> {
                            // Tool progress event handling
                        }
                    }
                }
            }


            launch {
                engine?.stateMachine?.currentState?.collect { sessionState ->
                    val isListeningOrStreaming = sessionState == SessionState.LISTENING || (engine?.isRecording() == true)
                    _uiState.update { state ->
                        state.copy(
                            isRecording = isListeningOrStreaming,
                            isThinking = sessionState == SessionState.WAITING_FOR_MODEL || sessionState == SessionState.TOOL_EXECUTION,
                            liveUserCaption = if (sessionState == SessionState.LISTENING) "Listening..." else state.liveUserCaption
                        )
                    }
                }
            }
        }
    }

    private fun persistChatMessage(role: String, text: String) {
        // Canonical chat persistence is handled by HomeViewModel / AssistantEngine
    }

    fun reconnect(newApiKey: String? = null, voiceName: String = "Aoede", responseLanguage: String = "Tenglish", preferredModel: String = "Auto") {
        engine?.reconnect()
    }
    
    fun sendTextMessage(text: String) {
        val ctx = appContext
        if (ctx != null && !com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(ctx)) {
            _uiState.update { it.copy(error = "MAX is currently OFF. Enable MAX in Settings.") }
            return
        }

        persistChatMessage("You", text)
        onSpeechInterrupted()
        _uiState.update { it.copy(
            messages = listOf(ChatMessage("You", text)) + it.messages,
            isThinking = true,
            liveUserCaption = text,
            liveAiCaption = ""
        )}

        // P1.4 — typed reply to a pending habit routine suggestion
        if (com.example.automation.habits.HabitSuggestionCoordinator.hasPending()) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val intent = com.example.automation.approval.ApprovalCoordinator.parseReply(text)
                when (intent) {
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE -> {
                        val ctx = com.example.automation.habits.HabitSuggestionCoordinator.context()
                            ?: appContext
                        if (ctx != null) {
                            val result = try {
                                com.example.automation.habits.HabitSuggestionCoordinator.approveAndRun(ctx)
                            } catch (e: Exception) {
                                "Could not create routine: ${e.message}"
                            }
                            _uiState.update { it.copy(isThinking = false) }
                            com.example.voice.assistant.AssistantEventBus.emit(
                                com.example.voice.assistant.AssistantEvent.TextTranscribed("MAX", result)
                            )
                        }
                    }
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY ->
                        com.example.automation.habits.HabitSuggestionCoordinator.cancel()
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.OTHER -> Unit
                }
                if (intent != com.example.automation.approval.ApprovalCoordinator.ReplyIntent.OTHER) {
                    _uiState.update { it.copy(isThinking = false) }
                }
            }
            return
        }

        // P1.2 — typed reply to a pending approval (Allow / Cancel)
        if (com.example.automation.approval.ApprovalCoordinator.hasPending()) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val intent = com.example.automation.approval.ApprovalCoordinator.parseReply(text)
                when (intent) {
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE -> {
                        val pending = com.example.automation.approval.ApprovalCoordinator.resolve()
                        if (pending != null) {
                            val actCtx = com.example.automation.approval.ApprovalCoordinator.context()
                                ?: appContext
                            if (actCtx != null) {
                                try {
                                    com.example.automation.engine.ActionDispatcher.dispatchWithResult(actCtx, pending.payload)
                                } catch (e: Exception) {
                                    _uiState.update { it.copy(isThinking = false, error = e.message) }
                                }
                            }
                        }
                    }
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY ->
                        com.example.automation.approval.ApprovalCoordinator.cancel()
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.OTHER -> Unit
                }
                if (intent != com.example.automation.approval.ApprovalCoordinator.ReplyIntent.OTHER) {
                    _uiState.update { it.copy(isThinking = false) }
                }
            }
            return
        }

        if (ctx != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val offlineResult = com.example.automation.engine.OfflineCommandEngine.executeIfMatched(ctx, text)
                if (offlineResult != null) {
                    val rawMsg = offlineResult.message ?: if (offlineResult.success) "Action executed." else "Action failed."
                    val replyText = if (offlineResult.success) rawMsg else "Failed: $rawMsg"
                    persistChatMessage("Gemini", replyText)
                    _uiState.update { state ->
                        state.copy(
                            messages = listOf(ChatMessage("MAX (Offline)", replyText)) + state.messages,
                            isThinking = false
                        )
                    }
                    return@launch
                }

                // If not handled offline, delegate prompt to canonical AssistantEngine
                engine?.sendTextMessage(text)
            }
        } else {
            engine?.sendTextMessage(text)
        }
    }

    fun toggleRecording() {
        val ctx = appContext
        if (ctx != null && !com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(ctx)) {
            _uiState.update { it.copy(error = "MAX is currently OFF. Enable MAX to use voice.") }
            return
        }

        val currentEng = engine ?: ctx?.let { AssistantLifecycleManager.getOrCreateEngine(it) }
        if (currentEng == null) return

        if (currentEng.isRecording() || _uiState.value.isRecording) {
            currentEng.stopListening()
            _uiState.update { it.copy(isRecording = false, liveUserCaption = "") }
        } else {
            onSpeechInterrupted()
            currentEng.startListening()
            _uiState.update { it.copy(isRecording = true, liveUserCaption = "Listening...") }
        }
    }

    fun stopRecording() {
        val currentEng = engine ?: appContext?.let { AssistantLifecycleManager.getOrCreateEngine(it) }
        currentEng?.stopListening()
        _uiState.update { it.copy(isRecording = false, liveUserCaption = "") }
    }

    override fun onCleared() {
        super.onCleared()
        engine?.stopListening()
        eventObservationJob?.cancel()
        chatHistoryJob?.cancel()
        captionClearJob?.cancel()
    }

    fun onSpeechInterrupted() {
        com.example.utils.stopAudioResponse()
        captionClearJob?.cancel()
        _uiState.update { it.copy(
            isAiSpeaking = false,
            liveAiCaption = ""
        ) }
    }

    private fun scheduleCaptionClear() {
        captionClearJob?.cancel()
        captionClearJob = viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            _uiState.update { it.copy(
                liveAiCaption = "",
                liveUserCaption = "",
                isAiSpeaking = false
            ) }
        }
    }

    fun clearPendingAutomation() {
        _uiState.update { it.copy(pendingAutomation = null) }
    }

    fun clearChatHistory() {
        val appCtx = appContext ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.example.data.local.AppDatabase.getDatabase(appCtx).chatMessageDao().clearAllMessages()
            _uiState.update { it.copy(
                messages = emptyList(),
                liveAiCaption = "",
                liveUserCaption = ""
            ) }
        }
    }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val isThinking: Boolean = false,
    val error: String? = null,
    val pendingAutomation: JSONObject? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val liveUserCaption: String = "",
    val liveAiCaption: String = "",
    val isAiSpeaking: Boolean = false
) {
    val coreState: com.example.ui.components.CoreState
        get() = when {
            error != null -> com.example.ui.components.CoreState.ERROR
            pendingAutomation != null -> com.example.ui.components.CoreState.EXECUTING
            isAiSpeaking -> com.example.ui.components.CoreState.SPEAKING
            isThinking -> com.example.ui.components.CoreState.THINKING
            isRecording -> com.example.ui.components.CoreState.LISTENING
            else -> com.example.ui.components.CoreState.IDLE
        }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
