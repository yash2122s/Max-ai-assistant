package com.example.license

import com.example.license.licensing.LicenseCanonical
import com.example.license.licensing.LicensePayload
import com.example.license.licensing.LicensePlan
import com.example.license.licensing.LicenseVerificationResult
import com.example.license.licensing.LicenseVerifierImpl
import com.example.license.licensing.SignedLicense
import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LicenseVerifierTest {

    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val signer = Ed25519Sign(keyPair.privateKey)
    private val verifier = LicenseVerifierImpl(Base64.getEncoder().encodeToString(keyPair.publicKey))

    private fun makeSignedLicense(
        version: Int = LicenseCanonical.SUPPORTED_VERSION,
        licenseId: String = "MAX-2026-000001",
        expiresAt: String = "2027-09-23T00:00:00Z",
        signWith: Ed25519Sign = signer,
    ): SignedLicense {
        val payload = LicensePayload(
            licenseId = licenseId,
            product = "MAX",
            plan = LicensePlan.PREMIUM,
            issuedAt = "2026-09-23T00:00:00Z",
            expiresAt = expiresAt,
            version = version,
        )
        val message = LicenseCanonical.canonicalString(payload).toByteArray()
        val signature = signWith.sign(message)
        return SignedLicense(payload, Base64.getEncoder().encodeToString(signature))
    }

    @Test
    fun valid_license_verifies_successfully() {
        val result = verifier.verify(makeSignedLicense())
        assertTrue(result is LicenseVerificationResult.Valid)
    }

    @Test
    fun tampered_payload_fails_verification() {
        val license = makeSignedLicense()
        val tampered = license.copy(payload = license.payload.copy(expiresAt = "2099-01-01T00:00:00Z"))
        assertEquals(LicenseVerificationResult.InvalidSignature, verifier.verify(tampered))
    }

    @Test
    fun tampered_signature_bytes_fail_verification() {
        val license = makeSignedLicense()
        val tampered = license.copy(signatureBase64 = license.signatureBase64.dropLast(4) + "AAAA")
        assertEquals(LicenseVerificationResult.InvalidSignature, verifier.verify(tampered))
    }

    @Test
    fun unsupported_version_is_rejected_before_signature_is_even_checked() {
        val license = makeSignedLicense(version = 99)
        assertEquals(LicenseVerificationResult.UnsupportedVersion, verifier.verify(license))
    }

    @Test
    fun signature_produced_by_a_different_private_key_fails() {
        val attackerKeyPair = Ed25519Sign.KeyPair.newKeyPair()
        val attackerSigner = Ed25519Sign(attackerKeyPair.privateKey)
        val license = makeSignedLicense(licenseId = "MAX-2026-000099", signWith = attackerSigner)
        assertEquals(LicenseVerificationResult.InvalidSignature, verifier.verify(license))
    }

    @Test
    fun real_generated_license_verifies_with_production_public_key() {
        val realLicenseJson = """
            {
                "payload": {
                    "licenseId": "MAX-2026-000001",
                    "product": "MAX",
                    "plan": "PREMIUM",
                    "issuedAt": "2026-09-22T19:07:25.856910500Z",
                    "expiresAt": "2027-09-22T19:07:25.856910500Z",
                    "version": 1
                },
                "signatureBase64": "cI5lfG/Ldw8XgIVknjT8yismVC+rC+VK9w+dsQ3Q9ujHy9mjuPnZCFa12sIPkkWizv800vcu5rrmiHxQTOFvAg=="
            }
        """.trimIndent()

        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val signedLicense = json.decodeFromString(SignedLicense.serializer(), realLicenseJson)
        val prodVerifier = LicenseVerifierImpl(LicenseConstants.MAX_PUBLIC_KEY)
        val result = prodVerifier.verify(signedLicense)

        assertTrue("Real license should verify successfully with production public key", result is LicenseVerificationResult.Valid)
        val validPayload = (result as LicenseVerificationResult.Valid).payload
        assertEquals("MAX-2026-000001", validPayload.licenseId)
        assertEquals("MAX", validPayload.product)
        assertEquals(LicensePlan.PREMIUM, validPayload.plan)
        assertEquals(1, validPayload.version)
    }

    @Test
    fun canonical_string_matches_generator_format_byte_for_byte() {
        val payload = LicensePayload(
            licenseId = "MAX-2026-000001",
            product = "MAX",
            plan = LicensePlan.PREMIUM,
            issuedAt = "2026-09-22T19:07:25.856910500Z",
            expiresAt = "2027-09-22T19:07:25.856910500Z",
            version = 1
        )
        val appCanonical = LicenseCanonical.canonicalString(payload)
        val expected = "MAX-2026-000001|MAX|PREMIUM|2026-09-22T19:07:25.856910500Z|2027-09-22T19:07:25.856910500Z|1"

        assertEquals(expected, appCanonical)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size, appCanonical.toByteArray(Charsets.UTF_8).size)
    }
}
