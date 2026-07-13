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

object InstalledAppsRepository {
    private const val TAG = "AppsRepository"

    @Volatile
    private var cachedApps: List<InstalledApp>? = null

    private val aliases = mapOf(
        "wa" to "WhatsApp",
        "whatsapp" to "WhatsApp",
        "yt" to "YouTube",
        "youtube" to "YouTube",
        "insta" to "Instagram",
        "instagram" to "Instagram",
        "maps" to "Google Maps",
        "google maps" to "Google Maps",
        "chrome" to "Google Chrome",
        "google chrome" to "Google Chrome",
        "fb" to "Facebook"
    )

    fun scanAndSaveInstalledApps(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
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
                cachedApps = installedApps
                Log.d(TAG, "Memory cache updated with ${installedApps.size} apps.")

                // Backup to Room
                val db = AppDatabase.getDatabase(context)
                db.installedAppDao().deleteAllApps()
                db.installedAppDao().insertApps(installedApps)
                Log.d(TAG, "Room database backup updated with ${installedApps.size} apps.")
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning and saving apps", e)
            }
        }
    }

    private fun getApps(context: Context): List<InstalledApp> {
        return cachedApps ?: run {
            val db = AppDatabase.getDatabase(context)
            val apps = runBlocking {
                try {
                    db.installedAppDao().getAllApps()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            cachedApps = apps
            apps
        }
    }

    fun findApp(context: Context, query: String): String? {
        val apps = getApps(context)
        if (apps.isEmpty()) return null

        val cleanQuery = query.trim().lowercase()

        // 1. Alias Match
        val resolvedName = aliases[cleanQuery]
        if (resolvedName != null) {
            val aliasMatch = apps.firstOrNull { it.appName.equals(resolvedName, ignoreCase = true) }
            if (aliasMatch != null) return aliasMatch.packageName
        }

        // 2. Exact Match
        val exactMatch = apps.firstOrNull { it.appName.equals(cleanQuery, ignoreCase = true) }
        if (exactMatch != null) return exactMatch.packageName

        // 3. Starts With Match
        val startsWithMatch = apps.firstOrNull { it.appName.lowercase().startsWith(cleanQuery) }
        if (startsWithMatch != null) return startsWithMatch.packageName

        // 4. Contains Match
        val containsMatch = apps.firstOrNull { it.appName.lowercase().contains(cleanQuery) }
        if (containsMatch != null) return containsMatch.packageName

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
