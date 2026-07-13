package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.WhatsAppController
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class WhatsAppTool : Tool {
    private val TAG = "WhatsAppTool"
    override val name: String = "send_whatsapp_message"
    override val supportedActions: Set<String> = setOf("SEND_WHATSAPP")
    override val retryPolicy: RetryPolicy = RetryPolicy.WaitForUi(3000L)
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val arguments = request.arguments
        val contact = arguments.get("contact")?.asString
            ?: arguments.get("contact_name")?.asString
            ?: arguments.get("phone")?.asString
            ?: arguments.get("phoneNumber")?.asString
            ?: ""
            
        val message = arguments.get("message")?.asString
            ?: arguments.get("text")?.asString
            ?: arguments.get("message_text")?.asString
            ?: ""

        return contact.isNotEmpty() && message.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val arguments = request.arguments
        val contact = arguments.get("contact")?.asString
            ?: arguments.get("contact_name")?.asString
            ?: arguments.get("phone")?.asString
            ?: arguments.get("phoneNumber")?.asString
            ?: ""
            
        val message = arguments.get("message")?.asString
            ?: arguments.get("text")?.asString
            ?: arguments.get("message_text")?.asString
            ?: ""

        Log.d(TAG, "Executing WhatsApp send message to: '$contact'")
        return try {
            val controller = WhatsAppController(isScheduled = false)
            val resultString = controller.execute(context, contact, message)
            
            if (resultString.startsWith("Could not") || resultString.startsWith("Neither")) {
                ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "WHATSAPP_ERROR",
                    message = resultString,
                    retryable = true
                )
            } else {
                ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = true,
                    metadata = JSONObject().put("message", resultString).put("contact", contact)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed executing WhatsApp tool", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "WHATSAPP_EXCEPTION",
                message = e.message ?: "Unknown error",
                retryable = true
            )
        }
    }
}
