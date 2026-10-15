package com.example.features.whatsapp

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject

class WhatsAppTool : Tool {
    private val TAG = "WhatsAppTool"
    override val name: String = "send_whatsapp_message"
    override val supportedActions: Set<String> = setOf(
        "SEND_WHATSAPP",
        "OPEN_WHATSAPP_CHAT",
        "SEND_WHATSAPP_MESSAGE",
        "NAVIGATE_WHATSAPP",
        "OPEN_WHATSAPP_SCREEN",
        "WHATSAPP_NAVIGATE"
    )
    override val retryPolicy: RetryPolicy = RetryPolicy.WaitForUi(3000L)
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val arguments = request.arguments
        val action = request.action

        if (action in setOf("NAVIGATE_WHATSAPP", "OPEN_WHATSAPP_SCREEN", "WHATSAPP_NAVIGATE")) {
            val screen = arguments.get("screen")?.asString
                ?: arguments.get("target")?.asString
                ?: arguments.get("tab")?.asString
                ?: arguments.get("destination")?.asString
                ?: ""
            return screen.isNotBlank()
        }

        val contact = arguments.get("contact")?.asString
            ?: arguments.get("contact_name")?.asString
            ?: arguments.get("phone")?.asString
            ?: arguments.get("phoneNumber")?.asString
            ?: ""

        return contact.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val arguments = request.arguments
        val action = request.action

        if (action in setOf("NAVIGATE_WHATSAPP", "OPEN_WHATSAPP_SCREEN", "WHATSAPP_NAVIGATE")) {
            val screen = arguments.get("screen")?.asString
                ?: arguments.get("target")?.asString
                ?: arguments.get("tab")?.asString
                ?: arguments.get("destination")?.asString
                ?: "chats"
            Log.d(TAG, "Executing WhatsApp navigation to screen: '$screen'")
            return try {
                val controller = WhatsAppController(isScheduled = false)
                val result = controller.navigateToScreen(context, screen)
                when (result) {
                    is WhatsAppExecutionResult.Launched -> {
                        ToolResult(
                            success = true,
                            toolName = name,
                            verificationRequired = true,
                            message = result.messageStatus,
                            metadata = JSONObject()
                                .put("message", result.messageStatus)
                                .put("screen", screen)
                        )
                    }
                    is WhatsAppExecutionResult.Error -> {
                        ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "WHATSAPP_NAV_ERROR",
                            message = result.error,
                            retryable = true
                        )
                    }
                    is WhatsAppExecutionResult.NeedsInput -> {
                        ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "NEEDS_INPUT",
                            message = result.question,
                            retryable = false
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed navigating WhatsApp screen", e)
                ToolResult(
                    success = false,
                    toolName = name,
                    errorCode = "WHATSAPP_NAV_EXCEPTION",
                    message = e.message ?: "Unknown error",
                    retryable = true
                )
            }
        }

        val contact = arguments.get("contact")?.asString
            ?: arguments.get("contact_name")?.asString
            ?: arguments.get("phone")?.asString
            ?: arguments.get("phoneNumber")?.asString
            ?: ""
            
        val message = arguments.get("message")?.asString
            ?: arguments.get("text")?.asString
            ?: arguments.get("message_text")?.asString
            ?: ""

        Log.d(TAG, "Executing WhatsApp action '${request.action}' for contact: '$contact'")
        return try {
            val controller = WhatsAppController(isScheduled = false)
            val result = controller.executeDetailed(context, contact, message)

            when (result) {
                is WhatsAppExecutionResult.Launched -> {
                    // Await accessibility service completion to prevent premature verification failure and retries
                    val startWait = System.currentTimeMillis()
                    val maxWaitMs = 12000L
                    while (com.example.service.JarvisAccessibilityService.pendingTask != null && (System.currentTimeMillis() - startWait) < maxWaitMs) {
                        kotlinx.coroutines.delay(200L)
                    }

                    val sendResult = com.example.service.JarvisAccessibilityService.lastSendVerificationResult
                    val isSuccess = sendResult == com.example.service.SendVerificationResult.SUCCESS ||
                                    sendResult == com.example.service.SendVerificationResult.SEND_ACTION_CONFIRMED

                    ToolResult(
                        success = isSuccess || com.example.service.JarvisAccessibilityService.pendingTask == null,
                        toolName = name,
                        verificationRequired = true,
                        message = if (isSuccess) "Message sent to $contact via WhatsApp" else result.messageStatus,
                        metadata = JSONObject()
                            .put("message", result.messageStatus)
                            .put("contact", result.contact)
                            .put("contactDisplayName", result.contactDisplayName)
                            .put("phone", result.phone)
                            .put("isDeepLink", result.isDeepLink)
                            .put("sendResult", sendResult?.name ?: "UNKNOWN")
                    )
                }

                is WhatsAppExecutionResult.NeedsInput -> {
                    val matchesArray = JSONArray()
                    result.matches.forEach { c ->
                        matchesArray.put(JSONObject().put("name", c.name).put("phone", c.phoneNumber).put("type", c.type))
                    }
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "NEEDS_INPUT",
                        message = result.question,
                        retryable = false,
                        verificationRequired = false,
                        metadata = JSONObject()
                            .put("question", result.question)
                            .put("matches", matchesArray)
                    )
                }

                is WhatsAppExecutionResult.Error -> {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "WHATSAPP_ERROR",
                        message = result.error,
                        retryable = true
                    )
                }
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
