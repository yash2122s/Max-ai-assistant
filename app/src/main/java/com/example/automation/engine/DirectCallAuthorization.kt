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
        if (!raw.matches(Regex("^\\+?[0-9][0-9\\s().-]*$"))) return null
        val digits = raw.filter(Char::isDigit)
        return digits.takeIf { it.length in 7..15 }
    }

    private fun isTrustedDirectCall(utterance: String, target: String): Boolean {
        if (normalize(target) == null || utterance.contains(Regex("[\\*#;,]|\\b(whatsapp|telegram|message|text|sms|maybe|if|quoted|or|either)\\b", RegexOption.IGNORE_CASE))) return false
        val targetDigits = target.filter(Char::isDigit)
        if (targetDigits.length !in 7..15) return false

        val utteranceDigits = utterance.filter(Char::isDigit)
        if (!utteranceDigits.contains(targetDigits)) return false

        val distinctNumbers = Regex("\\b\\d{7,15}\\b").findAll(utterance)
            .map { it.value }
            .distinct()
            .toList()
        if (distinctNumbers.size > 1) return false
        if (utteranceDigits.length > targetDigits.length + 3) return false

        val lower = utterance.trim().lowercase()
        val isPureNumberUtterance = lower.replace(Regex("[\\s()+\\-]"), "").matches(Regex("^\\d{7,15}$"))
        val hasCallIntent = lower.contains("call") || lower.contains("dial") || lower.contains("phone") || 
                            lower.contains("chey") || lower.contains("చేయి") || lower.contains("చెయ్") || lower.contains("చేయండి")
        return isPureNumberUtterance || hasCallIntent
    }

    private fun purge() { val now = nowMillis(); grants.entries.removeIf { it.value.expiresAt < now } }
    private data class Grant(val number: String, val expiresAt: Long)
}
