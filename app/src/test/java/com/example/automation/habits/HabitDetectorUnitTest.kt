package com.example.automation.habits

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitDetectorUnitTest {

    /** Timestamp for the given hour on a day relative to a fixed anchor day. */
    private fun atHour(anchorDay: Int, hour: Int, minute: Int = 30): Long {
        return Calendar.getInstance().apply {
            timeInMillis = BASE_DAY_MS + anchorDay * 86_400_000L
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun appSequence(day: Int, apps: List<String>, hour: Int = 7, minute: Int = 30, gapMs: Long = 60_000L): List<ForegroundEvent> {
        val start = atHour(day, hour, minute)
        return apps.mapIndexed { index, pkg ->
            ForegroundEvent(packageName = pkg, timestampMs = start + index * gapMs)
        }
    }

    companion object {
        // Fixed anchor so tests never depend on the current wall clock.
        private val BASE_DAY_MS: Long = 1_700_000_000_000L
    }

    // 3 repeated occurrences (distinct days) → candidate
    @Test
    fun `three repeated morning occurrences produce a candidate`() {
        val apps = listOf("com.google.android.gm", "com.google.android.calendar", "com.slack")
        val events = mutableListOf<ForegroundEvent>()
        for (day in 1..4) events.addAll(appSequence(day, apps))

        val candidates = HabitDetector.detect(events)
        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        assertEquals(4, candidate.occurrenceCount)
        assertEquals(4, candidate.distinctDays)
        assertEquals(listOf("com.google.android.gm", "com.google.android.calendar", "com.slack"), candidate.sequence)
        assertEquals(TimeBucket.MORNING, candidate.timeBucket)
    }

    // 2 occurrences → no candidate
    @Test
    fun `two occurrences do not produce a candidate`() {
        val apps = listOf("com.google.android.gm", "com.google.android.calendar")
        val events = appSequence(1, apps) + appSequence(2, apps)
        assertTrue(HabitDetector.detect(events).isEmpty())
    }

    // Different sequences → no false match
    @Test
    fun `different sequences do not match`() {
        val events = appSequence(1, listOf("com.google.android.gm", "com.google.android.calendar")) +
            appSequence(2, listOf("com.google.android.calendar", "com.slack")) +
            appSequence(3, listOf("com.slack", "com.google.android.gm"))
        assertTrue(HabitDetector.detect(events).isEmpty())
    }

    // Different times → different bucket, no candidate
    @Test
    fun `same sequence at different time buckets does not match`() {
        val apps = listOf("com.google.android.gm", "com.google.android.calendar")
        val events = appSequence(1, apps, hour = 7) +
            appSequence(2, apps, hour = 13) +
            appSequence(3, apps, hour = 20)
        // Occurrences split across MORNING / MIDDAY / NIGHT buckets → no single candidate.
        assertTrue(HabitDetector.detect(events).isEmpty())
    }

    // Duplicate/debounced events
    @Test
    fun `rapid duplicate foreground events are debounced`() {
        val now = System.currentTimeMillis()
        assertTrue("First occurrence of a package should record", HabitDetector.shouldRecord(null, now, 0L, "com.whatsapp"))
        assertFalse("Blank packages never record", HabitDetector.shouldRecord(null, now, 0L, ""))
        assertFalse("Same package within debounce window is dropped", HabitDetector.shouldRecord("com.whatsapp", now + 1_000L, now, "com.whatsapp"))
        assertTrue("Same package after debounce window records", HabitDetector.shouldRecord("com.whatsapp", now + 2_000L, now, "com.whatsapp"))
        assertTrue("Different package always records", HabitDetector.shouldRecord("com.whatsapp", now + 2_000L, now, "com.google.android.gm"))
    }

    @Test
    fun `consecutive duplicates collapse inside sessions`() {
        val apps = listOf("com.google.android.gm", "com.google.android.gm", "com.google.android.calendar")
        val sessions = HabitDetector.buildSessions(appSequence(1, apps))
        assertEquals(1, sessions.size)
        assertEquals(listOf("com.google.android.gm", "com.google.android.calendar"), sessions.first().first)
    }

    // System UI noise is ignored
    @Test
    fun `system UI noise is filtered`() {
        assertTrue(HabitDetector.isNoise("com.android.systemui"))
        assertTrue(HabitDetector.isNoise("com.miui.home"))
        assertTrue(HabitDetector.isNoise("com.google.android.permissioncontroller"))
        assertTrue(HabitDetector.isNoise("com.example.max"))
        assertFalse(HabitDetector.isNoise("com.whatsapp"))
    }

    @Test
    fun `noise events never form sessions`() {
        val events = mutableListOf<ForegroundEvent>()
        for (day in 1..5) {
            events.addAll(appSequence(day, listOf("com.android.systemui", "com.miui.home", "com.whatsapp")))
        }
        val candidates = HabitDetector.detect(events)
        assertTrue(candidates.isEmpty()) // noise apps removed; single real app left
    }

    // Suggestion naming
    @Test
    fun `suggestion uses readable routine name`() {
        val candidate = HabitCandidate(
            sequence = listOf("com.google.android.gm", "com.google.android.calendar", "com.slack"),
            timeBucket = TimeBucket.MORNING,
            occurrenceCount = 4,
            distinctDays = 4,
            firstSeenMs = atHour(1, 7),
            lastSeenMs = atHour(4, 7),
            bucketHour = 7
        )
        val suggestion = HabitDetector.suggest(candidate)
        assertEquals("morning_gmail_calendar_slack", suggestion.routineName)
        assertTrue(suggestion.description.contains("gmail"))
        assertTrue(suggestion.description.contains("calendar"))
        assertTrue(suggestion.description.contains("morning"))
    }
}
