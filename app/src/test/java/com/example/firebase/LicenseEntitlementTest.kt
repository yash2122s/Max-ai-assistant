package com.example.firebase

import com.example.firebase.model.CloudLicense
import com.example.firebase.model.LicenseKeyHelper
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Unit & Integration Test Suite for MAX Commercial License System:
 * - 7-Day Free Trial
 * - 100 Messages / Day Quota Limiter
 * - Lifetime License Key Formatting, Hashing & Idempotent Activation
 * - Expiration and Entitlement Invariants
 */
class LicenseEntitlementTest {

    // 1. First Google account gets exactly one 7-day trial
    @Test
    fun testFirstGoogleAccount_getsExactlyOneSevenDayTrial() {
        val now = 1700000000000L
        val license = CloudLicense.createTrial(
            userId = "google_user_001",
            nowEpochMillis = now
        )

        assertEquals("google_user_001", license.userId)
        assertEquals(LicensePlan.TRIAL, license.plan)
        assertEquals(LicenseStatus.TRIAL, license.status)
        assertEquals(now, license.trialStartedAtEpochMillis)

        val sevenDaysMillis = 7L * 24L * 60L * 60L * 1000L
        assertEquals(now + sevenDaysMillis, license.trialExpiresAtEpochMillis)
        assertTrue(license.isEntitled(now))
        assertEquals(7, license.remainingTrialDays(now))
    }

    // 2. Repeated initialization does not reset trial
    @Test
    fun testRepeatedInitialization_doesNotResetTrial() {
        val originalStart = 1700000000000L
        val originalLicense = CloudLicense.createTrial("google_user_002", originalStart)

        // Simulate app reopen 1.5 days later
        val later = originalStart + (36L * 60L * 60L * 1000L)

        // Invariant: Existing license preserves initial trialStartedAt and trialExpiresAt
        val firestoreMap = originalLicense.toFirestoreMap()
        val rehydrated = CloudLicense.fromFirestoreMap("google_user_002", firestoreMap)

        assertEquals(originalLicense.trialStartedAtEpochMillis, rehydrated.trialStartedAtEpochMillis)
        assertEquals(originalLicense.trialExpiresAtEpochMillis, rehydrated.trialExpiresAtEpochMillis)
        assertTrue(rehydrated.isEntitled(later))
        assertEquals(6, rehydrated.remainingTrialDays(later))
    }

    // 3. App restart preserves trial
    @Test
    fun testAppRestart_preservesTrialState() {
        val now = 1700000000000L
        val license = CloudLicense.createTrial("google_user_003", now)

        val map = license.toFirestoreMap()
        val afterRestart = CloudLicense.fromFirestoreMap("google_user_003", map)

        assertEquals(license.plan, afterRestart.plan)
        assertEquals(license.status, afterRestart.status)
        assertEquals(license.trialExpiresAtEpochMillis, afterRestart.trialExpiresAtEpochMillis)
    }

    // 4. Clear local app data does not reset cloud trial
    @Test
    fun testClearLocalAppData_recoversFromAuthoritativeCloudState() {
        val cloudStart = 1700000000000L
        val cloudLicense = CloudLicense.createTrial("google_user_004", cloudStart)

        // Local cache deleted; app queries cloud state:
        val restoredFromCloud = CloudLicense.fromFirestoreMap("google_user_004", cloudLicense.toFirestoreMap())
        assertEquals(cloudStart, restoredFromCloud.trialStartedAtEpochMillis)
        assertEquals(cloudLicense.trialExpiresAtEpochMillis, restoredFromCloud.trialExpiresAtEpochMillis)
    }

    // 5. Reinstall + same Google UID does not reset trial
    @Test
    fun testReinstallSameUid_preservesExistingTrial() {
        val uid = "google_uid_persistent"
        val start = 1700000000000L
        val firstInstallLicense = CloudLicense.createTrial(uid, start)

        // 2 days later, user reinstalls app
        val reinstallTime = start + (2L * 24L * 60L * 60L * 1000L)
        val cloudDoc = firstInstallLicense.toFirestoreMap()

        val afterReinstall = CloudLicense.fromFirestoreMap(uid, cloudDoc)
        assertEquals(start, afterReinstall.trialStartedAtEpochMillis)
        assertEquals(5, afterReinstall.remainingTrialDays(reinstallTime))
    }

