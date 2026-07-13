package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.automation.AutoReplyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WhatsAppNotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("WhatsAppNotifService", "Notification Listener Connected!")
    }

    @Suppress("DEPRECATION")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Ignore group chats
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) ||
                extras.getString(Notification.EXTRA_CONVERSATION_TITLE) != null
        if (isGroup) {
            Log.d("WhatsAppNotifService", "Ignoring group conversation")
            return
        }

        var title = extras.getString(Notification.EXTRA_TITLE) ?: return
        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Parse MessagingStyle if available
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val parsedMessages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)
            if (parsedMessages.isNotEmpty()) {
                val latestMessage = parsedMessages.last()
                val msgText = latestMessage.text?.toString()
                val senderPerson = latestMessage.senderPerson
                val senderName = senderPerson?.name?.toString() ?: latestMessage.sender?.toString()
                
                if (msgText != null && senderName != null) {
                    title = senderName
                    text = msgText
                }
            }
        }

        // Skip standard non-message notifications
        if (title.lowercase() == "whatsapp" || text.lowercase().contains("checking for new messages")) {
            return
        }

        Log.d("WhatsAppNotifService", "Received notification from $title: $text")

        // Hardening check: register SBN in active SBN map before handling
        AutoReplyController.updateSbn(title, sbn)

        serviceScope.launch {
            try {
                AutoReplyController.handleIncoming(
                    context = applicationContext,
                    sender = title,
                    message = text,
                    sbn = sbn
                )
            } catch (e: Exception) {
                Log.e("WhatsAppNotifService", "Error in AutoReplyController", e)
            }
        }
    }
}
