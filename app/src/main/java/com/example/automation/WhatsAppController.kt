package com.example.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.service.AutomationTask
import com.example.service.JarvisAccessibilityService

class WhatsAppController(private val isScheduled: Boolean = false) : AppAutomation {
    override fun execute(context: Context, contact: String, message: String): String {
        try {
            var targetPkg = "com.whatsapp"
            var launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
            var actualAppName = "WhatsApp"

            if (launchIntent == null) {
                targetPkg = "com.whatsapp.w4b"
                launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                actualAppName = "WhatsApp Business"
            }

            if (launchIntent == null) {
                return "Neither WhatsApp nor WhatsApp Business is installed on this device."
            }

            // Check if contact looks like a phone number (7-15 digits, optional plus, spaces, dashes)
            val isPhoneNumber = contact.matches(Regex("^[+]?[0-9\\s\\-]{7,18}$"))
            
            if (isPhoneNumber && contact.any { it.isDigit() }) {
                val cleanNumber = contact.replace(Regex("[^0-9+]"), "")
                val finalNumber = if (!cleanNumber.startsWith("+") && cleanNumber.length == 10) {
                    "91$cleanNumber"
                } else {
                    cleanNumber.replace("+", "")
                }
                val encodedMessage = android.net.Uri.encode(message)
                val url = "https://api.whatsapp.com/send?phone=$finalNumber&text=$encodedMessage"
                
                Log.d("WhatsAppSender", "Opening deep link: $url")
                
                // Set special task type for deep link automation
                JarvisAccessibilityService.pendingTask = AutomationTask(
                    type = "SEND_MESSAGE_DEEP_LINK",
                    contact = contact, // used for verification
                    message = message,
                    appName = actualAppName,
                    isScheduled = isScheduled
                )

                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                
                return "Opening WhatsApp deep link for $contact. Automation will verify and send shortly."
            } else {
                // Legacy search behavior
                JarvisAccessibilityService.pendingTask = AutomationTask(
                    type = "SEND_MESSAGE",
                    contact = contact,
                    message = message,
                    appName = actualAppName,
                    isScheduled = isScheduled
                )

                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launchIntent)
                return "Launching $actualAppName search automation for '$contact'."
            }
        } catch (e: Exception) {
            Log.e("WhatsAppController", "Error executing WhatsApp automation", e)
            return "Could not launch WhatsApp: ${e.localizedMessage}"
        }
    }
}