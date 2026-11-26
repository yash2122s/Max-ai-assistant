package com.maxaiagent.core

/**
 * Shared command normalization and intent classification (English + Telugu/Tinglish).
 */
object MaxCommandNormalizer {
    private val homeVariants = setOf(
        "go to home",
        "go to home screen",
        "home",
        "open home",
        "హోమ్ స్క్రీన్‌కి వెళ్ళు",
        "హోమ్ స్క్రీన్కి వెళ్ళు",
        "హోమ్ ఓపెన్ చేయి"
    )

    fun normalize(rawInput: String): MaxNormalizedCommand {
        val trimmed = rawInput.trim()
        val collapsed = trimmed.replace(Regex("\\s+"), " ")
        val lower = collapsed.lowercase()

        val intent = when {
            lower in homeVariants || homeVariants.any { lower.contains(it) } -> MaxIntentKind.HOME_NAVIGATION
            lower.startsWith("/status") || lower == "status" -> MaxIntentKind.STATUS_QUERY
            lower.startsWith("/toggle") || lower == "max off" || lower == "max on" -> MaxIntentKind.TOGGLE_RUNTIME
            lower.startsWith("/ping") || lower.contains("bridge") -> MaxIntentKind.WINDOWS_BRIDGE_PING
            lower.startsWith("/hash ") -> MaxIntentKind.CRYPTO_VAULT_HASH
            else -> MaxIntentKind.GENERAL_PROMPT
        }

        return MaxNormalizedCommand(
            rawText = rawInput,
            normalizedText = collapsed,
            intent = intent,
            fingerprint = MaxCrypto.sha256Hex(lower)
        )
    }
}
