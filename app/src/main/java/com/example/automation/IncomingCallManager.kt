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
            if (!number.isNullOrBlank() && currentPhoneNumber.isNullOrBlank()) {
                Log.d(TAG, "Updating incoming number for active ringing call: $number")
                currentPhoneNumber = number
                scope.launch {
                    val identity = resolveIdentity(context, number)
                    currentCallerIdentity = identity
                }
            }
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

    private val DIALER_IGNORED_LABELS = setOf(
        "incoming call", "calling", "answer", "decline", "reject", "dismiss",
        "swipe up to answer", "swipe down to decline", "phone", "call", "voice call",
        "hd", "sim 1", "sim 2", "sim1", "sim2", "jio", "airtel", "vi", "bsnl", "vodafone",
        "mobile", "home", "work", "main", "other", "custom", "cellular",
        "emergency", "private number", "unknown", "unknown caller", "unknown number",
        "speaker", "mute", "keypad", "bluetooth", "hold", "add call", "video call"
    )

    /**
     * Resolves the incoming caller identity with strict confidence gating:
     * 1. Telephony broadcast number (exact normalized match in ContactsContract).
     * 2. Accessibility screen scraping (rejecting system/dialer/carrier labels).
     * 3. Fresh CallLog entry only if timestamp is within last 15 seconds AND type is INCOMING/MISSED.
     * 4. Explicit fallback to "unknown number".
     */
    suspend fun resolveIdentity(context: Context, number: String?): String {
        val cleanRawNumber = number?.trim()
        val normalizedRaw = cleanRawNumber?.let { normalizePhoneDigits(it) }

        // Source 1: Telephony / Broadcast incoming number
        if (!cleanRawNumber.isNullOrBlank() && !isGenericUnknownLabel(cleanRawNumber)) {
            val contactName = getContactName(context, cleanRawNumber)
            if (!contactName.isNullOrBlank()) {
                logCallIdentity(
                    source = "TELEPHONY_BROADCAST",
                    rawNumber = cleanRawNumber,
                    normalizedNumber = normalizedRaw,
                    candidate = cleanRawNumber,
                    matchedContact = contactName,
                    confidence = "HIGH",
                    fallbackUsed = "NONE"
                )
                return contactName
            }

            // Incoming number -> correlated call-log record -> contact lookup
            val correlatedNumber = getCorrelatedCallLogEntry(context, cleanRawNumber, maxAgeSeconds = 15L)
            if (!correlatedNumber.isNullOrBlank()) {
                val correlatedContact = getContactName(context, correlatedNumber)
                if (!correlatedContact.isNullOrBlank()) {
                    logCallIdentity(
                        source = "CORRELATED_CALL_LOG",
                        rawNumber = cleanRawNumber,
                        normalizedNumber = normalizedRaw,
                        candidate = correlatedNumber,
                        matchedContact = correlatedContact,
                        confidence = "HIGH",
                        fallbackUsed = "CALL_LOG_CORRELATED_CONTACT"
                    )
                    return correlatedContact
                }
            }

            logCallIdentity(
                source = "TELEPHONY_BROADCAST",
                rawNumber = cleanRawNumber,
                normalizedNumber = normalizedRaw,
                candidate = cleanRawNumber,
                matchedContact = null,
                confidence = "LOW",
                fallbackUsed = "FALL_THROUGH_TO_SCRAPE"
            )
        }

        // Source 2: Accessibility Screen Scrape (Fallback when EXTRA_INCOMING_NUMBER is null or unsaved in contacts)
        val scrapedCandidate = scrapeCallerInfoWithRetry(context)
        if (!scrapedCandidate.isNullOrBlank() && !isGenericUnknownLabel(scrapedCandidate)) {
            val contactMatch = getContactName(context, scrapedCandidate)
            if (!contactMatch.isNullOrBlank()) {
                logCallIdentity(
                    source = "ACCESSIBILITY_SCRAPE",
                    rawNumber = cleanRawNumber,
                    normalizedNumber = normalizedRaw,
                    candidate = scrapedCandidate,
                    matchedContact = contactMatch,
                    confidence = "HIGH",
                    fallbackUsed = "ACCESSIBILITY"
                )
                return contactMatch
            }

            // Scraped text might already be a saved contact name displayed on the dialer
            if (!scrapedCandidate.matches(Regex("^[+]?[0-9\\s\\-()]{7,20}$"))) {
                logCallIdentity(
                    source = "ACCESSIBILITY_SCRAPE",
                    rawNumber = cleanRawNumber,
                    normalizedNumber = normalizedRaw,
                    candidate = scrapedCandidate,
                    matchedContact = scrapedCandidate,
                    confidence = "MEDIUM",
                    fallbackUsed = "ACCESSIBILITY_DIRECT_NAME"
                )
                return scrapedCandidate
            }
        }

        // When correlation is unavailable, return unknown rather than guessing an uncorrelated cached name
        logCallIdentity(
            source = "FALLBACK_DEFAULT",
            rawNumber = cleanRawNumber,
            normalizedNumber = normalizedRaw,
            candidate = null,
            matchedContact = null,
            confidence = "NONE",
            fallbackUsed = "UNKNOWN_NUMBER"
        )
        return "unknown number"
    }

    private fun logCallIdentity(
        source: String,
        rawNumber: String?,
        normalizedNumber: String?,
        candidate: String?,
        matchedContact: String?,
        confidence: String,
        fallbackUsed: String
    ) {
        Log.i(
            TAG,
            "CALL_IDENTITY:\n" +
                    "source=$source\n" +
                    "rawNumber=${rawNumber ?: "null"}\n" +
                    "normalizedNumber=${normalizedNumber ?: "null"}\n" +
                    "candidate=${candidate ?: "null"}\n" +
                    "matchedContact=${matchedContact ?: "null"}\n" +
                    "confidence=$confidence\n" +
                    "fallbackUsed=$fallbackUsed"
        )
    }

    fun normalizePhoneDigits(input: String): String {
        return input.replace(Regex("[^0-9]"), "")
    }

    fun isGenericUnknownLabel(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        return lower.isEmpty() ||
                lower == "unknown" ||
                lower == "unknown caller" ||
                lower == "unknown number" ||
                lower == "private" ||
                lower == "private number" ||
                lower == "null" ||
                lower == "restricted" ||
                lower == "withheld"
    }

    fun isIgnoredDialerLabel(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return true
        if (DIALER_IGNORED_LABELS.contains(lower)) return true

        // Check if starts with carrier / SIM label patterns
        if (lower.startsWith("sim ") || lower.startsWith("sim1") || lower.startsWith("sim2")) return true
        if (lower.startsWith("calling via") || lower.startsWith("incoming via")) return true
        if (lower.startsWith("hd ") || lower.endsWith(" call")) return true

        return false
    }

    @Volatile
    var testContactLookupHook: ((String) -> String?)? = null

    /**
     * Resolves contact name strictly with exact number verification.
     * Uses PhoneLookup first, then verifies CommonDataKinds.Phone with normalized digit equality.
     */
    fun getContactName(context: Context, phoneNumber: String): String? {
        val cleanNumber = phoneNumber.trim()
        if (cleanNumber.isEmpty() || isGenericUnknownLabel(cleanNumber)) return null

        testContactLookupHook?.let { hook ->
            return hook.invoke(cleanNumber)
        }

        val targetDigits = normalizePhoneDigits(cleanNumber)
        if (targetDigits.length < 5) return null

        // 1. Primary: ContactsContract.PhoneLookup
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(cleanNumber))
            val projection = arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val name = cursor.getString(nameIdx)
                        val numIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.NUMBER)
                        if (numIdx >= 0) {
                            val candNumber = cursor.getString(numIdx) ?: ""
                            val candDigits = normalizePhoneDigits(candNumber)
                            if (candDigits.isEmpty() || isNumberMatch(targetDigits, candDigits)) {
                                if (!name.isNullOrBlank()) return name
                            }
                        } else {
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying PhoneLookup for '$cleanNumber': ${e.message}")
        }

        // 2. Secondary: ContactsContract.CommonDataKinds.Phone
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val candNumber = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                    val candDigits = normalizePhoneDigits(candNumber)

                    if (isNumberMatch(targetDigits, candDigits)) {
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying CommonDataKinds.Phone: ${e.message}")
        }

        // 3. Tertiary: ContactsContract.Data table directly (ensures compatibility with all mock providers)
        try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.Data.MIMETYPE
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val candNumber = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                    val candDigits = normalizePhoneDigits(candNumber)

                    if (isNumberMatch(targetDigits, candDigits)) {
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying Data table: ${e.message}")
        }

        return null
    }

    /**
     * Compares two normalized digit strings for match.
     * Requires minimum 7-10 trailing digits exact match or full equality.
     */
    fun isNumberMatch(digitsA: String, digitsB: String): Boolean {
        if (digitsA.isEmpty() || digitsB.isEmpty()) return false
        if (digitsA == digitsB) return true

        val minLen = minOf(digitsA.length, digitsB.length)
        if (minLen >= 10) {
            return digitsA.takeLast(10) == digitsB.takeLast(10)
        }
        if (minLen >= 7) {
            return digitsA.takeLast(7) == digitsB.takeLast(7)
        }
        return false
    }

    private suspend fun scrapeCallerInfoWithRetry(context: Context): String? {
        val service = JarvisAccessibilityService.instance ?: return null

        // Retry window: 4 attempts x 350ms = 1.4s max
        for (attempt in 1..4) {
            val nodes = service.dumpScreenFlat()

            for (node in nodes) {
                val text = ((node["text"] as? String) ?: "").trim()
                val contentDesc = ((node["contentDescription"] as? String) ?: "").trim()
                val viewId = ((node["viewId"] as? String) ?: "").lowercase(Locale.ROOT)

                val candidate = when {
                    text.isNotEmpty() -> text
                    contentDesc.isNotEmpty() -> contentDesc
                    else -> ""
                }

                if (candidate.isEmpty()) continue
                if (isIgnoredDialerLabel(candidate)) continue

                // Check viewId keywords for caller identity
                if (viewId.contains("name") || viewId.contains("caller") ||
                    viewId.contains("contact") || viewId.contains("primary_info")
                ) {
                    Log.d(TAG, "[ACCESSIBILITY_CALLER_FOUND] Found node match ($viewId): '$candidate'")
                    return candidate
                }

                // Check if candidate text is formatted as phone number
                if (candidate.matches(Regex("^[+]?[0-9\\s\\-()]{7,20}$"))) {
                    return candidate
                }
            }

            delay(350)
        }
        return null
    }

    /**
     * Reads CallLog ONLY if a ringing or missed call matching [incomingNumber] was recorded within [maxAgeSeconds].
     * Never guesses from an uncorrelated record.
     */
    fun getCorrelatedCallLogEntry(context: Context, incomingNumber: String?, maxAgeSeconds: Long = 15L): String? {
        if (incomingNumber.isNullOrBlank() || isGenericUnknownLabel(incomingNumber)) return null
        val targetDigits = normalizePhoneDigits(incomingNumber)
        if (targetDigits.isEmpty()) return null

        try {
            val minTimestamp = System.currentTimeMillis() - (maxAgeSeconds * 1000L)
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ? AND (${CallLog.Calls.TYPE} = ? OR ${CallLog.Calls.TYPE} = ?)",
                arrayOf(
                    minTimestamp.toString(),
                    CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.MISSED_TYPE.toString()
                ),
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                    if (date >= minTimestamp) {
                        val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: ""
                        val candDigits = normalizePhoneDigits(number)
                        if (isNumberMatch(targetDigits, candDigits)) {
                            return number
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Correlated CallLog query skipped: ${e.message}")
        }
        return null
    }
}
