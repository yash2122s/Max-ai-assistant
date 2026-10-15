package com.example.voice.assistant

import com.example.data.preferences.SettingsManager
import com.example.network.ConnectionState
import com.example.network.model.GeminiModelMode
import com.example.viewmodel.ChatMessage
import com.example.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AssistantUiState(
    val sessionState: SessionState = SessionState.IDLE,
    val isRecording: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val isThinking: Boolean = false,
    val modelMode: GeminiModelMode = GeminiModelMode.AUTO,
    val error: String? = null,
    val userTranscription: String = "",
    val geminiResponse: String = "",
    val thoughtSummary: String = "",
    val isThinkingObservabilityEnabled: Boolean = true,
    val interactionStatus: String = "IDLE",
    val statusText: String = "Connected",
    val activeToolName: String? = null,
    val activeToolDescription: String? = null,
    val activeToolStatus: String? = null,
    val isWindowsAgent: Boolean = false,
    val visualizerRms: Float = 0f,
    val messages: List<ChatMessage> = emptyList()
)

class AssistantVoiceController {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val viewModel = AssistantSessionManager.viewModel

    init {
        scope.launch {
            viewModel.uiState.collect { chatState ->
                val sessionState = when {
                    chatState.error != null -> SessionState.ERROR
                    chatState.isRecording -> SessionState.LISTENING
                    chatState.isAiSpeaking -> SessionState.STREAMING_AUDIO
                    chatState.isThinking -> SessionState.WAITING_FOR_MODEL
                    chatState.connectionState == ConnectionState.CONNECTING -> SessionState.CONNECTING
                    chatState.connectionState == ConnectionState.CONNECTED -> SessionState.CONNECTED
                    else -> SessionState.IDLE
                }

                val currentMode = viewModel.appContext?.let { ctx ->
                    val sm = SettingsManager(ctx)
                    GeminiModelMode.fromString(sm.modelRoutingMode)
                } ?: GeminiModelMode.AUTO

                val observabilityEnabled = viewModel.appContext?.let { ctx ->
                    SettingsManager(ctx).isThinkingObservabilityEnabled
                } ?: true

                _uiState.update { current ->
                    current.copy(
                        sessionState = sessionState,
                        isRecording = chatState.isRecording,
                        isAiSpeaking = chatState.isAiSpeaking,
                        isThinking = chatState.isThinking || current.interactionStatus == "IN_PROGRESS",
                        modelMode = currentMode,
                        isThinkingObservabilityEnabled = observabilityEnabled,
                        error = chatState.error,
                        userTranscription = chatState.liveUserCaption,
                        geminiResponse = chatState.liveAiCaption,
                        statusText = when {
                            chatState.error != null -> "Error occurred"
                            current.activeToolName != null -> "Executing ${current.activeToolName}…"
                            chatState.isRecording -> "Listening…"
                            chatState.isAiSpeaking -> "MAX Speaking…"
                            chatState.isThinking || current.thoughtSummary.isNotEmpty() ->
                                if (currentMode == GeminiModelMode.DEEP) "Extended Thinking…" else "Thinking…"
                            current.interactionStatus == "IN_PROGRESS" -> "Reasoning in background…"
                            else -> "Ready"
                        },
                        messages = chatState.messages,
                        visualizerRms = if (chatState.isRecording || chatState.isAiSpeaking) ChatViewModel.rmsFlow.value else 0f
                    )
                }
            }
        }
        
        scope.launch {
            ChatViewModel.rmsFlow.collect { rms ->
                val current = _uiState.value
                if (current.isRecording || current.isAiSpeaking) {
                    _uiState.update { it.copy(visualizerRms = rms) }
                }
            }
        }

        scope.launch {
            AssistantEventBus.events.collect { event ->
                when (event) {
                    is AssistantEvent.ThoughtSummaryDelta -> {
                        _uiState.update { current ->
                            current.copy(
                                thoughtSummary = event.fullSummary,
                                isThinking = true,
                                statusText = if (current.modelMode == GeminiModelMode.DEEP) "Extended Thinking…" else "Thinking…"
                            )
                        }
                    }
                    is AssistantEvent.InteractionStatusChanged -> {
                        _uiState.update { current ->
                            val isProgress = event.status.equals("IN_PROGRESS", ignoreCase = true)
                            current.copy(
                                interactionStatus = event.status,
                                isThinking = isProgress,
                                statusText = if (isProgress) "Processing workflow…" else current.statusText
                            )
                        }
                    }
                    is AssistantEvent.ToolProgress -> {
                        _uiState.update { current ->
                            current.copy(
                                activeToolName = event.toolName,
                                activeToolStatus = event.status,
                                statusText = "Executing ${event.toolName}…"
                            )
                        }
                    }
                    is AssistantEvent.TextTranscribed -> {
                        if (event.role.equals("You", ignoreCase = true) || event.role.equals("user", ignoreCase = true)) {
                            _uiState.update { it.copy(thoughtSummary = "", activeToolName = null, activeToolStatus = null) }
                        }
                    }
                    is AssistantEvent.TurnComplete -> {
                        _uiState.update { current ->
                            current.copy(
                                interactionStatus = "IDLE",
                                isThinking = false,
                                activeToolName = null,
                                activeToolStatus = null
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun toggleMic() {
        viewModel.toggleRecording()
    }

    fun interruptSpeaking() {
        viewModel.onSpeechInterrupted()
    }

    fun toggleModelMode() {
        val ctx = viewModel.appContext ?: return
        val sm = SettingsManager(ctx)
        val current = GeminiModelMode.fromString(sm.modelRoutingMode)
        val next = if (current == GeminiModelMode.DEEP) GeminiModelMode.FAST else GeminiModelMode.DEEP
        sm.modelRoutingMode = next.name
        _uiState.update { it.copy(modelMode = next) }
    }

    fun sendTextMessage(text: String) {
        viewModel.sendTextMessage(text)
    }

    fun reconnect() {
        val context = viewModel.appContext ?: return
        viewModel.reconnect(
            "",
            "Aoede",
            "Tenglish"
        )
    }
}
