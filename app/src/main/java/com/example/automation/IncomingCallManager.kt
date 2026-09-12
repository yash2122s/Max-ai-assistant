package com.example.automation

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import com.example.service.JarvisAccessibilityService
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class IncomingCallFlowState {
    IDLE,
    INCOMING_CALL_RINGING,
    INCOMING_CALL_ANNOUNCING,
    INCOMING_CALL_LISTENING,
    INCOMING_CALL_ANSWERED,
    INCOMING_CALL_REJECTED,
    INCOMING_CALL_TIMEOUT,
    INCOMING_CALL_CLEANUP,
    INCOMING_CALL_COMPLETED
}

enum class CallDecision {
    ACCEPT,
    REJECT,
    TIMEOUT
}

object IncomingCallManager {
    private const val TAG = "IncomingCallManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var currentCallState: IncomingCallFlowState = IncomingCallFlowState.IDLE
        private set

    @Volatile
    var currentCallerIdentity: String? = null
        private set

    @Volatile
    var currentPhoneNumber: String? = null
        private set

    @Volatile
    var wasSessionOwnedByCall: Boolean = false
        private set

    private val isHandlingCall = AtomicBoolean(false)
    private val isProcessingDecision = AtomicBoolean(false)
    private var decisionTimeoutJob: Job? = null

    fun transitionTo(newState: IncomingCallFlowState) {
        Log.d(TAG, "State transition: $currentCallState -> $newState")
        currentCallState = newState
    }

    fun isListeningForCallDecision(): Boolean {
        return currentCallState == IncomingCallFlowState.INCOMING_CALL_LISTENING
    }

    fun parseCallDecision(input: String): CallDecision? {
        val text = input.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return null

        // 1. Check REJECT commands first (to prevent "don't answer" from matching "answer")
        val isReject = text == "no" ||
                text == "reject" ||
                text == "decline" ||
                text == "don't answer" ||
                text == "dont answer" ||
                text == "do not answer" ||
                text.contains("reject") ||
                text.contains("decline") ||
                text.contains("don't answer") ||
                text.contains("dont answer") ||
                text.contains("do not answer") ||
                text.startsWith("no ") ||
                text.endsWith(" no") ||
                text.contains(" no ")

        if (isReject) return CallDecision.REJECT

        // 2. Check ACCEPT commands
        val isAccept = text == "yes" ||
                text == "accept" ||
                text == "answer" ||
                text == "pick up" ||
                text == "pickup" ||
                text == "lift" ||
                text == "take call" ||
                text == "take the call" ||
                text.contains("accept") ||
                text.contains("answer") ||
                text.contains("pick up") ||
                text.contains("pickup") ||
                text.contains("lift") ||
                text.contains("take call") ||
                text.contains("take the call") ||
                text.startsWith("yes ") ||
                text.endsWith(" yes") ||
                text.contains(" yes ")

        if (isAccept) return CallDecision.ACCEPT

        return null
    }

    fun handleIncomingCall(context: Context, number: String?) {
        // Prevent duplicate announcements for the same active call
        if (currentCallState != IncomingCallFlowState.IDLE && 
            currentCallState != IncomingCallFlowState.INCOMING_CALL_COMPLETED) {
            Log.d(TAG, "Call already active (state=$currentCallState), ignoring duplicate broadcast")
            return
        }

        if (!isHandlingCall.compareAndSet(false, true)) {
            Log.d(TAG, "Call handling already in progress, skipping")
            return
        }

        isProcessingDecision.set(false)
        transitionTo(IncomingCallFlowState.INCOMING_CALL_RINGING)
        Log.d(TAG, "[CALL_DETECTED] Incoming call ringing detected")

        scope.launch {
            try {
                val identity = resolveIdentity(context, number)
                currentCallerIdentity = identity
                currentPhoneNumber = number
                Log.d(TAG, "[CALLER_RESOLUTION_RESULT] Resolved caller identity: '$identity'")

                transitionTo(IncomingCallFlowState.INCOMING_CALL_ANNOUNCING)
                Log.d(TAG, "[ANNOUNCEMENT_STARTED] Triggering voice announcement for: $identity")

                val engine = AssistantLifecycleManager.getOrCreateEngine(context)
                engine.announceIncomingCall(identity)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming call: ${e.message}", e)
                terminateCallAssistantSession(context)
            }
        }
    }

