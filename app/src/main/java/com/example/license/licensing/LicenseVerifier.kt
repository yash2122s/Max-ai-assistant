package com.example.license.licensing

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.util.Base64

interface LicenseVerifier {
    fun verify(license: SignedLicense): LicenseVerificationResult
}

/**
 * publicKeyBase64: the 32-byte Ed25519 public key, Base64-encoded and
 * hardcoded into the app (e.g. a constant, or a resource baked at build
 * time). This is the ONLY key that should ever exist inside the MAX
 * APK. The matching private key lives only in the offline license
 * generator tool and must never enter this repo.
 *
 * Deliberately pure: no storage access, no network, no side effects.
 * Bytes in, verdict out — makes it trivial to unit test with known-good
 * and tampered fixtures (see LicenseVerifierTest).
 */
class LicenseVerifierImpl(publicKeyBase64: String) : LicenseVerifier {

    private val verifier = Ed25519Verify(Base64.getDecoder().decode(publicKeyBase64))

    override fun verify(license: SignedLicense): LicenseVerificationResult {
        // Version check BEFORE signature check: an unsupported version
        // might not even canonicalize the same way, so don't try to
        // verify a signature over a format we don't understand.
        if (license.payload.version != LicenseCanonical.SUPPORTED_VERSION) {
            return LicenseVerificationResult.UnsupportedVersion
        }

        val signatureBytes = try {
            Base64.getDecoder().decode(license.signatureBase64)
        } catch (e: IllegalArgumentException) {
            return LicenseVerificationResult.Malformed
        }

        val message = LicenseCanonical.canonicalString(license.payload)
            .toByteArray(Charsets.UTF_8)

        return try {
            verifier.verify(signatureBytes, message)
            LicenseVerificationResult.Valid(license.payload)
        } catch (e: GeneralSecurityException) {
            // Tink throws on any invalid signature - wrong key, tampered
            // payload, tampered signature bytes, all land here.
            LicenseVerificationResult.InvalidSignature
        }
    }
}
