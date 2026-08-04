package com.example.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.network.agent.CompanionConnectionService
import org.json.JSONObject

class NotificationListenerBridge : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        if (packageName == applicationContext.packageName) return // Ignore self

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        Log.d(TAG, "Notification received from [$packageName]: $title - $text")

        com.example.network.agent.WindowsToolExecutor.sendNotificationEvent(packageName, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        private const val TAG = "NotificationBridge"
    }
}
