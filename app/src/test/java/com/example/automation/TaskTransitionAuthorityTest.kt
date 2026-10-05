package com.example.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.TaskTransitionAuthority
import com.example.automation.state.ActionStep
import com.example.data.local.AgentTaskDao
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskTransitionAuthorityTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AgentTaskDao
    private lateinit var store: AgentTaskStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.agentTaskDao()
        store = AgentTaskStore(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testValidLifecycleProgression_Succeeds() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_1"
        val executionId = "exec_1"

        store.create(
            taskId = taskId,
            goal = "Test valid lifecycle",
            steps = listOf(ActionStep(stepId = stepId, actionType = "OPEN_APP", targetText = "Settings"))
        )

        // 1. READY -> DISPATCHING
        val res1 = store.transitionStep(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = TaskTransitionAuthority.STATUS_DISPATCHING,
            actor = "ActionDispatcher"
        )
        assertTrue("READY -> DISPATCHING must succeed", res1 is TaskTransitionAuthority.TransitionResult.Success)

        // 2. DISPATCHING -> RUNNING
        val res2 = store.transitionStep(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = TaskTransitionAuthority.STATUS_RUNNING,
            actor = "ExecutionEngine"
        )
        assertTrue("DISPATCHING -> RUNNING must succeed", res2 is TaskTransitionAuthority.TransitionResult.Success)

        // 3. RUNNING -> VERIFYING
        val res3 = store.transitionStep(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = TaskTransitionAuthority.STATUS_VERIFYING,
            actor = "ExecutionEngine"
        )
        assertTrue("RUNNING -> VERIFYING must succeed", res3 is TaskTransitionAuthority.TransitionResult.Success)

        // 4. VERIFYING -> SUCCEEDED by Verifier
        val res4 = store.transitionStep(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = TaskTransitionAuthority.STATUS_SUCCEEDED,
            actor = "Verifier",
            verificationEvidence = "SettingsAppInForeground"
        )
        assertTrue("VERIFYING -> SUCCEEDED must succeed", res4 is TaskTransitionAuthority.TransitionResult.Success)

        // Check parent derived completion
        val parent = store.getTask(taskId)
        assertNotNull(parent)
        assertEquals(TaskTransitionAuthority.STATUS_COMPLETED, parent?.status)
    }

    @Test
    fun testIllegalDirectTransition_IsRejected() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_skip"
        val executionId = "exec_skip"

        store.create(
            taskId = taskId,
            goal = "Test illegal skip",
            steps = listOf(ActionStep(stepId = stepId, actionType = "SEND_SMS"))
        )

        // Attempting to skip directly from READY to SUCCEEDED without dispatch/run/verify
        val res = store.transitionStep(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = TaskTransitionAuthority.STATUS_SUCCEEDED,
            actor = "Verifier"
        )
        assertTrue("Direct READY -> SUCCEEDED without execution must be REJECTED", res is TaskTransitionAuthority.TransitionResult.Rejected)

        val step = store.getStep(taskId, stepId)
        assertEquals("READY", step?.status)
    }

    @Test
    fun testUnauthorizedActor_IsRejected() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_unauth"
        val executionId = "exec_unauth"

        store.create(
            taskId = taskId,
            goal = "Test unauthorized actor",
            steps = listOf(ActionStep(stepId = stepId, actionType = "CLICK_UI"))
        )

        store.transitionStep(taskId, stepId, executionId, TaskTransitionAuthority.STATUS_DISPATCHING, "ActionDispatcher")
        store.transitionStep(taskId, stepId, executionId, TaskTransitionAuthority.STATUS_RUNNING, "ExecutionEngine")
        store.transitionStep(taskId, stepId, executionId, TaskTransitionAuthority.STATUS_VERIFYING, "ExecutionEngine")

        // Untrusted actor attempting to declare success
        val unauthRes = store.transitionStep(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = TaskTransitionAuthority.STATUS_SUCCEEDED,
            actor = "UntrustedIngress"
        )
        assertTrue("Untrusted actor declaring SUCCEEDED must be REJECTED", unauthRes is TaskTransitionAuthority.TransitionResult.Rejected)
    }

    @Test
    fun testChildFailure_PreventsParentCompletion() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val step1 = "step_ok"
        val step2 = "step_fail"

        store.create(
            taskId = taskId,
            goal = "Multi-step failure test",
            steps = listOf(
                ActionStep(stepId = step1, actionType = "OPEN_APP"),
                ActionStep(stepId = step2, actionType = "SEND_WHATSAPP")
            )
        )

        // Step 1 succeeds
        store.transitionStep(taskId, step1, "exec_1", TaskTransitionAuthority.STATUS_DISPATCHING, "ActionDispatcher")
        store.transitionStep(taskId, step1, "exec_1", TaskTransitionAuthority.STATUS_RUNNING, "ExecutionEngine")
        store.transitionStep(taskId, step1, "exec_1", TaskTransitionAuthority.STATUS_VERIFYING, "ExecutionEngine")
        store.transitionStep(taskId, step1, "exec_1", TaskTransitionAuthority.STATUS_SUCCEEDED, "Verifier")

        // Step 2 fails
        store.transitionStep(taskId, step2, "exec_2", TaskTransitionAuthority.STATUS_DISPATCHING, "ActionDispatcher")
        store.transitionStep(taskId, step2, "exec_2", TaskTransitionAuthority.STATUS_RUNNING, "ExecutionEngine")
        store.transitionStep(
            taskId = taskId,
            stepId = step2,
            executionId = "exec_2",
            targetStatus = TaskTransitionAuthority.STATUS_FAILED,
            actor = "ExecutionEngine",
            failureReason = "Network timeout"
        )

        val parent = store.getTask(taskId)
        assertNotNull(parent)
        assertEquals("Parent task must be FAILED when any child step fails", TaskTransitionAuthority.STATUS_FAILED, parent?.status)
        assertEquals("Network timeout", parent?.failureReason)
    }
}