    fun onListeningStarted(context: Context, wasConnectedBeforeCall: Boolean) {
        wasSessionOwnedByCall = !wasConnectedBeforeCall
        transitionTo(IncomingCallFlowState.INCOMING_CALL_LISTENING)
        Log.d(TAG, "[DECISION_WINDOW_STARTED] 10s decision window started (sessionOwnedByCall=$wasSessionOwnedByCall)")

        decisionTimeoutJob?.cancel()
        decisionTimeoutJob = scope.launch {
            delay(10_000L)
            if (currentCallState == IncomingCallFlowState.INCOMING_CALL_LISTENING) {
                Log.d(TAG, "[CALL_ASSISTANT_TIMEOUT] Strict 10-second decision window expired without decision")
                handleCallDecision(context, CallDecision.TIMEOUT)
            }
        }
    }

    fun handleCallDecision(context: Context, decision: CallDecision): Job? {
        if (!isProcessingDecision.compareAndSet(false, true)) {
            Log.d(TAG, "Decision already processing ($decision ignored)")
            return null
        }

        decisionTimeoutJob?.cancel()
        decisionTimeoutJob = null

        return scope.launch {
            executeCallDecision(context, decision)
        }
    }

    suspend fun executeCallDecision(context: Context, decision: CallDecision) = withContext(Dispatchers.IO) {
        try {
            when (decision) {
                CallDecision.ACCEPT -> {
                    Log.d(TAG, "[CALL_DECISION_ACCEPT] Executing call answer")
                    transitionTo(IncomingCallFlowState.INCOMING_CALL_ANSWERED)
                    val controller = CallController(context)
                    val success = controller.answerCall()
                    Log.d(TAG, "[CALL_DECISION_ACCEPT] Answer execution result: $success")
                }
                CallDecision.REJECT -> {
                    Log.d(TAG, "[CALL_DECISION_REJECT] Executing call reject")
                    transitionTo(IncomingCallFlowState.INCOMING_CALL_REJECTED)
                    val controller = CallController(context)
                    val success = controller.rejectCall()
                    Log.d(TAG, "[CALL_DECISION_REJECT] Reject execution result: $success")
                }
                CallDecision.TIMEOUT -> {
                    Log.d(TAG, "[CALL_DECISION_TIMEOUT] Terminating decision listening session without answering or rejecting")
                    transitionTo(IncomingCallFlowState.INCOMING_CALL_TIMEOUT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing call decision $decision: ${e.message}", e)
        } finally {
            terminateCallAssistantSession(context)
        }
    }

    fun terminateCallAssistantSession(context: Context) {
        Log.d(TAG, "[CLEANUP_STARTED] Terminating call assistant session and releasing audio/voice resources")
        transitionTo(IncomingCallFlowState.INCOMING_CALL_CLEANUP)

        decisionTimeoutJob?.cancel()
        decisionTimeoutJob = null

        val engine = AssistantLifecycleManager.getEngine()
        if (engine != null) {
            try {
                // 1. Stop microphone / AudioRecord
                engine.stopListening()
                Log.d(TAG, "[MICROPHONE_RELEASED] AudioRecord stopped and microphone released")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio listening: ${e.message}", e)
            }

            try {
                // 2. Stop audio playback
                engine.streamingResponseManager.stopAllPlayback()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio playback: ${e.message}", e)
            }

            try {
                // 3. Disconnect Gemini Live if owned by call
                if (wasSessionOwnedByCall) {
                    engine.connectionManager.disconnect()
                    Log.d(TAG, "[GEMINI_DISCONNECTED] Call-owned Gemini session disconnected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting Gemini: ${e.message}", e)
            }

            try {
                // 4. Reset state machine to IDLE
                engine.stateMachine.transitionTo(SessionState.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting state machine: ${e.message}", e)
            }
        }

        currentCallerIdentity = null
        currentPhoneNumber = null
        wasSessionOwnedByCall = false
        isHandlingCall.set(false)
        isProcessingDecision.set(false)
        transitionTo(IncomingCallFlowState.IDLE)
        Log.d(TAG, "[STATE_IDLE] MAX returned to IDLE state. Call assistant session completely terminated.")
    }

    fun handleCallOffhook(context: Context) {
        Log.d(TAG, "Call answered externally or went offhook")
        if (currentCallState != IncomingCallFlowState.IDLE) {
            terminateCallAssistantSession(context)
        }
    }

    fun handleCallEnded(context: Context) {
        Log.d(TAG, "[CALL_FLOW_RESET] Call ended (IDLE state received). Resetting incoming call state.")
        terminateCallAssistantSession(context)
    }

    private suspend fun resolveIdentity(context: Context, number: String?): String {
        // Priority 1: If phone number is available, query ContactsContract
        if (!number.isNullOrBlank()) {
            val contactName = getContactName(context, number)
            if (!contactName.isNullOrBlank()) {
                Log.d(TAG, "[CONTACT_MATCH] Found contact name '$contactName' for number '$number'")
                return contactName
            }
            // NEVER return raw phone number; return unknown number
            Log.d(TAG, "[CALLER_NUMBER] Number available but no contact matched -> unknown number")
            return "unknown number"
        }

        // Priority 2: Accessibility Scrape with retries
        val scraped = scrapeCallerInfoWithRetry(context)
        if (!scraped.isNullOrBlank()) {
            val contactName = getContactName(context, scraped)
            if (!contactName.isNullOrBlank()) {
                Log.d(TAG, "[ACCESSIBILITY_CONTACT_FOUND] Found contact name '$contactName'")
                return contactName
            }
            // If scraped text was a contact name (not a phone number)
            if (!scraped.matches(Regex("^[+]?[0-9\\s\\-()]{7,20}$"))) {
                return scraped
            }
            return "unknown number"
        }

        // Priority 3: Query latest ringing entry from CallLog
        val callLogEntry = getLatestCallLogEntry(context)
        if (!callLogEntry.isNullOrBlank()) {
            val name = getContactName(context, callLogEntry)
            if (!name.isNullOrBlank()) return name
            if (!callLogEntry.matches(Regex("^[+]?[0-9\\s\\-()]{7,20}$"))) return callLogEntry
            return "unknown number"
        }

        // Priority 4: Fallback to unknown number
        Log.d(TAG, "No contact, number, or accessibility candidate found. Falling back to unknown number")
        return "unknown number"
    }

    private fun getContactName(context: Context, phoneNumber: String): String? {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isEmpty()) return null

        // 1. Query ContactsContract.PhoneLookup
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(cleanNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying PhoneLookup for '$cleanNumber': ${e.message}")
        }

        // 2. Query ContactsContract.CommonDataKinds.Phone with normalized digits matching
        try {
            val digitsOnly = cleanNumber.replace(Regex("[^0-9]"), "")
            val matchPattern = if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly
            if (matchPattern.isNotEmpty()) {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                    arrayOf("%$matchPattern%"),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying CommonDataKinds.Phone: ${e.message}")
        }

        return null
    }

    private suspend fun scrapeCallerInfoWithRetry(context: Context): String? {
        val service = JarvisAccessibilityService.instance ?: return null

        // Retry window: 6 attempts x 500ms = 3.0s max
        for (attempt in 1..6) {
            val nodes = service.dumpScreenFlat()
            
            for (node in nodes) {
                val text = ((node["text"] as? String) ?: "").trim()
                val contentDesc = ((node["contentDescription"] as? String) ?: "").trim()
                val viewId = ((node["viewId"] as? String) ?: "").lowercase()

                val candidate = when {
                    text.isNotEmpty() -> text
                    contentDesc.isNotEmpty() -> contentDesc
                    else -> ""
                }

                if (candidate.isEmpty()) continue

                // Ignore common dialer UI action buttons and static labels
                val isStaticLabel = candidate.equals("Incoming call", ignoreCase = true) ||
                        candidate.equals("Calling", ignoreCase = true) ||
                        candidate.equals("Answer", ignoreCase = true) ||
                        candidate.equals("Decline", ignoreCase = true) ||
                        candidate.equals("Reject", ignoreCase = true) ||
                        candidate.equals("Dismiss", ignoreCase = true) ||
                        candidate.equals("Swipe up to answer", ignoreCase = true) ||
                        candidate.equals("Swipe down to decline", ignoreCase = true) ||
                        candidate.equals("Phone", ignoreCase = true)

                if (isStaticLabel) continue

                // Check viewId keywords
                if (viewId.contains("name") || viewId.contains("caller") || 
                    viewId.contains("contact") || viewId.contains("title") ||
                    viewId.contains("primary_info")) {
                    Log.d(TAG, "[ACCESSIBILITY_CALLER_FOUND] Found node match ($viewId): '$candidate'")
                    // If the text looks like a phone number, resolve against contacts
                    val contactMatch = getContactName(context, candidate)
                    return contactMatch ?: candidate
                }

                // Check if candidate text is formatted as phone number
                if (candidate.matches(Regex("^[+]?[0-9\\s\\-()]{7,20}$"))) {
                    val contactMatch = getContactName(context, candidate)
                    return contactMatch ?: candidate
                }
            }

            delay(500)
        }
        return null
    }

    private fun getLatestCallLogEntry(context: Context): String? {
        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val cachedName = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME))
                    val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                    if (!cachedName.isNullOrBlank()) return cachedName
                    if (!number.isNullOrBlank()) return number
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "CallLog query skipped: ${e.message}")
        }
        return null
    }
}
