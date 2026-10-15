package com.example.automation.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.tools.mapUiTaskResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UiVerificationLoopTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun failedActionThenFinishSuccessStopsBeforeFinish() = runBlocking {
        val action = UiAction(type = UiAction.Type.TAP, x = 10, y = 10)
        val decisions = ArrayDeque<UiAgentReasoningResult>().apply {
            add(UiAgentReasoningResult.ActionDecision(action, reasoning = "test"))
            add(UiAgentReasoningResult.FinishTask(success = true, message = "model says done", reasoning = "test"))
        }
        val loop = testLoop(decisions, actionExecutor = { _, _ -> false })

        val result = loop.executeTask(UiTask(goal = "tap the target"))

        assertFalse(result.completionVerified)
        assertEquals("ACTION_EXECUTION_FAILED", result.error)
        assertEquals(1, decisions.size)
    }

    @Test
    fun unverifiedActionThenFinishSuccessStopsBeforeFinish() = runBlocking {
        val action = UiAction(type = UiAction.Type.TAP, x = 10, y = 10)
        val decisions = ArrayDeque<UiAgentReasoningResult>().apply {
            add(UiAgentReasoningResult.ActionDecision(action, reasoning = "test"))
            add(UiAgentReasoningResult.FinishTask(success = true, message = "model says done", reasoning = "test"))
        }
        val loop = testLoop(decisions, actionExecutor = { _, _ -> true })

        val result = loop.executeTask(UiTask(goal = "tap the target"))

        assertFalse(result.completionVerified)
        assertEquals("ACTION_UNVERIFIED", result.error)
        assertEquals(1, decisions.size)
    }

    @Test
    fun verifiedCompletionRequiresAndAcceptsForegroundCheck() = runBlocking {
        val action = UiAction(type = UiAction.Type.SCREENSHOT)
        val decisions = ArrayDeque<UiAgentReasoningResult>().apply {
            add(UiAgentReasoningResult.ActionDecision(action, reasoning = "test"))
            add(UiAgentReasoningResult.FinishTask(success = true, message = "done", reasoning = "test"))
        }
        val loop = testLoop(decisions, actionExecutor = { _, _ -> true })

        val result = loop.executeTask(UiTask(goal = "inspect target", targetPackage = "com.example.target"))

        assertTrue(result.success)
        assertTrue(result.completionVerified)
        assertEquals(0, decisions.size)
    }

    @Test
    fun uiAgentToolMapsOnlyLocallyVerifiedLoopSuccess() {
        val modelClaimWithoutLocalVerification = UiTaskResult(
            taskId = "task-1",
            success = true,
            message = "model says done",
            stepsExecuted = 1,
            providerUsed = "test",
            modelUsed = "test",
            completionVerified = false
        )
        val rejected = mapUiTaskResult(context, modelClaimWithoutLocalVerification)

        assertFalse(rejected.success)
        assertFalse(rejected.verification!!.success)
        assertEquals("UNVERIFIED_COMPLETION", rejected.errorCode)

        val locallyVerified = modelClaimWithoutLocalVerification.copy(completionVerified = true)
        val accepted = mapUiTaskResult(context, locallyVerified)

        assertTrue(accepted.success)
        assertTrue(accepted.verification!!.success)
        assertTrue(accepted.isVerifiedSuccess)
    }

    @Test
    fun wrongForegroundRejectsActionBeforeExecutor() = runBlocking {
        var executions = 0
        val decisions = ArrayDeque<UiAgentReasoningResult>().apply {
            add(UiAgentReasoningResult.ActionDecision(UiAction(type = UiAction.Type.SCREENSHOT), "test"))
        }
        val loop = testLoop(
            decisions,
            actionExecutor = { _, _ -> executions++; true },
            observation = observation("com.example.other")
        )

        val result = loop.executeTask(UiTask(goal = "inspect target", targetPackage = "com.example.target"))

        assertEquals("WRONG_FOREGROUND_PACKAGE", result.error)
        assertEquals(0, executions)
    }

    @Test
    fun launchPackageMustMatchAuthorizedTarget() = runBlocking {
        var executions = 0
        val decisions = ArrayDeque<UiAgentReasoningResult>().apply {
            add(UiAgentReasoningResult.ActionDecision(
                UiAction(type = UiAction.Type.LAUNCH_APP, packageName = "com.example.other"),
                "test"
            ))
        }
        val loop = testLoop(
            decisions,
            actionExecutor = { _, _ -> executions++; true },
            observation = observation("com.android.launcher")
        )

        val result = loop.executeTask(UiTask(goal = "open target", targetPackage = "com.example.target"))

        assertEquals("PACKAGE_NOT_AUTHORIZED", result.error)
        assertEquals(0, executions)
    }

    @Test
    fun authorizedLaunchReobservesAndAllowsTargetCompletion() = runBlocking {
        val observations = ArrayDeque(listOf(
            observation("com.android.launcher"),
            observation("com.example.target"),
            observation("com.example.target"),
            observation("com.example.target")
        ))
        val decisions = ArrayDeque<UiAgentReasoningResult>().apply {
            add(UiAgentReasoningResult.ActionDecision(
                UiAction(type = UiAction.Type.LAUNCH_APP, packageName = "com.example.target"),
                "test"
            ))
            add(UiAgentReasoningResult.FinishTask(true, "done", "test"))
        }
        var executions = 0
        val loop = testLoopWithObservations(
            decisions,
            observations,
            actionExecutor = { _, _ -> executions++; true }
        )

        val result = loop.executeTask(UiTask(goal = "open target", targetPackage = "com.example.target"))

        assertTrue(result.success)
        assertTrue(result.completionVerified)
        assertEquals(1, executions)
        assertEquals(0, observations.size)
    }

    @Test
    fun stepTimeoutReturnsStructuredErrorAndDoesNotExecuteAction() = runBlocking {
        var executions = 0
        val loop = UiVerificationLoop(
            context = context,
            decisionProvider = { _, _, _ ->
                delay(100L)
                Triple(UiAgentReasoningResult.ActionDecision(UiAction(type = UiAction.Type.SCREENSHOT), "test"), "test", "test")
            },
            actionExecutor = { _, _ -> executions++; true },
            observationProvider = { observation("com.example.target") },
            adaptiveWait = {}
        )

        val result = loop.executeTask(UiTask(goal = "timeout", stepTimeoutMs = 10L))

        assertEquals("ACTION_TIMEOUT", result.error)
        assertEquals(0, executions)
    }

    @Test
    fun coroutineCancellationPropagatesAndPreventsAction() = runBlocking {
        var executions = 0
        val loop = UiVerificationLoop(
            context = context,
            decisionProvider = { _, _, _ ->
                delay(5_000L)
                Triple(UiAgentReasoningResult.ActionDecision(UiAction(type = UiAction.Type.SCREENSHOT), "test"), "test", "test")
            },
            actionExecutor = { _, _ -> executions++; true },
            observationProvider = { observation("com.example.target") },
            adaptiveWait = {}
        )
        val job = async { loop.executeTask(UiTask(goal = "cancel")) }

        delay(25L)
        job.cancel()
        try {
            job.await()
            throw AssertionError("Expected coroutine cancellation to propagate")
        } catch (_: CancellationException) {
            // Expected: executeTask does not convert parent coroutine cancellation to success.
        }
        assertEquals(0, executions)
    }

    private fun testLoop(
        decisions: ArrayDeque<UiAgentReasoningResult>,
        actionExecutor: suspend (Context, UiAction) -> Boolean,
        observation: UiObservation = observation("com.example.target")
    ): UiVerificationLoop = UiVerificationLoop(
        context = context,
        decisionProvider = { _, _, _ ->
            Triple(decisions.removeFirst(), "test-provider", "test-model")
        },
        actionExecutor = actionExecutor,
        observationProvider = { observation },
        adaptiveWait = {}
    )

    private fun testLoopWithObservations(
        decisions: ArrayDeque<UiAgentReasoningResult>,
        observations: ArrayDeque<UiObservation>,
        actionExecutor: suspend (Context, UiAction) -> Boolean
    ): UiVerificationLoop = UiVerificationLoop(
        context = context,
        decisionProvider = { _, _, _ ->
            Triple(decisions.removeFirst(), "test-provider", "test-model")
        },
        actionExecutor = actionExecutor,
        observationProvider = { observations.removeFirst() },
        adaptiveWait = {}
    )

    private fun observation(packageName: String): UiObservation = UiObservation(
        screenshotJpeg = null,
        prunedElements = emptyList(),
        foregroundPackage = packageName,
        visibleText = "",
        clickableElements = emptyList()
    )
}
