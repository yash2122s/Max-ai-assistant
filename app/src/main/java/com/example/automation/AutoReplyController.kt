package com.example.automation

import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.data.repository.JarvisRepository
import com.example.utils.NotificationHelper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

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

    // Hardening: Active conversation map to always reference the most up-to-date StatusBarNotification
    private val activeSbnMap = ConcurrentHashMap<String, StatusBarNotification>()

    // Hardening: Message deduplication lock to avoid race conditions or dual sends
    private val processingMessages = ConcurrentHashMap.newKeySet<String>()

    // Hardening: AI fallback rate-limiting to prevent notification loops or battery drain
    private val lastAiCall = ConcurrentHashMap<String, Long>()

    // Pending decisions for 8-second cancel window
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    fun cancelPendingReply(msgId: String) {
        pendingDecisions[msgId]?.complete(null)
    }

    fun sendReplyNow(msgId: String) {
        // Will be completed with original reply text in the caller if needed, 
        // or we can use a sentinel value like "SEND_ORIGINAL"
        pendingDecisions[msgId]?.complete("SEND_ORIGINAL")
    }

    fun sendQuickReply(msgId: String, replyText: String) {
        pendingDecisions[msgId]?.complete(replyText)
    }

    fun updateSbn(sender: String, sbn: StatusBarNotification) {
        activeSbnMap[sender] = sbn
    }

    suspend fun handleIncoming(
        context: Context,
        sender: String,
        message: String,
        sbn: StatusBarNotification
    ) {
        val settings = SettingsManager(context)
        if (!settings.isAutoReplyEnabled) {
            Log.d("AutoReplyController", "Auto reply is disabled in settings.")
            return
        }
        
        if (normalize(sender) == normalize(settings.userName)) return
        if (sender.contains(":")) return

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryLevel = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryLevel > 0 && batteryLevel < 10) {
            Log.d("AutoReplyController", "Battery below 10% ($batteryLevel%), pausing auto-reply to save power.")
            return
        }

        // Hardening lock: prevent duplicate execution of the exact same message concurrently
        val msgId = "$sender|$message"
        if (!processingMessages.add(msgId)) {
            Log.d("AutoReplyController", "Duplicate execution lock triggered for message: $msgId")
            return
        }

        try {
            if (ServiceLocator.repository == null) {
                ServiceLocator.repository = JarvisRepository(context.applicationContext)
            }
            val repository = ServiceLocator.repository!!
            val rules = repository.getAllRules()
            
            val rule = rules.firstOrNull { it.enabled && !it.targetContact.isNullOrBlank() && it.targetContact.equals(sender, true) && MatchEngine.matches(message, it.triggerKeyword) } ?: rules.firstOrNull { it.enabled && it.targetContact.isNullOrBlank() && MatchEngine.matches(message, it.triggerKeyword) }

            val replyText = when {
                rule != null -> rule.replyMessage
                settings.isAiFallbackEnabled -> {
                    val allowedContactsString = settings.aiAllowedContacts
                    if (allowedContactsString.isNotBlank()) {
                        val allowedList = allowedContactsString.split(",").map { it.trim() }
                        val allowed = allowedList.any { normalize(it) == normalize(sender) }
                        if (!allowed) {
                            Log.d("AutoReplyController", "Sender '$sender' is not in the AI allowed contacts list. Suppressing AI reply.")
                            return
                        }
                    }

                    // Hardening: Rate limit AI requests to 60 seconds per sender
                    val now = System.currentTimeMillis()
                    val lastCallTime = lastAiCall[sender] ?: 0L
                    if (now - lastCallTime < 60_000L) {
                        Log.d("AutoReplyController", "AI fallback call rate limited for '$sender'. Suppressing reply.")
                        return
                    }
                    lastAiCall[sender] = now

                    val generatedReply = try {
                        "I am currently busy. I will text you later."
                    } catch (e: Exception) {
                        Log.e("AutoReplyController", "Error generating AI response", e)
                        "Got it, thank you!"
                    }
                    
                    val userName = settings.userName
                    val prefix = if (userName.isNotBlank()) "I'm ${userName}'s AI assistant: " else "I'm an AI assistant: "
                    prefix + generatedReply
                }
                else -> {
                    Log.d("AutoReplyController", "No matching rule, and AI fallback is disabled.")
                    return
                }
            }

            // 8-second review window
            val decisionDeferred = CompletableDeferred<String?>()
            pendingDecisions[msgId] = decisionDeferred
            
            NotificationHelper.showPendingReplyNotification(context, msgId, sender, replyText)

            var finalReplyText = replyText
            var shouldSend = true
            
            try {
                withTimeout(8000L) {
                    val decision = decisionDeferred.await()
                    if (decision == null) {
                        shouldSend = false
                    } else if (decision != "SEND_ORIGINAL") {
                        finalReplyText = decision
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // 8 seconds passed without user interaction
                shouldSend = true
            }

            NotificationHelper.cancelPendingReplyNotification(context, msgId)
            pendingDecisions.remove(msgId)

            if (!shouldSend) {
                Log.d("AutoReplyController", "Reply cancelled by user for message: $msgId")
                return
            }

            Log.d("AutoReplyController", "Attempting reply to '$sender' with: '$finalReplyText'")

            // Hardening: Use the latest valid registered notification instance
            val sbnToUse = activeSbnMap[sender] ?: sbn

            // Try RemoteInput reply first (Fast path)
            val remoteInputSent = RemoteInputSender.sendReply(context, sbnToUse, finalReplyText)
            if (remoteInputSent) {
                Log.d("AutoReplyController", "Reply sent successfully via RemoteInput!")
            } else {
                Log.d("AutoReplyController", "RemoteInput failed or unavailable. Falling back to Accessibility Automation.")
                // Falling back to WhatsAppController to type and send via accessibility service
                val controller = WhatsAppController()
                val result = controller.execute(context, sender, replyText)
                Log.d("AutoReplyController", "Accessibility Fallback initiated: $result")
            }
        } finally {
            // Remove the deduplication lock
            processingMessages.remove(msgId)
        }
    }
}
