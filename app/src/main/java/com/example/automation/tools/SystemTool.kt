package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.SystemAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class SystemTool(private val systemAction: SystemAction) : Tool {
    override val name: String = "system"
    override val supportedActions: Set<String> = setOf(
        "SYSTEM_ACTION",
        "BACK",
        "TAKE_SCREENSHOT",
        "SCREENSHOT",
        "PERFORM_HOME",
        "PERFORM_BACK",
        "PERFORM_RECENT_APPS",
        "PERFORM_NOTIFICATIONS",
        "PERFORM_QUICK_SETTINGS",
        "CLOSE_APP"
    )
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val action = request.action.uppercase()
        if (action in supportedActions) return true
        val actionStr = request.arguments.get("system_action_str")?.asString ?: ""
        return actionStr.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            if (!jsonPayload.has("system_action_str") || jsonPayload.optString("system_action_str").isEmpty()) {
                val mappedStr = when (request.action.uppercase()) {
                    "TAKE_SCREENSHOT", "SCREENSHOT" -> "screenshot"
                    "PERFORM_HOME" -> "home"
                    "PERFORM_BACK", "BACK" -> "back"
                    "PERFORM_RECENT_APPS" -> "recent"
                    "PERFORM_NOTIFICATIONS" -> "notifications"
                    "PERFORM_QUICK_SETTINGS" -> "quick_settings"
                    else -> {
                        val argAction = jsonPayload.optString("action").ifEmpty {
                            jsonPayload.optString("target").ifEmpty {
                                jsonPayload.optString("text")
                            }
                        }
                        if (argAction.isNotEmpty()) argAction.lowercase() else request.action.lowercase()
                    }
                }
                jsonPayload.put("system_action_str", mappedStr)
            }
            systemAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true,
                metadata = JSONObject().put("action", request.action)
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
