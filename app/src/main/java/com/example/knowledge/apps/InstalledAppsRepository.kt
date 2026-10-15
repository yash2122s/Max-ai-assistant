package com.example.knowledge.apps

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.InstalledApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

object InstalledAppsRepository {
    private const val TAG = "AppsRepository"

    @Volatile
    private var cachedApps: List<InstalledApp>? = null

    @Volatile
    var isTesting: Boolean = false

    fun clearCache() {
        cachedApps = null
    }

    fun setCachedAppsForTesting(apps: List<InstalledApp>?) {
        isTesting = true
        cachedApps = apps
    }

    private val aliases = mapOf(
        "wa" to "com.whatsapp",
        "whatsapp" to "com.whatsapp",
        "yt" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "insta" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "google map" to "com.google.android.apps.maps",
        "gmaps" to "com.google.android.apps.maps",
        "map" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "fb" to "com.facebook.katana",
        "calc" to "com.google.android.calculator",
        "calculator" to "com.google.android.calculator",
        "settings" to "com.android.settings",
        "setting" to "com.android.settings",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "notes" to "com.google.android.keep",
        "keep" to "com.google.android.keep",
        "క్యాలిక్యులేటర్" to "com.google.android.calculator",
        "క్రోమ్" to "com.android.chrome",
        "వాట్సాప్" to "com.whatsapp",
        "యూట్యూబ్" to "com.google.android.youtube",
        "మ్యాప్స్" to "com.google.android.apps.maps",
        "సెట్టింగ్స్" to "com.android.settings"
    )

