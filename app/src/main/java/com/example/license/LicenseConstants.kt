package com.example.license

object LicenseConstants {
    // The Ed25519 public key corresponding to the generator's private key.
    // This key is used on-device to cryptographically verify offline .maxlicense signatures.
    const val MAX_PUBLIC_KEY = "/fIooX0t0u1a5PYcClB1CGrKHbB8VaTr2iuMwV1vs0o="
    
    const val REVOCATION_URL = "https://raw.githubusercontent.com/you/max-revocations/main/revoked.json"
}
