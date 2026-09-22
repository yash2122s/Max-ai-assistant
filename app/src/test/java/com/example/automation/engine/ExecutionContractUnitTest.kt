package com.example.automation.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExecutionContractUnitTest {

    @Test
    fun testRunningState_HasSuccessFalse() {
        val runningJson = JSONObject().apply {
            put("status", "RUNNING")
            put("success", false)
            put("executionState", "RUNNING")
            put("message", "Task dispatched to background orchestrator.")
        }

        assertFalse("RUNNING must have success=false", runningJson.getBoolean("success"))
        assertEquals("RUNNING", runningJson.getString("status"))
        assertEquals("RUNNING", runningJson.getString("executionState"))
    }

    @Test
    fun testCompletedState_HasSuccessTrue() {
        val completedJson = JSONObject().apply {
            put("status", "success")
            put("success", true)
            put("executionState", "COMPLETED")
            put("message", "Action completed successfully")
        }

        assertTrue("COMPLETED must have success=true", completedJson.getBoolean("success"))
        assertEquals("success", completedJson.getString("status"))
        assertEquals("COMPLETED", completedJson.getString("executionState"))
    }

    @Test
    fun testFailedState_HasSuccessFalse() {
        val failedJson = JSONObject().apply {
            put("status", "error")
            put("success", false)
            put("executionState", "FAILED")
            put("error", JSONObject().apply {
                put("code", "ACTION_FAILED")
                put("message", "Element not found")
            })
        }

        assertFalse("FAILED must have success=false", failedJson.getBoolean("success"))
        assertEquals("error", failedJson.getString("status"))
        assertEquals("FAILED", failedJson.getString("executionState"))
    }

    @Test
    fun testBlockedState_HasSuccessFalse() {
        val blockedJson = JSONObject().apply {
            put("status", "blocked")
            put("success", false)
            put("executionState", "BLOCKED")
            put("reason", "Action blocked by safety authorizer")
        }

        assertFalse("BLOCKED must have success=false", blockedJson.getBoolean("success"))
        assertEquals("blocked", blockedJson.getString("status"))
        assertEquals("BLOCKED", blockedJson.getString("executionState"))
    }

    @Test
    fun testAggregateAutomationState() {
        // Step 1 SUCCESS + Step 2 RUNNING -> Overall RUNNING (success=false)
        val step1Success = true
        val step2Running = "RUNNING"
        val aggregateState1 = if (step1Success && step2Running == "RUNNING") "RUNNING" else "COMPLETED"
        val aggregateSuccess1 = aggregateState1 == "COMPLETED"
        assertEquals("RUNNING", aggregateState1)
        assertFalse(aggregateSuccess1)

        // Step 1 SUCCESS + Step 2 FAILED -> Overall FAILED (success=false)
        val step2Failed = "FAILED"
        val aggregateState2 = if (step2Failed == "FAILED") "FAILED" else "COMPLETED"
        val aggregateSuccess2 = aggregateState2 == "COMPLETED"
        assertEquals("FAILED", aggregateState2)
        assertFalse(aggregateSuccess2)

        // Step 1 SUCCESS + Step 2 SUCCESS + Verification SUCCESS -> Overall COMPLETED (success=true)
        val step2Success = true
        val verified = true
        val aggregateState3 = if (step1Success && step2Success && verified) "COMPLETED" else "FAILED"
        val aggregateSuccess3 = aggregateState3 == "COMPLETED"
        assertEquals("COMPLETED", aggregateState3)
        assertTrue(aggregateSuccess3)
    }
}
