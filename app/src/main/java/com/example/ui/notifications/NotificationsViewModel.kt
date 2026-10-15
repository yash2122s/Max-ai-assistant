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
    private val _whatsappAutoReplyScope = MutableStateFlow(settingsManager.whatsappAutoReplyScope)
    private val _selectedContacts = MutableStateFlow(settingsManager.whatsappSelectedContacts)
    private val _availableDeviceContacts = MutableStateFlow<List<com.example.automation.contact.Contact>>(emptyList())
    private val _isContactPickerOpen = MutableStateFlow(false)
    private val _contactSearchQuery = MutableStateFlow("")
    private val _isCreatingRule = MutableStateFlow(false)
    private val _selectedRuleForEdit = MutableStateFlow<AutoReplyRule?>(null)

    private val _events = Channel<NotificationsUiEvent>(Channel.BUFFERED)
    val events: Flow<NotificationsUiEvent> = _events.receiveAsFlow()

    private data class NotificationSettingsTuple(
        val isAutoReplyEnabled: Boolean,
        val isWhatsAppAutoReplyEnabled: Boolean,
        val isAiFallbackEnabled: Boolean,
        val quickReplies: String,
        val whatsappAutoReplyScope: String,
        val selectedContacts: Set<String>
    )

    private data class WhatsAppScopeTuple(
        val scope: String,
        val contacts: Set<String>
    )

    private val _whatsAppScopeFlow = combine(
        _whatsappAutoReplyScope,
        _selectedContacts
    ) { scope, contacts ->
        WhatsAppScopeTuple(scope, contacts)
    }

    private val _settingsFlow = combine(
        _isAutoReplyEnabled,
        _isWhatsAppAutoReplyEnabled,
        _isAiFallbackEnabled,
        _quickReplies,
        _whatsAppScopeFlow
    ) { autoReply, waAutoReply, aiFallback, replies, waScope ->
        NotificationSettingsTuple(
            isAutoReplyEnabled = autoReply,
            isWhatsAppAutoReplyEnabled = waAutoReply,
            isAiFallbackEnabled = aiFallback,
            quickReplies = replies,
            whatsappAutoReplyScope = waScope.scope,
            selectedContacts = waScope.contacts
        )
    }

    private data class PickerUiTuple(
        val availableDeviceContacts: List<com.example.automation.contact.Contact>,
        val isContactPickerOpen: Boolean,
        val contactSearchQuery: String
    )

    private val _pickerFlow = combine(
        _availableDeviceContacts,
        _isContactPickerOpen,
        _contactSearchQuery
    ) { contacts, isOpen, query ->
        PickerUiTuple(contacts, isOpen, query)
    }

    val uiState: StateFlow<NotificationsUiState> = combine(
        _settingsFlow,
        _pickerFlow,
        dao.getAllRulesFlow(),
        _isCreatingRule,
        _selectedRuleForEdit
    ) { settings, picker, rules, creating, editRule ->
        NotificationsUiState(
            isAutoReplyEnabled = settings.isAutoReplyEnabled,
            isWhatsAppAutoReplyEnabled = settings.isWhatsAppAutoReplyEnabled,
            isAiFallbackEnabled = settings.isAiFallbackEnabled,
            quickReplies = settings.quickReplies,
            whatsappAutoReplyScope = settings.whatsappAutoReplyScope,
            selectedContacts = settings.selectedContacts,
            availableDeviceContacts = picker.availableDeviceContacts,
            isContactPickerOpen = picker.isContactPickerOpen,
            contactSearchQuery = picker.contactSearchQuery,
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

    fun setWhatsAppAutoReplyScope(scope: String) {
        _whatsappAutoReplyScope.value = scope
        settingsManager.whatsappAutoReplyScope = scope
    }

    fun addSelectedContact(contact: String) {
        val trimmed = contact.trim()
        if (trimmed.isBlank()) return
        val current = _selectedContacts.value.toMutableSet()
        if (current.add(trimmed)) {
            _selectedContacts.value = current
            settingsManager.whatsappSelectedContacts = current
        }
    }

    fun removeSelectedContact(contact: String) {
        val current = _selectedContacts.value.toMutableSet()
        if (current.remove(contact.trim())) {
            _selectedContacts.value = current
            settingsManager.whatsappSelectedContacts = current
        }
    }

    fun openContactPicker() {
        _isContactPickerOpen.value = true
        _contactSearchQuery.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            val contacts = com.example.automation.contact.ContactResolver.getDeviceContacts(getApplication())
            _availableDeviceContacts.value = contacts
        }
    }

    fun closeContactPicker() {
        _isContactPickerOpen.value = false
        _contactSearchQuery.value = ""
    }

    fun updateContactSearchQuery(query: String) {
        _contactSearchQuery.value = query
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
