package com.example.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.state.ActionStep
import com.example.data.local.AgentTaskDao
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskScopedApprovalTest {

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
        ApprovalCoordinator.clearAll()
    }

    @After
    fun tearDown() {
        ApprovalCoordinator.clearAll()
        db.close()
    }

    @Test
    fun testTaskScopedApproval_HappyPath() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_transfer_1"
        val execId = "exec_1"

        store.create(
            taskId = taskId,
            goal = "Transfer money",
            steps = listOf(ActionStep(stepId = stepId, actionType = "TRANSFER_FUNDS", targetText = "Alice"))
        )

        val payload = JSONObject().apply {
            put("action", "TRANSFER_FUNDS")
            put("recipient", "Alice")
            put("amount", 100)
        }

        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "TRANSFER_FUNDS",
            target = "Alice",
            payload = payload,
            displayLabel = "Send 100 to Alice",
            parentTaskId = taskId,
            stepId = stepId,
            executionId = execId,
            taskStore = store
        )

        // Verify registered in memory and Room
        assertTrue(ApprovalCoordinator.hasPending(taskId))
        val currentPending = ApprovalCoordinator.currentPending(taskId = taskId)
        assertNotNull(currentPending)
        assertEquals(approvalId, currentPending!!.approvalId)
        assertEquals(taskId, currentPending.parentTaskId)
        assertEquals(stepId, currentPending.stepId)

        val roomRecord = dao.getApproval(approvalId)
        assertNotNull(roomRecord)
        assertEquals("PENDING", roomRecord!!.status)
        assertEquals(currentPending.payloadHash, roomRecord.payloadHash)

        // Verify step status in Room
        val stepRecord = dao.getStep(taskId, stepId)
        assertNotNull(stepRecord)
        assertEquals("WAITING_FOR_APPROVAL", stepRecord!!.status)

        // Handle reply with matching taskId and stepId
        val result = ApprovalCoordinator.handleReply(
            reply = "yes",
            context = context,
            taskStore = store,
            expectedTaskId = taskId,
            expectedStepId = stepId
        )

        assertTrue(result.handled)
        assertTrue(result.approved)
        assertEquals(approvalId, result.approvalId)

        // Verify consumed in Room
        val consumedRecord = dao.getApproval(approvalId)
        assertNotNull(consumedRecord)
        assertEquals("CONSUMED", consumedRecord!!.status)
        assertNotNull(consumedRecord.consumedAt)

        // In-memory pending map cleared
        assertFalse(ApprovalCoordinator.hasPending(taskId))
    }

    @Test
    fun testTaskMismatch_IsRejected() = runBlocking {
        val taskA = "task_A_" + UUID.randomUUID().toString()
        val taskB = "task_B_" + UUID.randomUUID().toString()
        val stepId = "step_1"

        store.create(taskId = taskA, goal = "Goal A", steps = listOf(ActionStep(stepId = stepId, actionType = "CALL")))
        store.create(taskId = taskB, goal = "Goal B", steps = listOf(ActionStep(stepId = stepId, actionType = "SMS")))

        val payload = JSONObject().apply { put("action", "CALL") }

        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "CALL",
            target = "Bob",
            payload = payload,
            parentTaskId = taskA,
            stepId = stepId,
            taskStore = store
        )

        // Attempt to approve for taskB
        val result = ApprovalCoordinator.handleReply(
            reply = "yes",
            context = context,
            taskStore = store,
            expectedTaskId = taskB
        )

        assertFalse(result.approved)
        assertTrue(result.message.contains("Task mismatch") || result.message.contains("No pending approval"))

        // Original approval must remain PENDING in Room
        val roomRecord = dao.getApproval(approvalId)
        assertNotNull(roomRecord)
        assertEquals("PENDING", roomRecord!!.status)
    }

    @Test
    fun testStepMismatch_IsRejected() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val step1 = "step_1"
        val step2 = "step_2"

        store.create(
            taskId = taskId,
            goal = "Multi-step goal",
            steps = listOf(
                ActionStep(stepId = step1, actionType = "STEP_1"),
                ActionStep(stepId = step2, actionType = "STEP_2")
            )
        )

        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "STEP_1",
            target = null,
            payload = JSONObject().apply { put("data", "1") },
            parentTaskId = taskId,
            stepId = step1,
            taskStore = store
        )

        // Attempt to approve with expectedStepId = step2
        val result = ApprovalCoordinator.handleReply(
            reply = "yes",
            context = context,
            taskStore = store,
            expectedTaskId = taskId,
            expectedStepId = step2
        )

        assertFalse(result.approved)
        assertTrue(result.message.contains("Step mismatch"))

        // Original approval must remain PENDING
        val roomRecord = dao.getApproval(approvalId)
        assertNotNull(roomRecord)
        assertEquals("PENDING", roomRecord!!.status)
    }

    @Test
    fun testTamperedPayload_IsRejected() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_1"

        store.create(taskId = taskId, goal = "Pay", steps = listOf(ActionStep(stepId = stepId, actionType = "PAY")))

        val payload = JSONObject().apply { put("amount", 10) }
        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "PAY",
            target = null,
            payload = payload,
            parentTaskId = taskId,
            stepId = stepId,
            taskStore = store
        )

        // Tamper payload after approval request was recorded
        val pending = ApprovalCoordinator.currentPending(taskId = taskId)
        assertNotNull(pending)
        pending!!.payload.put("amount", 1000000) // Tampered!

        val result = ApprovalCoordinator.handleReply(
            reply = "allow",
            context = context,
            taskStore = store,
            expectedTaskId = taskId
        )

        assertFalse(result.approved)
        assertTrue(result.message.contains("tampered") || result.message.contains("hash mismatch"))

        // Approval must NOT be consumed as success
        val roomRecord = dao.getApproval(approvalId)
        assertNotNull(roomRecord)
        assertEquals("PENDING", roomRecord!!.status)
    }

    @Test
    fun testExpiredApproval_IsRejected() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_1"

        store.create(taskId = taskId, goal = "Fast expire", steps = listOf(ActionStep(stepId = stepId, actionType = "FAST")))

        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "FAST",
            target = null,
            payload = JSONObject().apply { put("k", "v") },
            parentTaskId = taskId,
            stepId = stepId,
            taskStore = store,
            timeoutMs = 1L // 1 millisecond
        )

        Thread.sleep(10) // Wait for expiry

        val result = ApprovalCoordinator.handleReply(
            reply = "allow",
            context = context,
            taskStore = store,
            expectedTaskId = taskId
        )

        assertFalse(result.approved)
        assertTrue(result.message.contains("expired"))

        val roomRecord = dao.getApproval(approvalId)
        assertNotNull(roomRecord)
        assertEquals("EXPIRED", roomRecord!!.status)
    }

    @Test
    fun testReplayOrDuplicateConsumption_IsRejected() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_1"

        store.create(taskId = taskId, goal = "Replay test", steps = listOf(ActionStep(stepId = stepId, actionType = "ACT")))

        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "ACT",
            target = null,
            payload = JSONObject().apply { put("test", true) },
            parentTaskId = taskId,
            stepId = stepId,
            taskStore = store
        )

        // Pre-consume in DB to simulate replay or concurrent consumption
        val consumedFirst = dao.consumeApproval(approvalId, "CONSUMED", System.currentTimeMillis(), "first_consumer")
        assertEquals(1, consumedFirst)

        val result = ApprovalCoordinator.handleReply(
            reply = "allow",
            context = context,
            taskStore = store,
            expectedTaskId = taskId
        )

        assertFalse(result.approved)
        assertTrue(result.message.contains("already consumed") || result.message.contains("no longer pending") || result.message.contains("replay"))
    }

    @Test
    fun testRecoveryFromRoomAfterProcessDeath() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_1"

        store.create(taskId = taskId, goal = "Death test", steps = listOf(ActionStep(stepId = stepId, actionType = "DO")))

        val approvalId = ApprovalCoordinator.requestApproval(
            context = context,
            action = "DO",
            target = null,
            payload = JSONObject().apply { put("recovered", 42) },
            parentTaskId = taskId,
            stepId = stepId,
            taskStore = store
        )

        // Clear in-memory state simulating process kill
        ApprovalCoordinator.clearAll()
        assertFalse(ApprovalCoordinator.hasPending())

        // Re-attempt reply: coordinator rehydrates from Room!
        val result = ApprovalCoordinator.handleReply(
            reply = "allow",
            context = context,
            taskStore = store,
            expectedTaskId = taskId
        )

        assertTrue(result.handled)
        assertTrue(result.approved)
        assertEquals(approvalId, result.approvalId)

        val roomRecord = dao.getApproval(approvalId)
        assertNotNull(roomRecord)
        assertEquals("CONSUMED", roomRecord!!.status)
    }

    @Test
    fun testConcurrentApprovals_DoNotOverwriteEachOther() = runBlocking {
        val task1 = "task_1_" + UUID.randomUUID().toString()
        val task2 = "task_2_" + UUID.randomUUID().toString()

        store.create(taskId = task1, goal = "G1", steps = listOf(ActionStep(stepId = "s1", actionType = "A1")))
        store.create(taskId = task2, goal = "G2", steps = listOf(ActionStep(stepId = "s2", actionType = "A2")))

        val id1 = ApprovalCoordinator.requestApproval(
            context = context,
            action = "A1",
            target = "T1",
            payload = JSONObject().apply { put("n", 1) },
            parentTaskId = task1,
            stepId = "s1",
            taskStore = store
        )

        val id2 = ApprovalCoordinator.requestApproval(
            context = context,
            action = "A2",
            target = "T2",
            payload = JSONObject().apply { put("n", 2) },
            parentTaskId = task2,
            stepId = "s2",
            taskStore = store
        )

        // Both are active and distinct
        assertTrue(ApprovalCoordinator.hasPending(task1))
        assertTrue(ApprovalCoordinator.hasPending(task2))
        assertEquals(2, ApprovalCoordinator.getAllPending().size)

        // Consume task 1 only
        val res1 = ApprovalCoordinator.handleReply(
            reply = "allow",
            context = context,
            taskStore = store,
            expectedTaskId = task1
        )
        assertTrue(res1.approved)
        assertEquals(id1, res1.approvalId)

        // Task 2 must STILL be pending!
        assertFalse(ApprovalCoordinator.hasPending(task1))
        assertTrue(ApprovalCoordinator.hasPending(task2))
        val pending2 = ApprovalCoordinator.currentPending(taskId = task2)
        assertNotNull(pending2)
        assertEquals(id2, pending2!!.approvalId)
    }
}
