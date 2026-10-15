package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.CancellationToken
import com.example.automation.engine.GoalPlanner
import com.example.automation.engine.TaskCancellationCoordinator
import com.example.automation.engine.execution.LongTaskOrchestrator
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.state.ActionStep
import com.example.data.local.AgentTaskStepRecord
import com.example.data.local.InstalledApp
import com.example.knowledge.apps.InstalledAppsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutonomousTaskExecutionContractTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testApps = listOf(
            InstalledApp(packageName = "com.whatsapp", appName = "WhatsApp"),
            InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube"),
            InstalledApp(packageName = "com.instagram.android", appName = "Instagram"),
            InstalledApp(packageName = "com.android.chrome", appName = "Google Chrome")
        )
        InstalledAppsRepository.setCachedAppsForTesting(testApps)

        // Initialize tool registry for tests
        com.example.automation.tools.ToolRegistry.resetForTests()
        com.example.automation.verification.VerificationRegistry.resetForTests()

        // Register tools required for tests
        com.example.automation.tools.ToolRegistry.register(com.example.automation.tools.WaitTool())
        com.example.automation.tools.ToolRegistry.register(com.example.automation.tools.WaitForUiTool())
        com.example.automation.verification.VerificationRegistry.register(com.example.automation.verification.WaitForUiVerifier())
    }

    // =========================================================================
    // 1. Task Cancellation Coordinator Unit Verification
    // =========================================================================
    @Test
    fun testTaskCancellationCoordinatorLifecycle() {
        val taskId = "test_task_${UUID.randomUUID()}"
        val token = CancellationToken()

        TaskCancellationCoordinator.registerActiveTask(taskId, token)
        assertEquals(taskId, TaskCancellationCoordinator.getActiveTaskId())
        assertTrue(TaskCancellationCoordinator.isTaskActive())

        val wasCancelled = TaskCancellationCoordinator.cancelActiveTask(context, "User stopped test task")
        assertTrue(wasCancelled)
        assertTrue(token.isCancelled)
        // Cancellation request is not owner acknowledgement. Keep correlation
        // until the executor records CANCELLED and unregisters in finally.
        assertEquals(taskId, TaskCancellationCoordinator.getActiveTaskId())
        TaskCancellationCoordinator.unregisterActiveTask(taskId)
        assertNull(TaskCancellationCoordinator.getActiveTaskId())
    }

    // =========================================================================
    // 2. Chained Multi-Step Plan with First-Class WAIT Steps
    // =========================================================================
    @Test
    fun testMultiStepWorkflowWithWaitSteps() = runBlocking {
        val orchestrator = LongTaskOrchestrator(context = context)
        val token = CancellationToken()

        val steps = listOf(
            ActionStep(
                stepId = "step_1",
                actionType = "WAIT",
                targetText = "100ms",
                params = mapOf("duration" to "100ms")
            ),
            ActionStep(
                stepId = "step_2",
                actionType = "WAIT",
                targetText = "100ms",
                params = mapOf("duration" to "100ms"),
                dependsOn = listOf("step_1")
            )
        )

        val result = orchestrator.executePlan(
            context = context,
            goalPrompt = "Wait 100ms then wait 100ms",
            steps = steps,
            cancellationToken = token
        )

        assertTrue("Multi-step wait workflow must succeed: ${result.errorMessage}", result.success)
        assertEquals(2, result.executedStepsCount)
        assertEquals(2, result.totalStepsCount)
        assertFalse(result.isCancelled)
    }

    // =========================================================================
    // 3. Instant Cancellation During Wait Step
    // =========================================================================
    @Test
    fun testCancellationDuringWaitStepTerminatesImmediately() = runBlocking {
        val orchestrator = LongTaskOrchestrator(context = context)
        val token = CancellationToken()

        val steps = listOf(
            ActionStep(
                stepId = "step_1",
                actionType = "WAIT",
                targetText = "10s",
                params = mapOf("duration" to "10s")
            )
        )

        // Cancel after 150ms
        launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(150L)
            TaskCancellationCoordinator.cancelActiveTask(context, "Stop task pressed")
        }

        val startTime = System.currentTimeMillis()
        val result = orchestrator.executePlan(
            context = context,
            goalPrompt = "Wait 10s",
            steps = steps,
            cancellationToken = token
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertFalse("Cancelled plan must not be marked success", result.success)
        assertTrue("Execution must be halted quickly (< 2000ms), took $elapsed ms", elapsed < 2000L)
    }

    // =========================================================================
    // 4. Execution Truth: Invariant that Partial Failure NEVER Claims COMPLETED
    // =========================================================================
    @Test
    fun testParentStatusNeverClaimsCompletedOnPartialFailure() {
        val step1 = AgentTaskStepRecord(
            taskId = "task_1",
            stepId = "step_1",
            actionType = "OPEN_APP",
            stepIndex = 0,
            status = "SUCCESS",
            verificationResult = "VERIFIED"
        )
        val step2 = AgentTaskStepRecord(
            taskId = "task_1",
            stepId = "step_2",
            actionType = "WAIT",
            stepIndex = 1,
            status = "FAILED",
            failureReason = "Wait interrupted by user"
        )
        val step3 = AgentTaskStepRecord(
            taskId = "task_1",
            stepId = "step_3",
            actionType = "OPEN_APP",
            stepIndex = 2,
            status = "NOT_EXECUTED"
        )

        val (parentStatus, failureReason) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(
            listOf(step1, step2, step3)
        )

        assertNotEquals("COMPLETED", parentStatus)
        assertEquals("FAILED", parentStatus)
        assertTrue(failureReason?.contains("Wait interrupted") == true)
    }

    // =========================================================================
    // 5. Execution Truth: Anti-Hallucination Assertion Interception
    // =========================================================================
    @Test
    fun testAntiHallucinationTeluguAndEnglishSuppression() {
        // Record a failed execution truth
        ExecutionTruthCoordinator.recordExecution(
            taskId = "task_test",
            stepId = "step_test",
            executionId = "exec_1",
            actionName = "WAIT",
            executorSuccess = false,
            verifiedSuccess = false,
            failureReason = "Cancelled by user"
        )

        // If LLM hallucinated saying it was completed:
        val llmClaimEnglish = "I have completed the task successfully!"
        val correctedEnglish = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(llmClaimEnglish)
        assertFalse("LLM claim must be intercepted", correctedEnglish.contains("successfully"))
        assertTrue("Corrected response must mention inability or failure reason", correctedEnglish.contains("unable") || correctedEnglish.contains("Cancelled"))

        val llmClaimTelugu = "చేసేశాను, అంతా కంప్లీట్ అయ్యింది"
        val correctedTelugu = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(llmClaimTelugu)
        assertFalse("Telugu LLM claim must be intercepted", correctedTelugu.contains("చేసేశాను"))
    }
}
