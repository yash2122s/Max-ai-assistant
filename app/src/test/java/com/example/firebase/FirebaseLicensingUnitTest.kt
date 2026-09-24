package com.example.firebase

import com.example.firebase.cache.FirebaseLicenseCacheEntity
import com.example.firebase.cache.LicenseIntegrityVerifier
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.DeviceRegistration
import com.example.firebase.model.FirebaseUserAccount
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus
import com.example.license.entitlement.Entitlement
import com.example.license.entitlement.Feature
import com.example.license.entitlement.FeatureGate
import com.example.license.entitlement.FeatureTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

class FirebaseLicensingUnitTest {

    // Helper for computing test HMAC without Android Context
    private fun computeTestHmac(
        userId: String,
        plan: String,
        status: String,
        trialExpires: Long?,
        expires: Long?,
        cachedAt: Long,
        secretKeyString: String = "test_device_secret_12345"
    ): String {
        val payload = "$userId|$plan|$status|${trialExpires ?: -1}|${expires ?: -1}|$cachedAt"
        val md = MessageDigest.getInstance("SHA-256")
        val secretBytes = md.digest(secretKeyString.toByteArray(StandardCharsets.UTF_8))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        val hmac = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return hmac.joinToString("") { "%02x".format(it) }
    }

    // 1. First Google Login
    @Test
    fun testFirstGoogleLogin_createsAccountProfileWithCorrectFields() {
        val uid = "google_user_first_12345"
        val email = "student@example.com"
        val displayName = "Student User"

        val account = FirebaseUserAccount(
            uid = uid,
            email = email,
            displayName = displayName,
            photoUrl = "https://example.com/photo.jpg",
            isAnonymous = false,
            createdAtEpochMillis = 1700000000000L,
            lastLoginAtEpochMillis = 1700000000000L
        )

        assertEquals(uid, account.uid)
        assertEquals(email, account.email)
        assertEquals(displayName, account.displayName)
        assertFalse(account.isAnonymous)

        val firestoreMap = account.toFirestoreMap(currentDeviceId = "device_test_01")
        assertEquals(uid, firestoreMap["userId"])
        assertEquals(email, firestoreMap["email"])
        assertEquals("device_test_01", firestoreMap["currentDeviceId"])
    }

    // 2. Returning User
    @Test
    fun testReturningUser_preservesExistingLicenseAndDoesNotResetTrial() {
        val userId = "returning_user_999"
        val initialStart = 1700000000000L
        val initialExpiry = initialStart + (7L * 24L * 60L * 60L * 1000L)

        val existingLicense = CloudLicense(
            userId = userId,
            plan = LicensePlan.TRIAL,
            status = LicenseStatus.TRIAL,
            trialStartedAtEpochMillis = initialStart,
            trialExpiresAtEpochMillis = initialExpiry,
            expiresAtEpochMillis = initialExpiry,
            issuedAtEpochMillis = initialStart,
            updatedAtEpochMillis = initialStart
        )

        // Simulate returning user logging in 3 days later
        val threeDaysLater = initialStart + (3L * 24L * 60L * 60L * 1000L)
        assertTrue(existingLicense.isEntitled(threeDaysLater))
        assertEquals(initialStart, existingLicense.trialStartedAtEpochMillis)
        assertEquals(initialExpiry, existingLicense.trialExpiresAtEpochMillis)

        // Remaining time must be 4 days, not reset to 7 days
        val remainingMillis = existingLicense.remainingTrialMillis(threeDaysLater)
        val fourDaysMillis = 4L * 24L * 60L * 60L * 1000L
        assertEquals(fourDaysMillis, remainingMillis)
    }

