package com.example.ui.reminders

import com.example.data.local.Reminder

data class RemindersUiState(
    val pendingReminders: List<Reminder> = emptyList(),
    val completedReminders: List<Reminder> = emptyList(),
    val selectedFilter: String = "ALL", // "ALL", "PENDING", "COMPLETED"
    val isCreatingNew: Boolean = false,
    val selectedReminderForEdit: Reminder? = null,
    val isLoading: Boolean = false
)

sealed interface RemindersUiEvent {
    data class ShowToast(val message: String) : RemindersUiEvent
}
