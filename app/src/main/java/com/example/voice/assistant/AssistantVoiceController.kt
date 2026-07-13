package com.example.voice.assistant

import com.example.network.ConnectionState
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
    val error: String? = null,
    val userTranscription: String = "",
    val geminiResponse: String = "",
    val statusText: String = "Connected",
    val activeToolName: String? = null,
    val visualizerRms: Float = 0f
)

class AssistantVoiceController {
    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val viewModel = AssistantSessionManager.viewModel

    init {
        scope.launch {
            viewModel.uiState.collect { chatState ->
                val userMsg = chatState.messages.firstOrNull { it.role == "You" }?.content ?: ""
                val geminiMsg = chatState.messages.firstOrNull { it.role == "Gemini" }?.content ?: ""
                
                val sessionState = when {
                    chatState.error != null -> SessionState.ERROR
                    chatState.isRecording -> SessionState.LISTENING
                    chatState.connectionState == ConnectionState.CONNECTING -> SessionState.CONNECTING
                    chatState.connectionState == ConnectionState.CONNECTED -> SessionState.CONNECTED
                    else -> SessionState.IDLE
                }

                _uiState.update { it.copy(
                    sessionState = sessionState,
                    isRecording = chatState.isRecording,
                    error = chatState.error,
                    userTranscription = userMsg,
                    geminiResponse = geminiMsg,
                    statusText = if (chatState.isRecording) "Listening..." else "Connected",
                    visualizerRms = if (chatState.isRecording) ChatViewModel.rmsFlow.value else 0f
                ) }
            }
        }
        
        scope.launch {
            ChatViewModel.rmsFlow.collect { rms ->
                if (viewModel.uiState.value.isRecording) {
                    _uiState.update { it.copy(visualizerRms = rms) }
                }
            }
        }
    }

    fun toggleMic() {
        viewModel.toggleRecording()
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
