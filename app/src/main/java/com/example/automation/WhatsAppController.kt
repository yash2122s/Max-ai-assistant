package com.example.automation

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.automation.contact.Contact
import com.example.automation.contact.ContactResolution
import com.example.automation.contact.ContactResolver
import com.example.service.AutomationTask
import com.example.service.JarvisAccessibilityService

sealed class WhatsAppExecutionResult {
    data class Launched(
        val contact: String,
        val contactDisplayName: String,
        val phone: String,
        val message: String,
        val isDeepLink: Boolean,
        val messageStatus: String
    ) : WhatsAppExecutionResult()

    data class NeedsInput(
        val question: String,
        val matches: List<Contact> = emptyList()
    ) : WhatsAppExecutionResult()

    data class Error(val error: String) : WhatsAppExecutionResult()
}

class WhatsAppController(private val isScheduled: Boolean = false) : AppAutomation {
    private val TAG = "WhatsAppController"

    /**
     * Detailed execution returning typed result (Launched, NeedsInput, Error).
     * Canonical owner of contact resolution for both interactive and scheduled flows.
     */
    fun executeDetailed(context: Context, contact: String, message: String): WhatsAppExecutionResult {
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
                return WhatsAppExecutionResult.Error("Neither WhatsApp nor WhatsApp Business is installed on this device.")
            }

            val resolution = ContactResolver.resolve(context, contact)
            Log.d(TAG, "Contact resolution for '$contact': $resolution")

            when (resolution) {
                is ContactResolution.Found -> {
                    val resolvedContact = resolution.contact
                    val normalizedPhone = resolution.normalizedNumber
                    val urlPhone = ContactResolver.toWhatsAppUrlPhone(normalizedPhone)
                    val encodedMessage = android.net.Uri.encode(message)
                    val url = "https://api.whatsapp.com/send?phone=$urlPhone&text=$encodedMessage"

                    Log.d(TAG, "Opening direct deep link for ${resolvedContact.name} ($normalizedPhone): $url")

                    JarvisAccessibilityService.lastSendVerificationResult = com.example.service.SendVerificationResult.VERIFICATION_PENDING
                    JarvisAccessibilityService.pendingTask = AutomationTask(
                        type = "SEND_MESSAGE_DEEP_LINK",
                        contact = contact,
                        contactDisplayName = resolvedContact.name,
                        phone = normalizedPhone,
                        message = message,
                        appName = actualAppName,
                        isScheduled = isScheduled
                    )

                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        setPackage(targetPkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)

                    return WhatsAppExecutionResult.Launched(
                        contact = contact,
                        contactDisplayName = resolvedContact.name,
                        phone = normalizedPhone,
                        message = message,
                        isDeepLink = true,
                        messageStatus = "Opening WhatsApp deep link for ${resolvedContact.name}. Automation will verify and send shortly."
                    )
                }

                is ContactResolution.Ambiguous -> {
                    val candidates = resolution.matches.joinToString("\n") {
                        "• ${it.name} (${it.phoneNumber} - ${it.type})"
                    }
                    val question = "Multiple contacts found for '$contact':\n$candidates\nWhich one would you like to message?"
                    return WhatsAppExecutionResult.NeedsInput(question = question, matches = resolution.matches)
                }

                is ContactResolution.NotFound -> {
                    if (ContactResolver.isDirectPhoneNumber(contact)) {
                        return WhatsAppExecutionResult.Error("Invalid phone number format for '$contact'.")
                    }

                    // For non-numeric contact names not in phonebook, fall back to search-based automation
                    Log.d(TAG, "Contact '$contact' not found in phonebook. Falling back to search automation.")
                    JarvisAccessibilityService.pendingTask = AutomationTask(
                        type = "SEND_MESSAGE",
                        contact = contact,
                        contactDisplayName = contact,
                        phone = null,
                        message = message,
                        appName = actualAppName,
                        isScheduled = isScheduled
                    )

                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    context.startActivity(launchIntent)

                    return WhatsAppExecutionResult.Launched(
                        contact = contact,
                        contactDisplayName = contact,
                        phone = "",
                        message = message,
                        isDeepLink = false,
                        messageStatus = "Launching $actualAppName search automation for '$contact'."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp automation", e)
            return WhatsAppExecutionResult.Error("Could not launch WhatsApp: ${e.localizedMessage}")
        }
    }

    /**
     * Backward-compatible execute() adhering to AppAutomation interface.
     */
    override fun execute(context: Context, contact: String, message: String): String {
        return when (val result = executeDetailed(context, contact, message)) {
            is WhatsAppExecutionResult.Launched -> result.messageStatus
            is WhatsAppExecutionResult.NeedsInput -> result.question
            is WhatsAppExecutionResult.Error -> result.error
        }
    }
}