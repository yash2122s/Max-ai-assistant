package com.example.license.entitlement

import java.time.Instant
import kotlin.time.Duration

sealed class Entitlement {
    data object Free : Entitlement()
    data class Trial(val remaining: Duration) : Entitlement()
    data class Premium(val expiresAt: Instant) : Entitlement()
    data object Expired : Entitlement()
    data object Revoked : Entitlement()
    data object Invalid : Entitlement()
}

enum class Feature {
    ADVANCED_AUTOMATION,
    WHATSAPP_AUTOMATION,
    TELEGRAM_AUTOMATION,
    ADVANCED_MEMORY,
    COMPLEX_WORKFLOWS,
    HIGH_USAGE_LIMITS
}

/**
 * Single source of truth for which entitlements unlock which features.
 * Change limits here, not at each feature's call site.
 */
object FeatureTable {
    private val premiumOnly = setOf(
        Feature.WHATSAPP_AUTOMATION,
        Feature.TELEGRAM_AUTOMATION,
        Feature.HIGH_USAGE_LIMITS
    )
    private val trialOrPremium = premiumOnly + setOf(
        Feature.ADVANCED_AUTOMATION,
        Feature.ADVANCED_MEMORY,
        Feature.COMPLEX_WORKFLOWS
    )

    fun isAllowed(entitlement: Entitlement, feature: Feature): Boolean = when (entitlement) {
        is Entitlement.Premium -> true
        is Entitlement.Trial -> feature in trialOrPremium
        Entitlement.Free, Entitlement.Expired, Entitlement.Revoked, Entitlement.Invalid -> false
    }
}
