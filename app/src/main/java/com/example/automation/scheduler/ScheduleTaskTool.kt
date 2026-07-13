package com.example.automation.scheduler

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class ScheduleTaskTool : Tool {
    private val TAG = "ScheduleTaskTool"
    override val name: String = "schedule_task"
    override val supportedActions: Set<String> = setOf("SCHEDULE_TASK")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val targetTool = request.arguments.get("tool")?.asString
            ?: request.arguments.get("toolName")?.asString
            ?: ""
        val timeExpression = request.arguments.get("time_expression")?.asString
            ?: request.arguments.get("executeAt")?.asString
            ?: ""
        return targetTool.isNotEmpty() && timeExpression.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val arguments = request.arguments
        val targetTool = arguments.get("tool")?.asString
            ?: arguments.get("toolName")?.asString
            ?: ""
        val timeExpression = arguments.get("time_expression")?.asString
            ?: arguments.get("executeAt")?.asString
            ?: ""
        val repeatStr = arguments.get("repeat_type")?.asString
            ?: arguments.get("repeatType")?.asString
            ?: ""

        val executeAt = TimeParser.parseExpression(timeExpression)
        val repeatType = try {
            if (repeatStr.isNotEmpty()) RepeatType.valueOf(repeatStr.uppercase()) else RepeatType.NONE
        } catch (e: Exception) {
            RepeatType.NONE
        }

        val targetArgs = JSONObject()
        arguments.keySet().forEach { key ->
            if (key != "tool" && key != "toolName" && key != "time_expression" && key != "executeAt" && key != "repeat_type" && key != "repeatType") {
                val element = arguments.get(key)
                if (element != null) {
                    if (element.isJsonPrimitive) {
                        val prim = element.asJsonPrimitive
                        if (prim.isBoolean) targetArgs.put(key, prim.asBoolean)
                        else if (prim.isNumber) targetArgs.put(key, prim.asNumber)
                        else targetArgs.put(key, prim.asString)
                    } else {
                        targetArgs.put(key, element.toString())
                    }
                }
            }
        }

        Log.d(TAG, "Scheduling tool '$targetTool' at $executeAt ($timeExpression) with args $targetArgs")
        val taskManager = TaskManager(context)

        return try {
            val task = taskManager.schedule(
                toolName = targetTool,
                arguments = targetArgs,
                executeAt = executeAt,
                repeatType = repeatType,
                retryPolicy = if (targetTool == "flashlight") com.example.automation.scheduler.RetryPolicy.NONE else com.example.automation.scheduler.RetryPolicy.FIXED_30S
            )
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false,
                metadata = JSONObject().apply {
                    put("task_id", task.id)
                    put("executeAt", executeAt)
                    put("tool", targetTool)
                    put("message", "Task successfully scheduled.")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule task", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SCHEDULE_FAILED",
                message = e.message ?: "Failed to schedule task"
            )
        }
    }
}
