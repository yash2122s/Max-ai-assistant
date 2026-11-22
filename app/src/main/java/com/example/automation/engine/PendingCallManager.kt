package com.example.automation.engine

import org.json.JSONObject
import kotlinx.coroutines.*
import android.telephony.PhoneNumberUtils

enum class CallTargetKind {
    DIRECT_NUMBER,
    CONTACT
}

data class PreparedCallTarget(
    val targetKind: CallTargetKind,
    val normalizedNumber: String,
    val displayName: String,
    val contactId: Long? = null,
    val lookupKey: String? = null,
    val phoneId: Long? = null,
    val numberLabel: String? = null,
    val originalUserIntent: String? = null,
    val taskId: String? = null,
    val stepId: String? = null,
    val executionId: String? = null,
    val requestId: String? = null,
    val source: String? = null
)

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
        val createdAt: Long,
        val originalUserIntent: String? = null,
        val taskId: String? = null,
        val stepId: String? = null,
        val executionId: String? = null
    ) : CallState()
    
    data class AwaitingConfirmation(
        val contactId: Long,
        val lookupKey: String,
        val phoneId: Long,
        val displayName: String,
        val phoneNumber: String = "",
        val createdAt: Long,
        val expiresAt: Long,
        val originalUserIntent: String? = null,
        val preparedTarget: PreparedCallTarget? = null
    ) : CallState()
    
    data class Confirmed(
        val contactId: Long,
        val lookupKey: String,
        val phoneId: Long,
        val displayName: String,
        val phoneNumber: String = "",
        val confirmedAt: Long,
        val expiresAt: Long,
        val originalUserIntent: String? = null,
        val preparedTarget: PreparedCallTarget? = null,
        val createdAt: Long = confirmedAt
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
    fun setAwaitingContactSelection(
        matches: List<ContactMatch>,
        originalUserIntent: String? = null,
        taskId: String? = null,
        stepId: String? = null,
        executionId: String? = null
    ) {
        state = CallState.AwaitingContactSelection(
            matches = matches,
            createdAt = System.currentTimeMillis(),
            originalUserIntent = originalUserIntent,
            taskId = taskId,
            stepId = stepId,
            executionId = executionId
        )
        startTimeout(2 * 60 * 1000)
    }

    @Synchronized
    fun setAwaitingConfirmation(
        contactId: Long, 
        lookupKey: String, 
        phoneId: Long, 
        displayName: String,
        phoneNumber: String = "",
        originalUserIntent: String? = null,
        preparedTarget: PreparedCallTarget? = null
    ) {
        val now = System.currentTimeMillis()
        val duration = 2 * 60 * 1000L
        state = CallState.AwaitingConfirmation(
            contactId = contactId,
            lookupKey = lookupKey,
            phoneId = phoneId,
            displayName = displayName,
            phoneNumber = phoneNumber,
            createdAt = now,
            expiresAt = now + duration,
            originalUserIntent = originalUserIntent,
            preparedTarget = preparedTarget
        )
        startTimeout(duration)
    }

    @Synchronized
    fun hasPendingConfirmation(): Boolean {
        val currentState = state
        if (currentState is CallState.AwaitingConfirmation) {
            if (System.currentTimeMillis() > currentState.expiresAt) {
                clear()
                return false
            }
            return true
        }
        return false
    }

    @Synchronized
    fun getPendingConfirmation(): CallState.AwaitingConfirmation? {
        val currentState = state
        if (currentState is CallState.AwaitingConfirmation) {
            if (System.currentTimeMillis() > currentState.expiresAt) {
                clear()
                return null
            }
            return currentState
        }
        return null
    }

    @Synchronized
    fun getOriginalIntent(): String? {
        val currentState = state
        return when (currentState) {
            is CallState.AwaitingConfirmation -> currentState.originalUserIntent ?: "Call ${currentState.displayName}"
            is CallState.Confirmed -> currentState.originalUserIntent ?: "Call ${currentState.displayName}"
            is CallState.AwaitingContactSelection -> currentState.originalUserIntent
            else -> null
        }
    }

    data class CandidateSelection(
        val contactMatch: ContactMatch,
        val phoneNumber: PhoneNumber,
        val index: Int
    )

    @Synchronized
    fun getSelectionCandidates(): List<CandidateSelection> {
        val currentState = state as? CallState.AwaitingContactSelection ?: return emptyList()
        val candidates = mutableListOf<CandidateSelection>()
        var idx = 1
        currentState.matches.forEach { match ->
            match.phoneNumbers.forEach { phone ->
                candidates.add(CandidateSelection(match, phone, idx++))
            }
        }
        return candidates
    }

    /**
     * Resolves user input (e.g. "1", "first", "work", "mobile", contact name, or digits)
     * against pending candidates, binds to the exact candidate and phone row, and transitions
     * state to AwaitingConfirmation.
     */
    @Synchronized
    fun resolveAndSelectCandidate(userInput: String): CandidateSelection? {
        val candidates = getSelectionCandidates()
        if (candidates.isEmpty()) return null

        val trimmed = userInput.trim()
        val lower = trimmed.lowercase()

        // 1. By index number ("1", "2", "3")
        val num = trimmed.toIntOrNull()
        if (num != null && num in 1..candidates.size) {
            val selected = candidates[num - 1]
            if (selectCandidate(selected.contactMatch.contactId, selected.phoneNumber.id)) {
                return selected
            }
        }

        // 2. By ordinal word ("first", "second", "third", "1st", "2nd", "3rd", "ఒకటి", "రెండు")
        val ordinalMap = mapOf(
            "first" to 1, "1st" to 1, "one" to 1, "ఒకటి" to 1, "మొదటి" to 1,
            "second" to 2, "2nd" to 2, "two" to 2, "రెండు" to 2,
            "third" to 3, "3rd" to 3, "three" to 3, "మూడు" to 3
        )
        val ordinalIndex = ordinalMap[lower]
        if (ordinalIndex != null && ordinalIndex in 1..candidates.size) {
            val selected = candidates[ordinalIndex - 1]
            if (selectCandidate(selected.contactMatch.contactId, selected.phoneNumber.id)) {
                return selected
            }
        }

        // 3. By phone label ("work", "mobile", "home", etc.)
        val labelMatches = candidates.filter { it.phoneNumber.label.trim().lowercase() == lower }
        if (labelMatches.size == 1) {
            val selected = labelMatches[0]
            if (selectCandidate(selected.contactMatch.contactId, selected.phoneNumber.id)) {
                return selected
            }
        }

        // 4. By exact contact name or phone number
        val exactMatches = candidates.filter {
            it.contactMatch.displayName.trim().lowercase() == lower ||
            it.phoneNumber.normalizedNumber.trim() == trimmed ||
            it.contactMatch.displayName.trim().lowercase().contains(lower)
        }
        if (exactMatches.size == 1) {
            val selected = exactMatches[0]
            if (selectCandidate(selected.contactMatch.contactId, selected.phoneNumber.id)) {
                return selected
            }
        }

        return null
    }

    /**
     * Disambiguation / candidate selection: transitions from AwaitingContactSelection to AwaitingConfirmation
     * once a specific contact candidate and phone row are chosen.
     */
    @Synchronized
    fun selectCandidate(contactId: Long, phoneId: Long): Boolean {
        val currentState = state
        if (currentState is CallState.AwaitingContactSelection) {
            val match = currentState.matches.firstOrNull { it.contactId == contactId } ?: return false
            val phone = match.phoneNumbers.firstOrNull { it.id == phoneId } ?: return false
            val now = System.currentTimeMillis()
            val duration = 2 * 60 * 1000L
            val target = PreparedCallTarget(
                targetKind = CallTargetKind.CONTACT,
                normalizedNumber = phone.normalizedNumber,
                displayName = match.displayName,
                contactId = match.contactId,
                lookupKey = match.lookupKey,
                phoneId = phone.id,
                numberLabel = phone.label,
                originalUserIntent = currentState.originalUserIntent,
                taskId = currentState.taskId,
                stepId = currentState.stepId,
                executionId = currentState.executionId
            )
            state = CallState.AwaitingConfirmation(
                contactId = match.contactId,
                lookupKey = match.lookupKey,
                phoneId = phone.id,
                displayName = match.displayName,
                phoneNumber = phone.normalizedNumber,
                createdAt = now,
                expiresAt = now + duration,
                originalUserIntent = currentState.originalUserIntent,
                preparedTarget = target
            )
            startTimeout(duration)
            return true
        }
        return false
    }

    /**
     * Explicit confirmation transition: transitions from AwaitingConfirmation or AwaitingContactSelection
     * to Confirmed state.
     */
    @Synchronized
    fun confirmPendingCall(contactId: Long? = null, phoneId: Long? = null): Boolean {
        val currentState = state
        val now = System.currentTimeMillis()
        val duration = 15_000L // Reduced from 2 min to 15 s

        if (currentState is CallState.AwaitingConfirmation) {
            if (now > currentState.expiresAt) {
                clear()
                return false
            }
            if (contactId != null && currentState.contactId != contactId) {
                return false
            }
            if (phoneId != null && currentState.phoneId != phoneId) {
                return false
            }
            state = CallState.Confirmed(
                contactId = currentState.contactId,
                lookupKey = currentState.lookupKey,
                phoneId = currentState.phoneId,
                displayName = currentState.displayName,
                phoneNumber = currentState.phoneNumber,
                confirmedAt = now,
                expiresAt = now + duration,
                originalUserIntent = currentState.originalUserIntent,
                preparedTarget = currentState.preparedTarget,
                createdAt = currentState.createdAt
            )
            startTimeout(duration)
            return true
        }

        if (currentState is CallState.AwaitingContactSelection) {
            val targetMatch: ContactMatch?
            val targetPhone: PhoneNumber?

            if (contactId != null && phoneId != null) {
                targetMatch = currentState.matches.firstOrNull { it.contactId == contactId }
                targetPhone = targetMatch?.phoneNumbers?.firstOrNull { it.id == phoneId }
            } else if (currentState.matches.size == 1 && currentState.matches[0].phoneNumbers.size == 1) {
                targetMatch = currentState.matches[0]
                targetPhone = targetMatch.phoneNumbers[0]
            } else {
                return false
            }

            if (targetMatch != null && targetPhone != null) {
                val prepared = PreparedCallTarget(
                    targetKind = if (targetMatch.lookupKey.startsWith("raw_number:")) CallTargetKind.DIRECT_NUMBER else CallTargetKind.CONTACT,
                    normalizedNumber = targetPhone.normalizedNumber,
                    displayName = targetMatch.displayName,
                    contactId = targetMatch.contactId,
                    lookupKey = targetMatch.lookupKey,
                    phoneId = targetPhone.id,
                    numberLabel = targetPhone.label,
                    originalUserIntent = currentState.originalUserIntent,
                    taskId = currentState.taskId,
                    stepId = currentState.stepId,
                    executionId = currentState.executionId
                )
                state = CallState.Confirmed(
                    contactId = targetMatch.contactId,
                    lookupKey = targetMatch.lookupKey,
                    phoneId = targetPhone.id,
                    displayName = targetMatch.displayName,
                    phoneNumber = targetPhone.normalizedNumber,
                    confirmedAt = now,
                    expiresAt = now + duration,
                    originalUserIntent = currentState.originalUserIntent,
                    preparedTarget = prepared,
                    createdAt = currentState.createdAt
                )
                startTimeout(duration)
                return true
            }
        }

        return false
    }

    @Synchronized
    fun hasPendingCall(): Boolean {
        val currentState = state
        val now = System.currentTimeMillis()
        return when (currentState) {
            is CallState.AwaitingContactSelection -> {
                if (now - currentState.createdAt > 2 * 60 * 1000L) {
                    clear()
                    false
                } else {
                    true
                }
            }
            is CallState.AwaitingConfirmation -> {
                if (now > currentState.expiresAt) {
                    clear()
                    false
                } else {
                    true
                }
            }
            is CallState.Confirmed -> {
                if (now > currentState.expiresAt) {
                    clear()
                    false
                } else {
                    true
                }
            }
            else -> false
        }
    }

    @Synchronized
    fun pendingSinceMs(): Long {
        val currentState = state
        if (!hasPendingCall()) return 0L
        return when (currentState) {
            is CallState.AwaitingContactSelection -> currentState.createdAt
            is CallState.AwaitingConfirmation -> currentState.createdAt
            is CallState.Confirmed -> currentState.createdAt
            else -> 0L
        }
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

    /**
     * Strictly verifies whether the call is permitted.
     * ONLY returns true if the call state is [CallState.Confirmed] and not expired.
     */
    @Synchronized
    fun isCallPermitted(contactId: Long, phoneId: Long, targetPhoneNumber: String? = null): Boolean {
        val currentState = state
        
        if (currentState is CallState.Confirmed) {
            if (System.currentTimeMillis() > currentState.expiresAt) {
                clear()
                return false
            }
            val idMatches = currentState.contactId == contactId && currentState.phoneId == phoneId
            if (!idMatches) return false

            if (!targetPhoneNumber.isNullOrBlank() && currentState.phoneNumber.isNotBlank()) {
                val normTarget = PhoneNumberUtils.normalizeNumber(targetPhoneNumber) ?: targetPhoneNumber
                val normState = PhoneNumberUtils.normalizeNumber(currentState.phoneNumber) ?: currentState.phoneNumber
                return normTarget == normState
            }
            return true
        }
        
        return false
    }

    @Synchronized
    fun getLookupKeyForCall(contactId: Long): String? {
        val currentState = state
        if (currentState is CallState.Confirmed && currentState.contactId == contactId) {
            return currentState.lookupKey
        }
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
