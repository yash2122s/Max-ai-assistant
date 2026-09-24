package com.example.license.entitlement

/**
 * Every premium-gated call site goes through this. Never write
 * `if (isPremium) { ... }` inline at a feature call site — that's how
 * you end up with the entitlement check scattered across the codebase
 * and impossible to audit or change centrally.
 */
class FeatureGate(private val entitlementManager: EntitlementManager) {

    fun canUse(feature: Feature): Boolean =
        FeatureTable.isAllowed(entitlementManager.getEntitlement(), feature)

    inline fun <T> withFeature(feature: Feature, onAllowed: () -> T, onDenied: () -> T): T =
        if (canUse(feature)) onAllowed() else onDenied()
}