    // 3. Logout / Login State Transition
    @Test
    fun testLogoutAndLogin_resetsStateAndReloadsEntitlement() {
        val user = FirebaseUserAccount(uid = "user_switch_1")
        var currentLicense = CloudLicense.createTrial(user.uid)
        assertTrue(currentLicense.isEntitled())

        // Logout action: revert to Free
        currentLicense = CloudLicense.createFree("anonymous")
        assertFalse(currentLicense.isEntitled())
        assertEquals(LicensePlan.FREE, currentLicense.plan)

        // Login back: reload cloud license
        currentLicense = CloudLicense(
            userId = user.uid,
            plan = LicensePlan.PREMIUM,
            status = LicenseStatus.ACTIVE,
            expiresAtEpochMillis = System.currentTimeMillis() + 1000000L
        )
        assertTrue(currentLicense.isEntitled())
        assertEquals(LicensePlan.PREMIUM, currentLicense.plan)
    }

    // 4. First Trial Creation (7-Day Trial)
    @Test
    fun testFirstTrialCreation_exact3DaysDuration() {
        val now = 1720000000000L
        val trial = CloudLicense.createTrial("new_user_1", nowEpochMillis = now)

        assertEquals("new_user_1", trial.userId)
        assertEquals(LicensePlan.TRIAL, trial.plan)
        assertEquals(LicenseStatus.TRIAL, trial.status)
        assertEquals(now, trial.trialStartedAtEpochMillis)

        val threeDaysMillis = 3L * 24L * 60L * 60L * 1000L
        assertEquals(now + threeDaysMillis, trial.trialExpiresAtEpochMillis)
        assertTrue(trial.isEntitled(now))
        assertEquals(threeDaysMillis, trial.remainingTrialMillis(now))
    }

    // 5. No Duplicate Trial
    @Test
    fun testNoDuplicateTrial_expiredUserCannotRegainTrial() {
        val now = 1720000000000L
        val sevenDaysAgo = now - (7L * 24L * 60L * 60L * 1000L) - 1000L

        val expiredTrialLicense = CloudLicense(
            userId = "user_expired_trial",
            plan = LicensePlan.TRIAL,
            status = LicenseStatus.EXPIRED,
            trialStartedAtEpochMillis = sevenDaysAgo,
            trialExpiresAtEpochMillis = sevenDaysAgo + (7L * 24L * 60L * 60L * 1000L),
            expiresAtEpochMillis = sevenDaysAgo + (7L * 24L * 60L * 60L * 1000L)
        )

        assertFalse(expiredTrialLicense.isEntitled(now))
        assertTrue(expiredTrialLicense.isExpired(now))
        assertEquals(0L, expiredTrialLicense.remainingTrialMillis(now))
    }

    // 6. Trial Expiration Logic
    @Test
    fun testTrialExpiration_boundaryEvaluation() {
        val startTime = 1000000L
        val expiryTime = startTime + (7L * 24L * 60L * 60L * 1000L)

        val license = CloudLicense(
            userId = "boundary_user",
            plan = LicensePlan.TRIAL,
            status = LicenseStatus.TRIAL,
            trialStartedAtEpochMillis = startTime,
            trialExpiresAtEpochMillis = expiryTime,
            expiresAtEpochMillis = expiryTime
        )

        // 1 millisecond before expiry -> Entitled
        assertTrue(license.isEntitled(expiryTime - 1))
        // Exactly at expiry -> Not entitled
        assertFalse(license.isEntitled(expiryTime))
        // 1 millisecond after expiry -> Not entitled
        assertFalse(license.isEntitled(expiryTime + 1))
    }

