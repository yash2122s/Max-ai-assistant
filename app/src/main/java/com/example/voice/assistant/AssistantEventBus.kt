package com.example.voice.assistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class AssistantEvent {
    data class TextTranscribed(val role: String, val text: String) : AssistantEvent()
    data class AudioRmsChanged(val rms: Float) : AssistantEvent()
    data class ToolProgress(val toolName: String, val status: String) : AssistantEvent()
    data class ErrorOccurred(val errorType: ErrorType, val message: String) : AssistantEvent()
}

enum class ErrorType {
    NETWORK,
    PERMISSION,
    TOOL,
    AUDIO,
    GENERIC
}

object AssistantEventBus {
    private val _events = MutableSharedFlow<AssistantEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<AssistantEvent> = _events.asSharedFlow()

    fun emit(event: AssistantEvent) {
        _events.tryEmit(event)
    }
}
