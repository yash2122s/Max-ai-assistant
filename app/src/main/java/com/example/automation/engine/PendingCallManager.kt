package com.example.automation.engine

import org.json.JSONObject
import kotlinx.coroutines.*

data class ContactMatch(
    val contactId: Long,
    val lookupKey: String,
    val displayName: String,
    val phoneNumbers: List<PhoneNumber>
)

data class PhoneNumber(
    val id: Long,
    val label: String,
    val normalizedNumber: String
)

sealed class CallState {
    object Idle : CallState()
    
    data class AwaitingContactSelection(
        val matches: List<ContactMatch>, 
        val createdAt: Long
    ) : CallState()
    
    data class AwaitingConfirmation(
        val contactId: Long,
        val lookupKey: String,
        val phoneId: Long,
        val displayName: String,
        val createdAt: Long,
        val expiresAt: Long
    ) : CallState()
    
    object Calling : CallState()
    object Cancelled : CallState()
}

object PendingCallManager {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    private var timeoutJob: kotlinx.coroutines.Job? = null

    var state: CallState = CallState.Idle
        private set

    @Synchronized
    fun setAwaitingContactSelection(matches: List<ContactMatch>) {
        state = CallState.AwaitingContactSelection(matches, System.currentTimeMillis())
        startTimeout(2 * 60 * 1000)
    }

    @Synchronized
    fun setAwaitingConfirmation(
        contactId: Long, 
        lookupKey: String, 
        phoneId: Long, 
        displayName: String
    ) {
        val now = System.currentTimeMillis()
        val duration = 2 * 60 * 1000L
        state = CallState.AwaitingConfirmation(
            contactId = contactId,
            lookupKey = lookupKey,
            phoneId = phoneId,
            displayName = displayName,
            createdAt = now,
            expiresAt = now + duration
        )
        startTimeout(duration)
    }

    @Synchronized
    fun setCalling() {
        timeoutJob?.cancel()
        state = CallState.Calling
    }

    @Synchronized
    fun cancel() {
        timeoutJob?.cancel()
        state = CallState.Cancelled
    }

    @Synchronized
    fun clear() {
        timeoutJob?.cancel()
        state = CallState.Idle
    }

    @Synchronized
    fun isCallPermitted(contactId: Long, phoneId: Long): Boolean {
        val currentState = state
        
        if (currentState is CallState.AwaitingConfirmation) {
            if (System.currentTimeMillis() > currentState.expiresAt) {
                clear()
                return false
            }
            return currentState.contactId == contactId && currentState.phoneId == phoneId
        }
        
        if (currentState is CallState.AwaitingContactSelection) {
            val match = currentState.matches.firstOrNull { it.contactId == contactId }
            val phone = match?.phoneNumbers?.firstOrNull { it.id == phoneId }
            if (match != null && phone != null) {
                setAwaitingConfirmation(contactId, match.lookupKey, phoneId, match.displayName)
                return true
            }
        }
        
        return false
    }

    @Synchronized
    fun getLookupKeyForCall(contactId: Long): String? {
        val currentState = state
        if (currentState is CallState.AwaitingConfirmation && currentState.contactId == contactId) {
            return currentState.lookupKey
        }
        return null
    }

    private fun startTimeout(delayMs: Long) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            clear()
        }
    }
}
