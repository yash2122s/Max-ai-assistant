package com.example.automation.habits

/**
 * P1.4 — Pure habit detection logic (no Android dependencies; unit-testable).
 *
 * Detection rules (conservative):
 *  - the same ordered app sequence (length >= 2) must repeat at least 3 times
 *    within the 14-day observation window,
 *  - occurrences must span at least 2 distinct days,
 *  - the repetition must fall in the same [TimeBucket],
 *  - launcher / system-UI / permission / lock-screen / known-transient noise
 *    is ignored, and rapid duplicate transitions are debounced.
 */
object HabitDetector {

    const val OBSERVATION_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    const val MIN_OCCURRENCES = 3
    private const val SESSION_GAP_MS = 5L * 60 * 1000 // apps opened within 5 min belong to one session
    private const val DUPLICATE_DEBOUNCE_MS = 1_500L

    /** Known transient / system packages that must never feed habit detection. */
    val NOISE_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.keyguard",
        "com.android.settings.intelligence",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.miui.securityadd",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.gms",
        "com.google.android.googlequicksearchbox",
        "com.android.providers.media",
        "com.android.providers.calendar"
    )

    /** Common package → short readable label for suggested routine names. */
    val PACKAGE_LABELS = mapOf(
        "com.google.android.gm" to "gmail",
        "com.google.android.calendar" to "calendar",
        "com.slack" to "slack",
        "com.whatsapp" to "whatsapp",
        "org.telegram.messenger" to "telegram",
        "com.instagram.android" to "instagram",
        "com.youtube" to "youtube",
        "com.google.android.youtube" to "youtube",
        "com.spotify.music" to "spotify",
        "com.netflix.mediaclient" to "netflix",
        "com.google.android.apps.photos" to "photos",
        "com.google.android.apps.maps" to "maps",
        "com.google.android.apps.docs" to "drive",
        "com.microsoft.office.outlook" to "outlook",
        "com.microsoft.office.excel" to "excel",
        "com.microsoft.office.word" to "word"
    )

    fun readableLabel(packageName: String): String =
        PACKAGE_LABELS[packageName]
            ?: packageName.substringAfterLast('.').takeIf { it.length >= 3 }
            ?: packageName

    /** True for launcher/system/permission/lock noise or unknown self packages. */
    fun isNoise(packageName: String, selfPackage: String? = null): Boolean {
        val pkg = packageName.lowercase()
        if (pkg.isBlank()) return true
        if (pkg in NOISE_PACKAGES) return true
        if (selfPackage != null && pkg == selfPackage.lowercase()) return true
        if (pkg.startsWith("com.example")) return true // MAX's own surfaces
        return false
    }

    /** Debounce duplicate foreground transitions for the same package. */
    fun shouldRecord(lastPackage: String?, nowMs: Long, lastTsMs: Long, candidate: String): Boolean {
        if (candidate.isBlank()) return false
        if (lastPackage != null && candidate == lastPackage && nowMs - lastTsMs < DUPLICATE_DEBOUNCE_MS) {
            return false
        }
        return true
    }

    /**
     * Builds time-anchored app sessions from raw foreground events:
     * noise removed, duplicates collapsed, apps within [SESSION_GAP_MS]
     * grouped into a single session (sequence length >= 2 only).
     */
    fun buildSessions(events: List<ForegroundEvent>, selfPackage: String? = null): List<Triple<List<String>, Long, TimeBucket>> {
        val filtered = events
            .filter { !isNoise(it.packageName, selfPackage) }
            .sortedBy { it.timestampMs }
            .fold(mutableListOf<ForegroundEvent>()) { acc, event ->
                val last = acc.lastOrNull()
                if (last != null && last.packageName == event.packageName && event.timestampMs - last.timestampMs < DUPLICATE_DEBOUNCE_MS) {
                    acc
                } else {
                    acc.add(event)
                    acc
                }
            }

        val sessions = mutableListOf<Triple<List<String>, Long, TimeBucket>>()
        var currentApps = mutableListOf<String>()
        var sessionStart = 0L

        filtered.forEachIndexed { index, event ->
            if (currentApps.isEmpty()) {
                currentApps = mutableListOf(event.packageName)
                sessionStart = event.timestampMs
            } else if (event.timestampMs - sessionStart > SESSION_GAP_MS * 10 || // new day-ish break
                (index > 0 && event.timestampMs - filtered[index - 1].timestampMs > SESSION_GAP_MS)
            ) {
                if (currentApps.size >= 2) {
                    sessions.add(Triple(currentApps.toList(), sessionStart, TimeBucket.of(sessionStart)))
                }
                currentApps = mutableListOf(event.packageName)
                sessionStart = event.timestampMs
            } else {
                if (currentApps.last() != event.packageName) {
                    currentApps.add(event.packageName)
                }
            }
        }
        if (currentApps.size >= 2) {
            sessions.add(Triple(currentApps.toList(), sessionStart, TimeBucket.of(sessionStart)))
        }
        return sessions
    }

    /**
     * Detects conservative habit candidates: same ordered sequence, same time
     * bucket, >= [MIN_OCCURRENCES] occurrences within the observation window
     * across >= 2 distinct days.
     */
    fun detect(events: List<ForegroundEvent>, selfPackage: String? = null): List<HabitCandidate> {
        val now = events.maxOfOrNull { it.timestampMs } ?: return emptyList()
        val windowStart = now - OBSERVATION_WINDOW_MS
        val inWindow = events.filter { it.timestampMs >= windowStart && it.timestampMs <= now }
        val sessions = buildSessions(inWindow, selfPackage)

        val seqBucketGroups = sessions
            .filter { it.first.size >= 2 }
            .groupBy { "${it.third.name}|${it.first.joinToString(">")}" }

        val results = mutableListOf<HabitCandidate>()
        for ((_, groupSessions) in seqBucketGroups) {
            if (groupSessions.size < MIN_OCCURRENCES) continue
            val distinctDays = groupSessions.map { dayKey(it.second) }.distinct()
            if (distinctDays.size < 2) continue
            val first = groupSessions.first()
            val last = groupSessions.last()
            val sequence = first.first
            val bucket = first.third
            val bucketHour = java.util.Calendar.getInstance().apply { timeInMillis = first.second }
                .get(java.util.Calendar.HOUR_OF_DAY)
            results.add(
                HabitCandidate(
                    sequence = sequence,
                    timeBucket = bucket,
                    occurrenceCount = groupSessions.size,
                    distinctDays = distinctDays.size,
                    firstSeenMs = first.second,
                    lastSeenMs = last.second,
                    bucketHour = bucketHour
                )
            )
        }
        return results.sortedByDescending { it.occurrenceCount }
    }

    /** Builds a user-facing suggestion from a candidate. */
    fun suggest(candidate: HabitCandidate): HabitSuggestion {
        val appNames = candidate.sequence.joinToString(" → ") { readableLabel(it) }
        val routineName = buildRoutineName(candidate)
        val description = "You usually open $appNames around ${candidate.timeBucket.displayName()}" +
            " (${candidate.occurrenceCount}x across ${candidate.distinctDays} days). Create a routine for it?"
        return HabitSuggestion(candidate = candidate, routineName = routineName, description = description)
    }

    fun buildRoutineName(candidate: HabitCandidate): String {
        val prefix = when (candidate.timeBucket) {
            TimeBucket.EARLY_MORNING -> "early_morning"
            TimeBucket.MORNING -> "morning"
            TimeBucket.MIDDAY -> "midday"
            TimeBucket.AFTERNOON -> "afternoon"
            TimeBucket.EVENING -> "evening"
            TimeBucket.NIGHT -> "night"
        }
        val apps = candidate.sequence.take(3).joinToString("_") { readableLabel(it).lowercase().replace(Regex("[^a-z0-9]+"), "") }
        return "${prefix}_${apps}"
    }

    private fun dayKey(timestampMs: Long): Long = timestampMs / 86_400_000L
}
