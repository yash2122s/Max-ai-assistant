package com.example.automation.scheduler

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject

class ListScheduledTasksTool : Tool {
    private val TAG = "ListScheduledTasksTool"
    override val name: String = "list_scheduled_tasks"
    override val supportedActions: Set<String> = setOf("LIST_TASKS")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        Log.d(TAG, "Listing active scheduled tasks")
        val taskManager = TaskManager(context)
        return try {
            val tasks = taskManager.getActiveTasks()
            val jsonArray = JSONArray()
            for (task in tasks) {
                jsonArray.put(JSONObject().apply {
                    put("task_id", task.id)
                    put("tool", task.toolName)
                    put("arguments", JSONObject(task.arguments.json))
                    put("executeAt", task.executeAt)
                    put("repeatType", task.repeatType.name)
                    put("status", task.status.name)
                })
            }
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false,
                metadata = JSONObject().put("tasks", jsonArray)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed listing scheduled tasks", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "LIST_FAILED",
                message = e.message ?: "Unknown list error"
            )
        }
    }
}