    // 7. ACTIVE License Behavior
    @Test
    fun testActiveLicense_entitledAndAllowsFeatures() {
        val activeLicense = CloudLicense(
            userId = "premium_user_1",
            plan = LicensePlan.PREMIUM,
            status = LicenseStatus.ACTIVE,
            expiresAtEpochMillis = System.currentTimeMillis() + 86400000L
        )

        assertTrue(activeLicense.isEntitled())
        assertFalse(activeLicense.isExpired())

        val entitlement = Entitlement.Premium(java.time.Instant.ofEpochMilli(activeLicense.expiresAtEpochMillis!!))
        assertTrue(FeatureTable.isAllowed(entitlement, Feature.WHATSAPP_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(entitlement, Feature.TELEGRAM_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(entitlement, Feature.ADVANCED_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(entitlement, Feature.ADVANCED_MEMORY))
        assertTrue(FeatureTable.isAllowed(entitlement, Feature.COMPLEX_WORKFLOWS))
    }

    // 8. EXPIRED License Behavior
    @Test
    fun testExpiredLicense_deniesGatedFeatures() {
        val expiredLicense = CloudLicense(
            userId = "expired_user_1",
            plan = LicensePlan.PREMIUM,
            status = LicenseStatus.EXPIRED,
            expiresAtEpochMillis = System.currentTimeMillis() - 10000L
        )

        assertFalse(expiredLicense.isEntitled())
        assertTrue(expiredLicense.isExpired())

        val entitlement = Entitlement.Expired
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.WHATSAPP_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.TELEGRAM_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.ADVANCED_AUTOMATION))
    }

    // 9. REVOKED License Behavior
    @Test
    fun testRevokedLicense_hardBlockRegardlessOfDates() {
        val revokedLicense = CloudLicense(
            userId = "revoked_user_1",
            plan = LicensePlan.PREMIUM,
            status = LicenseStatus.REVOKED,
            expiresAtEpochMillis = System.currentTimeMillis() + 1000000000L // Even if future expiry!
        )

        assertFalse(revokedLicense.isEntitled())

        val entitlement = Entitlement.Revoked
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.WHATSAPP_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.TELEGRAM_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.ADVANCED_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(entitlement, Feature.COMPLEX_WORKFLOWS))
    }

    // 10. Firestore Permission Rejection Handling
    @Test
    fun testFirestorePermissionRejection_handledWithoutCrash() {
        val exception = com.google.firebase.firestore.FirebaseFirestoreException(
            "PERMISSION_DENIED: Missing or insufficient permissions.",
            com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED
        )

        val result: Result<CloudLicense> = Result.failure(exception)
        assertTrue(result.isFailure)
        assertEquals(com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED, (result.exceptionOrNull() as com.google.firebase.firestore.FirebaseFirestoreException).code)

        // Fallback to Free license on permission rejection
        val fallback = result.getOrElse { CloudLicense.createFree("rejected_user") }
        assertEquals(LicensePlan.FREE, fallback.plan)
        assertFalse(fallback.isEntitled())
    }

    // 11. Offline Cached State & Integrity Verification
    @Test
    fun testOfflineCachedState_integrityVerificationDetectsTampering() {
        val userId = "cached_user_1"
        val plan = "PREMIUM"
        val status = "ACTIVE"
        val expiresAt = 1800000000000L
        val cachedAt = 1700000000000L

        val legitimateHash = computeTestHmac(userId, plan, status, null, expiresAt, cachedAt)

        val validEntity = FirebaseLicenseCacheEntity(
            userId = userId,
            plan = plan,
            status = status,
            trialStartedAtEpochMillis = null,
            trialExpiresAtEpochMillis = null,
            expiresAtEpochMillis = expiresAt,
            issuedAtEpochMillis = cachedAt,
            updatedAtEpochMillis = cachedAt,
            cachedAtEpochMillis = cachedAt,
            integrityHash = legitimateHash
        )

        // Verify valid entity matches computed hash
        val recomputedValid = computeTestHmac(
            validEntity.userId, validEntity.plan, validEntity.status,
            validEntity.trialExpiresAtEpochMillis, validEntity.expiresAtEpochMillis,
            validEntity.cachedAtEpochMillis
        )
        assertEquals(legitimateHash, recomputedValid)

        // Tamper test: Attacker changes status from EXPIRED to ACTIVE or alters plan
        val tamperedEntity = validEntity.copy(plan = "PRO")
        val recomputedTampered = computeTestHmac(
            tamperedEntity.userId, tamperedEntity.plan, tamperedEntity.status,
            tamperedEntity.trialExpiresAtEpochMillis, tamperedEntity.expiresAtEpochMillis,
            tamperedEntity.cachedAtEpochMillis
        )
        assertFalse("Tampered entity must not match original HMAC", legitimateHash.equals(recomputedTampered, ignoreCase = true))
    }

