package com.example.license.licensing

import kotlinx.serialization.Serializable

/**
 * The data that gets signed. Field order in this data class is NOT what
 * gets signed — see [LicenseCanonical.canonicalString] for the fixed
 * signing order. Adding a field? Bump SUPPORTED_VERSION and keep the old
 * canonicalString path alive for verifying already-issued licenses.
 */
@Serializable
data class LicensePayload(
    val licenseId: String,
    val product: String,
    val plan: LicensePlan,
    val issuedAt: String,   // ISO-8601, e.g. 2026-09-23T00:00:00Z
    val expiresAt: String,  // ISO-8601
    val version: Int
)

@Serializable
enum class LicensePlan { PREMIUM }

object LicenseCanonical {
    const val SUPPORTED_VERSION = 1

    /**
     * Fixed field order, pipe-delimited. This — not raw JSON — is what
     * gets signed. JSON key order/whitespace isn't guaranteed to match
     * between the generator and the app's parser, which would break
     * valid signatures. Never reorder or reformat this without bumping
     * `version` and keeping this exact function's old behavior around
     * for verifying licenses issued before the change.
     *
     * MUST stay byte-for-byte identical to the copy of this function in
     * the license generator tool. If they drift, every license issued
     * after the drift fails to verify.
     */
    fun canonicalString(payload: LicensePayload): String =
        listOf(
            payload.licenseId,
            payload.product,
            payload.plan.name,
            payload.issuedAt,
            payload.expiresAt,
            payload.version.toString()
        ).joinToString("|")
}
