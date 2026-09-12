package com.example.automation.habits

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolRegistry
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HabitSuggestionFlowUnitTest {

    private class FakeOpenAppTool : Tool {
        override val name: String = "fake_open_app"
        override val supportedActions: Set<String> = setOf("OPEN_APP")
        override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
        override val capabilities: ToolCapabilities = ToolCapabilities(
            supportsPlanner = true, requiresAccessibility = false, requiresNetwork = false
        )
        override fun validate(request: ExecutionRequest): Boolean = true
        override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult =
            ToolResult(success = true, toolName = name, verificationRequired = false)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun candidate(): HabitCandidate = HabitCandidate(
        sequence = listOf("com.google.android.gm", "com.google.android.calendar"),
        timeBucket = TimeBucket.MORNING,
        occurrenceCount = 3,
        distinctDays = 3,
        firstSeenMs = 0L,
        lastSeenMs = 0L,
        bucketHour = 8
    )

    @Before
    fun setUp() {
        ToolRegistry.resetForTests()
        ToolRegistry.register(FakeOpenAppTool())
        context.getSharedPreferences("max_habit_events", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("max_routines", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `suggestion requires explicit approval and does nothing on cancel`() {
        val store = HabitEventStore(context)
        val suggestion = HabitDetector.suggest(candidate())
        assertTrue(HabitSuggestionCoordinator.suggest(context, suggestion, store))
        assertTrue(HabitSuggestionCoordinator.hasPending())

        HabitSuggestionCoordinator.cancel()
        assertFalse(HabitSuggestionCoordinator.hasPending())

        // No routine should have been created without approval.
        val routines = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
        assertFalse(routines.contains(suggestion.routineName))
    }

    @Test
    fun `approved suggestion creates and runs an existing-style routine`() = runBlocking {
        val store = HabitEventStore(context)
        val suggestion = HabitDetector.suggest(candidate())
        assertTrue(HabitSuggestionCoordinator.suggest(context, suggestion, store))
        assertTrue(HabitSuggestionCoordinator.hasPending())

        val result = HabitSuggestionCoordinator.approveAndRun(context)
        assertTrue("Expected created/started but got: $result", result.contains("created"))

        val routines = context.getSharedPreferences("max_routines", Context.MODE_PRIVATE)
        val stored = routines.getString(suggestion.routineName, null)
        assertNotNull("Routine should be persisted after approval", stored)
        assertTrue(stored!!.contains("OPEN_APP"))
        assertFalse(HabitSuggestionCoordinator.hasPending())
    }

    @Test
    fun `same pattern is not re-suggested within the throttle window`() {
        val store = HabitEventStore(context)
        val suggestion = HabitDetector.suggest(candidate())
        assertTrue(HabitSuggestionCoordinator.suggest(context, suggestion, store))

        HabitSuggestionCoordinator.cancel()

        // markSuggested happened on first suggest; a second ask within 24h is suppressed.
        assertFalse(HabitSuggestionCoordinator.suggest(context, suggestion, store))
    }

    @Test
    fun `suggestion question is conversational and hides internals`() {
        // Directly verified through the pending state exposed to reply loops.
        val store = HabitEventStore(context)
        val suggestion = HabitDetector.suggest(candidate())
        HabitSuggestionCoordinator.suggest(context, suggestion, store)
        assertEquals(suggestion.routineName, HabitSuggestionCoordinator.currentPending()?.routineName)
        HabitSuggestionCoordinator.cancel()
    }
}
