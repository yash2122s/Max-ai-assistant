package com.example.license.entitlement

import com.example.license.licensing.LicenseVerificationResult
import com.example.license.licensing.LicenseVerifier
import com.example.license.revocation.RevocationProvider
import com.example.license.storage.LicenseStorage
import com.example.license.trial.TrialManager
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface EntitlementManager {
    /** Recomputes from the signed license every call. This is the source of truth. */
    fun getEntitlement(): Entitlement

    /** In-memory only, for UI observing. NEVER persisted, NEVER trusted on its own. */
    val entitlementFlow: StateFlow<Entitlement>

    fun refresh()
}

class EntitlementManagerImpl(
    private val verifier: LicenseVerifier,
    private val storage: LicenseStorage,
    private val trialManager: TrialManager,
    private val revocationProvider: RevocationProvider,
    private val maxLicenseManagerProvider: (() -> com.example.firebase.manager.MaxLicenseManager?)? = null
) : EntitlementManager {

    private val _entitlementFlow = MutableStateFlow<Entitlement>(Entitlement.Free)
    override val entitlementFlow: StateFlow<Entitlement> = _entitlementFlow

    override fun getEntitlement(): Entitlement {
        val computed = computeEntitlement()
        _entitlementFlow.value = computed
        return computed
    }

    override fun refresh() {
        getEntitlement()
    }

    private fun computeEntitlement(): Entitlement {
        // 1. Check Firebase Cloud License first
        val firebaseLicenseManager = maxLicenseManagerProvider?.invoke()
        if (firebaseLicenseManager != null) {
            val cloudEntitlement = firebaseLicenseManager.toEntitlement()
            if (cloudEntitlement is Entitlement.Premium || cloudEntitlement is Entitlement.Trial) {
                return cloudEntitlement
            }
            if (cloudEntitlement is Entitlement.Revoked) {
                return Entitlement.Revoked
            }
        }

        // 2. Check offline Ed25519 cryptographically signed license file (.maxlicense)
        val stored = storage.getStoredLicense()
        if (stored != null) {
            return when (val result = verifier.verify(stored)) {
                is LicenseVerificationResult.Valid -> {
                    storage.recordLastSeenWallClock(Instant.now())
                    val payload = result.payload
                    when {
                        payload.licenseId in revocationProvider.cachedRevokedIds() -> Entitlement.Revoked
                        Instant.parse(payload.expiresAt).isBefore(Instant.now()) -> Entitlement.Expired
                        else -> Entitlement.Premium(Instant.parse(payload.expiresAt))
                    }
                }
                LicenseVerificationResult.InvalidSignature,
                LicenseVerificationResult.Malformed,
                LicenseVerificationResult.UnsupportedVersion -> Entitlement.Invalid
            }
        }

        // 3. If cloud license was explicitly expired, return Expired
        if (firebaseLicenseManager != null) {
            val cloudEntitlement = firebaseLicenseManager.toEntitlement()
            if (cloudEntitlement is Entitlement.Expired) {
                return Entitlement.Expired
            }
        }

        // 4. Fallback to local trial manager
        return trialManager.getTrialState()
    }
}
