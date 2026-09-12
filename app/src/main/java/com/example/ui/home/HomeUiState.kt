package com.example.ui.home

import com.example.network.ConnectionState
import org.json.JSONObject

/**
 * Immutable UI message item for the native chat feed.
 */
data class ChatMessageItem(
    val id: Int = 0,
    val sender: String, // "user" or "jarvis"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isPending: Boolean = false
) {
    val isUser: Boolean get() = sender.equals("user", ignoreCase = true) || sender.equals("You", ignoreCase = true)
}

/**
 * Immutable state contract for the Native HomeScreen.
 * Contains all state required to render the MAX home interface.
 */
data class HomeUiState(
    val isMaxEnabled: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isRecording: Boolean = false,
    val isThinking: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val audioRms: Float = 0f,
    val liveUserCaption: String = "",
    val liveAiCaption: String = "",
    val messages: List<ChatMessageItem> = emptyList(),
    val error: String? = null,
    val pendingAutomation: JSONObject? = null
)

/**
 * One-shot UI side-effect events emitted via SharedFlow.
 */
sealed interface HomeUiEvent {
    data class ShowSnackbar(val message: String) : HomeUiEvent
    data class RequestPermission(val permission: String) : HomeUiEvent
    data class NavigateTo(val route: String) : HomeUiEvent
    object ScrollToBottom : HomeUiEvent
}
