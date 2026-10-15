package com.example.automation

import android.graphics.Rect
import com.example.automation.computeruse.*
import com.example.automation.engine.DependencyGraphResolver
import com.example.automation.engine.PlanValidationResult
import com.example.automation.engine.execution.DurableRecoveryCoordinator
import com.example.automation.engine.execution.VerificationEngine
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.state.ActionStep
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.data.local.AgentTaskRecord
import com.example.data.local.AgentTaskStepRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentVerificationRegressionTest {
    private fun screen() = ScreenObservation(foregroundPackage = "com.android.settings", visibleTexts = listOf("Settings"))

    @Test fun blankOrPartialPackageCannotVerifyLaunch() {
        val action = ComputerAction.LaunchApp("Settings", "com.android.settings")
        val result = ActionResult(success = true, actionType = action.actionName, targetApp = "com.android.settings")
        assertFalse(ActionVerifier.verify(action, result, screen(), screen().copy(foregroundPackage = "")).first)
        assertFalse(ActionVerifier.verify(action, result, screen(), screen().copy(foregroundPackage = "com.android.settings.fake")).first)
        assertTrue(ActionVerifier.verify(action, result, screen(), screen()).first)
    }

    @Test fun dispatchedKeyWithoutTransitionCannotVerify() {
        val before = screen()
        val result = ActionResult(success = true, actionType = "PRESS_ENTER")
        assertFalse(ActionVerifier.verify(ComputerAction.PressEnter, result, before, before.copy(observationId = "new")).first)
        assertTrue(ActionVerifier.verify(ComputerAction.PressEnter, result, before, before.copy(visibleTexts = listOf("Results"))).first)
    }

    @Test fun signaturesDetectStateAndBoundsBeyondFirstTwentyNodes() {
        val elements = (0..30).map { InteractiveElement(id = "id$it", bounds = Rect(0, it * 10, 50, it * 10 + 9)) }
        val before = screen().copy(interactiveElements = elements)
        val after = before.copy(interactiveElements = elements.dropLast(1) + elements.last().copy(isSelected = true))
        assertTrue(before.hasScreenChanged(after))
        assertNotEquals(before.screenId, after.screenId)
        assertEquals(before.screenId, before.copy(observationId = "fresh").screenId)
    }

    @Test fun staleGroundingRequiresCurrentTargetAndBounds() {
        val element = InteractiveElement(id = "settings", text = "Settings", bounds = Rect(10, 20, 100, 70), isClickable = true)
        val obs = screen().copy(interactiveElements = listOf(element))
        val target = TargetGrounding(observationId = "older", packageName = obs.foregroundPackage, bounds = Rect(element.bounds), semanticLabel = "Settings", resourceId = "settings")
        assertFalse(target.isStale(obs))
        assertTrue(target.isStale(obs.copy(interactiveElements = listOf(element.copy(bounds = Rect(30, 40, 120, 90))))))
        assertTrue(target.isStale(obs.copy(timestamp = System.currentTimeMillis() - 10_000)))
    }

    @Test fun acceptedAndUnverifiedToolsNeverAdvanceDependencies() {
        assertFalse(ToolResult.success("CLICK_TEXT").isVerifiedSuccess)
        assertFalse(ToolResult(true, "CALL_PHONE", verificationRequired = false, status = ToolResultStatus.ACCEPTED).isVerifiedSuccess)
        assertTrue(ToolResult(true, "READ_TIME", verificationRequired = false).isVerifiedSuccess)
        val steps = listOf(ActionStep("a", "OPEN_APP"), ActionStep("b", "TYPE_TEXT", dependsOn = listOf("a")))
        assertEquals(listOf("a"), DependencyGraphResolver.getReadySteps(steps, emptySet()).map { it.stepId })
        assertEquals(emptyList<String>(), DependencyGraphResolver.getReadySteps(steps, emptySet(), failedStepIds = setOf("a")).map { it.stepId })
        assertTrue(DependencyGraphResolver.validatePlan(listOf(steps.last())) is PlanValidationResult.Invalid)
    }

    @Test fun missingExpectedOutcomeCannotVerifySuccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertFalse(VerificationEngine(context).verify(null))
    }

    @Test fun oneVerifiedInterruptedCallCannotCompleteWholeTask() {
        val task = AgentTaskRecord("task", "Two steps", "INTERRUPTED", 0, 2, createdAt = 1, updatedAt = 1)
        val steps = listOf(
            AgentTaskStepRecord("task", "a", "CALL_PHONE", 0, "VERIFYING", executionId = "exec", verificationResult = "VERIFIED"),
            AgentTaskStepRecord("task", "b", "OPEN_APP", 1, "READY")
        )
        assertTrue(DurableRecoveryCoordinator.evaluateRecovery(task, steps) is DurableRecoveryCoordinator.RecoveryDecision.BlockAmbiguousSideEffect)
        assertNotEquals("COMPLETED", ExecutionTruthCoordinator.deriveParentStatusFromSteps(steps).first)
    }

    @Test fun unrelatedCallTruthCannotSatisfyIdentityLookup() {
        ExecutionTruthCoordinator.clearAllExecutions()
        try {
            ExecutionTruthCoordinator.recordExecution(taskId = "old", stepId = "a", executionId = "exec-old",
                actionName = "CALL_PHONE", executorSuccess = true, verifiedSuccess = true, status = "CALL_PLACED")
            assertNotNull(ExecutionTruthCoordinator.getCallExecution(taskId = "old", executionId = "exec-old"))
            assertNull(ExecutionTruthCoordinator.getCallExecution(taskId = "new"))
            assertNull(ExecutionTruthCoordinator.getCallExecution(taskId = "old", executionId = "new"))
        } finally { ExecutionTruthCoordinator.clearAllExecutions() }
    }
}
