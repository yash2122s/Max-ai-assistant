package com.example.automation

import com.example.automation.engine.ActionDispatcher
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class P1DispatchAndWorldStateTest {
    @Before
    fun resetWorldState() {
        WorldStateManager.reset()
    }

    @Test
    fun inFlightDuplicateIsNotReportedAsCompletedAndFailedAttemptCanRetry() {
        val target = "Settings-${UUID.randomUUID()}"
        val firstExecution = UUID.randomUUID()
        assertNull(ActionDispatcher.tryReserveDuplicateForTests("OPEN_APP", target, firstExecution, now = 100L))

        val duplicate = ActionDispatcher.tryReserveDuplicateForTests(
            "OPEN_APP", target, UUID.randomUUID(), now = 101L
        )
        assertEquals(firstExecution, duplicate)

        // The dispatcher releases the marker from finally, including failures.
        ActionDispatcher.releaseDuplicateForTests("OPEN_APP", target, firstExecution)
        assertNull(ActionDispatcher.tryReserveDuplicateForTests("OPEN_APP", target, UUID.randomUUID(), now = 102L))
    }

    @Test
    fun staleInFlightMarkerDoesNotBlockNewExecution() {
        val target = "cats-${UUID.randomUUID()}"
        val firstExecution = UUID.randomUUID()
        assertNull(ActionDispatcher.tryReserveDuplicateForTests("YOUTUBE_SEARCH", target, firstExecution, now = 100L))

        // A crashed/interrupted owner cannot suppress retries forever.
        val retryExecution = UUID.randomUUID()
        assertNull(ActionDispatcher.tryReserveDuplicateForTests(
            "YOUTUBE_SEARCH", target, retryExecution, now = 35_101L
        ))
        ActionDispatcher.releaseDuplicateForTests("YOUTUBE_SEARCH", target, retryExecution)
    }

    @Test
    fun failedUnverifiedUnrelatedAndWrongStepActionsDoNotAdvance() {
        val taskId = "task-1"
        WorldStateManager.setCurrentGoal(
            "Open Settings then search",
            listOf(
                ActionStep("step-open", "OPEN_APP", targetText = "Settings"),
                ActionStep("step-search", "YOUTUBE_SEARCH", targetText = "cats")
            ),
            taskId
        )

        assertFalse(WorldStateManager.recordActionExecuted("OPEN_APP", "Settings", success = false,
            verifiedSuccess = false, taskId = taskId, stepId = "step-open"))
        assertEquals("step-open", WorldStateManager.getCurrentState().remainingSteps.first().stepId)

        assertFalse(WorldStateManager.recordActionExecuted("OPEN_APP", "Settings", success = true,
            verifiedSuccess = false, taskId = taskId, stepId = "step-open"))
        assertEquals("step-open", WorldStateManager.getCurrentState().remainingSteps.first().stepId)

        assertFalse(WorldStateManager.recordActionExecuted("YOUTUBE_SEARCH", "cats", success = true,
            verifiedSuccess = true, taskId = taskId, stepId = "step-search"))
        assertEquals("step-open", WorldStateManager.getCurrentState().remainingSteps.first().stepId)

        assertFalse(WorldStateManager.recordActionExecuted("OPEN_APP", "Settings", success = true,
            verifiedSuccess = true, taskId = taskId, stepId = "wrong-step"))
        assertEquals("step-open", WorldStateManager.getCurrentState().remainingSteps.first().stepId)
    }

    @Test
    fun matchingVerifiedActionAdvancesOnlyItsCurrentStep() {
        val taskId = "task-2"
        WorldStateManager.setCurrentGoal(
            "Open Settings then search",
            listOf(
                ActionStep("step-open", "OPEN_APP", targetText = "Settings"),
                ActionStep("step-search", "YOUTUBE_SEARCH", targetText = "cats")
            ),
            taskId
        )

        assertTrue(WorldStateManager.recordActionExecuted("OPEN_APP", "Settings", success = true,
            verifiedSuccess = true, taskId = taskId, stepId = "step-open"))
        assertEquals("step-search", WorldStateManager.getCurrentState().remainingSteps.first().stepId)
    }
}
