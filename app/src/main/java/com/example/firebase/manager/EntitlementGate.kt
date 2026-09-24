package com.example.firebase.manager

import android.content.Context
import android.util.Log
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus

sealed class GateDecision {
    data object AllowLifetime : GateDecision()
    data class AllowTrial(val messageCountToday: Long, val messagesRemaining: Long) : GateDecision()
    data class BlockDailyLimitReached(val messageCountToday: Long, val limit: Long = 10L) : GateDecision()
    data object BlockTrialExpired : GateDecision()
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

        // 1. Lifetime user: ALWAYS ALLOW
        if (license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE) {
            Log.d(TAG, "[License] gate_allowed: LIFETIME user")
            return GateDecision.AllowLifetime
        }

        // 2. Expired Trial check: only block if explicitly expired plan or active trial has elapsed
        if (license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))) {
            Log.w(TAG, "[License] gate_blocked: TRIAL EXPIRED")
            return GateDecision.BlockTrialExpired
        }

        // 3. Active Trial or First-Time Sync: Consume 1 message atomically
        if (license.plan == LicensePlan.TRIAL || license.plan == LicensePlan.FREE) {
            val result = licenseManager.consumeDailyMessage()
            if (result.isSuccess) {
                val (newCount, allowed) = result.getOrNull()!!
                return if (allowed) {
                    val remaining = (10L - newCount).coerceAtLeast(0L)
                    Log.d(TAG, "[License] gate_allowed: TRIAL message count=$newCount, remaining=$remaining")
                    GateDecision.AllowTrial(newCount, remaining)
                } else {
                    Log.w(TAG, "[License] gate_blocked: DAILY LIMIT REACHED ($newCount/10)")
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
                return if (currentLocalCount < 10L) {
                    GateDecision.AllowTrial(currentLocalCount + 1L, 10L - (currentLocalCount + 1L))
                } else {
                    GateDecision.BlockDailyLimitReached(currentLocalCount)
                }
            }
        }

        return GateDecision.BlockTrialExpired
    }

    /**
     * Non-suspending pre-check before starting microphone voice session.
     */
    fun canStartVoiceSession(): GateDecision {
        val license = licenseManager.currentLicense.value
        val now = System.currentTimeMillis()

        if (license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE) {
            return GateDecision.AllowLifetime
        }

        if (license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))) {
            return GateDecision.BlockTrialExpired
        }

        val usage = licenseManager.dailyUsageCount.value
        if (usage >= 10L) {
            return GateDecision.BlockDailyLimitReached(usage)
        }

        return GateDecision.AllowTrial(usage, (10L - usage).coerceAtLeast(0L))
    }

    companion object {
        private const val TAG = "EntitlementGate"
    }
}
