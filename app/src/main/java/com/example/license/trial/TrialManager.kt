package com.example.license.trial

import android.os.SystemClock
import com.example.license.entitlement.Entitlement
import com.example.license.storage.LicenseStorage
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

interface TrialManager {
    fun getTrialState(): Entitlement
}

/**
 * Offline trial tracking is best-effort, not tamper-proof — that's a
 * documented limitation, not a bug to chase later. Uninstalling and
 * reinstalling the app resets the trial; there is no way to prevent
 * that without an identity that survives uninstall (a Play Store
 * account signal, or a backend account). Don't oversell this to users.
 */
class TrialManagerImpl(
    private val storage: LicenseStorage,
    private val trialLength: Duration = TRIAL_LENGTH,
    private val nowProvider: () -> Instant = { Instant.now() },
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) : TrialManager {

    override fun getTrialState(): Entitlement {
        val nowElapsedRealtime = elapsedRealtimeProvider()
        val nowWallClock = nowProvider()

        val existingWallClock = storage.getTrialStartWallClock()
        val existingElapsed = storage.getTrialStartElapsedRealtime()

        val wallClockStart = if (existingWallClock == null) {
            storage.initTrialStart(nowElapsedRealtime, nowWallClock)
            nowWallClock
        } else {
            if (existingElapsed == null) {
                storage.initTrialStart(nowElapsedRealtime, existingWallClock)
            }
            existingWallClock
        }

        val wallClockDeltaMillis = nowWallClock.toEpochMilli() - wallClockStart.toEpochMilli()
        val lastSeenWallClock = storage.getLastSeenWallClock()

        // Anti-tampering check:
        // 1. Clock moved to before trial start timestamp
        // 2. Clock rolled back behind last observed runtime checkpoint
        val isClockRolledBack = wallClockDeltaMillis < 0 ||
                (lastSeenWallClock != null && nowWallClock.isBefore(lastSeenWallClock))

        if (isClockRolledBack) {
            return Entitlement.Free
        }

        // Monotonic progression: Record current wall clock as high-water mark
        storage.recordLastSeenWallClock(nowWallClock)

        val elapsedSinceStart = wallClockDeltaMillis.milliseconds
        val remaining = (trialLength - elapsedSinceStart).coerceAtLeast(Duration.ZERO)

        return if (remaining > Duration.ZERO) Entitlement.Trial(remaining) else Entitlement.Free
    }

    companion object {
        val TRIAL_LENGTH = 7.days
    }
}
