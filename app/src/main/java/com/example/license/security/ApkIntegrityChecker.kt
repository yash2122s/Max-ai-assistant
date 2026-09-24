package com.example.license.security

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import java.security.MessageDigest

/**
 * Not bulletproof — a sufficiently determined attacker can patch this
 * check itself, same as any client-side check. This raises the bar
 * against casual re-signing/repackaging of the APK; it doesn't stop a
 * targeted crack. Treat it as one more speed bump, not a security
 * boundary on its own.
 *
 * Only enforce this in release builds — a debug build's signing cert
 * won't match, and you don't want to lock yourself out during dev.
 */
object ApkIntegrityChecker {

    // Computed from release keystore (max-release-key.jks):
    // SHA256: 37:58:22:23:59:0F:ED:74:A5:84:7B:97:1B:A4:6E:8D:58:F3:CD:AD:BB:27:C5:37:DE:0D:23:22:08:00:00:F1
    const val EXPECTED_SIGNING_CERT_SHA256 = "N1giI1kP7XSlhHuXG6RujVjzza27J8U33g0jIggAAPE="

    fun isSignatureValid(context: Context): Boolean {
        // If compiled in debug mode (development builds), bypass the release certificate check.
        // In release builds, BuildConfig.DEBUG is a compile-time constant (false) inlined into DEX bytecode.
        if (com.example.BuildConfig.DEBUG) return true

        return try {
            actualSigningCertHash(context) == EXPECTED_SIGNING_CERT_SHA256
        } catch (e: Exception) {
            false
        }
    }

    private fun actualSigningCertHash(context: Context): String {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
        
        val signer = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()
        } ?: throw IllegalStateException("No signing certificate found")
        
        val digest = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