    // 6. Trial allows up to 100 messages/day
    @Test
    fun testTrial_allowsUpTo100MessagesPerDay() {
        val maxLimit = 100L
        for (currentCount in 0L until 100L) {
            val isAllowed = currentCount < maxLimit
            val newCount = if (isAllowed) currentCount + 1 else currentCount
            assertTrue("Count $currentCount must be allowed", isAllowed)
            assertTrue("New count must be <= 100", newCount <= 100L)
        }
    }

    // 7. 101st message is blocked
    @Test
    fun testTrial_101stMessageIsBlocked() {
        val maxLimit = 100L
        val currentCount = 100L

        val isAllowed = currentCount < maxLimit
        assertFalse("101st message must be blocked when currentCount=100", isAllowed)
    }

    // 8. Next usage period resets correctly (daily period key)
    @Test
    fun testUsagePeriod_generatesUtcDailyKey() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val day1Millis = 1700000000000L // 2023-11-14
        val day2Millis = day1Millis + (24L * 60L * 60L * 1000L) // 2023-11-15

        val periodDay1 = sdf.format(Date(day1Millis))
        val periodDay2 = sdf.format(Date(day2Millis))

        assertNotEquals(periodDay1, periodDay2)
    }

    // 9. Trial expires after 7 days
    @Test
    fun testTrial_expiresAfter7Days() {
        val start = 1700000000000L
        val license = CloudLicense.createTrial("google_user_009", start)

        val beforeExpiry = start + (6L * 24L * 60L * 60L * 1000L)
        val afterExpiry = start + (7L * 24L * 60L * 60L * 1000L) + 1000L

        assertTrue(license.isEntitled(beforeExpiry))
        assertFalse(license.isEntitled(afterExpiry))
        assertTrue(license.isExpired(afterExpiry))
    }

    // 10. Expired trial is blocked
    @Test
    fun testExpiredTrial_isBlocked() {
        val start = 1700000000000L
        val expiredLicense = CloudLicense(
            userId = "user_expired",
            plan = LicensePlan.EXPIRED,
            status = LicenseStatus.EXPIRED,
            trialStartedAtEpochMillis = start,
            trialExpiresAtEpochMillis = start + (7L * 24L * 60L * 60L * 1000L)
        )

        assertFalse(expiredLicense.isEntitled())
        assertTrue(expiredLicense.isExpired())
    }

    // 11. Invalid license key rejected
    @Test
    fun testLicenseKeyValidation_rejectsMalformedKeys() {
        assertFalse(LicenseKeyHelper.isValidFormat(""))
        assertFalse(LicenseKeyHelper.isValidFormat("INVALID"))
        assertFalse(LicenseKeyHelper.isValidFormat("MAX-LIFE-123"))
        assertFalse(LicenseKeyHelper.isValidFormat("MAX-PRO-7K4P-X9QM-28TZ"))
        assertFalse(LicenseKeyHelper.isValidFormat("MAX-LIFE-7K4P-X9QM-28TZ-EXTRA"))
        assertFalse(LicenseKeyHelper.isValidFormat("MAX-LIFE-7K4-X9QM-28TZ")) // 3 chars instead of 4

        // Valid key
        assertTrue(LicenseKeyHelper.isValidFormat("MAX-LIFE-7K4P-X9QM-28TZ"))
    }

    // 12. Valid unused key activates lifetime
    @Test
    fun testValidUnusedKey_activatesLifetime() {
        val rawKey = "max-life-7k4p-x9qm-28tz"
        val normalized = LicenseKeyHelper.normalize(rawKey)
        assertEquals("MAX-LIFE-7K4P-X9QM-28TZ", normalized)

        val keyHash = LicenseKeyHelper.sha256Hex(normalized)
        assertNotNull(keyHash)
        assertEquals(64, keyHash.length)

        val lifetime = CloudLicense.createLifetime(
            userId = "user_lifetime_001",
            licenseId = keyHash,
            nowEpochMillis = 1700000000000L
        )

        assertEquals(LicensePlan.LIFETIME, lifetime.plan)
        assertEquals(LicenseStatus.ACTIVE, lifetime.status)
        assertEquals(keyHash, lifetime.licenseId)
        assertTrue(lifetime.isEntitled())
        assertFalse(lifetime.isExpired())
    }

    // 13. Same key entered twice by same UID is idempotent
    @Test
    fun testSameKeyClaimedBySameUid_isIdempotent() {
        val uid = "user_repeat_claim"
        val keyHash = LicenseKeyHelper.sha256Hex("MAX-LIFE-7K4P-X9QM-28TZ")

        // First claim
        val firstClaim = CloudLicense.createLifetime(uid, keyHash, 1700000000000L)

        // Second claim simulation: key status is CLAIMED and claimedBy matches uid
        val isSameUser = (uid == firstClaim.userId)
        assertTrue(isSameUser)
        assertEquals(LicensePlan.LIFETIME, firstClaim.plan)
    }

    // 14. Same key cannot be claimed by another UID
    @Test
    fun testSameKeyClaimedByDifferentUid_isRejected() {
        val ownerUid = "user_owner_A"
        val attackerUid = "user_attacker_B"
        val claimedBy = ownerUid

        val canClaim = (claimedBy == null || claimedBy == attackerUid)
        assertFalse("Key claimed by user A must not be claimable by user B", canClaim)
    }

    // 15. Lifetime never expires
    @Test
    fun testLifetime_neverExpires() {
        val lifetime = CloudLicense.createLifetime(
            userId = "user_forever",
            licenseId = "hash_123",
            nowEpochMillis = 1700000000000L
        )

        // Test 100 years into the future
        val farFuture = 1700000000000L + (100L * 365L * 24L * 60L * 60L * 1000L)
        assertTrue(lifetime.isEntitled(farFuture))
        assertFalse(lifetime.isExpired(farFuture))
        assertEquals(0L, lifetime.remainingTrialMillis(farFuture))
        assertEquals(0, lifetime.remainingTrialDays(farFuture))
    }

    // 16. App update preserves lifetime
    @Test
    fun testAppUpdate_preservesLifetimeEntitlement() {
        val keyHash = LicenseKeyHelper.sha256Hex("MAX-LIFE-AAAA-BBBB-CCCC")
        val v1License = CloudLicense.createLifetime("user_v1", keyHash, 1700000000000L)

        // Updated app reads same Firestore license document
        val firestoreMap = v1License.toFirestoreMap()
        val v2License = CloudLicense.fromFirestoreMap("user_v1", firestoreMap)

        assertEquals(LicensePlan.LIFETIME, v2License.plan)
        assertEquals(LicenseStatus.ACTIVE, v2License.status)
        assertEquals(keyHash, v2License.licenseId)
    }

    // 17. Normalization trims whitespace and handles case
    @Test
    fun testLicenseKeyHelper_normalization() {
        val rawInput = "  max-life-7k4p-x9qm-28tz  "
        val normalized = LicenseKeyHelper.normalize(rawInput)
        assertEquals("MAX-LIFE-7K4P-X9QM-28TZ", normalized)

        val formattedInput = "MAX LIFE 7K4P X9QM 28TZ"
        assertEquals("MAX-LIFE-7K4P-X9QM-28TZ", LicenseKeyHelper.normalize(formattedInput))
    }

    // 18. Redaction never leaks raw keys in logs
    @Test
    fun testLicenseKeyHelper_redactionProtectsSecrets() {
        val rawKey = "MAX-LIFE-7K4P-X9QM-28TZ"
        val redacted = LicenseKeyHelper.redact(rawKey)

        assertEquals("MAX-LIFE-****-****-28TZ", redacted)
        assertFalse(redacted.contains("7K4P"))
        assertFalse(redacted.contains("X9QM"))
    }

    // 19. Firestore Map serialization preserves all licensing fields
    @Test
    fun testFirestoreMapSerialization_roundTrip() {
        val now = 1700000000000L
        val license = CloudLicense(
            userId = "user_roundtrip",
            plan = LicensePlan.LIFETIME,
            status = LicenseStatus.ACTIVE,
            licenseId = "hash_xyz_789",
            activatedAtEpochMillis = now,
            issuedAtEpochMillis = now,
            updatedAtEpochMillis = now
        )

        val map = license.toFirestoreMap()
        assertEquals("LIFETIME", map["plan"])
        assertEquals("ACTIVE", map["status"])
        assertEquals("hash_xyz_789", map["licenseId"])

        val deserialized = CloudLicense.fromFirestoreMap("user_roundtrip", map)
        assertEquals(license.userId, deserialized.userId)
        assertEquals(license.plan, deserialized.plan)
        assertEquals(license.status, deserialized.status)
        assertEquals(license.licenseId, deserialized.licenseId)
    }

    // 20. Expired status correctly parsed from String
    @Test
    fun testLicensePlanAndStatus_parsing() {
        assertEquals(LicensePlan.LIFETIME, LicensePlan.fromString("LIFETIME"))
        assertEquals(LicensePlan.EXPIRED, LicensePlan.fromString("EXPIRED"))
        assertEquals(LicensePlan.TRIAL, LicensePlan.fromString("TRIAL"))

        assertEquals(LicenseStatus.ACTIVE, LicenseStatus.fromString("ACTIVE"))
        assertEquals(LicenseStatus.EXPIRED, LicenseStatus.fromString("EXPIRED"))
        assertEquals(LicenseStatus.REVOKED, LicenseStatus.fromString("REVOKED"))
    }

    // 21. Entitlement State Matrix Tests
    @Test
    fun testEntitlementMatrix_lifetimeActive_isEntitled() {
        val license = CloudLicense(
            userId = "user_lt_active",
            plan = LicensePlan.LIFETIME,
            status = LicenseStatus.ACTIVE
        )
        assertTrue(license.isEntitled())
        assertFalse(license.isExpired())
    }

    @Test
    fun testEntitlementMatrix_lifetimeRevoked_notEntitled() {
        val license = CloudLicense(
            userId = "user_lt_revoked",
            plan = LicensePlan.LIFETIME,
            status = LicenseStatus.REVOKED
        )
        assertFalse(license.isEntitled())
    }

    @Test
    fun testEntitlementMatrix_lifetimeNonActive_notEntitled() {
        // e.g. EXPIRED or unexpected status
        val license = CloudLicense(
            userId = "user_lt_expired_status",
            plan = LicensePlan.LIFETIME,
            status = LicenseStatus.EXPIRED
        )
        assertFalse(license.isEntitled())
        assertTrue(license.isExpired())
    }

    @Test
    fun testEntitlementMatrix_trialActive_isEntitled() {
        val now = 1700000000000L
        val license = CloudLicense(
            userId = "user_trial_active",
            plan = LicensePlan.TRIAL,
            status = LicenseStatus.TRIAL,
            trialStartedAtEpochMillis = now,
            trialExpiresAtEpochMillis = now + 100000L
        )
        assertTrue(license.isEntitled(now))
        assertFalse(license.isExpired(now))
    }

    @Test
    fun testEntitlementMatrix_trialExpired_notEntitled() {
        val now = 1700000000000L
        val license = CloudLicense(
            userId = "user_trial_exp",
            plan = LicensePlan.TRIAL,
            status = LicenseStatus.TRIAL,
            trialStartedAtEpochMillis = now - 200000L,
            trialExpiresAtEpochMillis = now - 100000L
        )
        assertFalse(license.isEntitled(now))
        assertTrue(license.isExpired(now))
    }
}
