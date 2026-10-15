package com.example.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.memory.data.MemoryRepository
import com.example.memory.data.PermanentMemory
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.example.data.repository.JarvisRepository

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
    private var jarvisRepository: JarvisRepository? = null
    private var chatHistoryJob: Job? = null
    private var eventObservationJob: Job? = null
    private var captionClearJob: Job? = null
    private var thinkingTimeoutJob: Job? = null

    private var engine: AssistantEngine? = null
    @Volatile private var accountSession: MemorySession = MemoryAccountScope.capture()

    private fun resetAccount(session: MemorySession) {
        accountSession = session
        captionClearJob?.cancel()
        thinkingTimeoutJob?.cancel()
        _uiState.update { it.copy(messages = emptyList(), pendingAutomation = null,
            liveAiCaption = "", liveUserCaption = "", isThinking = false, isAiSpeaking = false) }
    }
    
    fun initialize(context: Context, apiKey: String, voiceName: String = "Aoede", responseLanguage: String = "Tenglish", preferredModel: String = "Auto") {
        appContext = context.applicationContext
        val appCtx = context.applicationContext
        memoryRepository = MemoryRepository.getInstance(appCtx)
        jarvisRepository = JarvisRepository(appCtx)

        if (chatHistoryJob == null || chatHistoryJob?.isActive == false) {
            chatHistoryJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                MemoryAccountScope.sessions.flatMapLatest { session ->
                    resetAccount(session)
                    if (session.ownerKey == MemoryAccountScope.UNAVAILABLE) flowOf(emptyList())
                    else com.example.data.local.AppDatabase.getDatabase(appCtx).chatMessageDao()
                        .getAllMessagesFlow(session.ownerKey).map { rows ->
                            if (MemoryAccountScope.isCurrent(session)) rows else emptyList()
                        }.onStart { emit(emptyList()) }
                }.collect { roomMsgs ->
                        val viewMsgs = roomMsgs.map { 
                            ChatMessage(
                                role = if (it.sender == "user") "You" else "Gemini", 
                                content = it.text, 
                                timestamp = it.timestamp
                            ) 
                        }.reversed()
                         if (MemoryAccountScope.isCurrent(MemoryAccountScope.capture())) {
                             _uiState.update { state -> state.copy(messages = viewMsgs) }
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
                            val isUser = event.role.equals("You", ignoreCase = true) || event.role.equals("user", ignoreCase = true)
                            if (!isUser) {
                                thinkingTimeoutJob?.cancel()
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
                                        isAiSpeaking = false
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
                            thinkingTimeoutJob?.cancel()
                            _uiState.update { it.copy(isThinking = false) }
                        }
                        is AssistantEvent.AudioRmsChanged -> {
                            updateRms(event.rms)
                        }
                        is AssistantEvent.ConnectionStateChanged -> {
                            _uiState.update { it.copy(connectionState = event.state) }
                        }
                        is AssistantEvent.ErrorOccurred -> {
                            thinkingTimeoutJob?.cancel()
                            _uiState.update { it.copy(error = event.message, isThinking = false) }
                        }
                        is AssistantEvent.NeedsUserInput -> {
                            persistChatMessage("Gemini", event.question)
                            _uiState.update { it.copy(isThinking = false, liveAiCaption = event.question) }
                        }
                        is AssistantEvent.ToolProgress -> {
                            // Tool progress event handling
                        }
                        is AssistantEvent.ThoughtSummaryDelta -> {
                            // Thought summary update
                        }
                        is AssistantEvent.InteractionStatusChanged -> {
                            val isIdle = event.status.equals("IDLE", ignoreCase = true)
                            _uiState.update { state ->
                                state.copy(
                                    isThinking = if (isIdle) false else state.isThinking
                                )
                            }
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
        val repo = jarvisRepository ?: return
        val session = MemoryAccountScope.capture()
        if (!MemoryAccountScope.isCurrent(session)) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                MemoryAccountScope.requireCurrent(session)
                repo.insertMessage(if (role.equals("you", true) || role.equals("user", true)) "user" else "jarvis", text)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Account changed while the write was queued; discard the callback.
            }
        }
    }

    fun reconnect(newApiKey: String? = null, voiceName: String = "Aoede", responseLanguage: String = "Tenglish", preferredModel: String = "Auto") {
        engine?.reconnect()
    }
    
    fun sendTextMessage(text: String) {
        val session = MemoryAccountScope.capture()
        if (!MemoryAccountScope.isCurrent(session)) return
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

        // Safety timeout (8s): ensure isThinking is never stuck permanently on dropped turns or blocked tools
        thinkingTimeoutJob?.cancel()
        thinkingTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(8000)
            if (!MemoryAccountScope.isCurrent(session)) return@launch
            if (_uiState.value.isThinking) {
                _uiState.update { it.copy(isThinking = false) }
            }
        }

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
                val actCtx = com.example.automation.approval.ApprovalCoordinator.context()
                    ?: appContext
                if (actCtx != null) {
                    com.example.automation.approval.ApprovalCoordinator.handleReply(text, actCtx)
                }
                _uiState.update { it.copy(isThinking = false) }
            }
            return
        }

        // P1-4: Typed selection for multi-contact disambiguation
        if (com.example.automation.engine.PendingCallManager.state is com.example.automation.engine.CallState.AwaitingContactSelection) {
            val replyIntent = com.example.automation.approval.ApprovalCoordinator.parseReply(text)
            if (replyIntent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY) {
                com.example.automation.engine.PendingCallManager.cancel()
                val reply = "Call cancelled."
                persistChatMessage("Gemini", reply)
                thinkingTimeoutJob?.cancel()
                _uiState.update { state ->
                    state.copy(
                        messages = listOf(ChatMessage("MAX", reply)) + state.messages,
                        isThinking = false,
                        liveAiCaption = reply
                    )
                }
                scheduleCaptionClear()
                return
            }

            val selectedCandidate = com.example.automation.engine.PendingCallManager.resolveAndSelectCandidate(text)
            if (selectedCandidate != null) {
                val confirmPrompt = "Do you want to call ${selectedCandidate.contactMatch.displayName} (${selectedCandidate.phoneNumber.normalizedNumber})? Please say yes to confirm or cancel."
                persistChatMessage("Gemini", confirmPrompt)
                thinkingTimeoutJob?.cancel()
                _uiState.update { state ->
                    state.copy(
                        messages = listOf(ChatMessage("MAX", confirmPrompt)) + state.messages,
                        isThinking = false,
                        liveAiCaption = confirmPrompt
                    )
                }
                scheduleCaptionClear()
                return
            } else {
                val clarification = "Please choose which number or contact to call (e.g., 1, 2, Mobile, or Work)."
                persistChatMessage("Gemini", clarification)
                thinkingTimeoutJob?.cancel()
                _uiState.update { state ->
                    state.copy(
                        messages = listOf(ChatMessage("MAX", clarification)) + state.messages,
                        isThinking = false,
                        liveAiCaption = clarification
                    )
                }
                scheduleCaptionClear()
                return
            }
        }

        // Typed reply to a pending phone call confirmation
        if (com.example.automation.engine.PendingCallManager.hasPendingConfirmation()) {
            val replyIntent = com.example.automation.approval.ApprovalCoordinator.parseReply(text)
            if (replyIntent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE) {
                val pending = com.example.automation.engine.PendingCallManager.getPendingConfirmation()
                if (pending != null) {
                    com.example.automation.engine.PendingCallManager.confirmPendingCall(pending.contactId, pending.phoneId)
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val actCtx = com.example.automation.approval.ApprovalCoordinator.context() ?: appContext ?: ctx
                        val callArgs = org.json.JSONObject().apply {
                            put("action", "CALL_PHONE")
                            put("contactId", pending.contactId)
                            put("phoneId", pending.phoneId)
                            put("contact", pending.displayName)
                            put("phoneNumber", pending.phoneNumber)
                            put("confirmed", true)
                            put("user_confirmed", true)
                            put("last_input_transcription", text)
                        }
                        val resultJsonStr = if (actCtx != null) {
                            com.example.automation.engine.ActionDispatcher.dispatchWithResult(actCtx, callArgs)
                        } else "{}"
                        val resultObj = runCatching { org.json.JSONObject(resultJsonStr) }.getOrNull()
                        val isSuccess = resultObj?.optBoolean("success", false) == true
                        val reply = if (isSuccess) {
                            "Calling ${pending.displayName}..."
                        } else {
                            resultObj?.optString("message") ?: "Failed to place call."
                        }
                        persistChatMessage("Gemini", reply)
                        thinkingTimeoutJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                messages = listOf(ChatMessage("MAX", reply)) + state.messages,
                                isThinking = false,
                                liveAiCaption = reply
                            )
                        }
                        scheduleCaptionClear()
                    }
                    return
                }
            } else if (replyIntent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY) {
                com.example.automation.engine.PendingCallManager.cancel()
                val reply = "Call cancelled."
                persistChatMessage("Gemini", reply)
                thinkingTimeoutJob?.cancel()
                _uiState.update { state ->
                    state.copy(
                        messages = listOf(ChatMessage("MAX", reply)) + state.messages,
                        isThinking = false,
                        liveAiCaption = reply
                    )
                }
                scheduleCaptionClear()
                return
            }
        }

        if (ctx != null) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (!MemoryAccountScope.isCurrent(session)) return@launch
                val offlineResult = com.example.automation.engine.OfflineCommandEngine.executeIfMatched(ctx, text)
                if (!MemoryAccountScope.isCurrent(session)) return@launch
                if (offlineResult != null) {
                    val rawMsg = offlineResult.message ?: if (offlineResult.success) "Action executed." else "Action failed."
                    val replyText = if (offlineResult.success || offlineResult.status == com.example.automation.tools.ToolResultStatus.NEEDS_INPUT) rawMsg else "Failed: $rawMsg"
                    persistChatMessage("Gemini", replyText)
                    thinkingTimeoutJob?.cancel()
                    _uiState.update { state ->
                        state.copy(
                            messages = listOf(ChatMessage("MAX (Offline)", replyText)) + state.messages,
                            isThinking = false
                        )
                    }
                    return@launch
                }

                // Check memory save directly
                val saveResult = com.example.memory.retrieval.MemoryFactResolver.extractAndSaveMemoryFact(ctx, text)
                if (!MemoryAccountScope.isCurrent(session)) return@launch
                if (saveResult != null) {
                    val app = ctx as? com.example.MyApplication
                    app?.entitlementGate?.checkAndConsumeQuota()
                    persistChatMessage("Gemini", saveResult)
                    thinkingTimeoutJob?.cancel()
                    _uiState.update { state ->
                        state.copy(
                            messages = listOf(ChatMessage("Gemini", saveResult)) + state.messages,
                            isThinking = false,
                            liveAiCaption = saveResult
                        )
                    }
                    scheduleCaptionClear()
                    return@launch
                }

                // Check memory retrieval directly
                if (com.example.memory.retrieval.MemoryFactResolver.isMemoryQuery(text)) {
                    val factResult = com.example.memory.retrieval.MemoryFactResolver.resolve(ctx, text)
                    if (!MemoryAccountScope.isCurrent(session)) return@launch
                    if (factResult != null && factResult.found) {
                        val app = ctx as? com.example.MyApplication
                        app?.entitlementGate?.checkAndConsumeQuota()
                        persistChatMessage("Gemini", factResult.responseText)
                        thinkingTimeoutJob?.cancel()
                        _uiState.update { state ->
                            state.copy(
                                messages = listOf(ChatMessage("Gemini", factResult.responseText)) + state.messages,
                                isThinking = false,
                                liveAiCaption = factResult.responseText
                            )
                        }
                        scheduleCaptionClear()
                        return@launch
                    }
                }

                // If not handled offline, delegate prompt to canonical AssistantEngine
                if (MemoryAccountScope.isCurrent(session)) engine?.sendTextMessage(text)
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
        thinkingTimeoutJob?.cancel()
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
        val session = MemoryAccountScope.capture()
        MemoryAccountScope.requireCurrent(session)
        val appCtx = appContext ?: return
        resetAccount(session)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            MemoryAccountScope.requireCurrent(session)
            com.example.data.local.AppDatabase.getDatabase(appCtx).chatMessageDao().clearAllMessages(session.ownerKey)
            if (!MemoryAccountScope.isCurrent(session)) return@launch
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
