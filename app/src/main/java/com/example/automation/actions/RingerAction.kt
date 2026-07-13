package com.example.automation.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject

class RingerAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // Check Notification Policy Access (required for Ringer mode / DND changes on Android 6.0+)
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "MAX needs Notification Policy Access to change DND / Ring Mode", Toast.LENGTH_LONG).show()
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

        val mode = payload.optString("mode", "").lowercase()
        val actionName = payload.optString("action", "").uppercase()

        when {
            mode == "silent" || actionName == "SILENT_MODE_ON" -> {
                log("Setting ringer mode to SILENT")
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            mode == "vibrate" || actionName == "VIBRATE_MODE_ON" -> {
                log("Setting ringer mode to VIBRATE")
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            }
            mode == "normal" || mode == "ring" || actionName == "SILENT_MODE_OFF" -> {
                log("Setting ringer mode to NORMAL")
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            mode == "dnd_on" || actionName == "DND_ON" -> {
                log("Enabling DND (Do Not Disturb)")
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
            mode == "dnd_off" || actionName == "DND_OFF" -> {
                log("Disabling DND (Do Not Disturb)")
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            else -> {
                logError("Unhandled Ringer/DND mode payload: $payload")
            }
        }
    }
}
