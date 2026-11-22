package com.example.ui.reminders

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.Reminder
import com.example.data.repository.JarvisRepository
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

    private var saving = false

    fun saveReminder(message: String, triggerAtMillis: Long?) {
        if (saving) return
        if (message.isBlank()) {
            viewModelScope.launch { _events.send(RemindersUiEvent.ShowToast("Reminder message cannot be empty")) }
            return
        }
        val id = _selectedReminderForEdit.value?.id
        saving = true
        viewModelScope.launch {
            try {
                repo.saveReminder(id, message, triggerAtMillis)
                dismissEditor()
                _events.send(RemindersUiEvent.ShowToast("Reminder saved"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(RemindersUiEvent.ShowToast("Could not save reminder. Check alarm permissions and try again."))
            } finally {
                saving = false
            }
        }
    }

    fun toggleComplete(reminder: Reminder) {
        viewModelScope.launch {
            if (reminder.status.equals("pending", ignoreCase = true)) {
                repo.markReminderCompleted(reminder.id)
                _events.send(RemindersUiEvent.ShowToast("Marked as completed"))
            } else if (reminder.triggerAt <= System.currentTimeMillis()) {
                startEdit(reminder)
                _events.send(RemindersUiEvent.ShowToast("Choose a new time to reopen this reminder"))
            } else if (repo.reopenReminder(reminder.id)) {
                _events.send(RemindersUiEvent.ShowToast("Reminder scheduled"))
            } else {
                _events.send(RemindersUiEvent.ShowToast("Could not reschedule reminder"))
            }
        }
    }

    fun deleteReminder(id: Int) {
        viewModelScope.launch {
            repo.deleteReminder(id)
            _events.send(RemindersUiEvent.ShowToast("Reminder deleted"))
        }
    }
}
