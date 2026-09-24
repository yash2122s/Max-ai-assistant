package com.example.license.licensing

import kotlinx.serialization.Serializable

@Serializable
data class SignedLicense(
    val payload: LicensePayload,
    val signatureBase64: String
)

sealed class LicenseVerificationResult {
    data class Valid(val payload: LicensePayload) : LicenseVerificationResult()
    data object InvalidSignature : LicenseVerificationResult()
    data object UnsupportedVersion : LicenseVerificationResult()
    data object Malformed : LicenseVerificationResult()
}
