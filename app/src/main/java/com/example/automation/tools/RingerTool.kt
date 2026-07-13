package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.RingerAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class RingerTool(private val ringerAction: RingerAction) : Tool {
    override val name: String = "ringer"
    override val supportedActions: Set<String> = setOf("SET_RINGER_MODE")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            ringerAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "RINGER_ERROR",
                message = e.message ?: "Ringer operation failed"
            )
        }
    }
}
