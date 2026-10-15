package com.example.service

import android.content.Context
import android.util.Log
import com.example.data.preferences.SettingsManager

object NotificationAppAccessManager {
    private const val TAG = "NotificationAppAccess"

    fun isAppEnabled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val settings = SettingsManager(context)
        val isWhatsApp = packageName.contains("whatsapp", ignoreCase = true)
        val enabled = if (isWhatsApp) {
            settings.isWhatsAppAutoReplyEnabled || settings.isNotificationAppEnabled(packageName)
        } else {
            settings.isNotificationAppEnabled(packageName)
        }
        if (!enabled) {
            Log.d(TAG, "PRIVACY GATE: Notification event from '$packageName' IGNORED IMMEDIATELY (MAX app access disabled).")
        } else {
            Log.d(TAG, "PRIVACY GATE: Notification event from '$packageName' ALLOWED for MAX processing.")
        }
        return enabled
    }

    fun setAppEnabled(context: Context, packageName: String, enabled: Boolean) {
        val settings = SettingsManager(context)
        settings.setNotificationAppEnabled(packageName, enabled)
        Log.d(TAG, "Updated notification processing state for '$packageName': enabled=$enabled")
    }
}
