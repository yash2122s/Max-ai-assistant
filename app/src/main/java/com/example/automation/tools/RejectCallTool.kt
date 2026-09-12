package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.RejectCallAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class RejectCallTool(private val rejectCallAction: RejectCallAction) : Tool {
    override val name: String = "reject_call"
    override val supportedActions: Set<String> = setOf("REJECT_CALL")
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
            rejectCallAction.execute(context, JSONObject())
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "REJECT_ERROR",
                message = e.message ?: "Failed to reject call"
            )
        }
    }
}
