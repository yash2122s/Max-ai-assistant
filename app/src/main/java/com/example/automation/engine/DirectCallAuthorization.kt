package com.example.automation.engine

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Short-lived, single-use capability for an unambiguous number spoken by the user. */
object DirectCallAuthorization {
    private const val TTL_MS = 30_000L
    private const val MAX_ENTRIES = 32
    private val random = SecureRandom()
    private val grants = ConcurrentHashMap<String, Grant>()
    @Volatile var nowMillis: () -> Long = { System.currentTimeMillis() }

    val EMERGENCY_NUMBERS = setOf("112", "911", "100", "101", "102", "108", "000", "999")

    fun issue(utterance: String, target: String): String? {
        val normalized = normalize(target) ?: return null
        if (!isTrustedDirectCall(utterance, target)) return null
        purge()
        if (grants.size >= MAX_ENTRIES) grants.keys.firstOrNull()?.let { grants.remove(it) }
        val token = ByteArray(18).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        grants[token] = Grant(normalized, nowMillis() + TTL_MS)
        return token
    }

    fun consume(token: String?, normalizedNumber: String): Boolean {
        if (token.isNullOrBlank()) return false
        val grant = grants.remove(token) ?: return false
        return grant.expiresAt >= nowMillis() && grant.number == normalize(normalizedNumber)
    }

    fun clear() = grants.clear()

    fun normalize(value: String): String? {
        val raw = value.trim()
        if (raw.any { it in "*#;," }) return null
        val digits = SpokenDigits.digitsOnly(raw)
        if (digits.length !in 7..15) return null
        if (EMERGENCY_NUMBERS.contains(digits)) return null
        return digits
    }

    private fun isTrustedDirectCall(utterance: String, target: String): Boolean {
        if (target.any { it in "*#;," }) return false
        val targetDigits = SpokenDigits.digitsOnly(target)
        if (targetDigits.length !in 7..15 || EMERGENCY_NUMBERS.contains(targetDigits)) return false

        if (utterance.contains(Regex("[\\*#;,]|\\b(whatsapp|telegram|message|text|sms|maybe|if|quoted|or|either)\\b", RegexOption.IGNORE_CASE))) return false

        val utteranceDigits = SpokenDigits.digitsOnly(utterance)
        if (utteranceDigits != targetDigits) return false

        val lower = utterance.trim().lowercase()
        val hasCallVerb = Regex("""\b(?:call(?:ing)?|dial(?:ing)?|phone)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            lower.contains("కాల్") || lower.contains("ఫోన్") || lower.contains("డయల్")
        return hasCallVerb
    }

    private fun purge() { val now = nowMillis(); grants.entries.removeIf { it.value.expiresAt < now } }
    private data class Grant(val number: String, val expiresAt: Long)
}
