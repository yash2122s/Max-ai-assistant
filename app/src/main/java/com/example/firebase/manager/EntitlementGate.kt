package com.example.firebase.manager

import android.content.Context
import android.util.Log
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus

sealed class GateDecision {
    data object AllowLifetime : GateDecision()
    data class AllowTrial(val messageCountToday: Long, val messagesRemaining: Long) : GateDecision()
    data class BlockDailyLimitReached(val messageCountToday: Long, val limit: Long = EntitlementGate.DEFAULT_DAILY_LIMIT) : GateDecision()
    data object BlockTrialExpired : GateDecision()
    data object BlockLicenseRevoked : GateDecision()
    data class BlockLicenseInactive(val reason: String = "MAX License is not active.") : GateDecision()
    data class BlockNotAuthenticated(val message: String = "Google Sign-In required.") : GateDecision()
    data class BlockError(val message: String) : GateDecision()

    val isAllowed: Boolean
        get() = this is AllowLifetime || this is AllowTrial
}

class EntitlementGate(
    private val context: Context,
    private val licenseManager: MaxLicenseManager
) {
    /**
     * Checks entitlement and atomically consumes 1 message for the day.
     * Central checkpoint for all AI requests.
     */
    suspend fun checkAndConsumeQuota(): GateDecision {
        val user = licenseManager.currentUser.value
        val license = licenseManager.currentLicense.value
        val now = System.currentTimeMillis()

        // 0. Revoked check
        if (license.status == LicenseStatus.REVOKED) {
            Log.w(TAG, "[License] gate_blocked: LICENSE REVOKED")
            return GateDecision.BlockLicenseRevoked
        }

        // 1. Lifetime user: Check ACTIVE
        if (license.plan == LicensePlan.LIFETIME) {
            if (license.status == LicenseStatus.ACTIVE) {
                Log.d(TAG, "[License] gate_allowed: LIFETIME user")
                return GateDecision.AllowLifetime
            } else {
                Log.w(TAG, "[License] gate_blocked: LIFETIME license inactive (status=${license.status})")
                return GateDecision.BlockLicenseInactive("MAX Lifetime License is not active (${license.status}).")
            }
        }

        // 2. Paid / Premium tiers
        if (license.plan == LicensePlan.PREMIUM || license.plan == LicensePlan.PRO) {
            if (license.status == LicenseStatus.ACTIVE && license.isEntitled(now)) {
                Log.d(TAG, "[License] gate_allowed: PREMIUM/PRO user")
                return GateDecision.AllowLifetime
            } else {
                Log.w(TAG, "[License] gate_blocked: PREMIUM/PRO inactive (status=${license.status})")
                return GateDecision.BlockLicenseInactive("MAX Premium License is not active (${license.status}).")
            }
        }

        // 3. Expired Trial check: only block if explicitly expired plan or active trial has elapsed
        if (license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))) {
            Log.w(TAG, "[License] gate_blocked: TRIAL EXPIRED")
            return GateDecision.BlockTrialExpired
        }

        // 4. Active Trial or First-Time Sync: Consume 1 message atomically
        if (license.plan == LicensePlan.TRIAL || license.plan == LicensePlan.FREE) {
            val result = licenseManager.consumeDailyMessage()
            if (result.isSuccess) {
                val (newCount, allowed) = result.getOrNull()!!
                return if (allowed) {
                    val remaining = (DEFAULT_DAILY_LIMIT - newCount).coerceAtLeast(0L)
                    Log.d(TAG, "[License] gate_allowed: TRIAL message count=$newCount, remaining=$remaining")
                    GateDecision.AllowTrial(newCount, remaining)
                } else {
                    Log.w(TAG, "[License] gate_blocked: DAILY LIMIT REACHED ($newCount/$DEFAULT_DAILY_LIMIT)")
                    GateDecision.BlockDailyLimitReached(newCount)
                }
            } else {
                val err = result.exceptionOrNull()
                if (err?.message == "TRIAL_EXPIRED") {
                    return GateDecision.BlockTrialExpired
                }
                Log.w(TAG, "[License] gate_check fallback warning: ${err?.message}")
                // Graceful check against current local count if temporary network hiccup
                val currentLocalCount = licenseManager.dailyUsageCount.value
                return if (currentLocalCount < DEFAULT_DAILY_LIMIT) {
                    GateDecision.AllowTrial(currentLocalCount + 1L, DEFAULT_DAILY_LIMIT - (currentLocalCount + 1L))
                } else {
                    GateDecision.BlockDailyLimitReached(currentLocalCount)
                }
            }
        }

        return GateDecision.BlockError("Access denied: Invalid license state.")
    }

    /**
     * Non-suspending pre-check before starting microphone voice session.
     */
    fun canStartVoiceSession(): GateDecision {
        val license = licenseManager.currentLicense.value
        val now = System.currentTimeMillis()

        if (license.status == LicenseStatus.REVOKED) {
            return GateDecision.BlockLicenseRevoked
        }

        if (license.plan == LicensePlan.LIFETIME) {
            return if (license.status == LicenseStatus.ACTIVE) {
                GateDecision.AllowLifetime
            } else {
                GateDecision.BlockLicenseInactive("MAX Lifetime License is not active (${license.status}).")
            }
        }

        if (license.plan == LicensePlan.PREMIUM || license.plan == LicensePlan.PRO) {
            return if (license.status == LicenseStatus.ACTIVE && license.isEntitled(now)) {
                GateDecision.AllowLifetime
            } else {
                GateDecision.BlockLicenseInactive("MAX Premium License is not active (${license.status}).")
            }
        }

        if (license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))) {
            return GateDecision.BlockTrialExpired
        }

        val usage = licenseManager.dailyUsageCount.value
        if (usage >= DEFAULT_DAILY_LIMIT) {
            return GateDecision.BlockDailyLimitReached(usage)
        }

        return GateDecision.AllowTrial(usage, (DEFAULT_DAILY_LIMIT - usage).coerceAtLeast(0L))
    }

    companion object {
        private const val TAG = "EntitlementGate"
        const val DEFAULT_DAILY_LIMIT = 20L
    }
}
