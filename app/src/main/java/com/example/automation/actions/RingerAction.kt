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
            throw IllegalStateException("Notification Policy Access permission not granted for MAX. Prompted user to allow.")
        }

        val mode = payload.optString("mode")
            .ifEmpty { payload.optString("ringer_mode") }
            .ifEmpty { payload.optString("state") }
            .lowercase()
        val actionName = payload.optString("action", "").uppercase()

        when {
            mode in listOf("silent", "mute") || actionName in listOf("SILENT_MODE_ON", "SET_SILENT") -> {
                log("Setting ringer mode to SILENT")
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            }
            mode in listOf("vibrate", "vib") || actionName in listOf("VIBRATE_MODE_ON", "SET_VIBRATE") -> {
                log("Setting ringer mode to VIBRATE")
                try {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (e: Exception) {
                    logError("Failed to clear interruption filter for VIBRATE", e)
                }
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            }
            mode in listOf("normal", "ring", "sound", "unmute") || actionName in listOf("SILENT_MODE_OFF", "NORMAL_MODE_ON", "SET_NORMAL") -> {
                log("Setting ringer mode to NORMAL")
                try {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                } catch (e: Exception) {
                    logError("Failed to clear interruption filter for NORMAL", e)
                }
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            mode in listOf("dnd_on", "dnd") || actionName == "DND_ON" -> {
                log("Enabling DND (Do Not Disturb)")
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
            mode == "dnd_off" || actionName == "DND_OFF" -> {
                log("Disabling DND (Do Not Disturb)")
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            else -> {
                throw IllegalArgumentException("Unhandled Ringer mode: '$mode' (payload: $payload)")
            }
        }
    }
}
