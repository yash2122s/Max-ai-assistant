package com.example.automation.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject

class DndAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: throw IllegalStateException("NotificationManager service unavailable")
        val action = payload.optString("action", "").uppercase()

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
            throw IllegalStateException("Notification Policy Access permission not granted for MAX. Prompted user to allow.")
        }

        val dndEnabled = if (payload.has("enabled")) {
            payload.optBoolean("enabled")
        } else if (payload.has("dndEnabled")) {
            payload.optBoolean("dndEnabled")
        } else if (payload.has("state")) {
            val st = payload.optString("state").trim()
            st.equals("ON", ignoreCase = true) || st.equals("TRUE", ignoreCase = true) || st == "1"
        } else if (action in listOf("DND_ON", "ENABLE_DND")) {
            true
        } else if (action in listOf("DND_OFF", "DISABLE_DND")) {
            false
        } else {
            true
        }

        if (dndEnabled) {
            log("Enabling DND (NotificationManager.INTERRUPTION_FILTER_NONE)")
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        } else {
            log("Disabling DND (NotificationManager.INTERRUPTION_FILTER_ALL)")
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
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
