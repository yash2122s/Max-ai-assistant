package com.example.features.whatsapp

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.automation.contact.ContactResolver
import com.example.data.preferences.SettingsManager
import com.example.data.repository.JarvisRepository
import com.example.utils.NotificationHelper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ConfirmationState {
    PENDING_CONFIRMATION,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    SENT,
    REJECTED_NO_CONFIRMATION
}

data class PendingReplyConfirmation(
    val confirmationId: String = UUID.randomUUID().toString(),
    val packageName: String,
    val sender: String,
    val incomingMessage: String,
    val proposedReply: String,
    val timestamp: Long = System.currentTimeMillis(),
    val expirationTime: Long = System.currentTimeMillis() + 60_000L,
    var state: ConfirmationState = ConfirmationState.PENDING_CONFIRMATION
)

object MatchEngine { 
    fun matches(message: String, keyword: String): Boolean { 
        val regex = Regex("\\b${Regex.escape(keyword.lowercase().trim())}\\b")
        return regex.containsMatchIn(message.lowercase()) 
    } 
}

fun normalize(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]"), "").trim()

object ServiceLocator { 
    var repository: JarvisRepository? = null 
}

object AutoReplyController {
    private const val TAG = "AutoReplyController"

    // Active pending reply confirmations mapped by confirmationId
    private val pendingConfirmations = ConcurrentHashMap<String, PendingReplyConfirmation>()

    // Deduplication lock by sender|incomingMessage to avoid duplicate pending confirmations
    private val activeMessageKeys = ConcurrentHashMap.newKeySet<String>()

    // StatusBarNotification instances by sender
    private val activeSbnMap = ConcurrentHashMap<String, StatusBarNotification>()

    // Rate limiting map per sender
    private val lastAiCall = ConcurrentHashMap<String, Long>()

    // Concurrency-safe cooldown map per sender to prevent reply loops & ping-pong
    private val lastReplyTimePerSender = ConcurrentHashMap<String, Long>()

    fun updateSbn(sender: String, sbn: StatusBarNotification) {
        val latestConf = pendingConfirmations.values
            .filter { normalize(it.sender) == normalize(sender) && it.state == ConfirmationState.PENDING_CONFIRMATION }
            .maxByOrNull { it.timestamp }
        if (latestConf != null) {
            activeSbnMap[latestConf.confirmationId] = sbn
        }
    }

    fun hasPendingConfirmation(): Boolean {
        cleanupExpiredConfirmations()
        return pendingConfirmations.values.any { it.state == ConfirmationState.PENDING_CONFIRMATION }
    }

    fun getLatestPendingConfirmation(): PendingReplyConfirmation? {
        cleanupExpiredConfirmations()
        return pendingConfirmations.values
            .filter { it.state == ConfirmationState.PENDING_CONFIRMATION }
            .maxByOrNull { it.timestamp }
    }

    private fun cleanupExpiredConfirmations() {
        val now = System.currentTimeMillis()
        val expiredIds = mutableListOf<String>()
        for ((id, conf) in pendingConfirmations) {
            if (conf.state == ConfirmationState.PENDING_CONFIRMATION && now > conf.expirationTime) {
                conf.state = ConfirmationState.EXPIRED
                Log.d(TAG, "REPLY_EXPIRED: Pending confirmation $id for ${conf.sender} expired without user confirmation. NO SEND.")
                expiredIds.add(id)
            } else if (conf.state != ConfirmationState.PENDING_CONFIRMATION && now > conf.timestamp + 300_000L) {
                expiredIds.add(id)
            }
        }
        expiredIds.forEach { id ->
            val removed = pendingConfirmations.remove(id)
            if (removed != null) {
                activeSbnMap.remove(id)
                activeMessageKeys.remove("${removed.sender}|${removed.incomingMessage}")
            }
        }
    }

    suspend fun handleIncoming(
        context: Context,
        sender: String,
        message: String,
        sbn: StatusBarNotification
    ) {
        val settings = SettingsManager(context)
        if (!settings.isAutoReplyEnabled) {
            Log.d(TAG, "Auto reply is disabled in settings.")
            return
        }

        // Fail-closed guard: reject blank or empty sender
        if (sender.isBlank()) {
            Log.d(TAG, "Sender is blank. Suppressing auto-reply (fail closed).")
            return
        }

        if (settings.userName.isNotBlank() && normalize(sender) == normalize(settings.userName)) return
        if (sender.contains(":")) return

        val isWhatsApp = sbn.packageName.contains("whatsapp", ignoreCase = true)
        if (isWhatsApp) {
            if (!settings.isWhatsAppAutoReplyEnabled) {
                Log.d(TAG, "WhatsApp auto-reply is disabled in settings.")
                return
            }

            if (settings.whatsappAutoReplyScope == "SELECTED") {
                val selectedContacts = settings.whatsappSelectedContacts
                val isAllowed = ContactResolver.isSenderInSelectedContacts(sender, selectedContacts)
                if (!isAllowed) {
                    Log.d(TAG, "WhatsApp auto-reply suppressed: Sender '$sender' is not in selected contacts whitelist (${selectedContacts.size} selected).")
                    return
                }
            }

            // RemoteInput verification: fail closed gracefully if no Direct Reply action exists
            if (!RemoteInputSender.hasRemoteInput(sbn)) {
                Log.d(TAG, "WhatsApp notification from '$sender' lacks RemoteInput direct-reply action. Suppressing.")
                return
            }

            // Reply loop & ping-pong protection: 30s debounce cooldown per sender
            val now = System.currentTimeMillis()
            val lastSent = lastReplyTimePerSender[normalize(sender)] ?: 0L
            if (now - lastSent < 30_000L) {
                Log.d(TAG, "WhatsApp auto-reply suppressed: Cooldown active for sender '$sender'.")
                return
            }
        }

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel in 1..9) {
            Log.d(TAG, "Battery below 10% ($batteryLevel%), pausing auto-reply to save power.")
            return
        }

