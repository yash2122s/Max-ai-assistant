package com.example.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regression guard: the scheduler's AlarmManager PendingIntent must
 * remain explicit and immutable. This test is intentionally source-level:
 * it verifies the
 * production construction contract without requiring a device AlarmManager.
 */
class AlarmSchedulerInternalTargetSourceTest {
    @Test
    fun `scheduler keeps explicit TaskReceiver target and immutable pending intent`() {
        val source = File("src/main/java/com/example/automation/scheduler/AlarmScheduler.kt").readText()
        assertTrue(source.contains("Intent(context, TaskReceiver::class.java)"))
        assertTrue(source.contains("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE"))
        assertTrue(source.contains("PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE"))
    }
}
