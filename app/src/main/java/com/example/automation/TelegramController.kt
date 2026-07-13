package com.example.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.service.AutomationTask
import com.example.service.JarvisAccessibilityService

class TelegramController(private val isScheduled: Boolean = false) : AppAutomation {
    override fun execute(context: Context, contact: String, message: String): String {
        try {
            // Set the pending task for the accessibility service
            JarvisAccessibilityService.pendingTask = AutomationTask(
                type = "SEND_MESSAGE",
                contact = contact,
                message = message,
                appName = "Telegram",
                isScheduled = isScheduled
            )

            val intent = context.packageManager.getLaunchIntentForPackage("org.telegram.messenger")
            return if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Launching Telegram automation. Sending to '$contact': \"$message\""
            } else {
                "Telegram is not installed on this device. (Automation configured for org.telegram.messenger)"
            }
        } catch (e: Exception) {
            Log.e("TelegramController", "Error executing Telegram automation", e)
            return "Could not launch Telegram: ${e.localizedMessage}"
        }
    }
}