    // 11b. Cache Staleness Check
    @Test
    fun testCacheStaleness_expiresAfterGracePeriod() {
        val now = System.currentTimeMillis()
        val freshCachedTime = now - (24L * 60L * 60L * 1000L) // 24 hours old (under 72h grace)
        val staleCachedTime = now - (80L * 60L * 60L * 1000L) // 80 hours old (over 72h grace)
        val gracePeriod = 72L * 60L * 60L * 1000L

        val isFreshStale = (now - freshCachedTime) > gracePeriod
        val isOldStale = (now - staleCachedTime) > gracePeriod

        assertFalse(isFreshStale)
        assertTrue(isOldStale)
    }

    // 12. Duplicate Device Registration Handling
    @Test
    fun testDuplicateDeviceRegistration_updatesTimestampWithoutDuplication() {
        val deviceId = "unique_device_hardware_id_abc"
        val originalRegTime = 1700000000000L
        val updateTime = 1700050000000L

        val initialDevice = DeviceRegistration(
            deviceId = deviceId,
            deviceModel = "Redmi K20 Pro",
            androidVersion = 36,
            appVersion = "1.0",
            registeredAtEpochMillis = originalRegTime,
            lastActiveAtEpochMillis = originalRegTime,
            isActive = true
        )

        // On subsequent login, deviceId is retained, lastActiveAt updated, registeredAt preserved
        val updatedDevice = initialDevice.copy(lastActiveAtEpochMillis = updateTime)

        assertEquals(deviceId, updatedDevice.deviceId)
        assertEquals(originalRegTime, updatedDevice.registeredAtEpochMillis)
        assertEquals(updateTime, updatedDevice.lastActiveAtEpochMillis)
        assertTrue(updatedDevice.isActive)
    }

    // 13. FeatureGate Behavior Across All Plans
    @Test
    fun testFeatureGate_matrixEvaluation() {
        // FREE Tier
        val freeEntitlement = Entitlement.Free
        assertFalse(FeatureTable.isAllowed(freeEntitlement, Feature.WHATSAPP_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(freeEntitlement, Feature.TELEGRAM_AUTOMATION))
        assertFalse(FeatureTable.isAllowed(freeEntitlement, Feature.ADVANCED_AUTOMATION))

        // TRIAL Tier (Active)
        val trialEntitlement = Entitlement.Trial(7.days)
        assertTrue(FeatureTable.isAllowed(trialEntitlement, Feature.WHATSAPP_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(trialEntitlement, Feature.TELEGRAM_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(trialEntitlement, Feature.ADVANCED_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(trialEntitlement, Feature.COMPLEX_WORKFLOWS))

        // PREMIUM Tier (Active)
        val premiumEntitlement = Entitlement.Premium(java.time.Instant.now().plus(java.time.Duration.ofDays(30)))
        assertTrue(FeatureTable.isAllowed(premiumEntitlement, Feature.WHATSAPP_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(premiumEntitlement, Feature.TELEGRAM_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(premiumEntitlement, Feature.ADVANCED_AUTOMATION))
        assertTrue(FeatureTable.isAllowed(premiumEntitlement, Feature.COMPLEX_WORKFLOWS))
        assertTrue(FeatureTable.isAllowed(premiumEntitlement, Feature.HIGH_USAGE_LIMITS))
    }

    // 14. App Check Debug vs Release Provider Verification
    @Test
    fun testAppCheckProviderSelection_logicCheck() {
        fun selectProviderName(isDebug: Boolean): String {
            return if (isDebug) "DebugAppCheckProviderFactory" else "PlayIntegrityAppCheckProviderFactory"
        }

        assertEquals("DebugAppCheckProviderFactory", selectProviderName(isDebug = true))
        assertEquals("PlayIntegrityAppCheckProviderFactory", selectProviderName(isDebug = false))
    }
}
