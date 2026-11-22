package com.example.automation

/**
 * Resolves the two identities that are commonly mixed by UI automation:
 * the user-facing app label (for example, "Google Maps") and the Android
 * package name (for example, "com.google.android.apps.maps").
 *
 * Package checks must be strict for known packages. A display label must not
 * be compared to a package name with a raw substring check because labels may
 * contain spaces while package names never do.
 */
object AppPackageMatcher {
    private val knownPackages = mapOf(
        "maps" to "com.google.android.apps.maps",
        "map" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "google map" to "com.google.android.apps.maps",
        "gmaps" to "com.google.android.apps.maps",
        "youtube" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "settings" to "com.android.settings"
    )

    fun canonicalPackage(appLabelOrPackage: String): String? {
        val normalized = appLabelOrPackage.trim().lowercase().replace(Regex("\\s+"), " ")
        return knownPackages[normalized]
    }

    fun matches(currentPackage: String, expectedLabelOrPackage: String): Boolean {
        val current = currentPackage.trim()
        val expected = expectedLabelOrPackage.trim()
        if (current.isBlank() || expected.isBlank()) return false

        val canonical = canonicalPackage(expected)
        if (canonical != null) {
            return current.equals(canonical, ignoreCase = true)
        }

        // Callers that already provide a package name should not receive a
        // fuzzy match to a different package.
        if (expected.contains('.')) {
            return current.equals(expected, ignoreCase = true)
        }

        val compactExpected = expected.lowercase().replace(Regex("\\s+"), "")
        return current.lowercase().contains(compactExpected)
    }
}
