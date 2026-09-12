package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.*
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldState
import com.example.automation.state.WorldStateManager
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class P0AutonomousAgentUnitTest {

    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GoalPlanner.clearGoal()
        WorldStateManager.setCurrentGoal(null, emptyList())
    }

    // ==========================================
    // TEST 1: ToolResult Backward Compatibility & Statuses
    // ==========================================
    @Test
    fun testToolResultExtensions() {
        // 1. Existing positional constructor unchanged & defaults
        val legacySuccess = ToolResult(
            success = true,
            toolName = "ClickTool"
        )
        assertTrue(legacySuccess.success)
        assertEquals("ClickTool", legacySuccess.toolName)
        assertEquals(ToolResultStatus.SUCCESS, legacySuccess.status)
        assertTrue(legacySuccess.outputData.isEmpty())
        assertNull(legacySuccess.needsInputQuestion)

        val legacyFailure = ToolResult(
            success = false,
            toolName = "ClickTool",
            errorCode = "NODE_NOT_FOUND",
            message = "Element not on screen"
        )
        assertFalse(legacyFailure.success)
        assertEquals(ToolResultStatus.FAILURE, legacyFailure.status)

        // 2. NeedsInput factory helper
        val needsInputResult = ToolResult.needsInput(
            toolName = "ContactTool",
            question = "Which contact would you like to message?",
            options = listOf("Yaswanth", "Bhaskar"),
            targetParam = "contact"
        )
        assertFalse(needsInputResult.success)
        assertEquals(ToolResultStatus.NEEDS_INPUT, needsInputResult.status)
        assertEquals("Which contact would you like to message?", needsInputResult.needsInputQuestion)
        assertEquals(2, needsInputResult.needsInputOptions.size)
        assertEquals("contact", needsInputResult.targetParam)

        // 3. Output data propagation
        val outputs = mapOf("phone" to "+919876543210", "contactName" to "Yaswanth")
        val successWithOutput = ToolResult.success(
            toolName = "ContactSearchTool",
            message = "Found contact",
            outputData = outputs
        )
        assertTrue(successWithOutput.success)
        assertEquals(ToolResultStatus.SUCCESS, successWithOutput.status)
        assertEquals("+919876543210", successWithOutput.outputData["phone"])

        // 4. User action required
        val userAction = ToolResult.userActionRequired("SecurityTool", "Biometric required")
        assertEquals(ToolResultStatus.USER_ACTION_REQUIRED, userAction.status)
        assertFalse(userAction.success)
    }

    // ==========================================
    // TEST 2: ActionStep Extensions & JSON Compatibility
    // ==========================================
    @Test
    fun testActionStepExtensionsAndSerialization() {
        // 1. Legacy constructor compatibility
        val legacyStep = ActionStep(
            stepId = "step_1",
            actionType = "CLICK_TEXT",
            targetText = "Submit",
            resourceId = "com.app:id/submit",
            contentDescription = "Submit button",
            fallbackX = 0.5f,
            fallbackY = 0.8f
        )
        assertEquals("step_1", legacyStep.stepId)
        assertTrue(legacyStep.dependsOn.isEmpty())
        assertTrue(legacyStep.params.isEmpty())
        assertNull(legacyStep.fallbackAction)
        assertNull(legacyStep.outputKey)
        assertEquals("PENDING", legacyStep.status)

        // 2. New DAG ActionStep
        val dagStep = ActionStep(
            stepId = "step_2",
            actionType = "TYPE_TEXT",
            dependsOn = listOf("step_1"),
            params = mapOf("text" to "${'$'}step_1.phone", "field" to "input_box"),
            fallbackAction = "FALLBACK_CLICK",
            outputKey = "step_2_result",
            status = "READY"
        )
        assertEquals(listOf("step_1"), dagStep.dependsOn)
        assertEquals("${'$'}step_1.phone", dagStep.params["text"])
        assertEquals("FALLBACK_CLICK", dagStep.fallbackAction)
        assertEquals("step_2_result", dagStep.outputKey)
        assertEquals("READY", dagStep.status)

        // 3. Serialization Roundtrip
        val json = gson.toJson(dagStep)
        val deserialized = gson.fromJson(json, ActionStep::class.java)
        assertEquals(dagStep.stepId, deserialized.stepId)
        assertEquals(dagStep.dependsOn, deserialized.dependsOn)
        assertEquals(dagStep.params, deserialized.params)
        assertEquals(dagStep.fallbackAction, deserialized.fallbackAction)

        // 4. Old JSON without new fields deserializes safely
        val oldJson = """{"stepId":"step_old","actionType":"OPEN_APP","targetText":"com.whatsapp"}"""
        val fromOldJson = gson.fromJson(oldJson, ActionStep::class.java)
        assertEquals("step_old", fromOldJson.stepId)
        assertTrue(fromOldJson.dependsOn == null || fromOldJson.dependsOn.isEmpty())
        assertTrue(fromOldJson.params == null || fromOldJson.params.isEmpty())
        assertNull(fromOldJson.fallbackAction)
        assertEquals("PENDING", fromOldJson.status ?: "PENDING")
    }


    // ==========================================
    // TEST 3: DependencyGraphResolver (Topological Sort, DAG, Cycles)
    // ==========================================
    @Test
    fun testDependencyGraphResolver() {
        // 1. Linear Plan
        val linearSteps = listOf(
            ActionStep(stepId = "A", actionType = "OPEN_APP"),
            ActionStep(stepId = "B", actionType = "CLICK_TEXT"),
            ActionStep(stepId = "C", actionType = "TYPE_TEXT")
        )
        assertTrue(DependencyGraphResolver.isLinearPlan(linearSteps))
        val validLinear = DependencyGraphResolver.validatePlan(linearSteps)
        assertTrue(validLinear is PlanValidationResult.Valid)

        // 2. Sequential Dependency (A -> B)
        val seqSteps = listOf(
            ActionStep(stepId = "A", actionType = "OPEN_APP"),
            ActionStep(stepId = "B", actionType = "CLICK_TEXT", dependsOn = listOf("A"))
        )
        assertFalse(DependencyGraphResolver.isLinearPlan(seqSteps))
        val readyInit = DependencyGraphResolver.getReadySteps(seqSteps, completedStepIds = emptySet())
        assertEquals(1, readyInit.size)
        assertEquals("A", readyInit[0].stepId)

        val readyAfterA = DependencyGraphResolver.getReadySteps(seqSteps, completedStepIds = setOf("A"))
        assertEquals(1, readyAfterA.size)
        assertEquals("B", readyAfterA[0].stepId)

        // 3. Diamond DAG: A -> B, A -> C, B+C -> D
        val diamondSteps = listOf(
            ActionStep(stepId = "A", actionType = "STEP_A"),
            ActionStep(stepId = "B", actionType = "STEP_B", dependsOn = listOf("A")),
            ActionStep(stepId = "C", actionType = "STEP_C", dependsOn = listOf("A")),
            ActionStep(stepId = "D", actionType = "STEP_D", dependsOn = listOf("B", "C"))
        )
        val diamondVal = DependencyGraphResolver.validatePlan(diamondSteps)
        assertTrue(diamondVal is PlanValidationResult.Valid)

        val readyA = DependencyGraphResolver.getReadySteps(diamondSteps, setOf("A"))
        assertEquals(2, readyA.size)
        assertTrue(readyA.any { it.stepId == "B" })
        assertTrue(readyA.any { it.stepId == "C" })

        val readyBOnly = DependencyGraphResolver.getReadySteps(diamondSteps, setOf("A", "B"))
        assertEquals(1, readyBOnly.size)
        assertEquals("C", readyBOnly[0].stepId)

        val readyBC = DependencyGraphResolver.getReadySteps(diamondSteps, setOf("A", "B", "C"))
        assertEquals(1, readyBC.size)
        assertEquals("D", readyBC[0].stepId)

        // 4. Circular Dependency (A -> B, B -> A) MUST FAIL
        val cycleSteps = listOf(
            ActionStep(stepId = "A", actionType = "STEP_A", dependsOn = listOf("B")),
            ActionStep(stepId = "B", actionType = "STEP_B", dependsOn = listOf("A"))
        )
        val cycleVal = DependencyGraphResolver.validatePlan(cycleSteps)
        assertTrue(cycleVal is PlanValidationResult.Invalid)
        assertEquals(DependencyGraphResolver.ERROR_CIRCULAR_DEPENDENCY, (cycleVal as PlanValidationResult.Invalid).errorCode)

        // 5. Unknown Dependency MUST FAIL
        val unknownDepSteps = listOf(
            ActionStep(stepId = "A", actionType = "STEP_A", dependsOn = listOf("NON_EXISTENT"))
        )
        val unknownVal = DependencyGraphResolver.validatePlan(unknownDepSteps)
        assertTrue(unknownVal is PlanValidationResult.Invalid)
        assertEquals(DependencyGraphResolver.ERROR_MISSING_DEPENDENCY, (unknownVal as PlanValidationResult.Invalid).errorCode)

        // 6. Duplicate Step ID MUST FAIL
        val dupSteps = listOf(
            ActionStep(stepId = "A", actionType = "STEP_A"),
            ActionStep(stepId = "A", actionType = "STEP_A_DUP")
        )
        val dupVal = DependencyGraphResolver.validatePlan(dupSteps)
        assertTrue(dupVal is PlanValidationResult.Invalid)
        assertEquals(DependencyGraphResolver.ERROR_DUPLICATE_STEP_ID, (dupVal as PlanValidationResult.Invalid).errorCode)

        // 7. Cascading Failed Dependency Blocking
        // A -> B -> C. If A fails, B and C must be blocked.
        val chainSteps = listOf(
            ActionStep(stepId = "A", actionType = "STEP_A"),
            ActionStep(stepId = "B", actionType = "STEP_B", dependsOn = listOf("A")),
            ActionStep(stepId = "C", actionType = "STEP_C", dependsOn = listOf("B"))
        )
        val blockedIds = DependencyGraphResolver.getAllBlockedStepIds(chainSteps, setOf("A"))
        assertEquals(setOf("A", "B", "C"), blockedIds)

        val readyAfterFail = DependencyGraphResolver.getReadySteps(chainSteps, completedStepIds = emptySet(), failedStepIds = setOf("A"))
        assertTrue(readyAfterFail.isEmpty())
    }

    // ==========================================
    // TEST 4: ParameterInterpolationEngine
    // ==========================================
    @Test
    fun testParameterInterpolationEngine() {
        val contextOutputs = mapOf(
            "step_search" to mapOf(
                "phone" to "+919876543210",
                "name" to "Yaswanth",
                "nested" to mapOf("city" to "Hyderabad")
            )
        )

        // 1. Direct variable interpolation
        val params1 = mapOf("targetPhone" to "${'$'}step_search.phone")
        val res1 = ParameterInterpolationEngine.interpolate(params1, contextOutputs)
        assertTrue(res1 is InterpolationResult.Success)
        assertEquals("+919876543210", (res1 as InterpolationResult.Success).interpolatedParams["targetPhone"])

        // 2. Embedded string interpolation
        val params2 = mapOf("message" to "Hello ${'$'}step_search.name at ${'$'}step_search.phone")
        val res2 = ParameterInterpolationEngine.interpolate(params2, contextOutputs)
        assertTrue(res2 is InterpolationResult.Success)
        assertEquals("Hello Yaswanth at +919876543210", (res2 as InterpolationResult.Success).interpolatedParams["message"])

        // 3. Nested dictionary interpolation
        val params3 = mapOf("location" to "${'$'}step_search.nested.city")
        val res3 = ParameterInterpolationEngine.interpolate(params3, contextOutputs)
        assertTrue(res3 is InterpolationResult.Success)
        assertEquals("Hyderabad", (res3 as InterpolationResult.Success).interpolatedParams["location"])

        // 4. Missing output variable reference -> Failure
        val paramsMissing = mapOf("phone" to "${'$'}step_unknown.phone")
        val resMissing = ParameterInterpolationEngine.interpolate(paramsMissing, contextOutputs)
        assertTrue(resMissing is InterpolationResult.Failure)
        assertEquals("step_unknown.phone", (resMissing as InterpolationResult.Failure).missingReference)

        // 5. Normal strings without $ are preserved
        val paramsLiteral = mapOf("text" to "Normal plain message", "count" to "42")
        val resLiteral = ParameterInterpolationEngine.interpolate(paramsLiteral, contextOutputs)
        assertTrue(resLiteral is InterpolationResult.Success)
        assertEquals("Normal plain message", (resLiteral as InterpolationResult.Success).interpolatedParams["text"])
        assertEquals("42", (resLiteral as InterpolationResult.Success).interpolatedParams["count"])
    }

    // ==========================================
    // TEST 5: ActionQueue Dependency-Aware Polling
    // ==========================================
    @Test
    fun testActionQueueDependencyExecution() {
        val queue = ActionQueue()

        // 1. Linear FIFO behavior
        val linearSteps = listOf(
            ActionStep(stepId = "1", actionType = "A"),
            ActionStep(stepId = "2", actionType = "B"),
            ActionStep(stepId = "3", actionType = "C")
        )
        queue.replaceQueue(linearSteps)
        assertEquals("1", queue.dequeueReadyStep(emptySet())?.stepId)
        assertEquals("2", queue.dequeueReadyStep(setOf("1"))?.stepId)
        assertEquals("3", queue.dequeueReadyStep(setOf("1", "2"))?.stepId)
        assertNull(queue.dequeueReadyStep(setOf("1", "2", "3")))

        // 2. DAG Ready-step polling
        val dagSteps = listOf(
            ActionStep(stepId = "step_root", actionType = "ROOT"),
            ActionStep(stepId = "step_child", actionType = "CHILD", dependsOn = listOf("step_root"))
        )
        queue.replaceQueue(dagSteps)

        // Before root is completed, child cannot be dequeued
        val firstPoll = queue.dequeueReadyStep(completedStepIds = emptySet())
        assertNotNull(firstPoll)
        assertEquals("step_root", firstPoll?.stepId)

        // Child is ready only after root completed
        val secondPoll = queue.dequeueReadyStep(completedStepIds = setOf("step_root"))
        assertNotNull(secondPoll)
        assertEquals("step_child", secondPoll?.stepId)
    }

    // ==========================================
    // TEST 6: ReEvaluationEngine Decisions & Bounds
    // ==========================================
    @Test
    fun testReEvaluationEngine() = runBlocking {
        val failedStep = ActionStep(
            stepId = "step_click",
            actionType = "CLICK_TEXT",
            targetText = "Submit",
            fallbackAction = "FALLBACK_CLICK"
        )
        val toolFailResult = ToolResult.failure("ClickTool", "NODE_NOT_FOUND", "Element missing")
        val worldState = WorldState(currentApp = "com.test.app")

        // 1. Configured Fallback Action
        val fallbackDecision = ReEvaluationEngine.evaluate(
            goal = "Submit form",
            failedStep = failedStep,
            toolResult = toolFailResult,
            worldState = worldState,
            completedSteps = emptyList(),
            remainingSteps = emptyList()
        )
        assertTrue(fallbackDecision is ReEvalDecision.MODIFY)
        val modFallback = fallbackDecision as ReEvalDecision.MODIFY
        assertEquals("FALLBACK_CLICK", modFallback.updatedSteps[0].actionType)

        // 2. Modal Dialog Scrim Recovery
        val dialogStep = ActionStep(stepId = "step_click", actionType = "CLICK_TEXT", targetText = "Submit")
        val dialogWorldState = WorldState(currentApp = "com.test.app", isDialogVisible = true)
        val dialogDecision = ReEvaluationEngine.evaluate(
            goal = "Submit form",
            failedStep = dialogStep,
            toolResult = toolFailResult,
            worldState = dialogWorldState,
            completedSteps = emptyList(),
            remainingSteps = emptyList()
        )
        assertTrue(dialogDecision is ReEvalDecision.MODIFY)
        val modDialog = dialogDecision as ReEvalDecision.MODIFY
        assertEquals("SYSTEM_ACTION", modDialog.updatedSteps[0].actionType)
        assertEquals("back", modDialog.updatedSteps[0].targetText)

        // 3. Max Re-Evaluations Bound Limit
        val maxReEvalDecision = ReEvaluationEngine.evaluate(
            goal = "Submit form",
            failedStep = failedStep,
            toolResult = toolFailResult,
            worldState = worldState,
            completedSteps = emptyList(),
            remainingSteps = emptyList(),
            reEvalCount = ReEvaluationEngine.MAX_REEVALUATIONS
        )
        assertTrue(maxReEvalDecision is ReEvalDecision.ABANDON)
        assertTrue((maxReEvalDecision as ReEvalDecision.ABANDON).reason.contains("limit"))

        // 4. Max Total Steps Bound Limit
        val maxStepsDecision = ReEvaluationEngine.evaluate(
            goal = "Submit form",
            failedStep = failedStep,
            toolResult = toolFailResult,
            worldState = worldState,
            completedSteps = (1..10).map { ActionStep(stepId = "s$it", actionType = "DUMMY") },
            remainingSteps = (1..3).map { ActionStep(stepId = "r$it", actionType = "DUMMY") }
        )
        assertTrue(maxStepsDecision is ReEvalDecision.ABANDON)
    }

    // ==========================================
    // TEST 7: NeedsInput Pause & Resume Lifecycle
    // ==========================================
    @Test
    fun testNeedsInputLifecycle() = runBlocking {
        val steps = listOf(
            ActionStep(
                stepId = "step_ask",
                actionType = "OPEN_APP",
                targetText = "com.whatsapp",
                params = mapOf("target" to "placeholder")
            )
        )
        GoalPlanner.initializeGoalPlan("Open WhatsApp", steps)

        // Simulate resume without waiting state -> Error
        val invalidResume = GoalPlanner.resumePlanWithInput(context, "some_input")
        assertFalse(invalidResume.success)
        assertEquals("No active paused plan awaiting input.", invalidResume.errorMessage)
    }

    // ==========================================
    // TEST 8: Goal Validation & Authorization
    // ==========================================
    @Test
    fun testPlanValidationAndSecurity() {
        // Supported action passes
        val validSteps = listOf(
            ActionStep(stepId = "s1", actionType = "SET_VOLUME", targetText = "up")
        )
        val validError = GoalPlanner.validatePlan(validSteps, "Increase volume")
        assertNull(validError)

        // Circular dependency fails validation
        val cycleSteps = listOf(
            ActionStep(stepId = "s1", actionType = "SET_VOLUME", targetText = "up", dependsOn = listOf("s2")),
            ActionStep(stepId = "s2", actionType = "SET_VOLUME", targetText = "up", dependsOn = listOf("s1"))
        )
        val cycleError = GoalPlanner.validatePlan(cycleSteps, "Increase volume")
        assertNotNull(cycleError)
        assertTrue(cycleError!!.contains("INVALID_PLAN_CIRCULAR_DEPENDENCY"))
    }
}
