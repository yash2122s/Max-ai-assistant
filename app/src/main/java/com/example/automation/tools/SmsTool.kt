package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.SendSmsAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class SmsTool(private val sendSmsAction: SendSmsAction) : Tool {
    override val name: String = "send_sms"
    override val supportedActions: Set<String> = setOf("SEND_SMS")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val arguments = request.arguments
        return (arguments.has("number") || arguments.has("phone")) && (arguments.has("message") || arguments.has("text"))
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            sendSmsAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SMS_ERROR",
                message = e.message ?: "Failed to send SMS"
            )
        }
    }
}
