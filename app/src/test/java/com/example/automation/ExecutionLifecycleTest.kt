package com.example.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.ExecutionLifecycleEvent
import com.example.automation.engine.truth.ExecutionTruth
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.engine.truth.TaskTransitionAuthority
import com.example.automation.state.ActionStep
import com.example.data.local.AgentTaskDao
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Phase E: Canonical Execution Lifecycle & Truth Test Suite.
 *
 * Verifies:
 * 1. Canonical lifecycle event sequence (Requested -> Started -> Returned -> Verifying -> Verified -> StepCompleted -> TaskCompleted).
 * 2. Executor failure transitions step to FAILED.
 * 3. Verifier failure overrides executor success and marks step FAILED.
 * 4. False-success interception overrides LLM claims of success when execution/verification failed.
 * 5. Parent task completion requires all steps to have verified success.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecutionLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AgentTaskDao
    private lateinit var taskStore: AgentTaskStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.agentTaskDao()
        taskStore = AgentTaskStore(dao)
        ExecutionTruthCoordinator.clearTurnExecution()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testCanonicalLifecycle_HappyPath() = runBlocking {
        val taskId = "task_p0_1"
        val stepId = "step_p0_1"
        val executionId = "exec_p0_1"

        // 1. Initial Task and Step in Room
        taskStore.create(
            taskId = taskId,
            goal = "Test Launch App",
            steps = listOf(ActionStep(stepId = stepId, actionType = "LAUNCH_APP"))
        )

        // 2. ExecutionRequested -> DISPATCHING
        val reqEvent = ExecutionLifecycleEvent.ExecutionRequested(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            action = "LAUNCH_APP"
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(reqEvent, taskStore)
        assertEquals(TaskTransitionAuthority.STATUS_DISPATCHING, taskStore.getStep(taskId, stepId)?.status)

        // 3. ExecutionStarted -> RUNNING
        val startEvent = ExecutionLifecycleEvent.ExecutionStarted(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            action = "LAUNCH_APP"
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(startEvent, taskStore)
        assertEquals(TaskTransitionAuthority.STATUS_RUNNING, taskStore.getStep(taskId, stepId)?.status)

        // 4. ExecutionReturned -> VERIFYING (executor returned true)
        val returnEvent = ExecutionLifecycleEvent.ExecutionReturned(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            action = "LAUNCH_APP",
            executorSuccess = true,
            rawResultJson = """{"launched":true}"""
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(returnEvent, taskStore)
        assertEquals(TaskTransitionAuthority.STATUS_VERIFYING, taskStore.getStep(taskId, stepId)?.status)

        // 5. VerificationReturned -> SUCCEEDED (verifier returned true)
        val vrfEvent = ExecutionLifecycleEvent.VerificationReturned(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            verificationId = "vrf_1",
            verified = true,
            evidence = "Foreground package is com.example.target"
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(vrfEvent, taskStore)
        assertEquals(TaskTransitionAuthority.STATUS_SUCCEEDED, taskStore.getStep(taskId, stepId)?.status)

        // 6. Verify ExecutionTruthCoordinator recorded successful truth
        val lastTruth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(lastTruth)
        assertTrue(lastTruth!!.finalSuccess)
        assertEquals("SUCCEEDED", taskStore.getStep(taskId, stepId)?.status)

        // 7. Verify Parent Task is marked COMPLETED
        val parent = taskStore.getTask(taskId)
        assertNotNull(parent)
        assertEquals(TaskTransitionAuthority.STATUS_COMPLETED, parent?.status)
    }

    @Test
    fun testCanonicalLifecycle_ExecutorFails() = runBlocking {
        val taskId = "task_p0_2"
        val stepId = "step_p0_2"
        val executionId = "exec_p0_2"

        taskStore.create(
            taskId = taskId,
            goal = "Test Call Failure",
            steps = listOf(ActionStep(stepId = stepId, actionType = "CALL_PHONE"))
        )

        // Transition to RUNNING
        ExecutionTruthCoordinator.recordLifecycleEvent(
            ExecutionLifecycleEvent.ExecutionRequested(taskId = taskId, stepId = stepId, executionId = executionId, action = "CALL_PHONE"),
            taskStore
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(
            ExecutionLifecycleEvent.ExecutionStarted(taskId = taskId, stepId = stepId, executionId = executionId, action = "CALL_PHONE"),
            taskStore
        )

        // Executor returns failure
        val returnEvent = ExecutionLifecycleEvent.ExecutionReturned(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            action = "CALL_PHONE",
            executorSuccess = false,
            failureReason = "SIM not ready"
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(returnEvent, taskStore)

        assertEquals(TaskTransitionAuthority.STATUS_FAILED, taskStore.getStep(taskId, stepId)?.status)
        assertEquals("SIM not ready", taskStore.getStep(taskId, stepId)?.failureReason)

        val parent = taskStore.getTask(taskId)
        assertEquals(TaskTransitionAuthority.STATUS_FAILED, parent?.status)
        assertEquals("SIM not ready", parent?.failureReason)
    }

    @Test
    fun testCanonicalLifecycle_VerifierFails_OverridesExecutorSuccess() = runBlocking {
        val taskId = "task_p0_3"
        val stepId = "step_p0_3"
        val executionId = "exec_p0_3"

        taskStore.create(
            taskId = taskId,
            goal = "Test WhatsApp Verifier Failure",
            steps = listOf(ActionStep(stepId = stepId, actionType = "SEND_WHATSAPP"))
        )

        ExecutionTruthCoordinator.recordLifecycleEvent(
            ExecutionLifecycleEvent.ExecutionRequested(taskId = taskId, stepId = stepId, executionId = executionId, action = "SEND_WHATSAPP"),
            taskStore
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(
            ExecutionLifecycleEvent.ExecutionStarted(taskId = taskId, stepId = stepId, executionId = executionId, action = "SEND_WHATSAPP"),
            taskStore
        )

        // Executor returned true
        val returnEvent = ExecutionLifecycleEvent.ExecutionReturned(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            action = "SEND_WHATSAPP",
            executorSuccess = true
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(returnEvent, taskStore)
        assertEquals(TaskTransitionAuthority.STATUS_VERIFYING, taskStore.getStep(taskId, stepId)?.status)

        // Verifier returned false
        val vrfEvent = ExecutionLifecycleEvent.VerificationReturned(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            verificationId = "vrf_fail",
            verified = false,
            failureReason = "Send button still visible; message not sent"
        )
        ExecutionTruthCoordinator.recordLifecycleEvent(vrfEvent, taskStore)

        assertEquals(TaskTransitionAuthority.STATUS_FAILED, taskStore.getStep(taskId, stepId)?.status)
        assertEquals("Send button still visible; message not sent", taskStore.getStep(taskId, stepId)?.failureReason)

        val truth = ExecutionTruthCoordinator.getLastExecution()
        assertNotNull(truth)
        assertFalse(truth!!.finalSuccess)

        // Ensure LLM claiming success is intercepted and corrected
        val llmClaim = "Done sir! I have sent the message successfully."
        val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(llmClaim)
        assertTrue(corrected.contains("unable to complete the request"))
        assertTrue(corrected.contains("Send button still visible; message not sent"))
    }

    @Test
    fun testFalseSuccessInterception_MultiplePhrases() {
        val failedTruth = ExecutionTruth(
            taskId = "t1",
            stepId = "s1",
            executionId = "e1",
            actionName = "CLICK",
            executorSuccess = false,
            verifiedSuccess = false,
            status = "FAILED",
            failureReason = "Node not found"
        )
        ExecutionTruthCoordinator.recordExecution(failedTruth)

        val testCases = listOf(
            "I've completed the task sir.",
            "Sent your message to John.",
            "Task is complete.",
            "Called Mom now.",
            "Screenshot captured successfully."
        )

        for (phrase in testCases) {
            val corrected = ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(phrase)
            assertTrue("Expected correction for: $phrase", corrected.startsWith("I was unable to complete the request:"))
        }
    }
}
