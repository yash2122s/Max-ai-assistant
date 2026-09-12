package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.CancellationToken
import com.example.automation.engine.GoalPlanner
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
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
class GoalPlannerUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val planningProvider = com.example.network.provider.GeminiPlanningProvider(context)
        GoalPlanner.setPlanningProvider(planningProvider)
        GoalPlanner.clearGoal()
        WorldStateManager.setCurrentGoal(null, emptyList())
    }

    @Test
    fun testGoalPlanInitializationAndQueue() {
        val steps = listOf(
            ActionStep(stepId = "step_1", actionType = "GET_BATTERY_STATUS"),
            ActionStep(stepId = "step_2", actionType = "GET_DND")
        )

        GoalPlanner.initializeGoalPlan("Check battery and DND", steps)

        assertTrue(GoalPlanner.isGoalActive())
        assertEquals(2, GoalPlanner.actionQueue.size())
        assertEquals("Check battery and DND", WorldStateManager.getCurrentState().currentGoal)

        val first = GoalPlanner.getNextStep()
        assertNotNull(first)
        assertEquals("step_1", first?.stepId)
        assertEquals("GET_BATTERY_STATUS", first?.actionType)

        val second = GoalPlanner.getNextStep()
        assertNotNull(second)
        assertEquals("step_2", second?.stepId)
        assertEquals("GET_DND", second?.actionType)

        assertFalse(GoalPlanner.isGoalActive())
    }

    @Test
    fun testExecutePlanEmptyQueueReturnsFailure() = runBlocking {
        GoalPlanner.clearGoal()
        val result = GoalPlanner.executePlan(context)
        assertFalse(result.success)
        assertEquals(0, result.executedStepsCount)
    }

    @Test
    fun testExecutePlanCancellation() = runBlocking {
        val steps = listOf(
            ActionStep(stepId = "step_1", actionType = "GET_BATTERY_STATUS"),
            ActionStep(stepId = "step_2", actionType = "GET_DND")
        )

        GoalPlanner.initializeGoalPlan("Test Cancel", steps)
        val token = CancellationToken(isCancelledCheck = { true })

        val result = GoalPlanner.executePlan(context, token)
        assertFalse(result.success)
        assertTrue(result.isCancelled)
        assertFalse(GoalPlanner.isGoalActive())
    }
}
