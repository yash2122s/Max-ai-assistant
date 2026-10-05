package com.example.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.TaskTransitionAuthority
import com.example.automation.state.ActionStep
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentPersistenceRegressionTest {
    @Test fun replanRetainsCompletedEvidenceAndPersistsDependenciesAndHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = AgentTaskStore(db.agentTaskDao())
            store.create("task", "Navigate", listOf(ActionStep("a", "OPEN_APP"), ActionStep("b", "CLICK_TEXT", dependsOn = listOf("a"))))
            store.recordStepStarted("task", "a", "exec-a")
            store.recordStepResult("task", "a", "exec-a", true, true, verificationEvidence = "Foreground matches target")
            store.replacePendingPlan("task", listOf(ActionStep("c", "CLICK_TEXT", dependsOn = listOf("a"))), setOf("a"))
            assertEquals(2, store.getTask("task")!!.planVersion)
            assertEquals(listOf("a", "c"), store.getSteps("task").map { it.stepId })
            assertEquals("Foreground matches target", store.getStep("task", "a")!!.verificationEvidence)
            assertTrue(store.getStep("task", "c")!!.payloadJson!!.contains("dependsOn"))
            assertEquals(1, store.getEventsForTask("task").size)
            assertNotEquals("COMPLETED", store.deriveAndPersistTaskCompletion("task")!!.status)
            store.recordStepWaitingInput("task", "c", "exec-c", "Which target?")
            assertEquals("WAITING_FOR_INPUT", store.deriveAndPersistTaskCompletion("task")!!.status)
            assertTrue(store.getRecoverableTasks().any { it.taskId == "task" })
        } finally { db.close() }
    }

    @Test fun mismatchedExecutionCannotWriteSuccess() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = AgentTaskStore(db.agentTaskDao())
            store.create("task", "Navigate", listOf(ActionStep("a", "OPEN_APP")))
            store.transitionStep("task", "a", "exec-a", "DISPATCHING", "ExecutionEngine")
            store.transitionStep("task", "a", "exec-a", "RUNNING", "ExecutionEngine")
            store.transitionStep("task", "a", "exec-a", "VERIFYING", "ExecutionEngine")
            val stale = store.transitionStep("task", "a", "other", "SUCCEEDED", "Verifier", verificationEvidence = "Wrong attempt")
            assertTrue(stale is TaskTransitionAuthority.TransitionResult.Rejected)
            assertEquals("VERIFYING", store.getStep("task", "a")!!.status)
            val missing = store.transitionStep("task", "a", "exec-a", "SUCCEEDED", "Verifier")
            assertTrue(missing is TaskTransitionAuthority.TransitionResult.Rejected)
        } finally { db.close() }
    }
}
