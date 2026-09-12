package com.example.automation.habits

/**
 * P1.4 — Autonomous habit & routine discovery (models).
 *
 * Privacy: a habit event records ONLY the foreground package + timestamp.
 * No message bodies, passwords, typed text, or screen content is ever stored.
 */
enum class TimeBucket {
    EARLY_MORNING,
    MORNING,
    MIDDAY,
    AFTERNOON,
    EVENING,
    NIGHT;

    fun displayName(): String = when (this) {
        EARLY_MORNING -> "early morning"
        MORNING -> "morning"
        MIDDAY -> "midday"
        AFTERNOON -> "afternoon"
        EVENING -> "evening"
        NIGHT -> "night"
    }

    companion object {
        fun of(hourOfDay: Int): TimeBucket = when (hourOfDay) {
            in 4..6 -> EARLY_MORNING
            in 7..11 -> MORNING
            in 12..14 -> MIDDAY
            in 15..17 -> AFTERNOON
            in 18..21 -> EVENING
            else -> NIGHT
        }

        fun of(timestampMs: Long): TimeBucket = of(java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }.get(java.util.Calendar.HOUR_OF_DAY))
    }
}

/** One observed foreground transition (package only, no content). */
data class ForegroundEvent(
    val packageName: String,
    val timestampMs: Long
)

/** A repeated, time-anchored app sequence worth suggesting. */
data class HabitCandidate(
    val sequence: List<String>,
    val timeBucket: TimeBucket,
    val occurrenceCount: Int,
    val distinctDays: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val bucketHour: Int
) {
    val confidence: Float = (occurrenceCount / 5f).coerceIn(0.4f, 1f)

    fun key(): String = "${timeBucket.name}|${sequence.joinToString(">") { it.lowercase() }}"
}

/** A user-facing routine suggestion derived from a [HabitCandidate]. */
data class HabitSuggestion(
    val candidate: HabitCandidate,
    val routineName: String,
    val description: String
)
