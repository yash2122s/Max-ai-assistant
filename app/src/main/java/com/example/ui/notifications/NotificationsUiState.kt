package com.example.ui.notifications

import com.example.data.local.AutoReplyRule

data class NotificationsUiState(
    val isAutoReplyEnabled: Boolean = false,
    val isWhatsAppAutoReplyEnabled: Boolean = false,
    val isAiFallbackEnabled: Boolean = true,
    val quickReplies: String = "Yes,No,Can't talk now,Call you later",
    val rules: List<AutoReplyRule> = emptyList(),
    val isCreatingRule: Boolean = false,
    val selectedRuleForEdit: AutoReplyRule? = null,
    val isLoading: Boolean = false
)

sealed interface NotificationsUiEvent {
    data class ShowToast(val message: String) : NotificationsUiEvent
}
