package com.example.automation.computeruse

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Privacy and security guard for detecting sensitive input fields (passwords, PINs, OTPs, CVVs),
 * redacting sensitive logs, and preventing unauthorized automated typing into secure fields.
 */
object SecureFieldProtector {

    private val SECURE_KEYWORDS = listOf(
        "password", "passcode", "pin_entry", "pinentry", "security_pin",
        "otp_entry", "otp", "secret", "cvv", "cvc", "credit_card", "card_number"
    )

    /**
     * Determines whether an InteractiveElement is a secure/sensitive field.
     */
    fun isSecureElement(element: InteractiveElement): Boolean {
        if (element.isPassword) return true
        val id = element.id?.lowercase(Locale.ROOT) ?: ""
        val desc = element.contentDescription?.lowercase(Locale.ROOT) ?: ""
        val className = element.className?.lowercase(Locale.ROOT) ?: ""

        if (className.contains("password") || className.contains("pin")) return true
        return SECURE_KEYWORDS.any { id.contains(it) || desc.contains(it) }
    }

    /**
     * Determines whether an AccessibilityNodeInfo is a secure/sensitive field.
     */
    fun isSecureNode(node: AccessibilityNodeInfo): Boolean {
        if (try { node.isPassword } catch (_: Exception) { false }) return true
        val id = try { node.viewIdResourceName?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        val desc = try { node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }
        val className = try { node.className?.toString()?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }

        if (className.contains("password") || className.contains("pin")) return true
        return SECURE_KEYWORDS.any { id.contains(it) || desc.contains(it) }
    }

    /**
     * Redacts text if it matches sensitive patterns (PINs, passwords, card numbers).
     */
    fun redactSensitiveText(text: String): String {
        if (text.isBlank()) return text
        var sanitized = text
        // Redact potential 4-8 digit OTPs/PINs
        sanitized = sanitized.replace(Regex("""\b\d{4,8}\b"""), "[REDACTED_CODE]")
        // Redact credit card patterns
        sanitized = sanitized.replace(Regex("""\b(?:\d{4}[-\s]?){3}\d{4}\b"""), "[REDACTED_CARD]")
        return sanitized
    }
}
