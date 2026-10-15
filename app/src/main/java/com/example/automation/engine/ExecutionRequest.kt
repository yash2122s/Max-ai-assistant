package com.example.automation.engine

import com.google.gson.JsonObject
import java.util.UUID

data class ExecutionRequest(
    val executionId: UUID = UUID.randomUUID(),
    val action: String,
    val arguments: JsonObject = JsonObject(),
    val source: ExecutionSource = ExecutionSource.GEMINI_LIVE,
    val cancellationToken: CancellationToken = CancellationToken(),
    val timestamp: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val stepId: String? = null,
    val requestId: String? = null
) {
    companion object {
        /** One argument adapter shared by direct and durable ActionStep execution. */
        fun fromStep(
            step: com.example.automation.state.ActionStep,
            source: ExecutionSource,
            cancellationToken: CancellationToken,
            taskId: String? = null
        ): ExecutionRequest {
            val args = JsonObject().apply {
                step.targetText?.let { target ->
                    listOf("text", "target", "app_name", "app", "appName", "query", "direction", "key", "mode", "system_action_str")
                        .forEach { addProperty(it, target) }
                    if (target.lowercase() in setOf("true", "on", "false", "off")) {
                        val enabled = target.lowercase() in setOf("true", "on")
                        listOf("enabled", "state", "dndEnabled").forEach { addProperty(it, enabled) }
                    }
                    target.toIntOrNull()?.let { addProperty("percent", it); addProperty("level", it) }
                }
                step.resourceId?.let { addProperty("resourceId", it); addProperty("id", it) }
                step.contentDescription?.let { addProperty("desc", it); addProperty("description", it) }
                step.fallbackX?.let { addProperty("x", it) }
                step.fallbackY?.let { addProperty("y", it) }
                step.params.forEach { (key, value) -> addProperty(key, value) }
            }
            val action = when (step.actionType.uppercase().trim()) {
                "OPEN_SETTINGS", "SETTINGS" -> { args.addProperty("app_name", "Settings"); "OPEN_APP" }
                "LAUNCH_APP" -> "OPEN_APP"
                else -> step.actionType.uppercase().trim()
            }
            return ExecutionRequest(action = action, arguments = args, source = source,
                cancellationToken = cancellationToken, taskId = taskId, stepId = step.stepId)
        }
    }
}