    fun scanAndSaveInstalledApps(context: Context) {
        if (isTesting) return
        CoroutineScope(Dispatchers.IO).launch {
            if (isTesting) return@launch
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }

                // Query launcher activities
                val apps = pm.queryIntentActivities(intent, 0)
                val installedApps = apps.map { resolveInfo ->
                    InstalledApp(
                        packageName = resolveInfo.activityInfo.packageName,
                        appName = resolveInfo.loadLabel(pm).toString()
                    )
                }.distinctBy { it.packageName }

                // Update memory cache
                if (installedApps.isNotEmpty()) {
                    cachedApps = installedApps
                    Log.d(TAG, "Memory cache updated with ${installedApps.size} apps.")

                    // Backup to Room
                    val db = AppDatabase.getDatabase(context)
                    db.installedAppDao().deleteAllApps()
                    db.installedAppDao().insertApps(installedApps)
                    Log.d(TAG, "Room database backup updated with ${installedApps.size} apps.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning and saving apps", e)
            }
        }
    }

    fun getApps(context: Context? = null): List<InstalledApp> {
        if (isTesting) {
            return cachedApps ?: emptyList()
        }
        cachedApps?.let { if (it.isNotEmpty()) return it }
        if (context == null) return cachedApps ?: emptyList()

        val db = AppDatabase.getDatabase(context)
        val apps = runBlocking {
            try {
                withTimeoutOrNull(1500L) { db.installedAppDao().getAllApps() } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (apps.isNotEmpty()) {
            cachedApps = apps
            return apps
        }

        // Direct fallback to PackageManager if cache and database are not yet populated
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val pmApps = pm.queryIntentActivities(intent, 0).map { resolveInfo ->
                InstalledApp(
                    packageName = resolveInfo.activityInfo.packageName,
                    appName = resolveInfo.loadLabel(pm).toString()
                )
            }.distinctBy { it.packageName }
            if (pmApps.isNotEmpty()) {
                cachedApps = pmApps
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        db.installedAppDao().insertApps(pmApps)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error caching scanned apps: ${e.message}")
                    }
                }
            }
            pmApps
        } catch (e: Exception) {
            Log.w(TAG, "Error querying package manager: ${e.message}")
            emptyList()
        }
    }

    private val IGNORED_ASSISTANT_NAMES = setOf("max", "jarvis", "max ai", "assistant", "ai assistant", "max assistant")
    private val IGNORED_ASSISTANT_PACKAGES = setOf("com.maxaiagent.yash", "com.aistudio.jarvis.vqzrtp", "com.example", "com.example.max")

    fun extractAllMatchedApps(context: Context? = null, intent: String): Set<String> {
        val rawClean = intent.trim().lowercase()
        if (rawClean.isBlank()) return emptySet()

        // Strip common leading wake-words (e.g., "max, ", "hey max ", "jarvis: ")
        val cleanIntent = rawClean
            .replaceFirst(Regex("""^(?:hey\s+|hi\s+|ok\s+)?(?:max|jarvis)[,\s:]*"""), "")
            .trim()
            .ifEmpty { rawClean }

        val result = mutableSetOf<String>()

        // 1. Check alias map matches first (high precision curated aliases)
        for ((aliasKey, aliasPkg) in aliases) {
            val pattern = "\\b${Regex.escape(aliasKey)}\\b".toRegex(RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(cleanIntent) || pattern.containsMatchIn(rawClean)) {
                result.add(aliasPkg)
            }
        }

        val apps = getApps(context).filter { app ->
            val appNameLower = app.appName.lowercase().trim()
            val pkgLower = app.packageName.lowercase().trim()
            appNameLower !in IGNORED_ASSISTANT_NAMES &&
            pkgLower !in IGNORED_ASSISTANT_PACKAGES &&
            (context == null || pkgLower != context.packageName)
        }

        // 2. Match package names in intent
        for (app in apps) {
            val pkgLower = app.packageName.lowercase()
            if (pkgLower.isNotBlank() && (cleanIntent.contains(pkgLower) || rawClean.contains(pkgLower))) {
                result.add(app.packageName)
            }
        }

        // 3. Match app names in intent (longer names first to prevent partial matches, using word boundaries)
        val sortedApps = apps.sortedByDescending { it.appName.length }
        for (app in sortedApps) {
            val appNameLower = app.appName.lowercase()
            if (appNameLower.length >= 3) {
                val pattern = "\\b${Regex.escape(appNameLower)}\\b".toRegex(RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(cleanIntent) || pattern.containsMatchIn(rawClean)) {
                    result.add(app.packageName)
                }
            }
        }

        // 4. Fallback: try findApp on clean intent if nothing matched
        if (result.isEmpty()) {
            findApp(context, cleanIntent)?.let { result.add(it) }
        }

        return result
    }

    fun extractAndMatchApp(context: Context? = null, intent: String): String? {
        val rawClean = intent.trim().lowercase()
        if (rawClean.isBlank()) return null

        // Strip common leading wake-words (e.g., "max, ", "hey max ", "jarvis: ")
        val cleanIntent = rawClean
            .replaceFirst(Regex("""^(?:hey\s+|hi\s+|ok\s+)?(?:max|jarvis)[,\s:]*"""), "")
            .trim()
            .ifEmpty { rawClean }

        // 1. Check alias map matches first (high precision curated aliases)
        for ((aliasKey, aliasPkg) in aliases) {
            val pattern = "\\b${Regex.escape(aliasKey)}\\b".toRegex(RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(cleanIntent) || pattern.containsMatchIn(rawClean)) {
                return aliasPkg
            }
        }

        val apps = getApps(context).filter { app ->
            val appNameLower = app.appName.lowercase().trim()
            val pkgLower = app.packageName.lowercase().trim()
            appNameLower !in IGNORED_ASSISTANT_NAMES &&
            pkgLower !in IGNORED_ASSISTANT_PACKAGES &&
            (context == null || pkgLower != context.packageName)
        }

        // 2. Match package names in intent
        for (app in apps) {
            val pkgLower = app.packageName.lowercase()
            if (pkgLower.isNotBlank() && (cleanIntent.contains(pkgLower) || rawClean.contains(pkgLower))) {
                return app.packageName
            }
        }

        // 3. Match app names in intent (longer names first to prevent partial matches, using word boundaries)
        val sortedApps = apps.sortedByDescending { it.appName.length }
        for (app in sortedApps) {
            val appNameLower = app.appName.lowercase()
            if (appNameLower.length >= 3) {
                val pattern = "\\b${Regex.escape(appNameLower)}\\b".toRegex(RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(cleanIntent) || pattern.containsMatchIn(rawClean)) {
                    return app.packageName
                }
            }
        }

        // 4. Fallback: try findApp on clean intent
        return findApp(context, cleanIntent)
    }

    fun findApp(context: Context? = null, query: String): String? {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return null

        // Existing canonical identities do not require a Room/cache warm-up.
        aliases[cleanQuery]?.takeIf { it.contains(".") }?.let { return it }
        if (context != null && cleanQuery.contains(".") &&
            context.packageManager.getLaunchIntentForPackage(cleanQuery) != null) return cleanQuery

        val apps = getApps(context)

        // 0. Direct package name match in app list
        val packageMatch = apps.firstOrNull { it.packageName.equals(cleanQuery, ignoreCase = true) }
        if (packageMatch != null) return packageMatch.packageName

        // 1. Alias Match
        val resolvedName = aliases[cleanQuery]
        if (resolvedName != null) {
            if (resolvedName.contains(".")) {
                val pkgMatch = apps.firstOrNull { it.packageName.equals(resolvedName, ignoreCase = true) }
                if (pkgMatch != null) return pkgMatch.packageName
                if (context != null) {
                    try {
                        if (context.packageManager.getLaunchIntentForPackage(resolvedName) != null) {
                            return resolvedName
                        }
                    } catch (_: Exception) {}
                }
                return resolvedName
            }
            val aliasMatch = apps.firstOrNull { it.appName.equals(resolvedName, ignoreCase = true) }
            if (aliasMatch != null) return aliasMatch.packageName
        }

        // 2. Exact Match (case-insensitive)
        val exactMatch = apps.firstOrNull { it.appName.equals(cleanQuery, ignoreCase = true) }
        if (exactMatch != null) return exactMatch.packageName

        // 3. Starts With Match
        val startsWithMatch = apps.firstOrNull { it.appName.lowercase().startsWith(cleanQuery) }
        if (startsWithMatch != null) return startsWithMatch.packageName

        // 4. Contains Match
        val containsMatch = apps.firstOrNull { it.appName.lowercase().contains(cleanQuery) }
        if (containsMatch != null) return containsMatch.packageName

        // 5. Fallback for valid package name with launch intent
        if (cleanQuery.contains(".")) {
            if (context != null) {
                try {
                    if (context.packageManager.getLaunchIntentForPackage(cleanQuery) != null) {
                        return cleanQuery
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            } else {
                return cleanQuery
            }
        }

        // 5. Levenshtein Distance similarity score
        var bestMatch: InstalledApp? = null
        var bestScore = 0.0
        val similarityThreshold = 0.6 // 60% similarity threshold

        for (app in apps) {
            val appNameLower = app.appName.lowercase()
            val distance = levenshteinDistance(cleanQuery, appNameLower)
            val maxLength = maxOf(cleanQuery.length, appNameLower.length)
            val similarity = if (maxLength == 0) 1.0 else 1.0 - (distance.toDouble() / maxLength.toDouble())

            if (similarity > bestScore) {
                bestScore = similarity
                bestMatch = app
            }
        }

        if (bestScore >= similarityThreshold && bestMatch != null) {
            return bestMatch.packageName
        }

        return null
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length

        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1)

        for (i in 1..rhsLength) {
            newCost[0] = i

            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1

                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1

                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }

            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLength]
    }
}