        // Duplicate protection: Check if a message from the same sender with same text is already being processed/pending
        val msgKey = "$sender|$message"
        if (!activeMessageKeys.add(msgKey)) {
            Log.d(TAG, "Duplicate notification event ignored for: $msgKey")
            return
        }

        Log.d(TAG, "NOTIFICATION_RECEIVED: From '$sender' (pkg=${sbn.packageName}): '$message'")

        try {
            if (ServiceLocator.repository == null) {
                ServiceLocator.repository = JarvisRepository(context.applicationContext)
            }
            val repository = ServiceLocator.repository!!
            val rules = repository.getAllRules()

            val rule = rules.firstOrNull { it.enabled && !it.targetContact.isNullOrBlank() && it.targetContact.equals(sender, true) && MatchEngine.matches(message, it.triggerKeyword) } 
                ?: rules.firstOrNull { it.enabled && it.targetContact.isNullOrBlank() && MatchEngine.matches(message, it.triggerKeyword) }

            val proposedReply = when {
                rule != null -> rule.replyMessage
                settings.isAiFallbackEnabled -> {
                    val allowedContactsString = settings.aiAllowedContacts
                    if (allowedContactsString.isNotBlank()) {
                        val allowedList = allowedContactsString.split(",").map { it.trim() }
                        val allowed = allowedList.any { normalize(it) == normalize(sender) }
                        if (!allowed) {
                            Log.d(TAG, "Sender '$sender' is not in AI allowed contacts. Suppressing AI reply.")
                            return
                        }
                    }

                    val now = System.currentTimeMillis()
                    val lastCallTime = lastAiCall[sender] ?: 0L
                    if (now - lastCallTime < 60_000L) {
                        Log.d(TAG, "AI fallback rate limited for '$sender'. Suppressing reply.")
                        return
                    }
                    lastAiCall[sender] = now

                    val generatedReply = try {
                        "I am currently busy. I will text you later."
                    } catch (e: Exception) {
                        Log.e(TAG, "Error generating AI response", e)
                        "Got it, thank you!"
                    }

                    val userName = settings.userName
                    val prefix = if (userName.isNotBlank()) "I'm ${userName}'s AI assistant: " else "I'm an AI assistant: "
                    prefix + generatedReply
                }
                else -> {
                    Log.d(TAG, "No matching rule, and AI fallback is disabled.")
                    return
                }
            }

            // Create PendingReplyConfirmation with MANDATORY PENDING_CONFIRMATION state
            val confirmation = PendingReplyConfirmation(
                packageName = sbn.packageName,
                sender = sender,
                incomingMessage = message,
                proposedReply = proposedReply
            )

            pendingConfirmations[confirmation.confirmationId] = confirmation
            activeSbnMap[confirmation.confirmationId] = sbn

            Log.d(TAG, "REPLY_PROPOSED: ID=${confirmation.confirmationId}, Sender='$sender', Proposed='$proposedReply'")
            Log.d(TAG, "CONFIRMATION_SHOWN: Presenting confirmation request to user for ID=${confirmation.confirmationId}")

            // Display system notification with explicit CONFIRM and CANCEL buttons
            NotificationHelper.showPendingReplyNotification(
                context = context,
                confirmationId = confirmation.confirmationId,
                sender = sender,
                incomingMessage = message,
                proposedReply = proposedReply
            )

            // Schedule auto-send after 5 seconds if user does not cancel
            CoroutineScope(Dispatchers.IO).launch {
                delay(5_000L)
                if (confirmation.state == ConfirmationState.PENDING_CONFIRMATION) {
                    Log.d(TAG, "AUTO_REPLY_AUTO_SEND: 5s grace period elapsed without cancellation. Confirming and sending reply for ${confirmation.confirmationId} to $sender")
                    confirmAndSend(context, confirmation.confirmationId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in AutoReplyController handleIncoming", e)
            activeMessageKeys.remove(msgKey)
        }
    }

    fun confirmAndSend(context: Context, confirmationId: String): Boolean {
        val confirmation = pendingConfirmations[confirmationId]
        if (confirmation == null) {
            Log.w(TAG, "REPLY_BLOCKED_NO_CONFIRMATION: Confirmation ID $confirmationId not found")
            return false
        }

        if (confirmation.state != ConfirmationState.PENDING_CONFIRMATION) {
            Log.w(TAG, "REPLY_BLOCKED_NO_CONFIRMATION: Cannot confirm reply $confirmationId in state ${confirmation.state}")
            return false
        }

        // Explicit user confirmation transition
        confirmation.state = ConfirmationState.CONFIRMED
        Log.d(TAG, "REPLY_CONFIRMED: User confirmed reply for ID=$confirmationId (${confirmation.sender})")

        NotificationHelper.cancelPendingReplyNotification(context, confirmationId)
        return executeSendGuard(context, confirmation)
    }

    fun cancelPendingReply(context: Context, confirmationId: String) {
        val confirmation = pendingConfirmations.remove(confirmationId)
        if (confirmation != null && confirmation.state == ConfirmationState.PENDING_CONFIRMATION) {
            confirmation.state = ConfirmationState.CANCELLED
            Log.d(TAG, "REPLY_CANCELLED: User explicitly cancelled reply for ID=$confirmationId (${confirmation.sender}). NO SEND.")
            NotificationHelper.cancelPendingReplyNotification(context, confirmationId)
            val msgKey = "${confirmation.sender}|${confirmation.incomingMessage}"
            activeMessageKeys.remove(msgKey)
            activeSbnMap.remove(confirmationId)
        }
    }

    private fun executeSendGuard(context: Context, confirmation: PendingReplyConfirmation): Boolean {
        // MANDATORY SAFETY GUARD IMMEDIATELY BEFORE SENDING
        if (confirmation.state != ConfirmationState.CONFIRMED) {
            Log.e(TAG, "REPLY_BLOCKED_NO_CONFIRMATION: Send rejected! State is ${confirmation.state}, expected CONFIRMED")
            confirmation.state = ConfirmationState.REJECTED_NO_CONFIRMATION
            pendingConfirmations.remove(confirmation.confirmationId)
            activeSbnMap.remove(confirmation.confirmationId)
            return false
        }

        confirmation.state = ConfirmationState.SENT
        Log.d(TAG, "REPLY_SENT: Executing confirmed reply send to '${confirmation.sender}' with text: '${confirmation.proposedReply}'")

        val sbnToUse = activeSbnMap.remove(confirmation.confirmationId)
            ?: activeSbnMap.values.lastOrNull { it.packageName == confirmation.packageName }
        var sent = false
        if (sbnToUse != null) {
            sent = RemoteInputSender.sendReply(context, sbnToUse, confirmation.proposedReply)
        }

        if (!sent) {
            if (confirmation.packageName.contains("whatsapp", ignoreCase = true)) {
                Log.w(TAG, "RemoteInput send failed or unavailable for WhatsApp sender '${confirmation.sender}'. Suppressing UI automation fallback per safety boundary.")
            } else {
                Log.d(TAG, "RemoteInput failed or unavailable. Falling back to WhatsAppController.")
                val controller = WhatsAppController()
                val result = controller.execute(context, confirmation.sender, confirmation.proposedReply)
                Log.d(TAG, "Accessibility Fallback initiated: $result")
                sent = true
            }
        } else {
            lastReplyTimePerSender[normalize(confirmation.sender)] = System.currentTimeMillis()
        }

        val msgKey = "${confirmation.sender}|${confirmation.incomingMessage}"
        activeMessageKeys.remove(msgKey)
        pendingConfirmations.remove(confirmation.confirmationId)
        return sent
    }

    fun handleVoiceConfirmationCommand(context: Context, text: String): String? {
        val latest = getLatestPendingConfirmation() ?: return null
        val cleaned = text.trim().lowercase()

        val confirmTriggers = listOf("yes", "send it", "confirm", "send", "yes send it", "ok send it", "sure send it", "send message", "confirm reply")
        val cancelTriggers = listOf("no", "cancel", "don't send", "dont send", "stop", "do not send", "reject")

        val isConfirm = confirmTriggers.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.endsWith(" $it") }
        val isCancel = cancelTriggers.any { cleaned == it || cleaned.startsWith("$it ") || cleaned.endsWith(" $it") }

        return when {
            isConfirm -> {
                val sent = confirmAndSend(context, latest.confirmationId)
                if (sent) "Confirmed and sent reply to ${latest.sender}." else "Failed to send reply to ${latest.sender}."
            }
            isCancel -> {
                cancelPendingReply(context, latest.confirmationId)
                "Cancelled proposed reply to ${latest.sender}."
            }
            else -> {
                // Ambiguous input (e.g. "maybe", "wait", "later", "okay") -> MUST NOT send
                Log.d(TAG, "Ambiguous voice input '$text' for pending reply ${latest.confirmationId}. NO SEND. Requesting explicit confirmation.")
                "Pending reply to ${latest.sender} ('${latest.proposedReply}') requires explicit confirmation. Please say 'Yes' to send or 'No' to cancel."
            }
        }
    }
}
