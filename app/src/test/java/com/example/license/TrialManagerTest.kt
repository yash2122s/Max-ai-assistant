package com.example.license

import com.example.license.entitlement.Entitlement
import com.example.license.storage.LicenseStateDao
import com.example.license.storage.LicenseStateEntity
import com.example.license.storage.LicenseStorage
import com.example.license.trial.TrialManagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.days

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TrialManagerTest {

    private class InMemoryLicenseStateDao : LicenseStateDao {
        var entity: LicenseStateEntity? = null
        override fun getState(): LicenseStateEntity? = entity
        override fun upsert(entity: LicenseStateEntity) {
            this.entity = entity
        }
    }

    @Test
    fun fresh_install_initializes_and_grants_seven_day_trial() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        val state = trialManager.getTrialState()
        assertTrue(state is Entitlement.Trial)
        val remaining = (state as Entitlement.Trial).remaining
        assertTrue("Remaining days should be between 6 and 7 days", remaining.inWholeDays in 6..7)
        
        // Assert storage recorded both timestamps
        val entity = dao.getState()
        assertTrue(entity?.trialStartWallClockEpochMillis != null)
        assertTrue(entity?.trialStartElapsedRealtime != null)
        assertTrue(entity?.lastSeenWallClockEpochMillis != null)
    }

    @Test
    fun trial_advances_normally_over_time() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        // Seed state 4 days ago
        val fourDaysAgo = Instant.now().minus(4, ChronoUnit.DAYS)
        storage.initTrialStart(elapsedRealtime = 100_000L, wallClock = fourDaysAgo)

        val state = trialManager.getTrialState()
        assertTrue(state is Entitlement.Trial)
        val remaining = (state as Entitlement.Trial).remaining
        assertTrue("Remaining should be approximately 3 days", remaining.inWholeDays in 2..3)
    }

    @Test
    fun device_reboot_does_not_extend_or_break_trial() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        // Seed trial started 5 days ago when device uptime was 200 hours
        val fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS)
        storage.initTrialStart(elapsedRealtime = 200 * 3600 * 1000L, wallClock = fiveDaysAgo)

        // After a phone reboot, uptime is now fresh (e.g. 10 seconds = 10_000ms)
        // TrialManager must calculate remaining calendar time (~2 days) and NOT extend or overflow
        val state = trialManager.getTrialState()
        assertTrue(state is Entitlement.Trial)
        val remaining = (state as Entitlement.Trial).remaining
        assertTrue("Remaining after reboot should be ~2 days", remaining.inWholeDays in 1..2)
    }

    @Test
    fun expired_trial_after_seven_days_returns_free() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        // Seed trial started 8 days ago
        val eightDaysAgo = Instant.now().minus(8, ChronoUnit.DAYS)
        storage.initTrialStart(elapsedRealtime = 50_000L, wallClock = eightDaysAgo)

        val state = trialManager.getTrialState()
        assertEquals(Entitlement.Free, state)
    }

    @Test
    fun clock_rollback_before_trial_start_expires_trial_immediately() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        // Seed trial started in the "future" relative to current instant (clock rolled back)
        val futureStart = Instant.now().plus(2, ChronoUnit.DAYS)
        storage.initTrialStart(elapsedRealtime = 50_000L, wallClock = futureStart)

        val state = trialManager.getTrialState()
        assertEquals("Tampered clock must immediately collapse trial to Free", Entitlement.Free, state)
    }

    @Test
    fun clock_rollback_after_prior_checkpoint_expires_trial_immediately() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        // Seed trial started 5 days ago, but lastSeen was 1 day ago, and now user set clock to 3 days ago
        val fiveDaysAgo = Instant.now().minus(5, ChronoUnit.DAYS)
        storage.initTrialStart(elapsedRealtime = 50_000L, wallClock = fiveDaysAgo)
        
        // Advance lastSeen to tomorrow
        storage.recordLastSeenWallClock(Instant.now().plus(1, ChronoUnit.DAYS))

        val state = trialManager.getTrialState()
        assertEquals("Time moved backwards past checkpoint must collapse trial to Free", Entitlement.Free, state)
    }

    @Test
    fun partial_write_recovery_heals_missing_wall_clock_or_uptime() {
        val dao = InMemoryLicenseStateDao()
        // Simulate corrupted/partial write where trialStartElapsedRealtime is set, but wallClock is null
        dao.upsert(
            LicenseStateEntity(
                id = 0,
                rawSignedLicenseJson = null,
                lastSeenWallClockEpochMillis = null,
                trialStartElapsedRealtime = 50_000L,
                trialStartWallClockEpochMillis = null,
                cachedRevokedIdsJson = "[]"
            )
        )

        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage, trialLength = 7.days)

        val state = trialManager.getTrialState()
        assertTrue("Partial write must be healed without crashing", state is Entitlement.Trial)
        val healed = dao.getState()
        assertTrue(healed?.trialStartWallClockEpochMillis != null)
        assertTrue(healed?.trialStartElapsedRealtime != null)
    }

    @Test
    fun clock_rollback_between_checkpoints_collapses_trial_to_zero() {
        val dao = InMemoryLicenseStateDao()
        val storage = LicenseStorage(dao)
        val t0 = Instant.parse("2026-09-01T00:00:00Z")
        var currentMockTime = t0

        val trialManager = TrialManagerImpl(
            storage = storage,
            trialLength = 7.days,
            nowProvider = { currentMockTime }
        )

        // 1. Initial trial start at T0 (7 days remaining)
        val stateT0 = trialManager.getTrialState()
        assertTrue(stateT0 is Entitlement.Trial)
        assertEquals(7, (stateT0 as Entitlement.Trial).remaining.inWholeDays)

        // 2. Advance mock time by 5 days (T0 + 5 days) -> 2 days remaining, updates lastSeen checkpoint
        currentMockTime = t0.plus(5, ChronoUnit.DAYS)
        val stateT5 = trialManager.getTrialState()
        assertTrue(stateT5 is Entitlement.Trial)
        assertEquals(2, (stateT5 as Entitlement.Trial).remaining.inWholeDays)
        assertEquals(currentMockTime.toEpochMilli(), dao.getState()?.lastSeenWallClockEpochMillis)

        // 3. User attempts rollback: winds clock back by 2 days (to T0 + 3 days)
        // This is still after trialStart (T0), but before lastSeen checkpoint (T0 + 5 days)
        currentMockTime = t0.plus(3, ChronoUnit.DAYS)
        val stateT3RolledBack = trialManager.getTrialState()

        // 4. Assert trial immediately collapses to Free (zero remaining) rather than showing 4 days
        assertEquals("Trial must collapse to Free when rolled back behind lastSeen checkpoint", Entitlement.Free, stateT3RolledBack)
    }
}
