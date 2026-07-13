package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.SystemAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class SystemTool(private val systemAction: SystemAction) : Tool {
    override val name: String = "system_action"
    override val supportedActions: Set<String> = setOf("SYSTEM_ACTION")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val actionStr = request.arguments.get("system_action_str")?.asString ?: ""
        return actionStr.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            systemAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SYSTEM_ACTION_ERROR",
                message = e.message ?: "System action failed"
            )
        }
    }
}
