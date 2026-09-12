package com.example.ui.reminders

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.Reminder
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RemindersViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getDatabase(application).reminderDao()
    private val repo = JarvisRepository(application)

    private val _selectedFilter = MutableStateFlow("ALL")
    private val _isCreatingNew = MutableStateFlow(false)
    private val _selectedReminderForEdit = MutableStateFlow<Reminder?>(null)

    private val _events = Channel<RemindersUiEvent>(Channel.BUFFERED)
    val events: Flow<RemindersUiEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<RemindersUiState> = combine(
        dao.getAllRemindersFlow(),
        _selectedFilter,
        _isCreatingNew,
        _selectedReminderForEdit
    ) { allReminders, filter, isCreating, editReminder ->
        val pending = allReminders.filter { it.status.equals("pending", ignoreCase = true) }
        val completed = allReminders.filter { it.status.equals("completed", ignoreCase = true) }

        RemindersUiState(
            pendingReminders = pending,
            completedReminders = completed,
            selectedFilter = filter,
            isCreatingNew = isCreating,
            selectedReminderForEdit = editReminder,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RemindersUiState(isLoading = true)
    )

    fun onFilterSelected(filter: String) {
        _selectedFilter.value = filter
    }

    fun startCreate() {
        _isCreatingNew.value = true
        _selectedReminderForEdit.value = null
    }

    fun startEdit(reminder: Reminder) {
        _selectedReminderForEdit.value = reminder
        _isCreatingNew.value = false
    }

    fun dismissEditor() {
        _isCreatingNew.value = false
        _selectedReminderForEdit.value = null
    }

    fun saveReminder(
        message: String,
        triggerAtMillis: Long,
        automationType: String? = null,
        automationTarget: String? = null,
        automationMessage: String? = null
    ) {
        if (message.isBlank()) {
            viewModelScope.launch { _events.send(RemindersUiEvent.ShowToast("Reminder message cannot be empty")) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val current = _selectedReminderForEdit.value
            if (current != null) {
                val updated = current.copy(
                    message = message,
                    triggerAt = triggerAtMillis,
                    automationType = automationType,
                    automationTarget = automationTarget,
                    automationMessage = automationMessage
                )
                dao.insertReminder(updated)
                repo.scheduleSystemAlarm(updated.id, updated.message, updated.triggerAt)
            } else {
                val newRem = Reminder(
                    message = message,
                    triggerAt = triggerAtMillis,
                    status = "pending",
                    automationType = automationType,
                    automationTarget = automationTarget,
                    automationMessage = automationMessage
                )
                val id = dao.insertReminder(newRem).toInt()
                repo.scheduleSystemAlarm(id, newRem.message, newRem.triggerAt)
            }
            dismissEditor()
            _events.send(RemindersUiEvent.ShowToast("Reminder scheduled"))
        }
    }

    fun toggleComplete(reminder: Reminder) {
        viewModelScope.launch(Dispatchers.IO) {
            val newStatus = if (reminder.status == "pending") "completed" else "pending"
            dao.updateReminderStatus(reminder.id, newStatus)
            _events.send(RemindersUiEvent.ShowToast(if (newStatus == "completed") "Marked as completed" else "Marked as pending"))
        }
    }

    fun deleteReminder(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteReminderById(id)
            _events.send(RemindersUiEvent.ShowToast("Reminder deleted"))
        }
    }
}
