package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.CallPhoneAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class CallTool(private val callPhoneAction: CallPhoneAction) : Tool {
    override val name: String = "call_contact"
    override val supportedActions: Set<String> = setOf("CALL_PHONE")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val arguments = request.arguments
        return arguments.has("contactId") && arguments.has("phoneId")
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            callPhoneAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CALL_ERROR",
                message = e.message ?: "Call operation failed"
            )
        }
    }
}
