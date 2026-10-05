package com.example.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.state.AgentTaskStore
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomCanonicalIdentityMigrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AgentTaskDao
    private lateinit var store: AgentTaskStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // in tests only for direct assertions
            .build()
        dao = db.agentTaskDao()
        store = AgentTaskStore(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testTaskCanonicalIdentity_PersistedAndRetrieved() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val requestId = "req_123"
        val now = System.currentTimeMillis()

        store.createTask(
            taskId = taskId,
            goal = "Send report to manager",
            totalSteps = 2,
            requestId = requestId,
            source = "VOICE_GEMINI",
            requestHash = "hash_abc_123",
            deadlineAt = now + 60_000L
        )

        val task = store.getTask(taskId)
        assertNotNull(task)
        assertEquals(taskId, task?.taskId)
        assertEquals("Send report to manager", task?.goal)
        assertEquals("READY", task?.status)
        assertEquals(requestId, task?.requestId)
        assertEquals("VOICE_GEMINI", task?.source)
        assertEquals("hash_abc_123", task?.requestHash)
        assertEquals(1, task?.planVersion)
        assertEquals(now + 60_000L, task?.deadlineAt)
    }

    @Test
    fun testStepCanonicalIdentity_PersistedAndRetrieved() = runBlocking {
        val taskId = "task_" + UUID.randomUUID().toString()
        val stepId = "step_1"
        val executionId = "exec_" + UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        store.createTask(taskId = taskId, goal = "Test step persistence", totalSteps = 1)
        store.recordStepWaitingApproval(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            actionType = "SEND_WHATSAPP",
            stepIndex = 0,
            approvalId = "appr_999",
            payloadHash = "payload_hash_xyz",
            payloadJson = "{\"contact\":\"Yash\"}"
        )

        val step = store.getStep(taskId, stepId)
        assertNotNull(step)
        assertEquals("WAITING_FOR_APPROVAL", step?.status)
        assertEquals(executionId, step?.executionId)
        assertEquals("appr_999", step?.approvalId)
        assertEquals("PENDING", step?.approvalStatus)
        assertEquals("payload_hash_xyz", step?.payloadHash)
        assertEquals("{\"contact\":\"Yash\"}", step?.payloadJson)

        // Record verified result
        store.recordStepResult(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            executorSuccess = true,
            verifiedSuccess = true,
            verificationId = "verif_555",
            verificationEvidence = "MessageSentConfirmed",
            executorResultJson = "{\"status\":\"ok\"}",
            idempotencyKey = "idemp_step_1"
        )

        val updatedStep = store.getStep(taskId, stepId)
        assertNotNull(updatedStep)
        assertEquals("SUCCEEDED", updatedStep?.status)
        assertEquals("VERIFIED", updatedStep?.verificationResult)
        assertEquals("verif_555", updatedStep?.verificationId)
        assertEquals("MessageSentConfirmed", updatedStep?.verificationEvidence)
        assertEquals("{\"status\":\"ok\"}", updatedStep?.executorResultJson)
        assertEquals("idemp_step_1", updatedStep?.idempotencyKey)
    }

    @Test
    fun testDurableApprovals_StoreAndConsume() = runBlocking {
        val taskId = "task_appr_" + UUID.randomUUID().toString()
        val stepId = "step_appr"
        val approvalId = "appr_" + UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val approval = AgentApprovalRecord(
            approvalId = approvalId,
            taskId = taskId,
            stepId = stepId,
            executionId = "exec_1",
            action = "CALL_PHONE",
            target = "John",
            displayLabel = "John (555-1234)",
            payloadJson = "{\"contactId\":123}",
            payloadHash = "hash_call_123",
            question = "MAX wants to call John. Allow?",
            status = "PENDING",
            createdAt = now,
            expiresAt = now + 90_000L
        )

        store.recordApproval(approval)

        val retrieved = store.getApproval(approvalId)
        assertNotNull(retrieved)
        assertEquals("PENDING", retrieved?.status)
        assertEquals("hash_call_123", retrieved?.payloadHash)

        val pendingList = store.getPendingApprovalsForTask(taskId)
        assertEquals(1, pendingList.size)
        assertEquals(approvalId, pendingList[0].approvalId)

        // Consume approval
        val consumed = store.consumeApproval(approvalId, "CONSUMED", "event_consume_1")
        assertTrue("Approval should be consumed successfully", consumed)

        val afterConsume = store.getApproval(approvalId)
        assertEquals("CONSUMED", afterConsume?.status)
        assertNotNull(afterConsume?.consumedAt)
        assertEquals("event_consume_1", afterConsume?.consumedEventId)

        // Second consume attempt must fail (atomic state machine check)
        val secondConsume = store.consumeApproval(approvalId, "CONSUMED")
        assertFalse("Second consumption of already-consumed approval must fail", secondConsume)
    }

    @Test
    fun testEventDeduplication_ByIdempotencyKey() = runBlocking {
        val taskId = "task_event_" + UUID.randomUUID().toString()
        val idempotencyKey = "key_dispatch_12345"

        val event1 = AgentTaskEventRecord(
            eventId = "evt_1",
            taskId = taskId,
            eventType = "STEP_DISPATCHED",
            status = "RUNNING",
            idempotencyKey = idempotencyKey,
            eventDataJson = "{\"step\":0}"
        )

        val recorded1 = store.recordEventIfNew(event1)
        assertTrue("First event must be recorded", recorded1)

        val event2 = AgentTaskEventRecord(
            eventId = "evt_2",
            taskId = taskId,
            eventType = "STEP_DISPATCHED",
            status = "RUNNING",
            idempotencyKey = idempotencyKey,
            eventDataJson = "{\"step\":0}"
        )

        val recorded2 = store.recordEventIfNew(event2)
        assertFalse("Duplicate event with same idempotency key must be rejected", recorded2)

        val retrieved = store.getEventByIdempotencyKey(idempotencyKey)
        assertNotNull(retrieved)
        assertEquals("evt_1", retrieved?.eventId)
    }

    @Test
    fun testMigration_19_to_20_PreservesExistingData() {
        // Setup SQLite database at version 19
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_19_20.db")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Create v19 schema
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `agent_tasks` (
                                `taskId` TEXT NOT NULL PRIMARY KEY,
                                `goal` TEXT NOT NULL,
                                `status` TEXT NOT NULL,
                                `currentStepIndex` INTEGER NOT NULL,
                                `totalSteps` INTEGER NOT NULL,
                                `approvalState` TEXT,
                                `failureReason` TEXT,
                                `recoveryState` TEXT,
                                `createdAt` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                `interruptedAt` INTEGER
                            )
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `agent_task_steps` (
                                `taskId` TEXT NOT NULL,
                                `stepId` TEXT NOT NULL,
                                `actionType` TEXT NOT NULL,
                                `stepIndex` INTEGER NOT NULL,
                                `status` TEXT NOT NULL,
                                `executionId` TEXT,
                                `failureReason` TEXT,
                                `verificationResult` TEXT,
                                `attemptCount` INTEGER NOT NULL,
                                `updatedAt` INTEGER NOT NULL,
                                PRIMARY KEY(`taskId`, `stepId`)
                            )
                        """.trimIndent())
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        val dbv19 = helper.writableDatabase
        // Insert a v19 task and step
        dbv19.execSQL("""
            INSERT INTO `agent_tasks` (`taskId`, `goal`, `status`, `currentStepIndex`, `totalSteps`, `createdAt`, `updatedAt`)
            VALUES ('old_task_1', 'Legacy Goal', 'READY', 0, 1, 1000, 1000)
        """.trimIndent())
        dbv19.execSQL("""
            INSERT INTO `agent_task_steps` (`taskId`, `stepId`, `actionType`, `stepIndex`, `status`, `attemptCount`, `updatedAt`)
            VALUES ('old_task_1', 'step_legacy', 'OPEN_APP', 0, 'READY', 1, 1000)
        """.trimIndent())

        // Run Migration 19 -> 20
        AppDatabase.MIGRATION_19_20.migrate(dbv19)

        // Verify existing rows are intact
        val cursor = dbv19.query("SELECT taskId, goal, status, planVersion, requestId FROM agent_tasks WHERE taskId = 'old_task_1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("old_task_1", cursor.getString(0))
        assertEquals("Legacy Goal", cursor.getString(1))
        assertEquals("READY", cursor.getString(2))
        assertEquals(1, cursor.getInt(3)) // Default planVersion = 1
        assertNull(cursor.getString(4)) // Default requestId is null
        cursor.close()

        val stepCursor = dbv19.query("SELECT stepId, actionType, stepVersion, hasExternalSideEffects FROM agent_task_steps WHERE taskId = 'old_task_1'")
        assertTrue(stepCursor.moveToFirst())
        assertEquals("step_legacy", stepCursor.getString(0))
        assertEquals("OPEN_APP", stepCursor.getString(1))
        assertEquals(1, stepCursor.getInt(2)) // Default stepVersion = 1
        assertEquals(0, stepCursor.getInt(3)) // Default hasExternalSideEffects = 0
        stepCursor.close()

        // Verify new tables agent_approvals and agent_task_events exist
        dbv19.execSQL("""
            INSERT INTO `agent_approvals` (
                `approvalId`, `taskId`, `stepId`, `action`, `displayLabel`,
                `payloadJson`, `payloadHash`, `question`, `status`, `createdAt`, `expiresAt`
            ) VALUES (
                'test_appr', 'old_task_1', 'step_legacy', 'OPEN_APP', 'Settings',
                '{}', 'hash_test', 'Allow?', 'PENDING', 1000, 2000
            )
        """.trimIndent())
        val apprCursor = dbv19.query("SELECT approvalId, status FROM agent_approvals WHERE approvalId = 'test_appr'")
        assertTrue(apprCursor.moveToFirst())
        assertEquals("test_appr", apprCursor.getString(0))
        assertEquals("PENDING", apprCursor.getString(1))
        apprCursor.close()

        dbv19.close()
    }
}
