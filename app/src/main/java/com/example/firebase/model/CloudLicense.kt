package com.example.firebase.model

import com.google.firebase.Timestamp
import java.util.Date

enum class LicensePlan {
    FREE,
    TRIAL,
    LIFETIME,
    PREMIUM,
    PRO,
    EXPIRED;

    companion object {
        fun fromString(value: String?): LicensePlan {
            return when (value?.uppercase()?.trim()) {
                "TRIAL" -> TRIAL
                "LIFETIME" -> LIFETIME
                "PREMIUM" -> PREMIUM
                "PRO" -> PRO
                "EXPIRED" -> EXPIRED
                else -> FREE
            }
        }
    }
}

enum class LicenseStatus {
    TRIAL,
    ACTIVE,
    EXPIRED,
    REVOKED;

    companion object {
        fun fromString(value: String?): LicenseStatus {
            val normalized = value?.uppercase()?.trim()
            return when (normalized) {
                "ACTIVE" -> ACTIVE
                "TRIAL" -> TRIAL
                "REVOKED" -> REVOKED
                "EXPIRED" -> EXPIRED
                else -> {
                    android.util.Log.w("CloudLicense", "Unrecognized or non-active license status: '$value', defaulting to EXPIRED")
                    EXPIRED
                }
            }
        }
    }
}

