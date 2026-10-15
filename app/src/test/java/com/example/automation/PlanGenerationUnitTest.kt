package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.engine.GoalPlanner
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlanGenerationUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val planningProvider = com.example.network.provider.GeminiPlanningProvider(context)
        GoalPlanner.setPlanningProvider(planningProvider)
        GoalPlanner.clearGoal()
        WorldStateManager.setCurrentGoal(null, emptyList())
        AgentActivityTracker.reset()
    }

    @Test
    fun testValidPlanValidation() {
        val validSteps = listOf(
            ActionStep(stepId = "step_1", actionType = "OPEN_APP", targetText = "WhatsApp"),
            ActionStep(stepId = "step_2", actionType = "CLICK_ELEMENT", targetText = "Bhaskar"),
            ActionStep(stepId = "step_3", actionType = "TYPE_TEXT", targetText = "Hello"),
            ActionStep(stepId = "step_4", actionType = "CLICK_ELEMENT", targetText = "Send")
        )

        val validationResult = GoalPlanner.validatePlan(validSteps)
        assertNull(validationResult)
    }

    @Test
    fun testEmptyPlanValidationFails() {
        val emptySteps = emptyList<ActionStep>()
        val result = GoalPlanner.validatePlan(emptySteps)
        assertNotNull(result)
        assertTrue(result!!.contains("empty"))
    }

    @Test
    fun testUnsupportedActionValidationFails() {
        val invalidSteps = listOf(
            ActionStep(stepId = "step_1", actionType = "UNSUPPORTED_MAGIC_ACTION", targetText = "Test")
        )
        val result = GoalPlanner.validatePlan(invalidSteps)
        assertNotNull(result)
        assertTrue(result!!.contains("Unsupported actionType"))
    }

    @Test
    fun testTooManyStepsValidationFails() {
        val steps = (1..19).map { i ->
            ActionStep(stepId = "step_$i", actionType = "GET_BATTERY_STATUS")
        }
        val result = GoalPlanner.validatePlan(steps)
        assertNotNull(result)
        assertTrue(result!!.contains("exceeds maximum"))
    }

    @Test
    fun testMissingRequiredParameterValidationFails() {
        val invalidStep = listOf(
            ActionStep(stepId = "step_1", actionType = "OPEN_APP", targetText = null, resourceId = null)
        )
        val result = GoalPlanner.validatePlan(invalidStep)
        assertNotNull(result)
        assertTrue(result!!.contains("requires targetText"))
    }

    @Test
    fun testBlankStepIdValidationFails() {
        val invalidStep = listOf(
            ActionStep(stepId = "   ", actionType = "GET_BATTERY_STATUS")
        )
        val result = GoalPlanner.validatePlan(invalidStep)
        assertNotNull(result)
        assertTrue(result!!.contains("blank stepId"))
    }

    @Test
    fun testDuplicateStepIdValidationFails() {
        val duplicateSteps = listOf(
            ActionStep(stepId = "step_1", actionType = "GET_BATTERY_STATUS"),
            ActionStep(stepId = "step_1", actionType = "GET_DND")
        )
        val result = GoalPlanner.validatePlan(duplicateSteps)
        assertNotNull(result)
        assertTrue(result!!.contains("Duplicate stepId"))
    }

    @Test
    fun testAmbiguousGoalsIdentified() {
        assertTrue(GoalPlanner.isGoalAmbiguous("vegetable"))
        assertTrue(GoalPlanner.isGoalAmbiguous("Bhaskar"))
        assertTrue(GoalPlanner.isGoalAmbiguous("music"))
        assertTrue(GoalPlanner.isGoalAmbiguous("hello"))
        assertTrue(GoalPlanner.isGoalAmbiguous("settings"))
        assertTrue(GoalPlanner.isGoalAmbiguous("   "))
    }

    @Test
    fun testValidActionGoalsNotAmbiguous() {
        assertFalse(GoalPlanner.isGoalAmbiguous("Search YouTube for vegetable"))
        assertFalse(GoalPlanner.isGoalAmbiguous("Open YouTube"))
        assertFalse(GoalPlanner.isGoalAmbiguous("Play vegetable recipes on YouTube"))
        assertFalse(GoalPlanner.isGoalAmbiguous("Open WhatsApp"))
        assertFalse(GoalPlanner.isGoalAmbiguous("Send Hello to Bhaskar"))
        assertFalse(GoalPlanner.isGoalAmbiguous("Open Settings and open Wi-Fi"))
        assertFalse(GoalPlanner.isGoalAmbiguous("flashlight"))
        assertFalse(GoalPlanner.isGoalAmbiguous("home"))
        assertFalse(GoalPlanner.isGoalAmbiguous("back"))
    }

    @Test
    fun testPlanAndExecuteAmbiguousGoalReturnsClarification() = runBlocking {
        val result = GoalPlanner.planAndExecute(context, "vegetable", "dummy_key")
        assertFalse(result.success)
        assertEquals(0, result.executedStepsCount)
        assertEquals(0, result.totalStepsCount)
        assertTrue(result.errorMessage?.contains("Ambiguous request") == true)
        
        val trackerState = AgentActivityTracker.state.value
        assertTrue(trackerState.isAmbiguous)
        assertTrue(trackerState.clarificationPrompt?.contains("vegetable") == true)
    }
}
