package com.example.ui.notifications

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.AutoReplyRule
import com.example.data.preferences.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class NotificationsViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)
    private val dao = AppDatabase.getDatabase(application).autoReplyRuleDao()

    private val _isAutoReplyEnabled = MutableStateFlow(settingsManager.isAutoReplyEnabled)
    private val _isWhatsAppAutoReplyEnabled = MutableStateFlow(settingsManager.isWhatsAppAutoReplyEnabled)
    private val _isAiFallbackEnabled = MutableStateFlow(settingsManager.isAiFallbackEnabled)
    private val _quickReplies = MutableStateFlow(settingsManager.quickReplies)
    private val _isCreatingRule = MutableStateFlow(false)
    private val _selectedRuleForEdit = MutableStateFlow<AutoReplyRule?>(null)

    private val _events = Channel<NotificationsUiEvent>(Channel.BUFFERED)
    val events: Flow<NotificationsUiEvent> = _events.receiveAsFlow()

    private data class NotificationSettingsTuple(
        val isAutoReplyEnabled: Boolean,
        val isWhatsAppAutoReplyEnabled: Boolean,
        val isAiFallbackEnabled: Boolean,
        val quickReplies: String
    )

    private val _settingsFlow = combine(
        _isAutoReplyEnabled,
        _isWhatsAppAutoReplyEnabled,
        _isAiFallbackEnabled,
        _quickReplies
    ) { autoReply, waAutoReply, aiFallback, replies ->
        NotificationSettingsTuple(autoReply, waAutoReply, aiFallback, replies)
    }

    val uiState: StateFlow<NotificationsUiState> = combine(
        _settingsFlow,
        dao.getAllRulesFlow(),
        _isCreatingRule,
        _selectedRuleForEdit
    ) { settings, rules, creating, editRule ->
        NotificationsUiState(
            isAutoReplyEnabled = settings.isAutoReplyEnabled,
            isWhatsAppAutoReplyEnabled = settings.isWhatsAppAutoReplyEnabled,
            isAiFallbackEnabled = settings.isAiFallbackEnabled,
            quickReplies = settings.quickReplies,
            rules = rules,
            isCreatingRule = creating,
            selectedRuleForEdit = editRule,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotificationsUiState(isLoading = true)
    )

    fun toggleAutoReply(enabled: Boolean) {
        _isAutoReplyEnabled.value = enabled
        settingsManager.isAutoReplyEnabled = enabled
    }

    fun toggleWhatsAppAutoReply(enabled: Boolean) {
        _isWhatsAppAutoReplyEnabled.value = enabled
        settingsManager.isWhatsAppAutoReplyEnabled = enabled
    }

    fun toggleAiFallback(enabled: Boolean) {
        _isAiFallbackEnabled.value = enabled
        settingsManager.isAiFallbackEnabled = enabled
    }

    fun updateQuickReplies(replies: String) {
        _quickReplies.value = replies
        settingsManager.quickReplies = replies
    }

    fun startCreateRule() {
        _isCreatingRule.value = true
        _selectedRuleForEdit.value = null
    }

    fun startEditRule(rule: AutoReplyRule) {
        _selectedRuleForEdit.value = rule
        _isCreatingRule.value = false
    }

    fun dismissEditor() {
        _isCreatingRule.value = false
        _selectedRuleForEdit.value = null
    }

    fun saveRule(triggerKeyword: String, replyMessage: String, targetContact: String? = null) {
        if (triggerKeyword.isBlank() || replyMessage.isBlank()) {
            viewModelScope.launch { _events.send(NotificationsUiEvent.ShowToast("Keyword and reply message are required")) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val current = _selectedRuleForEdit.value
            if (current != null) {
                val updated = current.copy(
                    triggerKeyword = triggerKeyword.trim(),
                    replyMessage = replyMessage.trim(),
                    targetContact = targetContact?.ifBlank { null }
                )
                dao.insertRule(updated)
            } else {
                val newRule = AutoReplyRule(
                    triggerKeyword = triggerKeyword.trim(),
                    replyMessage = replyMessage.trim(),
                    targetContact = targetContact?.ifBlank { null },
                    enabled = true
                )
                dao.insertRule(newRule)
            }
            dismissEditor()
            _events.send(NotificationsUiEvent.ShowToast("Auto-reply rule saved"))
        }
    }

    fun toggleRuleEnabled(rule: AutoReplyRule) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = rule.copy(enabled = !rule.enabled)
            dao.insertRule(updated)
        }
    }

    fun deleteRule(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteRuleById(id)
            _events.send(NotificationsUiEvent.ShowToast("Rule deleted"))
        }
    }
}
