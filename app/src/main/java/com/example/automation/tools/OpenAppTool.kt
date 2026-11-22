package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.OpenAppAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class OpenAppTool(private val openAppAction: OpenAppAction) : Tool {
    override val name: String = "open_app"
    override val supportedActions: Set<String> = setOf("OPEN_APP")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val appName = request.arguments.get("app_name")?.asString
            ?: request.arguments.get("app")?.asString
            ?: request.arguments.get("appName")?.asString
            ?: ""
        return appName.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val jsonPayload = JSONObject(request.arguments.toString())
            openAppAction.execute(context, jsonPayload)
            
            val appName = jsonPayload.optString("app_name")
                .ifEmpty { jsonPayload.optString("app") }
                .ifEmpty { "the requested app" }
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true,
                message = "Opened $appName successfully."
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "LAUNCH_FAILED",
                message = e.message ?: "Launch failed",
                retryable = false
            )
        }
    }
}
