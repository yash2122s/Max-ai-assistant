package com.example.automation

import com.example.automation.activity.ActionReasonFormatter
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.activity.AgentStage
import com.example.automation.engine.ExecutionRequest
import com.example.automation.event.AutomationEvent
import com.example.automation.state.ActionStep
import com.example.automation.tools.ToolResult
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class AgentActivityTrackerUnitTest {

    @Before
    fun setUp() {
        AgentActivityTracker.reset()
    }

    @Test
    fun testPlanningStarted() {
        val id = UUID.randomUUID()
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.PlanningStarted(id, "Open Settings and Wi-Fi"))

        val state = AgentActivityTracker.state.value
        assertTrue(state.active)
        assertEquals("Open Settings and Wi-Fi", state.goal)
        assertEquals(AgentStage.PLANNING, state.stage)
    }

    @Test
    fun testPlanCreatedPopulatesActionReasons() {
        val id = UUID.randomUUID()
        val steps = listOf(
            ActionStep(stepId = "s1", actionType = "OPEN_APP", targetText = "Settings"),
            ActionStep(stepId = "s2", actionType = "CLICK_ELEMENT", targetText = "Wi-Fi"),
            ActionStep(stepId = "s3", actionType = "YOUTUBE_SEARCH", targetText = "vegetable")
        )
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.PlanningStarted(id, "Search YouTube for vegetable"))
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.PlanCreated(id, steps))

        val state = AgentActivityTracker.state.value
        assertTrue(state.active)
        assertEquals(3, state.totalSteps)
        assertEquals(3, state.steps.size)
        assertEquals("OPEN_APP", state.steps[0].actionType)
        assertNotNull(state.steps[0].reason)
        assertTrue(state.steps[0].reason!!.contains("Settings"))
        assertTrue(state.steps[1].reason!!.contains("Wi-Fi"))
        assertTrue(state.steps[2].reason!!.contains("vegetable"))
    }

    @Test
    fun testStepProgressRunningAndCompleted() {
        val id = UUID.randomUUID()
        val steps = listOf(
            ActionStep(stepId = "s1", actionType = "OPEN_APP", targetText = "Settings")
        )
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.PlanCreated(id, steps))
        AgentActivityTracker.handleAutomationEvent(
            AutomationEvent.StepProgress(id, stepIndex = 1, totalSteps = 1, actionType = "OPEN_APP", targetText = "Settings", status = "RUNNING")
        )

        var state = AgentActivityTracker.state.value
        assertEquals(AgentStage.EXECUTING, state.stage)
        assertEquals("RUNNING", state.steps[0].status)
        assertEquals("OPEN_APP", state.currentAction)
        assertNotNull(state.currentReason)

        AgentActivityTracker.handleAutomationEvent(
            AutomationEvent.StepProgress(id, stepIndex = 1, totalSteps = 1, actionType = "OPEN_APP", targetText = "Settings", status = "COMPLETED")
        )

        state = AgentActivityTracker.state.value
        assertEquals("COMPLETED", state.steps[0].status)
        assertEquals("✓ Completed", state.steps[0].result)
    }

    @Test
    fun testObservationCompletedExplanation() {
        val id = UUID.randomUUID()
        AgentActivityTracker.handleAutomationEvent(
            AutomationEvent.ObservationCompleted(id, packageName = "com.android.settings", nodeCount = 24, visibleSummary = "Settings...")
        )

        val state = AgentActivityTracker.state.value
        assertEquals("com.android.settings", state.currentApp)
        assertEquals(24, state.observedNodeCount)
        assertNotNull(state.currentReason)
        assertTrue(state.currentReason!!.contains("Checking the current screen"))
    }

    @Test
    fun testAmbiguityStateTracking() {
        AgentActivityTracker.setAmbiguousGoal("vegetable", "What would you like me to do with \"vegetable\"?")
        val state = AgentActivityTracker.state.value
        assertTrue(state.isAmbiguous)
        assertEquals("vegetable", state.goal)
        assertEquals("What would you like me to do with \"vegetable\"?", state.clarificationPrompt)
        assertFalse(state.active)
    }

    @Test
    fun testActionReasonFormatterCoverage() {
        assertEquals("Capturing current screen", ActionReasonFormatter.formatActionTitle("GET_SCREEN_CONTEXT", null))
        assertEquals("Open WhatsApp", ActionReasonFormatter.formatActionTitle("OPEN_APP", "WhatsApp"))
        assertEquals("Tap \"Bhaskar\"", ActionReasonFormatter.formatActionTitle("CLICK_ELEMENT", "Bhaskar"))
        assertEquals("Type \"Hello\"", ActionReasonFormatter.formatActionTitle("TYPE_TEXT", "Hello"))
        assertEquals("YouTube Search: vegetable", ActionReasonFormatter.formatActionTitle("YOUTUBE_SEARCH", "vegetable"))

        assertTrue(ActionReasonFormatter.formatReason("OPEN_APP", "WhatsApp").contains("WhatsApp"))
        assertTrue(ActionReasonFormatter.formatReason("CLICK_ELEMENT", "Bhaskar").contains("Bhaskar"))
        assertTrue(ActionReasonFormatter.formatReason("TYPE_TEXT", "Hello").contains("Hello"))
        assertTrue(ActionReasonFormatter.formatReason("GET_SCREEN_CONTEXT", null).contains("Checking the current screen"))
        assertTrue(ActionReasonFormatter.formatReason("YOUTUBE_SEARCH", "vegetable").contains("YouTube"))
    }

    @Test
    fun testToolStartedAndFinished() {
        val id = UUID.randomUUID()
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.ToolStarted(id, "OpenAppTool"))
        AgentActivityTracker.handleAutomationEvent(
            AutomationEvent.ToolFinished(id, "OpenAppTool", ToolResult(success = true, toolName = "OpenAppTool", message = "Settings opened"))
        )

        val state = AgentActivityTracker.state.value
        assertTrue(state.traceLogs.any { it.tag == "OpenAppTool" && it.isSuccess == true })
    }

    @Test
    fun testVerificationPassedAndFailed() {
        val id = UUID.randomUUID()
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.VerificationPassed(id, "OpenAppVerifier"))
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.VerificationFailed(id, "WifiVerifier", "Toggle not found"))

        val state = AgentActivityTracker.state.value
        assertTrue(state.traceLogs.any { it.tag == "VerificationRegistry" && it.isSuccess == true })
        assertTrue(state.traceLogs.any { it.tag == "VerificationRegistry" && it.isSuccess == false })
    }

    @Test
    fun testRetryStartedAndFinished() {
        val id = UUID.randomUUID()
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.RetryStarted(id, "ClickUiTool", attempt = 1))
        assertEquals(AgentStage.VERIFYING, AgentActivityTracker.state.value.stage)

        AgentActivityTracker.handleAutomationEvent(AutomationEvent.RetryFinished(id, "ClickUiTool", success = true))
        val state = AgentActivityTracker.state.value
        assertTrue(state.traceLogs.any { it.tag == "RetryEngine" && it.isSuccess == true })
    }

    @Test
    fun testExecutionFinishedSuccessAndFailure() {
        val id = UUID.randomUUID()
        AgentActivityTracker.handleAutomationEvent(AutomationEvent.PlanningStarted(id, "Test Goal"))
        assertTrue(AgentActivityTracker.state.value.active)

        AgentActivityTracker.handleAutomationEvent(AutomationEvent.ExecutionFinished(id, success = true))
        assertFalse(AgentActivityTracker.state.value.active)
        assertEquals(AgentStage.COMPLETED, AgentActivityTracker.state.value.stage)

        AgentActivityTracker.handleAutomationEvent(AutomationEvent.ExecutionFinished(id, success = false))
        assertEquals(AgentStage.FAILED, AgentActivityTracker.state.value.stage)
    }
}
