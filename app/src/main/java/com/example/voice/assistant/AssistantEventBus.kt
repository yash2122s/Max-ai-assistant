package com.example.voice.assistant

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class AssistantEvent {
    data class TextTranscribed(val role: String, val text: String) : AssistantEvent()
    data class ResponseChunk(val text: String) : AssistantEvent()
    data class TurnComplete(val fullText: String = "") : AssistantEvent()
    data class AudioRmsChanged(val rms: Float) : AssistantEvent()
    data class ToolProgress(val toolName: String, val status: String) : AssistantEvent()
    data class NeedsUserInput(
        val stepId: String,
        val question: String,
        val options: List<String> = emptyList(),
        val targetParam: String? = null
    ) : AssistantEvent()
    data class ErrorOccurred(val errorType: ErrorType, val message: String) : AssistantEvent()
    data class ConnectionStateChanged(val state: com.example.network.ConnectionState) : AssistantEvent()
}


enum class ErrorType {
    NETWORK,
    PERMISSION,
    TOOL,
    AUDIO,
    GENERIC
}

object AssistantEventBus {
    private val _events = MutableSharedFlow<AssistantEvent>(replay = 0, extraBufferCapacity = 100)
    val events: SharedFlow<AssistantEvent> = _events.asSharedFlow()

    fun emit(event: AssistantEvent) {
        _events.tryEmit(event)
    }
}
