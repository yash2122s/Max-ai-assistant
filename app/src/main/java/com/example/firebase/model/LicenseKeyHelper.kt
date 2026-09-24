package com.example.firebase.model

import java.security.MessageDigest
import java.util.Locale

object LicenseKeyHelper {

    private val KEY_REGEX = Regex("^MAX-LIFE-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    /**
     * Normalizes a raw license key string:
     * - Trims whitespace
     * - Converts to uppercase
     * - Standardizes delimiters (replaces spaces/underscores with hyphens)
     * - Removes redundant hyphens
     */
    fun normalize(rawKey: String): String {
        return rawKey
            .trim()
            .uppercase(Locale.ROOT)
            .replace(Regex("[_\\s]+"), "-")
            .replace(Regex("-+"), "-")
    }

    /**
     * Validates whether the normalized key matches the required format:
     * MAX-LIFE-XXXX-XXXX-XXXX where X is an uppercase alphanumeric character.
     */
    fun isValidFormat(key: String): Boolean {
        val normalized = normalize(key)
        return KEY_REGEX.matches(normalized)
    }

    /**
     * Computes the SHA-256 hex string of the normalized license key.
     * This hash is used as the document ID in Firestore (`licenseKeys/{keyHash}`)
     * so that the raw key is never stored directly or exposed.
     */
    fun sha256Hex(key: String): String {
        val normalized = normalize(key)
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Safe string representation for logs that redacts intermediate blocks
     * to protect customer license secrets.
     * e.g. "MAX-LIFE-****-****-28TZ"
     */
    fun redact(key: String): String {
        val normalized = normalize(key)
        if (!isValidFormat(normalized)) {
            return if (normalized.length <= 8) "[REDACTED]" else normalized.take(4) + "****"
        }
        val parts = normalized.split("-")
        return "${parts[0]}-${parts[1]}-****-****-${parts[4]}"
    }
}
