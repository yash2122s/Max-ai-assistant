package com.example.network

import com.example.network.model.ModelHandoffContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GeminiModelHandoffTest {

    @Test
    fun testHandoffContext_DefaultInitialization() {
        val context = ModelHandoffContext()
        assertNotNull(context.taskId)
        assertTrue(context.conversationSummary.isEmpty())
        assertTrue(context.currentObjective.isEmpty())
        assertTrue(context.completedActions.isEmpty())
        assertTrue(context.pendingActions.isEmpty())
        assertTrue(context.toolState.isEmpty())
        assertEquals(null, context.screenContextSummary)
    }

    @Test
    fun testHandoffContext_ToSystemInstructionPrompt_FullPayload() {
        val taskId = "test-task-123"
        val objective = "Find why Gradle build is failing in terminal"
        val summary = "User asked MAX to inspect build output and fix kotlin compilation errors."
        val completed = listOf("windows_agent(core.terminal:run)", "get_clipboard")
        val pending = listOf("replace_file_content", "rebuild_project")
        val screen = "Terminal window open showing NullPointerException at Line 42"

        val context = ModelHandoffContext(
            taskId = taskId,
            currentObjective = objective,
            conversationSummary = summary,
            completedActions = completed,
            pendingActions = pending,
            screenContextSummary = screen
        )

        val prompt = context.toSystemInstructionPrompt()

        assertTrue("Prompt must contain Task Continuity header", prompt.contains("# TASK CONTINUITY & HANDOFF CONTEXT"))
        assertTrue("Prompt must contain Task ID", prompt.contains("Task ID: $taskId"))
        assertTrue("Prompt must contain Current Objective", prompt.contains("Current Objective: $objective"))
        assertTrue("Prompt must contain Context Summary", prompt.contains("Context Summary: $summary"))
        assertTrue("Prompt must contain Completed Actions", prompt.contains("Completed Actions: ${completed.joinToString(", ")}"))
        assertTrue("Prompt must contain Pending Actions", prompt.contains("Pending Actions: ${pending.joinToString(", ")}"))
        assertTrue("Prompt must contain Screen State", prompt.contains("Screen State: $screen"))
    }

    @Test
    fun testHandoffContext_ToSystemInstructionPrompt_MinimalPayload() {
        val taskId = "min-task-456"
        val context = ModelHandoffContext(
            taskId = taskId,
            currentObjective = "Quick query",
            conversationSummary = ""
        )

        val prompt = context.toSystemInstructionPrompt()

        assertTrue(prompt.contains("Task ID: $taskId"))
        assertTrue(prompt.contains("Current Objective: Quick query"))
        // Blank fields should not appear
        assertTrue(!prompt.contains("Context Summary:"))
        assertTrue(!prompt.contains("Completed Actions:"))
        assertTrue(!prompt.contains("Pending Actions:"))
        assertTrue(!prompt.contains("Screen State:"))
    }

    @Test
    fun testHandoffContext_CustomTaskIdPreserved() {
        val customId = UUID.randomUUID().toString()
        val context = ModelHandoffContext(taskId = customId)
        assertEquals(customId, context.taskId)
        assertTrue(context.toSystemInstructionPrompt().contains("Task ID: $customId"))
    }
}
