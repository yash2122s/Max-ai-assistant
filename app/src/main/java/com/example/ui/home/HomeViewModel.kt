package com.example.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.data.local.AppDatabase
import com.example.data.local.ChatMessage
import com.example.domain.usecase.AssistantUseCase
import com.example.memory.data.MemoryRepository
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
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
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val assistantUseCase = AssistantUseCase(application)
    private val chatMessageDao = AppDatabase.getDatabase(application).chatMessageDao()
    private val memoryRepository = MemoryRepository.getInstance(application)
    private val settingsManager = com.example.data.preferences.SettingsManager(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _liveUserCaption = MutableStateFlow("")
    val liveUserCaption: StateFlow<String> = _liveUserCaption.asStateFlow()

    private val _liveAiCaption = MutableStateFlow("")
    val liveAiCaption: StateFlow<String> = _liveAiCaption.asStateFlow()

    private val _isDarkMode = MutableStateFlow<Boolean>(settingsManager.isDarkMode)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun toggleDarkMode() {
        val newMode = !_isDarkMode.value
        settingsManager.isDarkMode = newMode
        _isDarkMode.value = newMode
    }

    private val _uiEvents = MutableSharedFlow<HomeUiEvent>(extraBufferCapacity = 20)
    val uiEvents: SharedFlow<HomeUiEvent> = _uiEvents.asSharedFlow()

    private val _edgeRevealTrigger = MutableStateFlow<Long?>(null)
    val edgeRevealTrigger: StateFlow<Long?> = _edgeRevealTrigger.asStateFlow()

    val cloudLicense: StateFlow<com.example.firebase.model.CloudLicense> =
        (application as com.example.MyApplication).maxLicenseManager.currentLicense
    val dailyUsage: StateFlow<Long> =
        (application as com.example.MyApplication).maxLicenseManager.dailyUsageCount

    private var persistedChatItems: List<ChatMessageItem> = emptyList()
    private val streamingResponseBuilder = StringBuilder()
    @Volatile
    private var activeStreamingMessage: ChatMessageItem? = null
    @Volatile
    private var lastPersistedUserText: String = ""
    private var captionClearJob: Job? = null
    private var isModelIdle: Boolean = true
    @Volatile private var turnGeneration = 0L
    @Volatile private var responseSettled = false
    @Volatile private var suppressResponseUntilNextTurn = false
    private var sessionSnapshot = SessionState.IDLE
    private var terminalPhase: AssistantPhase? = null
    private var terminalDetail: String? = null
    private var waitingQuestion: String? = null
    private var cancellationRequested = false
    private var activeExecutionId: java.util.UUID? = null
    private var planningExecutionId: java.util.UUID? = null
    private var ignoredActivity: com.example.automation.activity.AgentActivityState? = null
    private var lastFinalText: String? = null
    @Volatile private var activeAccountSession = MemoryAccountScope.capture()

    private fun resetAccount(session: MemorySession) {
        activeAccountSession = session
        turnGeneration++
        suppressResponseUntilNextTurn = true
        responseSettled = false
        captionClearJob?.cancel()
        synchronized(streamingResponseBuilder) {
            streamingResponseBuilder.setLength(0)
            activeStreamingMessage = null
        }
        persistedChatItems = emptyList()
        lastPersistedUserText = ""
        lastFinalText = null
        _liveAiCaption.value = ""
        _liveUserCaption.value = ""
        _uiState.update { it.copy(messages = emptyList(), liveAiCaption = "", liveUserCaption = "",
            pendingAutomation = null, isThinking = false, isAiSpeaking = false, isRecording = false) }
    }

    private fun refreshPresentation() {
        val state = _uiState.value
        val approval = com.example.automation.approval.ApprovalCoordinator.currentPending()?.question
        val activity = AgentActivityTracker.state.value.let { activity ->
            if (!state.isMaxEnabled || activity === ignoredActivity ||
                (terminalPhase == null && !activity.active && activity.stage in setOf(
                    com.example.automation.activity.AgentStage.COMPLETED,
                    com.example.automation.activity.AgentStage.FAILED,
                    com.example.automation.activity.AgentStage.CANCELLED))) {
                com.example.automation.activity.AgentActivityState()
            } else activity
        }
        _uiState.update { it.copy(activity = activity, presentation = assistantPresentation(AssistantSnapshot(
            enabled = state.isMaxEnabled,
            connected = state.connectionState, session = sessionSnapshot,
            recording = state.isRecording, thinking = state.isThinking,
            streaming = activeStreamingMessage != null, playing = state.isAiSpeaking,
            activity = activity,
            terminal = terminalPhase, detail = terminalDetail,
            waitingQuestion = waitingQuestion, approvalQuestion = approval,
            cancelling = cancellationRequested, responseSettled = responseSettled
        ))) }
    }

    private fun beginTurn() {
        turnGeneration++
        responseSettled = false
        suppressResponseUntilNextTurn = false
        ignoredActivity = AgentActivityTracker.state.value
        terminalPhase = null
        terminalDetail = null
        waitingQuestion = null
        cancellationRequested = false
        sessionSnapshot = SessionState.IDLE
        isModelIdle = true
        activeExecutionId = null
        planningExecutionId = null
        lastFinalText = null
        discardStreaming()
        refreshPresentation()
    }

    private fun discardStreaming() {
        synchronized(streamingResponseBuilder) {
            streamingResponseBuilder.setLength(0)
            activeStreamingMessage = null
        }
        updateMergedMessages()
    }

    private fun finish(phase: AssistantPhase, detail: String? = null) {
        terminalPhase = phase
        terminalDetail = detail
        cancellationRequested = false
        isModelIdle = true
        waitingQuestion = null
        sessionSnapshot = SessionState.IDLE
        activeStreamingMessage?.takeIf { it.text.isNotBlank() && it.text != lastFinalText }?.let {
            lastFinalText = it.text
            persistChatMessage("jarvis", it.text)
        }
        discardStreaming()
        _uiState.update { it.copy(isThinking = false) }
        refreshPresentation()
    }

    init {
        (application as com.example.MyApplication).maxLicenseManager.authManager
            .addSessionInvalidationListener(this) {
                resetAccount(MemoryAccountScope.capture())
                turnGeneration++
                suppressResponseUntilNextTurn = true
                responseSettled = false
                terminalPhase = null
                terminalDetail = null
                waitingQuestion = null
                cancellationRequested = false
                sessionSnapshot = SessionState.IDLE
                discardStreaming()
                _liveAiCaption.value = ""
                _liveUserCaption.value = ""
                _uiState.update { it.copy(isThinking = false, isAiSpeaking = false,
                    isRecording = false, liveAiCaption = "", liveUserCaption = "") }
                refreshPresentation()
            }
        // 1. Observe master runtime enabled state
        viewModelScope.launch {
            MaxRuntimeManager.isMaxEnabledFlow.collect { enabled ->
                _uiState.update { it.copy(isMaxEnabled = enabled) }
                if (!enabled) {
                    turnGeneration++
                    suppressResponseUntilNextTurn = true
                    cancellationRequested = false
                    discardStreaming()
                    _audioRms.value = 0f
                    terminalPhase = null
                    terminalDetail = null
                    waitingQuestion = null
                    sessionSnapshot = SessionState.IDLE
                    _uiState.update { it.copy(isThinking = false, isRecording = false, isAiSpeaking = false) }
                    refreshPresentation()
                }
            }
        }
        viewModelScope.launch {
            AgentActivityTracker.state.collect { refreshPresentation() }
        }
        viewModelScope.launch {
            AutomationEventBus.events.collect { event ->
                when (event) {
                    is AutomationEvent.PlanningStarted -> {
                        planningExecutionId = event.executionId
                        activeExecutionId = event.executionId
                        terminalPhase = null
                        cancellationRequested = false
                    }
                    is AutomationEvent.ExecutionStarted -> {
                        activeExecutionId = event.executionId
                        terminalPhase = null
                        cancellationRequested = false
                    }
                    is AutomationEvent.ExecutionCancelled -> {
                        if (activeExecutionId == null || activeExecutionId == event.executionId || cancellationRequested) {
                            finish(AssistantPhase.CANCELLED, event.reason)
                        }
                    }
                    is AutomationEvent.ExecutionFinished -> {
                        if ((planningExecutionId ?: activeExecutionId) == event.executionId) {
                            val truth = com.example.automation.engine.truth.ExecutionTruthCoordinator.getLastExecution()
                                ?.takeIf { it.executionId == event.executionId.toString() }
                            val outcome = when {
                                truth?.status == "BLOCKED" -> AssistantPhase.BLOCKED
                                truth?.status == "UNVERIFIED" || truth?.status == "ACCEPTED" -> AssistantPhase.UNVERIFIED
                                event.success && truth?.finalSuccess == true -> AssistantPhase.COMPLETED
                                event.success -> AssistantPhase.UNVERIFIED
                                else -> AssistantPhase.FAILED
                            }
                            finish(outcome, truth?.failureReason)
                            planningExecutionId = null
                        }
                    }
                    is AutomationEvent.NeedsUserInput -> if (activeExecutionId == event.executionId) {
                        waitingQuestion = event.question
                    }
                    is AutomationEvent.ToolFailed -> if (activeExecutionId == event.executionId) {
                        terminalDetail = event.message
                    }
                    is AutomationEvent.VerificationFailed -> if (activeExecutionId == event.executionId) {
                        terminalDetail = event.reason
                    }
                    else -> Unit
                }
                refreshPresentation()
            }
        }

        // 2. Observe Room chat messages in real time
        viewModelScope.launch {
            MemoryAccountScope.sessions.flatMapLatest { session ->
                resetAccount(session)
                if (session.ownerKey == MemoryAccountScope.UNAVAILABLE) flowOf(emptyList())
                else chatMessageDao.getAllMessagesFlow(session.ownerKey).map { rows ->
                    if (MemoryAccountScope.isCurrent(session)) rows else emptyList()
                }.onStart { emit(emptyList()) }
            }.collect { roomList ->
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
                            beginTurn()
                            isModelIdle = false
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
                        } else {
                            if (suppressResponseUntilNextTurn || cancellationRequested) return@collect
                            val aiText = visibleResponse(event.text)
                            // Local replies may have no TurnComplete event at all.
                            // Use one finalization path for both event sources.
                            handleAiTurnComplete(event.text)
                            _liveAiCaption.value = aiText
                            _uiState.update { state ->
                                state.copy(
                                    liveAiCaption = aiText,
                                    isThinking = false
                                )
                            }
                            scheduleCaptionClear()
                            _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
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
                        if (event.state == ConnectionState.CONNECTING || event.state == ConnectionState.CONNECTED) {
                            if (terminalPhase == AssistantPhase.FAILED && activeExecutionId == null) {
                                terminalPhase = null
                                terminalDetail = null
                            }
                        }
                        if (isDisconnected) {
                            val hasInFlightWork = activeExecutionId != null || _uiState.value.isThinking ||
                                _uiState.value.isRecording || activeStreamingMessage?.isPending == true
                            isModelIdle = true
                            responseSettled = false
                            suppressResponseUntilNextTurn = true
                            sessionSnapshot = SessionState.IDLE
                            discardStreaming()
                            if (terminalPhase == null && hasInFlightWork) terminalPhase = AssistantPhase.FAILED
                        }
                        _uiState.update { 
                            it.copy(
                                connectionState = event.state,
                                isThinking = if (isDisconnected) false else it.isThinking
                            ) 
                        }
                    }
                    is AssistantEvent.ErrorOccurred -> {
                        suppressResponseUntilNextTurn = true
                        finish(AssistantPhase.FAILED, event.message)
                        _uiState.update { it.copy(error = event.message, isThinking = false) }
                        _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("Assistant error: ${event.message}"))
                    }
                    is AssistantEvent.NeedsUserInput -> {
                        terminalPhase = null
                        isModelIdle = true
                        waitingQuestion = event.question
                        discardStreaming()
                        persistChatMessage("jarvis", event.question)
                        _uiState.update { it.copy(isThinking = false, liveAiCaption = event.question) }
                        _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                    }
                    is AssistantEvent.ToolProgress -> {
                        refreshPresentation()
                    }
                    is AssistantEvent.ThoughtSummaryDelta -> {
                        // Extended thinking summary received
                    }
                    is AssistantEvent.InteractionStatusChanged -> {
                        val isIdle = event.status.equals("IDLE", ignoreCase = true)
                        isModelIdle = isIdle
                        if (isIdle) sessionSnapshot = SessionState.IDLE
                        _uiState.update { state ->
                            state.copy(
                                isThinking = if (isIdle || responseSettled || terminalPhase != null) false else (!state.isAiSpeaking)
                            )
                        }
                    }
                }
                if (event !is AssistantEvent.AudioRmsChanged) refreshPresentation()
            }
        }


        // 4. Observe Assistant Session State Machine
        viewModelScope.launch {
            assistantUseCase.sessionState.collect { sessionState ->
                sessionSnapshot = sessionState
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
                refreshPresentation()
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
                refreshPresentation()
            }
        }

        // Initial connection state & immediate connection trigger on screen load
        val initialState = assistantUseCase.getConnectionState()
        _uiState.update { it.copy(connectionState = initialState) }
        refreshPresentation()
        viewModelScope.launch {
            assistantUseCase.ensureConnected()
        }
    }

    fun ensureConnected() {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) {
            terminalPhase = null
            refreshPresentation()
        }
        viewModelScope.launch {
            assistantUseCase.ensureConnected()
        }
    }

    fun sendTextMessage(text: String) {
        val accountSession = MemoryAccountScope.capture()
        if (!MemoryAccountScope.isCurrent(accountSession)) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val app = getApplication<Application>()
        if (!MaxRuntimeManager.isMaxEnabled(app)) {
            _uiState.update { it.copy(error = "MAX is currently OFF. Enable MAX to send messages.") }
            _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("MAX is OFF. Enable MAX first."))
            return
        }

        // Immediately persist user message
        persistChatMessage("user", trimmed)
        interruptSpeech()
        beginTurn()
        isModelIdle = false

        _uiState.update { it.copy(
            isThinking = true,
            liveUserCaption = trimmed,
            liveAiCaption = "",
            error = null
        )}

        val generation = turnGeneration
        refreshPresentation()

        // P1.2 — typed reply to a pending approval (Allow / Cancel)
        if (com.example.automation.approval.ApprovalCoordinator.hasPending()) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    val ctx = com.example.automation.approval.ApprovalCoordinator.context()
                        ?: getApplication<Application>()
                    com.example.automation.approval.ApprovalCoordinator.handleReply(trimmed, ctx)
                }
                if (generation != turnGeneration || !MemoryAccountScope.isCurrent(accountSession)) return@launch
                _uiState.update { it.copy(isThinking = false) }
                refreshPresentation()
            }
            return
        }

        // P1-4: Typed selection for multi-contact disambiguation
        if (com.example.automation.engine.PendingCallManager.state is com.example.automation.engine.CallState.AwaitingContactSelection) {
            val replyIntent = com.example.automation.approval.ApprovalCoordinator.parseReply(trimmed)
            if (replyIntent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY) {
                com.example.automation.engine.PendingCallManager.cancel()
                val reply = "Call cancelled."
                persistChatMessage("jarvis", reply)
                isModelIdle = true
                _liveAiCaption.value = reply
                _uiState.update { it.copy(isThinking = false, liveAiCaption = reply) }
                finish(AssistantPhase.CANCELLED, reply)
                scheduleCaptionClear()
                _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                return
            }

            val selectedCandidate = com.example.automation.engine.PendingCallManager.resolveAndSelectCandidate(trimmed)
            if (selectedCandidate != null) {
                val confirmPrompt = "Do you want to call ${selectedCandidate.contactMatch.displayName} (${selectedCandidate.phoneNumber.normalizedNumber})? Please say yes to confirm or cancel."
                persistChatMessage("jarvis", confirmPrompt)
                isModelIdle = true
                _liveAiCaption.value = confirmPrompt
                _uiState.update { it.copy(isThinking = false, liveAiCaption = confirmPrompt) }
                finish(AssistantPhase.WAITING_FOR_INPUT, confirmPrompt)
                scheduleCaptionClear()
                _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                return
            } else {
                val clarification = "Please choose which number or contact to call (e.g., 1, 2, Mobile, or Work)."
                persistChatMessage("jarvis", clarification)
                isModelIdle = true
                _liveAiCaption.value = clarification
                _uiState.update { it.copy(isThinking = false, liveAiCaption = clarification) }
                finish(AssistantPhase.WAITING_FOR_INPUT, clarification)
                scheduleCaptionClear()
                _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                return
            }
        }

        // Typed reply to a pending phone call confirmation
        if (com.example.automation.engine.PendingCallManager.hasPendingConfirmation()) {
            val replyIntent = com.example.automation.approval.ApprovalCoordinator.parseReply(trimmed)
            if (replyIntent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE) {
                val pending = com.example.automation.engine.PendingCallManager.getPendingConfirmation()
                if (pending != null) {
                    com.example.automation.engine.PendingCallManager.confirmPendingCall(pending.contactId, pending.phoneId)
                    viewModelScope.launch {
                        val callArgs = org.json.JSONObject().apply {
                            put("action", "CALL_PHONE")
                            put("contactId", pending.contactId)
                            put("phoneId", pending.phoneId)
                            put("contact", pending.displayName)
                            put("phoneNumber", pending.phoneNumber)
                            put("confirmed", true)
                            put("user_confirmed", true)
                            put("last_input_transcription", trimmed)
                        }
                        val resultJsonStr = withContext(Dispatchers.IO) {
                            com.example.automation.engine.ActionDispatcher.dispatchWithResult(getApplication<Application>(), callArgs)
                        }
                        if (generation != turnGeneration) return@launch
                        val resultObj = runCatching { org.json.JSONObject(resultJsonStr) }.getOrNull()
                        val isSuccess = resultObj?.optBoolean("success", false) == true
                        val reply = if (isSuccess) {
                            "Calling ${pending.displayName}..."
                        } else {
                            resultObj?.optString("message") ?: "Failed to place call."
                        }
                        persistChatMessage("jarvis", reply)
                        isModelIdle = true
                        _liveAiCaption.value = reply
                        _uiState.update { it.copy(isThinking = false, liveAiCaption = reply) }
                        if (terminalPhase == null) finish(
                            if (isSuccess) AssistantPhase.UNVERIFIED else AssistantPhase.FAILED, reply)
                        scheduleCaptionClear()
                        _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                    }
                    return
                }
            } else if (replyIntent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY) {
                com.example.automation.engine.PendingCallManager.cancel()
                val reply = "Call cancelled."
                persistChatMessage("jarvis", reply)
                isModelIdle = true
                _liveAiCaption.value = reply
                _uiState.update { it.copy(isThinking = false, liveAiCaption = reply) }
                finish(AssistantPhase.CANCELLED, reply)
                scheduleCaptionClear()
                _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)
                return
            }
        }

        // P1.4 — typed reply to a pending habit routine suggestion
        if (com.example.automation.habits.HabitSuggestionCoordinator.hasPending()) {
            viewModelScope.launch {
                val intent = com.example.automation.approval.ApprovalCoordinator.parseReply(trimmed)
                when (intent) {
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE -> {
                        val ctx = com.example.automation.habits.HabitSuggestionCoordinator.context()
                            ?: getApplication<Application>()
                        val result = withContext(Dispatchers.IO) { try {
                            com.example.automation.habits.HabitSuggestionCoordinator.approveAndRun(ctx)
                        } catch (e: Exception) {
                            "Could not create routine: ${e.message}"
                        } }
                        if (generation != turnGeneration) return@launch
                        _uiState.update { it.copy(isThinking = false) }
                        com.example.voice.assistant.AssistantEventBus.emit(
                            com.example.voice.assistant.AssistantEvent.TextTranscribed("MAX", result)
                        )
                    }
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY ->
                        com.example.automation.habits.HabitSuggestionCoordinator.cancel()
                    com.example.automation.approval.ApprovalCoordinator.ReplyIntent.OTHER -> Unit
                }
                if (generation != turnGeneration) return@launch
                if (intent != com.example.automation.approval.ApprovalCoordinator.ReplyIntent.OTHER) {
                    _uiState.update { it.copy(isThinking = false) }
                    refreshPresentation()
                }
            }
            return
        }

        // Check if there is an active paused plan waiting for user input
        if (com.example.automation.engine.GoalPlanner.getWaitingInputState() != null) {
            viewModelScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        com.example.automation.engine.GoalPlanner.resumePlanWithInput(app, trimmed)
                    }
                    if (generation != turnGeneration) return@launch
                    // If resumePlanWithInput yielded another NeedsInput, it already emitted NeedsUserInput event.
                    // Only persist final completion/stop reply if no new NeedsInput was requested.
                    if (com.example.automation.engine.GoalPlanner.getWaitingInputState() == null) {
                        val reply = res.errorMessage ?: if (res.success) "Task completed." else "Action stopped."
                        persistChatMessage("jarvis", reply)
                    }
                    _uiState.update { it.copy(isThinking = false) }
                    refreshPresentation()
                } catch (e: Exception) {
                    if (generation != turnGeneration) return@launch
                    _uiState.update { it.copy(isThinking = false, error = e.message) }
                    finish(AssistantPhase.FAILED, e.message)
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                val offlineResult = withContext(Dispatchers.IO) {
                    assistantUseCase.sendTextMessage(trimmed)
                }
                if (generation != turnGeneration) return@launch

                if (offlineResult != null) {
                    val reply = offlineResult.message ?: if (offlineResult.isVerifiedSuccess) "Task completed and verified."
                        else if (offlineResult.success) "Action submitted; verification pending." else "Action failed."
                    persistChatMessage("jarvis", reply)
                    isModelIdle = true
                    _liveAiCaption.value = reply
                    _uiState.update { it.copy(isThinking = false, liveAiCaption = reply) }
                    scheduleCaptionClear()
                    _uiEvents.tryEmit(HomeUiEvent.ScrollToBottom)

                    val outcome = resultPhase(offlineResult,
                        com.example.automation.approval.ApprovalCoordinator.hasPending())
                    finish(outcome, offlineResult.needsInputQuestion ?: offlineResult.message)
                    if (offlineResult.toolName == "security_gate" && !offlineResult.success) {
                        _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("API key required. Redirecting to Settings..."))
                        _uiEvents.tryEmit(HomeUiEvent.NavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS))
                    }
                }
            } catch (e: Exception) {
                if (generation != turnGeneration) return@launch
                isModelIdle = true
                _uiState.update { it.copy(isThinking = false, error = e.message) }
                finish(AssistantPhase.FAILED, e.message)
            }
        }
    }

    fun toggleRecording() {
        terminalPhase = null
        terminalDetail = null
        waitingQuestion = null
        refreshPresentation()
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
            beginTurn()
            interruptSpeech()
            assistantUseCase.startListening()
            _uiState.update { it.copy(isRecording = true, liveUserCaption = "Listening...") }
        }
        refreshPresentation()
    }

    /**
     * Handles hardware Power-button double-press, System Assistant, or Voice-key triggers.
     * Launches HomeScreen directly into active listening state and triggers open animation.
     */
    fun handleAssistantTrigger(
        triggerSource: String = "power_button",
        startListening: Boolean = true,
        openReveal: Boolean = true
    ) {
        val app = getApplication<Application>()
        if (!MaxRuntimeManager.isMaxEnabled(app)) {
            MaxRuntimeManager.enableMax(app)
        }

        if (openReveal) {
            _edgeRevealTrigger.value = System.currentTimeMillis()
        }

        if (startListening) {
            startListeningDirectly()
        }
    }

    fun startListeningDirectly() {
        terminalPhase = null
        terminalDetail = null
        waitingQuestion = null
        refreshPresentation()
        val app = getApplication<Application>()
        if (!MaxRuntimeManager.isMaxEnabled(app)) {
            MaxRuntimeManager.enableMax(app)
        }
        val settings = com.example.data.preferences.SettingsManager(app)
        if (!settings.hasValidGeminiApiKey()) {
            _uiState.update { it.copy(error = "Gemini API key required for voice assistant.") }
            _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("Gemini API key required. Redirecting to Settings..."))
            _uiEvents.tryEmit(HomeUiEvent.NavigateTo(com.example.ui.navigation.MaxDestinations.SETTINGS))
            return
        }

        beginTurn()
        interruptSpeech()
        assistantUseCase.startListening()
        _uiState.update { it.copy(isRecording = true, liveUserCaption = "Listening...") }
        refreshPresentation()
    }

    fun stopRecording() {
        assistantUseCase.stopListening()
        _uiState.update { it.copy(isRecording = false, liveUserCaption = "") }
        if (sessionSnapshot == SessionState.LISTENING) sessionSnapshot = SessionState.IDLE
        refreshPresentation()
    }

    fun interruptSpeech() {
        assistantUseCase.interruptSpeech()
        captionClearJob?.cancel()
        discardStreaming()
        _uiState.update { it.copy(isAiSpeaking = false, liveAiCaption = "") }
        refreshPresentation()
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
        val session = MemoryAccountScope.capture()
        MemoryAccountScope.requireCurrent(session)
        resetAccount(session)
        viewModelScope.launch {
            synchronized(streamingResponseBuilder) {
                streamingResponseBuilder.setLength(0)
                activeStreamingMessage = null
            }
            withContext(Dispatchers.IO) {
                MemoryAccountScope.requireCurrent(session)
                chatMessageDao.clearAllMessages(session.ownerKey)
            }
            if (!MemoryAccountScope.isCurrent(session)) return@launch
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
        if (terminalPhase == AssistantPhase.FAILED) {
            terminalPhase = null
            terminalDetail = null
            sessionSnapshot = SessionState.IDLE
        }
        refreshPresentation()
    }

    private fun handleIncomingAiChunk(chunk: String) {
        if (cancellationRequested || suppressResponseUntilNextTurn || chunk.isEmpty()) return
        responseSettled = false
        checkForPendingAutomation(chunk)
        captionClearJob?.cancel()

        val currentAiText = synchronized(streamingResponseBuilder) {
            val raw = appendResponseChunk(streamingResponseBuilder.toString(), chunk)
            streamingResponseBuilder.setLength(0)
            streamingResponseBuilder.append(raw)
            val full = visibleResponse(raw)
            activeStreamingMessage = ChatMessageItem(
                id = -1,
                sender = "jarvis",
                text = full,
                timestamp = activeStreamingMessage?.timestamp ?: System.currentTimeMillis(),
                isPending = true
            )
            full
        }

        _liveAiCaption.value = currentAiText
        _liveUserCaption.value = ""
        _uiState.update { state ->
            state.copy(
                isThinking = false,
                liveUserCaption = "",
                liveAiCaption = currentAiText
            )
        }
        updateMergedMessages()
    }

    private fun handleAiTurnComplete(turnText: String) {
        captionClearJob?.cancel()
        if (cancellationRequested || suppressResponseUntilNextTurn) return
        isModelIdle = true
        responseSettled = true
        val textToPersist = synchronized(streamingResponseBuilder) {
            val res = if (turnText.isNotBlank()) turnText else streamingResponseBuilder.toString()
            streamingResponseBuilder.setLength(0)
            visibleResponse(res)
        }
        if (textToPersist.isNotBlank() && textToPersist != lastFinalText) {
            lastFinalText = textToPersist
            activeStreamingMessage = ChatMessageItem(
                id = -1, sender = "jarvis", text = textToPersist,
                timestamp = activeStreamingMessage?.timestamp ?: System.currentTimeMillis(), isPending = false)
            persistChatMessage("jarvis", textToPersist)
        }
        updateMergedMessages()
        _uiState.update { state ->
            state.copy(isThinking = false)
        }
        sessionSnapshot = if (_uiState.value.isAiSpeaking) SessionState.STREAMING_AUDIO else SessionState.IDLE
        refreshPresentation()
        scheduleCaptionClear()
    }

    private fun updateMergedMessages() {
        var currentActive = activeStreamingMessage
        if (currentActive != null && !currentActive.isPending && persistedChatItems.any {
                !it.isUser && it.text == currentActive.text && it.timestamp >= currentActive.timestamp
            }) {
            activeStreamingMessage = null
            currentActive = null
        }
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
                    _uiEvents.tryEmit(HomeUiEvent.ShowSnackbar("Reconnecting to AI Voice Engine..."))
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
        val session = activeAccountSession
        if (!MemoryAccountScope.isCurrent(session)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                MemoryAccountScope.requireCurrent(session)
                chatMessageDao.insertMessage(
                    ChatMessage(
                        sender = sender,
                        text = text,
                        timestamp = System.currentTimeMillis(), ownerKey = session.ownerKey
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Failed to persist chat message", e)
            }
        }
    }

    private fun scheduleCaptionClear() {
        captionClearJob?.cancel()
        captionClearJob = viewModelScope.launch {
            val session = activeAccountSession
            kotlinx.coroutines.delay(4000)
            if (!MemoryAccountScope.isCurrent(session)) return@launch
            _uiState.update { it.copy(
                liveAiCaption = "",
                liveUserCaption = ""
            ) }
        }
    }

    val agentActivityState: StateFlow<com.example.automation.activity.AgentActivityState> =
        com.example.automation.activity.AgentActivityTracker.state

    fun cancelActiveTask() {
        cancellationRequested = true
        suppressResponseUntilNextTurn = true
        turnGeneration++
        discardStreaming()
        _uiState.update { it.copy(isThinking = false) }
        refreshPresentation()
        stopRecording()
        interruptSpeech()
        val wasActive = com.example.automation.engine.TaskCancellationCoordinator.cancelActiveTask(
            context = getApplication(),
            reason = "Task stopped by user from Home UI"
        )
        if (!wasActive) finish(AssistantPhase.CANCELLED, "Voice interaction stopped")
    }

    override fun onCleared() {
        (getApplication<Application>() as com.example.MyApplication).maxLicenseManager.authManager
            .removeSessionInvalidationListener(this)
        super.onCleared()
        assistantUseCase.stopListening()
        captionClearJob?.cancel()
    }
}
