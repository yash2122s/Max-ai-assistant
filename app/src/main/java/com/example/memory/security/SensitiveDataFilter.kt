package com.example.memory.security

import java.util.regex.Pattern

object SensitiveDataFilter {

    data class FilterResult(
        val isSafe: Boolean,
        val reason: String? = null
    )

    private val API_KEY_PATTERNS = listOf(
        Pattern.compile("AIza[0-9A-Za-z-_]{16,}"), // Google API Key (live or test)
        Pattern.compile("sk-[a-zA-Z0-9]{16,}"), // OpenAI API Key
        Pattern.compile("ghp_[a-zA-Z0-9]{20,}"), // GitHub Personal Access Token
        Pattern.compile("ey[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_.+/=]*"), // JWT Token
        Pattern.compile("(?i)(?:bearer)\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+"), // Bearer token
        Pattern.compile("(?i)(?:api_key|apikey|api\\s+key|secret_key|access_token|bearer)\\s*[:=]?\\s*['\"]?[a-zA-Z0-9_\\-\\.]+['\"]?")
    )

    private val OTP_PATTERNS = listOf(
        Pattern.compile("(?i)(?:otp|one[- ]time[- ]password|verification code|security code)\\s*(?:is|:)?\\s*\\b[0-9]{4,8}\\b"),
        Pattern.compile("(?i)\\b(?:otp|code)\\s*[:=]?\\s*[0-9]{4,8}\\b")
    )

    private val PASSWORD_PATTERNS = listOf(
        Pattern.compile("(?i)(?:my\\s+)?(?:password|passcode|pin|login pin|atm pin)\\s*(?:is|:|=|to)\\s*['\"]?\\S+['\"]?"),
        Pattern.compile("(?i)(?:username|user)\\s*[:=]\\s*\\S+\\s+(?:password|pass)\\s*[:=]\\s*\\S+")
    )

    private val CREDIT_CARD_PATTERNS = listOf(
        Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12}|(?:2131|1800|35\\d{3})\\d{11})\\b")
    )

    fun isSafeToStore(title: String, content: String): FilterResult {
        val combined = "$title $content"

        for (pattern in PASSWORD_PATTERNS) {
            if (pattern.matcher(combined).find()) {
                return FilterResult(false, "Passwords, PINs, and login credentials cannot be stored in permanent memory.")
            }
        }

        for (pattern in OTP_PATTERNS) {
            if (pattern.matcher(combined).find()) {
                return FilterResult(false, "One-time passwords (OTPs) and verification codes cannot be stored in permanent memory.")
            }
        }

        for (pattern in API_KEY_PATTERNS) {
            if (pattern.matcher(combined).find()) {
                return FilterResult(false, "API keys, access tokens, and secret headers cannot be stored in permanent memory.")
            }
        }

        for (pattern in CREDIT_CARD_PATTERNS) {
            if (pattern.matcher(combined).find()) {
                return FilterResult(false, "Payment card numbers and banking credentials cannot be stored in permanent memory.")
            }
        }

        return FilterResult(true)
    }
}