data class CloudLicense(
    val userId: String,
    val plan: LicensePlan = LicensePlan.FREE,
    val status: LicenseStatus = LicenseStatus.EXPIRED,
    val trialStartedAtEpochMillis: Long? = null,
    val trialExpiresAtEpochMillis: Long? = null,
    val expiresAtEpochMillis: Long? = null,
    val activatedAtEpochMillis: Long? = null,
    val licenseId: String? = null,
    val issuedAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
) {
    /**
     * Determines whether the user currently has active rights to features based on plan and status.
     * Evaluates expiration against the provided authoritative timestamp.
     */
    fun isEntitled(authoritativeNow: Long = System.currentTimeMillis()): Boolean {
        if (status == LicenseStatus.REVOKED) return false

        return when (plan) {
            LicensePlan.FREE, LicensePlan.EXPIRED -> false
            LicensePlan.TRIAL -> {
                if (status != LicenseStatus.TRIAL && status != LicenseStatus.ACTIVE) return false
                val expiry = trialExpiresAtEpochMillis ?: expiresAtEpochMillis
                if (expiry == null) true else authoritativeNow < expiry
            }
            LicensePlan.LIFETIME, LicensePlan.PREMIUM, LicensePlan.PRO -> {
                if (status != LicenseStatus.ACTIVE) return false
                val expiry = expiresAtEpochMillis
                // If expiry is null, it is considered lifetime/perpetual
                expiry == null || authoritativeNow < expiry
            }
        }
    }

    fun remainingTrialMillis(authoritativeNow: Long = System.currentTimeMillis()): Long {
        val expiry = trialExpiresAtEpochMillis ?: expiresAtEpochMillis ?: return 0L
        if (plan != LicensePlan.TRIAL) return 0L
        return (expiry - authoritativeNow).coerceAtLeast(0L)
    }

    fun remainingTrialDays(authoritativeNow: Long = System.currentTimeMillis()): Int {
        val remainingMillis = remainingTrialMillis(authoritativeNow)
        if (remainingMillis <= 0L) return 0
        val oneDayMillis = 24L * 60L * 60L * 1000L
        return ((remainingMillis + oneDayMillis - 1L) / oneDayMillis).toInt()
    }

    fun isExpired(authoritativeNow: Long = System.currentTimeMillis()): Boolean {
        if (status == LicenseStatus.EXPIRED || plan == LicensePlan.EXPIRED) return true
        if (plan == LicensePlan.LIFETIME) return false
        if (plan == LicensePlan.TRIAL) {
            val expiry = trialExpiresAtEpochMillis ?: expiresAtEpochMillis
            if (expiry == null) {
                return status == LicenseStatus.EXPIRED || status == LicenseStatus.REVOKED
            }
            return authoritativeNow >= expiry
        }
        val expiry = expiresAtEpochMillis
        return expiry != null && authoritativeNow >= expiry
    }

    fun toFirestoreMap(): Map<String, Any?> {
        return mapOf(
            "userId" to userId,
            "plan" to plan.name,
            "status" to status.name,
            "trialStartedAt" to trialStartedAtEpochMillis?.let { Timestamp(Date(it)) },
            "trialExpiresAt" to trialExpiresAtEpochMillis?.let { Timestamp(Date(it)) },
            "expiresAt" to expiresAtEpochMillis?.let { Timestamp(Date(it)) },
            "activatedAt" to activatedAtEpochMillis?.let { Timestamp(Date(it)) },
            "licenseId" to licenseId,
            "issuedAt" to Timestamp(Date(issuedAtEpochMillis)),
            "updatedAt" to Timestamp(Date(updatedAtEpochMillis))
        )
    }

    companion object {
        fun createFree(userId: String): CloudLicense {
            return CloudLicense(
                userId = userId,
                plan = LicensePlan.FREE,
                status = LicenseStatus.EXPIRED,
                issuedAtEpochMillis = System.currentTimeMillis(),
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        }

        fun createTrial(userId: String, nowEpochMillis: Long = System.currentTimeMillis()): CloudLicense {
            val sevenDaysMillis = 7L * 24L * 60L * 60L * 1000L
            return CloudLicense(
                userId = userId,
                plan = LicensePlan.TRIAL,
                status = LicenseStatus.TRIAL,
                trialStartedAtEpochMillis = nowEpochMillis,
                trialExpiresAtEpochMillis = nowEpochMillis + sevenDaysMillis,
                expiresAtEpochMillis = nowEpochMillis + sevenDaysMillis,
                issuedAtEpochMillis = nowEpochMillis,
                updatedAtEpochMillis = nowEpochMillis
            )
        }

        fun createLifetime(
            userId: String,
            licenseId: String,
            nowEpochMillis: Long = System.currentTimeMillis()
        ): CloudLicense {
            return CloudLicense(
                userId = userId,
                plan = LicensePlan.LIFETIME,
                status = LicenseStatus.ACTIVE,
                expiresAtEpochMillis = null,
                activatedAtEpochMillis = nowEpochMillis,
                licenseId = licenseId,
                issuedAtEpochMillis = nowEpochMillis,
                updatedAtEpochMillis = nowEpochMillis
            )
        }

        fun fromFirestoreMap(userId: String, data: Map<String, Any?>?): CloudLicense {
            if (data == null) return createFree(userId)

            fun parseTimestamp(key: String): Long? {
                val value = data[key]
                return when (value) {
                    is Timestamp -> value.toDate().time
                    is Long -> value
                    is Number -> value.toLong()
                    is Date -> value.time
                    else -> null
                }
            }

            val plan = LicensePlan.fromString(data["plan"] as? String)
            val status = LicenseStatus.fromString(data["status"] as? String)
            val trialStarted = parseTimestamp("trialStartedAt") ?: parseTimestamp("issuedAt")
            val trialExpires = parseTimestamp("trialExpiresAt") ?: parseTimestamp("expiresAt")
            val expiresAt = parseTimestamp("expiresAt") ?: trialExpires
            val activatedAt = parseTimestamp("activatedAt")
            val licenseId = data["licenseId"] as? String
            val issuedAt = parseTimestamp("issuedAt") ?: System.currentTimeMillis()
            val updatedAt = parseTimestamp("updatedAt") ?: System.currentTimeMillis()

            return CloudLicense(
                userId = userId,
                plan = plan,
                status = status,
                trialStartedAtEpochMillis = trialStarted,
                trialExpiresAtEpochMillis = trialExpires,
                expiresAtEpochMillis = expiresAt,
                activatedAtEpochMillis = activatedAt,
                licenseId = licenseId,
                issuedAtEpochMillis = issuedAt,
                updatedAtEpochMillis = updatedAt
            )
        }
    }
}
