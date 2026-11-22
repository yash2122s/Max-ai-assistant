package com.example.features.calls

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.RequestValidator
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.automation.verification.RetryPolicy
import com.example.features.calls.actions.CallConfirmationRequiredException
import com.example.features.calls.actions.CallPermissionRequiredException
import com.example.features.calls.actions.CallPhoneAction
import com.example.features.calls.actions.ContactNotFoundClarificationException
import org.json.JSONObject

class CallTool(private val callPhoneAction: CallPhoneAction) : Tool, RequestValidator {
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
        return (arguments.has("contactId") && arguments.has("phoneId")) || 
            arguments.has("contact") ||
            arguments.has("phoneNumber") ||
            arguments.has("number")
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            callPhoneAction.execute(context, jsonPayload)
            if (jsonPayload.optBoolean("dialerOpened", false) || jsonPayload.optString("callResult") == "Opened the dialer") {
                ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = false,
                    message = "Opened the dialer",
                    metadata = JSONObject().apply {
                        put("status", "DIALER_OPENED")
                        put("dialerOpened", true)
                    }
                )
            } else {
                ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = true,
                    message = "Call intent requested",
                    metadata = JSONObject().apply {
                        put("status", "CALL_REQUESTED")
                    }
                )
            }
        } catch (e: CallConfirmationRequiredException) {
            ToolResult.needsInput(
                toolName = name,
                question = e.confirmationPrompt,
                options = listOf("Call", "Cancel"),
                targetParam = "confirmed"
            ).copy(
                errorCode = "CONFIRMATION_REQUIRED",
                outputData = mapOf(
                    "contactId" to e.contactId,
                    "phoneId" to e.phoneId,
                    "displayName" to e.displayName,
                    "phoneNumber" to e.phoneNumber
                ),
                metadata = JSONObject().apply {
                    put("status", "CONFIRMATION_REQUIRED")
                    put("contactId", e.contactId)
                    put("phoneId", e.phoneId)
                    put("displayName", e.displayName)
                    put("phoneNumber", e.phoneNumber)
                }
            )
        } catch (e: CallPermissionRequiredException) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CALL_PERMISSION_REQUIRED",
                message = e.message,
                status = ToolResultStatus.USER_ACTION_REQUIRED,
                metadata = JSONObject().apply {
                    put("status", "PERMISSION_DENIED")
                }
            )
        } catch (e: ContactNotFoundClarificationException) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CONTACT_SELECTION_REQUIRED",
                message = e.clarificationMessage,
                status = ToolResultStatus.NEEDS_INPUT,
                needsInputQuestion = e.clarificationMessage,
                metadata = JSONObject().apply {
                    put("status", "CONTACT_SELECTION_REQUIRED")
                }
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "CALL_ERROR",
                message = e.message ?: "Call operation failed",
                metadata = JSONObject().apply {
                    put("status", "CALL_FAILED")
                }
            )
        }
    }
}
