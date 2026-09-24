package com.example.network.telemetry

import android.util.Log
import com.example.network.model.GeminiModelMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured telemetry for dual-model routing, tool performance, and session handoffs.
 * Strict privacy enforcement: NEVER records API keys, tokens, or sensitive user data.
 */
object DualModelTelemetry {

    private const val TAG = "DualModelTelemetry"

    data class TaskMetrics(
        val taskId: String,
        val initialMode: GeminiModelMode,
        var currentMode: GeminiModelMode,
        var currentModel: String,
        val startTimeMs: Long = System.currentTimeMillis(),
        var endTimeMs: Long? = null,
        val toolInvocations: MutableList<ToolMetric> = mutableListOf(),
        val modelSwitches: MutableList<ModelSwitchMetric> = mutableListOf(),
        var isSuccess: Boolean = false,
        var failureReason: String? = null
    )

    data class ToolMetric(
        val toolName: String,
        val startTimeMs: Long = System.currentTimeMillis(),
        var durationMs: Long = 0,
        var success: Boolean = true,
        var errorSummary: String? = null
    )

    data class ModelSwitchMetric(
        val fromMode: GeminiModelMode,
        val toMode: GeminiModelMode,
        val fromModel: String,
        val toModel: String,
        val reason: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val activeTasks = ConcurrentHashMap<String, TaskMetrics>()
    private val taskCounter = AtomicInteger(0)

    fun startTask(taskId: String, mode: GeminiModelMode, modelName: String): TaskMetrics {
        val metric = TaskMetrics(
            taskId = taskId,
            initialMode = mode,
            currentMode = mode,
            currentModel = modelName
        )
        activeTasks[taskId] = metric
        Log.i(TAG, "Task [$taskId] STARTED | Mode: $mode | Model: $modelName")
        return metric
    }

    fun recordModelSwitch(taskId: String, newMode: GeminiModelMode, newModel: String, reason: String) {
        val task = activeTasks[taskId] ?: return
        val prevMode = task.currentMode
        val prevModel = task.currentModel
        task.currentMode = newMode
        task.currentModel = newModel
        task.modelSwitches.add(
            ModelSwitchMetric(
                fromMode = prevMode,
                toMode = newMode,
                fromModel = prevModel,
                toModel = newModel,
                reason = reason
            )
        )
        Log.i(TAG, "Task [$taskId] MODEL SWITCH: $prevMode ($prevModel) -> $newMode ($newModel) | Reason: $reason")
    }

    fun startTool(taskId: String, toolName: String): ToolMetric {
        val tool = ToolMetric(toolName = toolName)
        val task = activeTasks[taskId]
        task?.toolInvocations?.add(tool)
        Log.d(TAG, "Task [$taskId] TOOL START: $toolName")
        return tool
    }

    fun completeTool(taskId: String, tool: ToolMetric, success: Boolean, errorSummary: String? = null) {
        tool.durationMs = System.currentTimeMillis() - tool.startTimeMs
        tool.success = success
        tool.errorSummary = errorSummary?.take(100)
        Log.i(TAG, "Task [$taskId] TOOL COMPLETE: ${tool.toolName} in ${tool.durationMs}ms | success=$success")
    }

    fun completeTask(taskId: String, success: Boolean, failureReason: String? = null) {
        val task = activeTasks.remove(taskId) ?: return
        task.endTimeMs = System.currentTimeMillis()
        task.isSuccess = success
        task.failureReason = failureReason?.take(100)
        val totalDuration = task.endTimeMs!! - task.startTimeMs
        Log.i(TAG, "Task [$taskId] FINISHED in ${totalDuration}ms | Mode: ${task.currentMode} | Success: $success | Tools: ${task.toolInvocations.size}")
    }

    fun getActiveTaskCount(): Int = activeTasks.size
}
