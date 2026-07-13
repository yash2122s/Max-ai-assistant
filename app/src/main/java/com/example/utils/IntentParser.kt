package com.example.utils

import java.util.regex.Pattern

data class ParsedRequest(
    val intent: String, // "OPEN_APP", "SEND_MESSAGE", "SET_REMINDER", "QUICK_ACTION", "CHAT", "CALL_PHONE"
    val appName: String? = null,
    val packageName: String? = null,
    val contact: String? = null,
    val message: String? = null,
    val quickActionType: String? = null, // "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "WIFI_SETTINGS", "BLUETOOTH_SETTINGS"
    val reminderMessage: String? = null,
    val reminderTimeAmount: Int? = null,
    val reminderTimeUnit: String? = null // "seconds", "minutes", "hours"
)

object IntentParser {
    fun parse(text: String): ParsedRequest {
        val t = text.lowercase().trim()

        // 1. Resolve context-aware pronouns if applicable (Feature 15)
        val resolvedContact = ContextManager.lastContact
        if (resolvedContact != null && (t.contains("tell her") || t.contains("tell him") || t.contains("send her") || t.contains("send him") || t.contains("message her") || t.contains("message him"))) {
            // Reconstruct the message to refer to the resolved contact
            val body = text.replace(Regex("(?i)^(?:tell|send|message)\\s+(?:her|him)\\s+(?:to\\s+)?"), "").trim()
            return ParsedRequest(
                intent = "SEND_MESSAGE",
                contact = resolvedContact,
                message = body
            )
        }

        // 2. Quick Actions (Feature 11)
        if (t.contains("flashlight on") || t.contains("torch on") || t.contains("turn on flashlight") || t.contains("turn on torch")) {
            return ParsedRequest(intent = "QUICK_ACTION", quickActionType = "FLASHLIGHT_ON")
        }
        if (t.contains("flashlight off") || t.contains("torch off") || t.contains("turn off flashlight") || t.contains("turn off torch")) {
            return ParsedRequest(intent = "QUICK_ACTION", quickActionType = "FLASHLIGHT_OFF")
        }
        if (t.contains("wifi") || t.contains("wi-fi") || t.contains("wireless")) {
            return ParsedRequest(intent = "QUICK_ACTION", quickActionType = "WIFI_SETTINGS")
        }

        // 3. Open App (Feature 6)
        if (t.startsWith("open ") && !t.contains("search") && !t.contains("and") && !t.contains("play")) {
            val app = t.substringAfter("open ").trim()
            val (pName, cleanAppName) = getAppPackageAndName(app)
            if (pName.isNotEmpty()) {
                return ParsedRequest(
                    intent = "OPEN_APP",
                    appName = cleanAppName,
                    packageName = pName
                )
            }
        }

        // 3.5 Call Phone
        if (t.startsWith("call ")) {
            val contact = t.substringAfter("call ").trim()
            if (contact.isNotEmpty()) {
                return ParsedRequest(
                    intent = "CALL_PHONE",
                    contact = contact
                )
            }
        }

        // 4. Send Message (Feature 10 / 9)
        // Match expressions like: "send message to [contact]: [message]" or "whatsapp [contact] [message]" or "message [contact] [message]"
        val sendMsgPattern = Pattern.compile("(?i)(?:send\\s+message\\s+to|send\\s+a\\s+message\\s+to|message|whatsapp|text)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+saying|\\s+to|:|\\s+that)?\\s+([a-zA-Z0-9\\s\\.\\,\\?\\!\\']+)$")
        val msgMatcher = sendMsgPattern.matcher(text)
        if (msgMatcher.find()) {
            val contact = msgMatcher.group(1)?.trim() ?: ""
            val message = msgMatcher.group(2)?.trim() ?: ""
            
            if (contact.isNotEmpty()) {
                ContextManager.lastContact = contact
                ContextManager.lastIntent = "SEND_MESSAGE"
            }
            return ParsedRequest(
                intent = "SEND_MESSAGE",
                contact = contact,
                message = message
            )
        }
        
        // Match simple contact setup: "message [contact]" (prompting for message later)
        val simpleMsgPattern = Pattern.compile("(?i)(?:message|whatsapp|text)\\s+([a-zA-Z0-9\\s]+)$")
        val simpleMsgMatcher = simpleMsgPattern.matcher(text)
        if (simpleMsgMatcher.find()) {
            val contact = simpleMsgMatcher.group(1)?.trim() ?: ""
            if (contact.isNotEmpty()) {
                ContextManager.lastContact = contact
                ContextManager.lastIntent = "SEND_MESSAGE"
                return ParsedRequest(
                    intent = "SEND_MESSAGE",
                    contact = contact,
                    message = null
                )
            }
        }

        // 5. Reminders (Feature 7)
        if (t.contains("remind") || t.contains("alarm") || t.contains("schedule")) {
            val remPattern = Pattern.compile("(?i)remind\\s+me\\s+to\\s+(.+?)\\s+in\\s+(\\d+)\\s+(second|minute|hour)s?")
            val remMatcher = remPattern.matcher(text)
            if (remMatcher.find()) {
                val rMsg = remMatcher.group(1)?.trim() ?: "Reminder"
                val amount = remMatcher.group(2)?.toInt() ?: 10
                val unit = remMatcher.group(3)?.lowercase() ?: "second"
                return ParsedRequest(
                    intent = "SET_REMINDER",
                    reminderMessage = rMsg,
                    reminderTimeAmount = amount,
                    reminderTimeUnit = unit
                )
            }
        }

        // Default to Chat
        return ParsedRequest(intent = "CHAT")
    }

    private fun getAppPackageAndName(appQuery: String): Pair<String, String> {
        val q = appQuery.lowercase().trim()
        return when {
            q.contains("whatsapp") && (q.contains("business") || q.contains("w4b")) -> "com.whatsapp.w4b" to "WhatsApp Business"
            q.contains("whatsapp") -> "com.whatsapp" to "WhatsApp"
            q.contains("instagram") -> "com.instagram.android" to "Instagram"
            q.contains("telegram") -> "org.telegram.messenger" to "Telegram"
            q.contains("youtube") -> "com.google.android.youtube" to "YouTube"
            q.contains("chrome") -> "com.android.chrome" to "Chrome"
            q.contains("facebook") -> "com.facebook.katana" to "Facebook"
            else -> "" to appQuery.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
