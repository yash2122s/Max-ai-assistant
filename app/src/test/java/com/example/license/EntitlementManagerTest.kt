package com.example.license

import com.example.license.entitlement.Entitlement
import com.example.license.entitlement.EntitlementManagerImpl
import com.example.license.entitlement.Feature
import com.example.license.entitlement.FeatureGate
import com.example.license.entitlement.FeatureTable
import com.example.license.licensing.LicenseCanonical
import com.example.license.licensing.LicensePayload
import com.example.license.licensing.LicensePlan
import com.example.license.licensing.LicenseVerifierImpl
import com.example.license.licensing.SignedLicense
import com.example.license.revocation.RevocationProvider
import com.example.license.storage.LicenseStateDao
import com.example.license.storage.LicenseStateEntity
import com.example.license.storage.LicenseStorage
import com.example.license.trial.TrialManagerImpl
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class EntitlementManagerTest {

    private class FakeLicenseStateDao : LicenseStateDao {
        var savedState: LicenseStateEntity? = null
        override fun getState(): LicenseStateEntity? = savedState
        override fun upsert(entity: LicenseStateEntity) {
            savedState = entity
        }
    }

    private class FakeRevocationProvider(private val revoked: Set<String> = emptySet()) : RevocationProvider {
        override fun cachedRevokedIds(): Set<String> = revoked
        override suspend fun refresh() {}
    }

    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val signer = Ed25519Sign(keyPair.privateKey)
    private val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.publicKey)
    private val verifier = LicenseVerifierImpl(publicKeyBase64)

    private fun signPayload(payload: LicensePayload): SignedLicense {
        val message = LicenseCanonical.canonicalString(payload).toByteArray()
        val signature = signer.sign(message)
        return SignedLicense(payload, Base64.getEncoder().encodeToString(signature))
    }

    @Test
    fun premium_license_grants_access_to_all_features() {
        val dao = FakeLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage)
        val revocation = FakeRevocationProvider()
        val manager = EntitlementManagerImpl(verifier, storage, trialManager, revocation)
        val gate = FeatureGate(manager)

        val validPayload = LicensePayload(
            licenseId = "MAX-2026-000001",
            product = "MAX",
            plan = LicensePlan.PREMIUM,
            issuedAt = Instant.now().minus(1, ChronoUnit.DAYS).toString(),
            expiresAt = Instant.now().plus(365, ChronoUnit.DAYS).toString(),
            version = 1
        )
        storage.setStoredLicense(signPayload(validPayload))

        val entitlement = manager.getEntitlement()
        assertTrue(entitlement is Entitlement.Premium)
        assertTrue(gate.canUse(Feature.WHATSAPP_AUTOMATION))
        assertTrue(gate.canUse(Feature.TELEGRAM_AUTOMATION))
        assertTrue(gate.canUse(Feature.ADVANCED_AUTOMATION))
    }

    @Test
    fun expired_license_denies_premium_features() {
        val dao = FakeLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage)
        val revocation = FakeRevocationProvider()
        val manager = EntitlementManagerImpl(verifier, storage, trialManager, revocation)
        val gate = FeatureGate(manager)

        val expiredPayload = LicensePayload(
            licenseId = "MAX-2026-000002",
            product = "MAX",
            plan = LicensePlan.PREMIUM,
            issuedAt = Instant.now().minus(30, ChronoUnit.DAYS).toString(),
            expiresAt = Instant.now().minus(1, ChronoUnit.DAYS).toString(),
            version = 1
        )
        storage.setStoredLicense(signPayload(expiredPayload))

        val entitlement = manager.getEntitlement()
        assertEquals(Entitlement.Expired, entitlement)
        assertFalse(gate.canUse(Feature.WHATSAPP_AUTOMATION))
    }

    @Test
    fun revoked_license_is_marked_invalid() {
        val dao = FakeLicenseStateDao()
        val storage = LicenseStorage(dao)
        val trialManager = TrialManagerImpl(storage)
        val revocation = FakeRevocationProvider(setOf("MAX-2026-REVOKED"))
        val manager = EntitlementManagerImpl(verifier, storage, trialManager, revocation)
        val gate = FeatureGate(manager)

        val payload = LicensePayload(
            licenseId = "MAX-2026-REVOKED",
            product = "MAX",
            plan = LicensePlan.PREMIUM,
            issuedAt = Instant.now().minus(1, ChronoUnit.DAYS).toString(),
            expiresAt = Instant.now().plus(365, ChronoUnit.DAYS).toString(),
            version = 1
        )
        storage.setStoredLicense(signPayload(payload))

        val entitlement = manager.getEntitlement()
        assertEquals(Entitlement.Revoked, entitlement)
        assertFalse(gate.canUse(Feature.WHATSAPP_AUTOMATION))
    }
}
