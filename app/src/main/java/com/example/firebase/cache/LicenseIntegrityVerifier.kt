package com.example.firebase.cache

import android.content.Context
import com.example.data.preferences.SettingsManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LicenseIntegrityVerifier(private val context: Context) {

    private val deviceSecret: ByteArray by lazy {
        // Derive device-bound entropy combining app installation ID and package salt
        val settings = SettingsManager(context)
        val id = settings.userId
        val salt = "MAX_DEVICE_BOUND_LICENSE_INTEGRITY_SALT_V1"
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray(StandardCharsets.UTF_8))
        md.digest(id.toByteArray(StandardCharsets.UTF_8))
    }

    fun computeIntegrityHash(
        userId: String,
        plan: String,
        status: String,
        trialExpiresAtEpochMillis: Long?,
        expiresAtEpochMillis: Long?,
        cachedAtEpochMillis: Long
    ): String {
        val payload = "$userId|$plan|$status|${trialExpiresAtEpochMillis ?: -1}|${expiresAtEpochMillis ?: -1}|$cachedAtEpochMillis"
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(deviceSecret, "HmacSHA256")
        mac.init(secretKey)
        val hmacBytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return hmacBytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyIntegrity(entity: FirebaseLicenseCacheEntity): Boolean {
        val expected = computeIntegrityHash(
            userId = entity.userId,
            plan = entity.plan,
            status = entity.status,
            trialExpiresAtEpochMillis = entity.trialExpiresAtEpochMillis,
            expiresAtEpochMillis = entity.expiresAtEpochMillis,
            cachedAtEpochMillis = entity.cachedAtEpochMillis
        )
        return expected.equals(entity.integrityHash, ignoreCase = true)
    }

    fun isCacheStale(cachedAtEpochMillis: Long, gracePeriodMillis: Long = MAX_OFFLINE_GRACE_PERIOD_MILLIS): Boolean {
        val now = System.currentTimeMillis()
        if (now < cachedAtEpochMillis) {
            // Local clock rolled back before cache time!
            return true
        }
        return (now - cachedAtEpochMillis) > gracePeriodMillis
    }

    companion object {
        // 72 hours maximum offline tolerance before requiring a cloud handshake
        const val MAX_OFFLINE_GRACE_PERIOD_MILLIS = 72L * 60L * 60L * 1000L
    }
}
