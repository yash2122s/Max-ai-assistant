package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.state.AgentTask
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.state.TaskStatus
import com.example.automation.engine.state.TaskStep
import com.example.automation.engine.truth.ExecutionTruth
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.models.UiStep
import com.example.data.local.AgentTaskDao
import com.example.data.local.AgentTaskRecord
import com.example.data.local.AgentTaskStepRecord
import com.example.data.local.AgentApprovalRecord
import com.example.data.local.AgentTaskEventRecord
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0 Execution Truth Test Suite.
 * Validates the core architectural invariant:
 * LLM response ≠ execution result ≠ verification result.
 *
 * Covers 10 mandatory scenarios:
 * 1. Executor success
 * 2. Executor failure
 * 3. No tool dispatch
 * 4. Approval success
 * 5. Approval execution failure
 * 6. Intermediate-step failure (Step 1=SUCCESS, Step 2=FAILED, Step 3=NOT_EXECUTED)
 * 7. Verification failure (Tool success + Verifier failure = FAILED)
 * 8. Interrupted task state & recovery
 * 9. LLM says "completed" while executor says FAILED (Hallucination interception)
 * 10. Parent task completion derived strictly from child-step state
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecutionTruthUnitTest {

    private lateinit var context: Context
    private lateinit var fakeDao: FakeAgentTaskDao
    private lateinit var taskStore: AgentTaskStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeDao = FakeAgentTaskDao()
        taskStore = AgentTaskStore(fakeDao)
        ExecutionTruthCoordinator.clearTurnExecution()
        ApprovalCoordinator.cancel()
    }

    @After
    fun tearDown() {
        ExecutionTruthCoordinator.clearTurnExecution()
        ApprovalCoordinator.cancel()
    }

    // =========================================================================
    // 1. EXECUTOR SUCCESS
    // =========================================================================
    @Test
    fun testExecutorSuccess() = runBlocking {
        val taskId = "task_exec_succ_1"
        val stepId = "step_1"
        val execId = "exec_1"

        taskStore.createTask(taskId, "Send test message", totalSteps = 1)
        taskStore.recordStepStarted(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            actionType = "SEND_MESSAGE",
            stepIndex = 0
        )

        // Executor succeeds and verification succeeds
        taskStore.recordStepResult(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            executorSuccess = true,
            verifiedSuccess = true,
            failureReason = null
        )

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertTrue("Final success must be true", truth!!.finalSuccess)
        assertEquals("COMPLETED", truth.status)
        assertNull(truth.failureReason)

        val updatedStep = fakeDao.getStep(taskId, stepId)
        assertNotNull(updatedStep)
        assertEquals("SUCCEEDED", updatedStep!!.status)
        assertEquals("VERIFIED", updatedStep.verificationResult)

        val parentTask = taskStore.deriveAndPersistTaskCompletion(taskId)
        assertNotNull(parentTask)
        assertEquals("COMPLETED", parentTask!!.status)

        // LLM saying done remains unchanged
        val response = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse("Message sent successfully.")
        assertEquals("Message sent successfully.", response)
    }

    // =========================================================================
    // 2. EXECUTOR FAILURE
    // =========================================================================
    @Test
    fun testExecutorFailure() = runBlocking {
        val taskId = "task_exec_fail_1"
        val stepId = "step_1"
        val execId = "exec_1"

        taskStore.createTask(taskId, "Make phone call", totalSteps = 1)
        taskStore.recordStepStarted(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            actionType = "CALL_CONTACT",
            stepIndex = 0
        )

        // Executor fails
        taskStore.recordStepResult(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            executorSuccess = false,
            verifiedSuccess = false,
            failureReason = "No cellular network available"
        )

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertFalse("Final success must be false on executor failure", truth!!.finalSuccess)
        assertEquals("FAILED", truth.status)
        assertEquals("No cellular network available", truth.failureReason)

        val updatedStep = fakeDao.getStep(taskId, stepId)
        assertNotNull(updatedStep)
        assertEquals("FAILED", updatedStep!!.status)
        assertEquals("FAILED", updatedStep.verificationResult)

        val parentTask = taskStore.deriveAndPersistTaskCompletion(taskId)
        assertNotNull(parentTask)
        assertEquals("FAILED", parentTask!!.status)
        assertEquals("No cellular network available", parentTask.failureReason)
    }

    // =========================================================================
    // 3. NO TOOL DISPATCH
    // =========================================================================
    @Test
    fun testNoToolDispatch() = runBlocking {
        val taskId = "task_no_dispatch_1"
        val stepId = "step_1"
        val execId = "exec_none"

        // Simulated missing tool / skipped dispatch
        ExecutionTruthCoordinator.recordExecution(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            actionName = "UNKNOWN_TOOL",
            executorSuccess = false,
            verifiedSuccess = false,
            status = "FAILED",
            failureReason = "Tool 'UNKNOWN_TOOL' not found or unsupported",
            message = "No tool dispatched"
        )

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertFalse(truth!!.finalSuccess)
        assertEquals("FAILED", truth.status)

        // If the LLM hallucinates success without dispatching, it MUST be intercepted
        val hallucination = "I have completed the task and sent your message."
        val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(hallucination)
        assertTrue(
            "Corrected text must explain failure instead of hallucinating completion",
            corrected.contains("I was unable to complete the request: Tool 'UNKNOWN_TOOL' not found or unsupported.")
        )
    }

    // =========================================================================
    // 4. APPROVAL SUCCESS
    // =========================================================================
    @Test
    fun testApprovalSuccess() = runBlocking {
        val parentTaskId = "task_approval_success_1"
        val stepId = "step_approval_1"
        val execId = "exec_appr_1"

        taskStore.createTask(parentTaskId, "Send payment", totalSteps = 1)
        taskStore.recordStepWaitingApproval(
            taskId = parentTaskId,
            stepId = stepId,
            executionId = execId,
            actionType = "SEND_PAYMENT"
        )

        // Pending approval registered
        ApprovalCoordinator.requestApproval(
            context = context,
            action = "SEND_PAYMENT",
            target = "John Doe",
            payload = JSONObject().put("action", "SEND_PAYMENT").put("amount", "50"),
            parentTaskId = parentTaskId,
            stepId = stepId,
            executionId = execId
        )

        val pending = ApprovalCoordinator.currentPending()
        assertNotNull(pending)
        assertEquals(parentTaskId, pending!!.parentTaskId)
        assertEquals(stepId, pending.stepId)
        assertEquals(execId, pending.executionId)
        assertTrue(ApprovalCoordinator.hasPending())

        val stepRecord = fakeDao.getStep(parentTaskId, stepId)
        assertNotNull(stepRecord)
        assertEquals("WAITING_FOR_APPROVAL", stepRecord!!.status)

        // Simulate successful approval execution & propagation
        taskStore.recordStepResult(
            taskId = parentTaskId,
            stepId = stepId,
            executionId = execId,
            executorSuccess = true,
            verifiedSuccess = true,
            failureReason = null
        )
        val parent = taskStore.deriveAndPersistTaskCompletion(parentTaskId)
        assertNotNull(parent)
        assertEquals("COMPLETED", parent!!.status)

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertTrue(truth!!.finalSuccess)
    }

    // =========================================================================
    // 5. APPROVAL EXECUTION FAILURE
    // =========================================================================
    @Test
    fun testApprovalExecutionFailure() = runBlocking {
        val parentTaskId = "task_approval_fail_1"
        val stepId = "step_approval_1"
        val execId = "exec_appr_fail"

        taskStore.createTask(parentTaskId, "Transfer funds", totalSteps = 1)
        taskStore.recordStepWaitingApproval(
            taskId = parentTaskId,
            stepId = stepId,
            executionId = execId,
            actionType = "SEND_PAYMENT"
        )

        ApprovalCoordinator.requestApproval(
            context = context,
            action = "SEND_PAYMENT",
            target = "Jane",
            payload = JSONObject().put("action", "SEND_PAYMENT"),
            parentTaskId = parentTaskId,
            stepId = stepId,
            executionId = execId
        )

        // User approves, but underlying executor fails
        taskStore.recordStepResult(
            taskId = parentTaskId,
            stepId = stepId,
            executionId = execId,
            executorSuccess = false,
            verifiedSuccess = false,
            failureReason = "Payment gateway rejected transaction: Insufficient funds"
        )
        val parent = taskStore.deriveAndPersistTaskCompletion(parentTaskId)
        assertNotNull(parent)
        assertEquals("FAILED", parent!!.status)
        assertEquals("Payment gateway rejected transaction: Insufficient funds", parent.failureReason)

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertFalse(truth!!.finalSuccess)
        assertEquals("FAILED", truth.status)

        // Ensure LLM hallucination would be intercepted
        val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse("Done! Payment completed.")
        assertTrue(corrected.contains("Insufficient funds"))
    }

    // =========================================================================
    // 6. INTERMEDIATE-STEP FAILURE
    // Invariant: Step 1 = SUCCESS, Step 2 = FAILED, Step 3 = NOT_EXECUTED
    // must NEVER be reported as COMPLETED.
    // =========================================================================
    @Test
    fun testIntermediateStepFailure_NeverReportsCompleted() = runBlocking {
        val taskId = "task_multi_step_fail"

        val steps = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "step_1", actionType = "OPEN_APP", stepIndex = 0, status = "SUCCESS"),
            AgentTaskStepRecord(taskId = taskId, stepId = "step_2", actionType = "FIND_CONTACT", stepIndex = 1, status = "FAILED", failureReason = "Contact 'Bob' not found"),
            AgentTaskStepRecord(taskId = taskId, stepId = "step_3", actionType = "SEND_MESSAGE", stepIndex = 2, status = "NOT_EXECUTED")
        )

        taskStore.createTask(taskId, "Message Bob", totalSteps = 3)
        fakeDao.upsertSteps(steps)

        val parent = taskStore.deriveAndPersistTaskCompletion(taskId)
        assertNotNull(parent)
        assertEquals("Task with intermediate failure must be FAILED", "FAILED", parent!!.status)
        assertEquals("Contact 'Bob' not found", parent.failureReason)

        // Also test AgentTask state machine model
        val step1 = TaskStep(id = "step_1", action = UiStep.OpenApp("WhatsApp"), expectedOutcome = null)
        val step2 = TaskStep(id = "step_2", action = UiStep.Tap("Bob"), expectedOutcome = null)
        val step3 = TaskStep(id = "step_3", action = UiStep.Tap("Send"), expectedOutcome = null)

        val agentTask = AgentTask(
            id = taskId,
            originalIntent = "Message Bob",
            goal = "Send WhatsApp message to Bob",
            steps = listOf(step1, step2, step3),
            status = TaskStatus.RUNNING
        )

        // Step 1 success
        step1.status = "SUCCESS"
        agentTask.advance(lastStepSuccess = true)
        assertEquals(TaskStatus.RUNNING, agentTask.status)
        assertEquals(1, agentTask.currentStepIndex)

        // Step 2 failure
        step2.status = "FAILED"
        agentTask.advance(lastStepSuccess = false)
        assertEquals("AgentTask must transition to FAILED on intermediate step failure", TaskStatus.FAILED, agentTask.status)
    }

    // =========================================================================
    // 7. VERIFICATION FAILURE
    // Tool returns success = true, but verifier returns success = false
    // =========================================================================
    @Test
    fun testVerificationFailure_OverridesExecutorSuccess() = runBlocking {
        val taskId = "task_verify_fail_1"
        val stepId = "step_1"
        val execId = "exec_ver_fail"

        taskStore.createTask(taskId, "Open settings", totalSteps = 1)
        taskStore.recordStepStarted(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            actionType = "OPEN_APP",
            stepIndex = 0
        )

        // Executor returned true, but verification failed!
        taskStore.recordStepResult(
            taskId = taskId,
            stepId = stepId,
            executionId = execId,
            executorSuccess = true,
            verifiedSuccess = false,
            failureReason = "Verification failed: Expected Settings in foreground, but Launcher detected"
        )

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertTrue("executorSuccess was true", truth!!.executorSuccess)
        assertFalse("verifiedSuccess was false", truth.verifiedSuccess)
        assertFalse("finalSuccess must be false when verification fails!", truth.finalSuccess)
        assertEquals("FAILED", truth.status)

        val step = fakeDao.getStep(taskId, stepId)
        assertNotNull(step)
        assertEquals("FAILED", step!!.status)
        assertEquals("FAILED", step.verificationResult)

        val parent = taskStore.deriveAndPersistTaskCompletion(taskId)
        assertNotNull(parent)
        assertEquals("FAILED", parent!!.status)

        // LLM hallucinating "Opened settings" must be corrected
        val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse("Opened the settings app for you.")
        assertTrue(
            "Response must be corrected when verification fails",
            corrected.contains("Verification failed: Expected Settings in foreground, but Launcher detected")
        )
    }

    // =========================================================================
    // 8. INTERRUPTION / RECOVERY STATE
    // =========================================================================
    @Test
    fun testInterruptedTask_RecoveryState() = runBlocking {
        val taskId = "task_interrupted_1"

        taskStore.createTask(taskId, "Download large file", totalSteps = 5)
        val initialTask = fakeDao.getTask(taskId)
        assertNotNull(initialTask)
        assertEquals("READY", initialTask!!.status)
        assertNull(initialTask.interruptedAt)

        // Mark interrupted
        taskStore.markInterrupted(taskId, "Process killed by system low memory")
        val interruptedTask = fakeDao.getTask(taskId)
        assertNotNull(interruptedTask)
        assertEquals("INTERRUPTED", interruptedTask!!.status)
        assertEquals("Process killed by system low memory", interruptedTask.failureReason)
        assertNotNull("interruptedAt must be populated", interruptedTask.interruptedAt)

        // Verify recoverable tasks query includes interrupted task for recovery
        val recoverableTasks = fakeDao.getRecoverableTasks()
        assertTrue(recoverableTasks.any { it.taskId == taskId })

        // Resume interrupted task
        val resumedTask = taskStore.resumeInterruptedTask(taskId)
        assertNotNull(resumedTask)
        assertEquals("RUNNING", resumedTask!!.status)
        assertNull("interruptedAt must be cleared upon resume", resumedTask.interruptedAt)
    }

    // =========================================================================
    // 9. LLM SAYS "COMPLETED" WHILE EXECUTOR SAYS FAILED
    // =========================================================================
    @Test
    fun testLlmClaimsCompletedWhileExecutorFailed_Intercepted() {
        // Record failure
        ExecutionTruthCoordinator.recordExecution(
            taskId = "task_test_hallucination",
            stepId = "step_1",
            executionId = "exec_fail",
            actionName = "SEND_SMS",
            executorSuccess = false,
            verifiedSuccess = false,
            failureReason = "SIM card not ready",
            message = "Radio off"
        )

        // Test various hallucinated completion expressions
        val hallucinations = listOf(
            "Done! Your message has been sent.",
            "I've completed the task sir.",
            "Finished sending the message successfully.",
            "All set! Sent your message to Mom.",
            "Task is complete."
        )

        for (text in hallucinations) {
            val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(text)
            assertFalse(
                "Must not preserve false completion claim: '$text'",
                corrected == text
            )
            assertTrue(
                "Corrected text must explain failure: '$corrected'",
                corrected.contains("I was unable to complete the request: SIM card not ready.")
            )
        }

        // Truthful or non-completion phrases must NOT be altered
        val neutralText = "Would you like me to try sending via WhatsApp instead?"
        assertEquals(neutralText, ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(neutralText))
    }

    // =========================================================================
    // 10. PARENT TASK COMPLETION DERIVED STRICTLY FROM CHILD-STEP STATE
    // =========================================================================
    @Test
    fun testParentTaskCompletionDerivedStrictlyFromChildSteps() {
        val taskId = "task_matrix"

        // Case A: Empty steps -> FAILED
        val (statusEmpty, reasonEmpty) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(emptyList())
        assertEquals("FAILED", statusEmpty)
        assertEquals("Task has no execution steps", reasonEmpty)

        // Case B: All steps SUCCESS -> COMPLETED
        val stepsAllSuccess = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "s1", actionType = "A", stepIndex = 0, status = "SUCCESS", verificationResult = "VERIFIED"),
            AgentTaskStepRecord(taskId = taskId, stepId = "s2", actionType = "B", stepIndex = 1, status = "COMPLETED", verificationResult = "VERIFIED")
        )
        val (statusAllSuccess, _) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsAllSuccess)
        assertEquals("COMPLETED", statusAllSuccess)
        assertNotEquals("COMPLETED", ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsAllSuccess.map { it.copy(verificationResult = null) }).first)

        // Case C: One step FAILED, one unexecuted -> FAILED
        val stepsWithFail = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "s1", actionType = "A", stepIndex = 0, status = "SUCCESS"),
            AgentTaskStepRecord(taskId = taskId, stepId = "s2", actionType = "B", stepIndex = 1, status = "FAILED", failureReason = "Target missing"),
            AgentTaskStepRecord(taskId = taskId, stepId = "s3", actionType = "C", stepIndex = 2, status = "NOT_EXECUTED")
        )
        val (statusFail, reasonFail) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsWithFail)
        assertEquals("FAILED", statusFail)
        assertEquals("Target missing", reasonFail)

        // Case D: One step CANCELLED -> CANCELLED
        val stepsCancelled = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "s1", actionType = "A", stepIndex = 0, status = "CANCELLED", failureReason = "User denied")
        )
        val (statusCancel, reasonCancel) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsCancelled)
        assertEquals("CANCELLED", statusCancel)
        assertEquals("User denied", reasonCancel)

        // Case E: One step WAITING_FOR_APPROVAL -> WAITING_FOR_APPROVAL
        val stepsApproval = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "s1", actionType = "A", stepIndex = 0, status = "SUCCESS"),
            AgentTaskStepRecord(taskId = taskId, stepId = "s2", actionType = "B", stepIndex = 1, status = "WAITING_FOR_APPROVAL")
        )
        val (statusAppr, _) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsApproval)
        assertEquals("WAITING_FOR_APPROVAL", statusAppr)

        // Case F: One step RUNNING -> RUNNING
        val stepsRunning = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "s1", actionType = "A", stepIndex = 0, status = "SUCCESS"),
            AgentTaskStepRecord(taskId = taskId, stepId = "s2", actionType = "B", stepIndex = 1, status = "RUNNING")
        )
        val (statusRun, _) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsRunning)
        assertEquals("RUNNING", statusRun)

        // Case G: Intermediate step skipped/unexecuted -> FAILED
        val stepsUnexecuted = listOf(
            AgentTaskStepRecord(taskId = taskId, stepId = "s1", actionType = "A", stepIndex = 0, status = "SUCCESS"),
            AgentTaskStepRecord(taskId = taskId, stepId = "s2", actionType = "B", stepIndex = 1, status = "NOT_EXECUTED")
        )
        val (statusUnex, _) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(stepsUnexecuted)
        assertEquals("FAILED", statusUnex)
    }

    // =========================================================================
    // 11. SCREENSHOT SCENARIO BEHAVIOR
    // Capture unavailable must NOT report success, and LLM hallucination must be caught
    // =========================================================================
    @Test
    fun testScreenshotScenario_FailureNeverBecomesSuccess() = runBlocking {
        val tool = com.example.automation.tools.CameraTool(com.example.automation.actions.CameraAction())
        val req = com.example.automation.engine.ExecutionRequest(action = "TAKE_SCREENSHOT")

        // In test environment without screen capture / accessibility, tool must report failure
        val toolResult = tool.execute(context, req)
        assertFalse("ToolResult must report false when screenshot capture is unavailable", toolResult.success)

        // Record truth from failure
        ExecutionTruthCoordinator.recordExecution(
            executionId = "exec_screenshot_fail",
            actionName = "TAKE_SCREENSHOT",
            executorSuccess = toolResult.success,
            verifiedSuccess = toolResult.success,
            failureReason = toolResult.message
        )

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertFalse(truth!!.finalSuccess)
        assertEquals("FAILED", truth.status)

        // LLM hallucinating that screenshot was captured
        val hallucination = "I have captured the screenshot for you."
        val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(hallucination)
        assertTrue(
            "Hallucinated completion must be intercepted and replaced with truth",
            corrected.startsWith("I was unable to complete the request:")
        )
    }

    // =========================================================================
    // FAKE DAO FOR DETERMINISTIC TESTING
    // =========================================================================
    class FakeAgentTaskDao : AgentTaskDao {
        private val tasks = mutableMapOf<String, AgentTaskRecord>()
        private val steps = mutableMapOf<String, MutableMap<String, AgentTaskStepRecord>>()

        override suspend fun upsertTask(task: AgentTaskRecord) {
            tasks[task.taskId] = task
        }

        override suspend fun getTask(taskId: String): AgentTaskRecord? = tasks[taskId]

        override suspend fun getAllTasks(): List<AgentTaskRecord> = tasks.values.toList()

        override suspend fun getRecoverableTasks(): List<AgentTaskRecord> =
            tasks.values.filter { it.status in listOf("READY", "RUNNING", "INTERRUPTED", "WAITING_FOR_APPROVAL") }

        override suspend fun updateStatus(taskId: String, status: String, reason: String?, updatedAt: Long, interruptedAt: Long?) {
            val t = tasks[taskId] ?: return
            tasks[taskId] = t.copy(
                status = status,
                failureReason = reason ?: t.failureReason,
                updatedAt = updatedAt,
                interruptedAt = interruptedAt
            )
        }

        override suspend fun requestCancellation(taskId: String, reason: String, updatedAt: Long) {
            val task = tasks[taskId] ?: return
            if (task.status !in setOf("COMPLETED", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED", "UNVERIFIED")) {
                tasks[taskId] = task.copy(status = "CANCEL_REQUESTED", cancellationReason = reason, updatedAt = updatedAt)
            }
        }

        override suspend fun upsertSteps(newSteps: List<AgentTaskStepRecord>) {
            for (s in newSteps) {
                val taskSteps = steps.getOrPut(s.taskId) { mutableMapOf() }
                taskSteps[s.stepId] = s
            }
        }

        override suspend fun getSteps(taskId: String): List<AgentTaskStepRecord> =
            steps[taskId]?.values?.sortedBy { it.stepIndex } ?: emptyList()

        override suspend fun getStep(taskId: String, stepId: String): AgentTaskStepRecord? =
            steps[taskId]?.get(stepId)

        override suspend fun deleteTask(taskId: String) {
            tasks.remove(taskId)
        }

        override suspend fun deleteSteps(taskId: String) {
            steps.remove(taskId)
        }

        private val approvals = mutableMapOf<String, AgentApprovalRecord>()
        private val events = mutableMapOf<String, AgentTaskEventRecord>()

        override suspend fun upsertApproval(approval: AgentApprovalRecord) {
            approvals[approval.approvalId] = approval
        }

        override suspend fun getApproval(approvalId: String): AgentApprovalRecord? = approvals[approvalId]

        override suspend fun getPendingApprovalsForTask(taskId: String): List<AgentApprovalRecord> =
            approvals.values.filter { it.taskId == taskId && it.status == "PENDING" }

        override suspend fun getAllPendingApprovals(): List<AgentApprovalRecord> =
            approvals.values.filter { it.status == "PENDING" }

        override suspend fun consumeApproval(approvalId: String, status: String, consumedAt: Long, consumedEventId: String?): Int {
            val app = approvals[approvalId] ?: return 0
            if (app.status != "PENDING") return 0
            approvals[approvalId] = app.copy(status = status, consumedAt = consumedAt, consumedEventId = consumedEventId)
            return 1
        }

        override suspend fun deleteApprovalsForTask(taskId: String) {
            approvals.values.removeAll { it.taskId == taskId }
        }

        override suspend fun insertEvent(event: AgentTaskEventRecord): Long {
            if (events.containsKey(event.idempotencyKey)) {
                throw IllegalStateException("Duplicate event with idempotencyKey: ${event.idempotencyKey}")
            }
            events[event.idempotencyKey] = event
            return 1L
        }

        override suspend fun getEventByIdempotencyKey(idempotencyKey: String): AgentTaskEventRecord? =
            events[idempotencyKey]

        override suspend fun getEventsForTask(taskId: String): List<AgentTaskEventRecord> =
            events.values.filter { it.taskId == taskId }.sortedBy { it.timestamp }
    }
}
