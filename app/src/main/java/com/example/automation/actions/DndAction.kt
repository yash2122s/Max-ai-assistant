package com.example.automation.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject

class DndAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val action = payload.optString("action", "").uppercase()
        val dndEnabled = payload.optBoolean("dndEnabled", false)

        // Check Notification Policy Access
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "MAX needs Notification Policy Access to change DND settings", Toast.LENGTH_LONG).show()
            }
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                logError("Failed to launch Notification Policy Access settings", e)
            }
            return
        }

        try {
            if (action == "SET_DND") {
                if (dndEnabled) {
                    log("Enabling DND")
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    log("Disabling DND")
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        } catch (e: Exception) {
            logError("Failed to execute DND action: ${e.message}", e)
        }
    }

    fun isDndEnabled(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val filter = notificationManager.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }
}
