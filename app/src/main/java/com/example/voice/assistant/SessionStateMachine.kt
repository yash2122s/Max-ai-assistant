package com.example.voice.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    LISTENING,
    PROCESSING_AUDIO,
    WAITING_FOR_MODEL,
    STREAMING_TEXT,
    STREAMING_AUDIO,
    TOOL_EXECUTION,
    WAITING_TOOL_RESULT,
    RESUMING,
    DISCONNECTING,
    ERROR
}

class SessionStateMachine {
    private val _currentState = MutableStateFlow(SessionState.IDLE)
    val currentState: StateFlow<SessionState> = _currentState.asStateFlow()

    fun transitionTo(newState: SessionState) {
        _currentState.value = newState
    }

    fun getState(): SessionState = _currentState.value
}
