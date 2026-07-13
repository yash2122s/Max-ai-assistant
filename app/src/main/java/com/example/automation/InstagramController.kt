package com.example.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.service.AutomationTask
import com.example.service.JarvisAccessibilityService

class InstagramController(private val isScheduled: Boolean = false) : AppAutomation {
    override fun execute(context: Context, contact: String, message: String): String {
        try {
            // Set the pending task for the accessibility service
            JarvisAccessibilityService.pendingTask = AutomationTask(
                type = "SEND_MESSAGE",
                contact = contact,
                message = message,
                appName = "Instagram",
                isScheduled = isScheduled
            )

            val intent = context.packageManager.getLaunchIntentForPackage("com.instagram.android")
            return if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Launching Instagram automation. Sending to '$contact': \"$message\""
            } else {
                "Instagram is not installed on this device. (Automation configured for com.instagram.android)"
            }
        } catch (e: Exception) {
            Log.e("InstagramController", "Error executing Instagram automation", e)
            return "Could not launch Instagram: ${e.localizedMessage}"
        }
    }
}
