package com.example.automation.scheduler

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class CancelTaskTool : Tool {
    private val TAG = "CancelTaskTool"
    override val name: String = "cancel_task"
    override val supportedActions: Set<String> = setOf("CANCEL_TASK")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val taskId = request.arguments.get("task_id")?.asString
            ?: request.arguments.get("id")?.asString
            ?: ""
        return taskId.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val arguments = request.arguments
        val taskId = arguments.get("task_id")?.asString
            ?: arguments.get("id")?.asString
            ?: ""

        Log.d(TAG, "Cancelling scheduled task with ID: '$taskId'")
        val taskManager = TaskManager(context)
        val cancelled = taskManager.cancel(taskId)
        return if (cancelled) {
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false,
                metadata = JSONObject().put("task_id", taskId).put("message", "Task successfully cancelled.")
            )
        } else {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CANCEL_FAILED",
                message = "Task with ID '$taskId' not found or cannot be cancelled"
            )
        }
    }
}
