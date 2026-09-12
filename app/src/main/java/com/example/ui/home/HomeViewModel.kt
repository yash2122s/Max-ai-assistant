package com.example.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessage
import com.example.domain.usecase.AssistantUseCase
import com.example.memory.data.MemoryRepository
import com.example.network.ConnectionState
import com.example.ui.theme.*
import com.example.utils.AudioPlaybackManager
import com.example.utils.QuickActions
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val assistantUseCase = AssistantUseCase(application)
    private val chatMessageDao = AppDatabase.getDatabase(application).chatMessageDao()
    private val memoryRepository = MemoryRepository.getInstance(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _liveUserCaption = MutableStateFlow("")
    val liveUserCaption: StateFlow<String> = _liveUserCaption.asStateFlow()

    private val _liveAiCaption = MutableStateFlow("")
    val liveAiCaption: StateFlow<String> = _liveAiCaption.asStateFlow()

    private val _uiEvents = MutableSharedFlow<HomeUiEvent>(extraBufferCapacity = 20)
    val uiEvents: SharedFlow<HomeUiEvent> = _uiEvents.asSharedFlow()

    private var persistedChatItems: List<ChatMessageItem> = emptyList()
    private val streamingResponseBuilder = StringBuilder()
    @Volatile
    private var activeStreamingMessage: ChatMessageItem? = null
    @Volatile
    private var lastPersistedUserText: String = ""
    private var captionClearJob: Job? = null

    init {
        // 1. Observe master runtime enabled state
        viewModelScope.launch {
            MaxRuntimeManager.isMaxEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isMaxEnabled = enabled) }
                if (!enabled) {
                    _audioRms.value = 0f
                }
            }
        }

        // 2. Observe Room chat messages in real time
        viewModelScope.launch(Dispatchers.IO) {
            chatMessageDao.getAllMessagesFlow().collect { roomList ->
                persistedChatItems = roomList.map { msg ->
                    ChatMessageItem(
                        id = msg.id,
                        sender = msg.sender,
                        text = msg.text,
                        timestamp = msg.timestamp,
                        isPending = msg.isPending
                    )
                }
                updateMergedMessages()
            }
        }

        // 3. Observe real-time assistant events (transcriptions, audio RMS, streaming text, errors)
        viewModelScope.launch {
            assistantUseCase.events.collect { event ->
                when (event) {
                    is AssistantEvent.TextTranscribed -> {
                        val isUser = event.role.equals("You", ignoreCase = true) || event.role.equals("user", ignoreCase = true)
                        if (isUser) {
                            val userText = event.text.trim()
                            if (userText.isNotEmpty() && userText != lastPersistedUserText) {
                                lastPersistedUserText = userText
                                persistChatMessage("user", userText)
                            }
                            captionClearJob?.cancel()
                            _liveUserCaption.value = userText
                            _liveAiCaption.value = ""
                            _uiState.update { state ->
                                state.copy(
                                    liveUserCaption = userText,
                                    liveAiCaption = "",
                                    isAiSpeaking = false,
                                    isThinking = true
                                )
                            }
                        }
                    }
                    is AssistantEvent.ResponseChunk -> {
                        handleIncomingAiChunk(event.text)
                    }
                    is AssistantEvent.TurnComplete -> {
                        handleAiTurnComplete(event.fullText)
                    }
                    is AssistantEvent.AudioRmsChanged -> {
                        // High frequency update isolated to dedicated StateFlow (does NOT trigger whole-screen recomposition)
                        _audioRms.value = event.rms
                    }
                    is AssistantEvent.ConnectionStateChanged -> {
                        val isDisconnected = event.state == ConnectionState.DISCONNECTED || event.state == ConnectionState.FAILED
                        _uiState.update { 
                            it.copy(
                                connectionState = event.state,
                                isThinking = if (isDisconnected) false else it.isThinking
                            ) 
                        }
                    }
                    is AssistantEvent.ErrorOccurred -> {
                        _uiState.update { it.copy(error = event.message, isThinking = false) }
                        _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("Assistant error: ${event.message}"))
                    }
                    is AssistantEvent.NeedsUserInput -> {
                        persistChatMessage("jarvis", event.question)
                        _uiState.update { it.copy(isThinking = false, liveAiCaption = event.question) }
                        _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                    }
                    is AssistantEvent.ToolProgress -> {
                        // Tool execution feedback
                    }
                }
            }
        }


        // 4. Observe Assistant Session State Machine
        viewModelScope.launch {
            assistantUseCase.sessionState.collect { sessionState ->
                val isRecording = sessionState == SessionState.LISTENING || assistantUseCase.isRecording()
                val isThinking = sessionState == SessionState.WAITING_FOR_MODEL || sessionState == SessionState.TOOL_EXECUTION
                if (!isRecording) {
                    _audioRms.value = 0f
                }
                if (sessionState == SessionState.LISTENING && _liveUserCaption.value.isBlank()) {
                    _liveUserCaption.value = "Listening..."
                }
                _uiState.update { state ->
                    state.copy(
                        isRecording = isRecording,
                        isThinking = isThinking,
                        liveUserCaption = if (sessionState == SessionState.LISTENING && state.liveUserCaption.isBlank()) "Listening..." else state.liveUserCaption
                    )
                }
            }
        }

        // 5. Hook direct audio playback StateFlow for accurate, non-clobbered SPEAKING indicator
        viewModelScope.launch {
            AudioPlaybackManager.isPlayingFlow.collect { playing ->
                _uiState.update { state ->
                    state.copy(
                        isAiSpeaking = playing,
                        isThinking = if (playing) false else state.isThinking
                    )
                }
            }
        }

        // Initial connection state
        _uiState.update { it.copy(connectionState = assistantUseCase.getConnectionState()) }
    }

    fun sendTextMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val app = getApplication<Application>()
        if (!MaxRuntimeManager.isMaxEnabled(app)) {
            _uiState.update { it.copy(error = "MAX is currently OFF. Enable MAX to send messages.") }
            _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("MAX is OFF. Enable MAX first."))
            return
        }

        val settings = com.example.data.preferences.SettingsManager(app)

        // Immediately persist user message
        persistChatMessage("user", trimmed)
        interruptSpeech()

        _uiState.update { it.copy(
            isThinking = true,
            liveUserCaption = trimmed,
            liveAiCaption = "",
            error = null
        )}

        // P1.2 — typed reply to a pending approval (Allow / Cancel)
        if (com.example.automation.approval.ApprovalCoordinator.hasPending()) {
            viewModelScope.launch(Dispatchers.IO) {
                val intent = com.example.automation.approval.ApprovalCoordinator.parseReply(trimmed)
                when (intent) {
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE -> {
                        val pending = com.example.automation.approval.ApprovalCoordinator.resolve()
                        if (pending != null) {
                            val ctx = com.example.automation.approval.ApprovalCoordinator.context()
                                ?: getApplication<Application>()
                            try {
                                com.example.automation.engine.ActionDispatcher.dispatchWithResult(ctx, pending.payload)
                            } catch (e: Exception) {
                                _uiState.update { it.copy(isThinking = false, error = e.message) }
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

        // P1.4 — typed reply to a pending habit routine suggestion
        if (com.example.automation.habits.HabitSuggestionCoordinator.hasPending()) {
            viewModelScope.launch(Dispatchers.IO) {
                val intent = com.example.automation.approval.ApprovalCoordinator.parseReply(trimmed)
                when (intent) {
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE -> {
                        val ctx = com.example.automation.habits.HabitSuggestionCoordinator.context()
                            ?: getApplication<Application>()
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

        // Check if there is an active paused plan waiting for user input
        if (com.example.automation.engine.GoalPlanner.getWaitingInputState() != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val res = com.example.automation.engine.GoalPlanner.resumePlanWithInput(app, trimmed)
                    // If resumePlanWithInput yielded another NeedsInput, it already emitted NeedsUserInput event.
                    // Only persist final completion/stop reply if no new NeedsInput was requested.
                    if (com.example.automation.engine.GoalPlanner.getWaitingInputState() == null) {
                        val reply = res.errorMessage ?: if (res.success) "Action completed." else "Action stopped."
                        persistChatMessage("jarvis", reply)
                    }
                    _uiState.update { it.copy(isThinking = false) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isThinking = false, error = e.message) }
                }
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val offlineResult = assistantUseCase.sendTextMessage(trimmed)

                if (offlineResult != null) {
                    val reply = offlineResult.message ?: if (offlineResult.success) "Action completed." else "Action failed."
                    persistChatMessage("jarvis", reply)
                    _uiState.update { it.copy(isThinking = false) }

                    if (offlineResult.toolName == "security_gate" && !offlineResult.success) {
                        _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("API key required. Redirecting to Settings..."))
                        _uiEvents.tryEmit(HomeUiEvent.NavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS))
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isThinking = false, error = e.message) }
            }
        }
    }

    fun toggleRecording() {
        val app = getApplication<Application>()
        if (!MaxRuntimeManager.isMaxEnabled(app)) {
            _uiState.update { it.copy(error = "MAX is currently OFF. Enable MAX to use voice.") }
            _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("MAX is OFF. Enable MAX to speak."))
            return
        }

        val settings = com.example.data.preferences.SettingsManager(app)
        if (!settings.hasValidGeminiApiKey()) {
            _uiState.update { it.copy(error = "Gemini API key required for voice assistant.") }
            _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("Gemini API key required. Redirecting to Settings..."))
            _uiEvents.tryEmit(HomeUiEvent.NavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS))
            return
        }

        if (_uiState.value.isRecording) {
            assistantUseCase.stopListening()
            _uiState.update { it.copy(isRecording = false, liveUserCaption = "") }
        } else {
            interruptSpeech()
            assistantUseCase.startListening()
            _uiState.update { it.copy(isRecording = true, liveUserCaption = "Listening...") }
        }
    }

    fun stopRecording() {
        assistantUseCase.stopListening()
        _uiState.update { it.copy(isRecording = false, liveUserCaption = "") }
    }

    fun interruptSpeech() {
        assistantUseCase.interruptSpeech()
        captionClearJob?.cancel()
        val partial = synchronized(streamingResponseBuilder) {
            val res = streamingResponseBuilder.toString().trim()
            streamingResponseBuilder.setLength(0)
            activeStreamingMessage = null
            res
        }
        if (partial.isNotEmpty()) {
            persistChatMessage("jarvis", partial)
        }
        updateMergedMessages()
        _uiState.update { it.copy(isAiSpeaking = false, liveAiCaption = "") }
    }

    fun setRuntimeEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            MaxRuntimeManager.enableMax(app)
        } else {
            MaxRuntimeManager.disableMax(app)
            stopRecording()
            interruptSpeech()
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(streamingResponseBuilder) {
                streamingResponseBuilder.setLength(0)
                activeStreamingMessage = null
            }
            chatMessageDao.clearAllMessages()
            persistedChatItems = emptyList()
            lastPersistedUserText = ""
            _uiState.update { it.copy(
                messages = emptyList(),
                liveAiCaption = "",
                liveUserCaption = ""
            ) }
        }
    }

    fun clearPendingAutomation() {
        _uiState.update { it.copy(pendingAutomation = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun handleIncomingAiChunk(chunk: String) {
        val sanitized = com.example.utils.InternalReasoningFilter.sanitize(chunk)
        if (sanitized.isBlank()) return
        checkForPendingAutomation(sanitized)
        captionClearJob?.cancel()

        val currentAiText = synchronized(streamingResponseBuilder) {
            if (streamingResponseBuilder.isNotEmpty()) {
                val lastChar = streamingResponseBuilder.last()
                val firstChar = sanitized.first()
                if (!lastChar.isWhitespace() && lastChar != '\n' && !sanitized.startsWith(" ") && firstChar.isLetterOrDigit()) {
                    streamingResponseBuilder.append(' ')
                }
            }
            streamingResponseBuilder.append(sanitized)
            val full = streamingResponseBuilder.toString()
            activeStreamingMessage = ChatMessageItem(
                id = -1,
                sender = "jarvis",
                text = full,
                timestamp = System.currentTimeMillis(),
                isPending = true
            )
            full
        }

        _liveAiCaption.value = currentAiText
        _liveUserCaption.value = ""
        _uiState.update { state ->
            state.copy(
                isThinking = false,
                isAiSpeaking = true,
                liveUserCaption = "",
                liveAiCaption = currentAiText
            )
        }
        updateMergedMessages()
    }

    private fun handleAiTurnComplete(turnText: String) {
        captionClearJob?.cancel()
        val textToPersist = synchronized(streamingResponseBuilder) {
            val res = if (turnText.isNotBlank()) turnText else streamingResponseBuilder.toString()
            streamingResponseBuilder.setLength(0)
            res.trim()
        }
        if (textToPersist.isNotEmpty()) {
            persistChatMessage("jarvis", textToPersist)
        }
        synchronized(streamingResponseBuilder) {
            activeStreamingMessage = null
        }
        updateMergedMessages()
        _uiState.update { it.copy(isThinking = false) }
        scheduleCaptionClear()
    }

    private fun updateMergedMessages() {
        val currentActive = activeStreamingMessage
        val merged = if (currentActive != null && currentActive.text.isNotBlank()) {
            persistedChatItems + listOf(currentActive)
        } else {
            persistedChatItems
        }
        _uiState.update { it.copy(messages = merged) }
        _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
    }

    private var isFlashlightOn = false

    fun executeQuickAction(action: String) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            when (action.uppercase()) {
                "FLASHLIGHT" -> {
                    isFlashlightOn = !isFlashlightOn
                    val result = QuickActions.toggleFlashlight(context, turnOn = isFlashlightOn)
                    _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar(result))
                }
                "WIFI" -> {
                    val result = QuickActions.openWifiSettings(context)
                    _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar(result))
                }
                "BLUETOOTH" -> {
                    val result = QuickActions.openBluetoothSettings(context)
                    _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar(result))
                }
                "RECONNECT" -> {
                    _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("Reconnecting to Gemini Live..."))
                    assistantUseCase.reconnect()
                }
            }
        }
    }

    private fun checkForPendingAutomation(text: String) {
        try {
            val jsonStart = text.indexOf("{")
            val jsonEnd = text.lastIndexOf("}")
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val jsonStr = text.substring(jsonStart, jsonEnd + 1)
                val jsonObj = JSONObject(jsonStr)
                _uiState.update { state -> state.copy(pendingAutomation = jsonObj) }
            }
        } catch (e: JSONException) {
            // Not a JSON automation payload
        }
    }

    private fun persistChatMessage(sender: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatMessageDao.insertMessage(
                    ChatMessage(
                        sender = sender,
                        text = text,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to persist chat message", e)
            }
        }
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

    override fun onCleared() {
        super.onCleared()
        assistantUseCase.stopListening()
        captionClearJob?.cancel()
    }
}
