package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.CreateContactAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class CreateContactTool(private val createContactAction: CreateContactAction) : Tool {
    override val name: String = "create_contact"
    override val supportedActions: Set<String> = setOf("CREATE_CONTACT")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val arguments = request.arguments
        val nameVal = arguments.get("name")?.asString ?: ""
        val phoneVal = arguments.get("phone")?.asString ?: arguments.get("phone_number")?.asString ?: ""
        return nameVal.isNotEmpty() || phoneVal.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            createContactAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CREATE_CONTACT_ERROR",
                message = e.message ?: "Failed to open contact creator"
            )
        }
    }
}
