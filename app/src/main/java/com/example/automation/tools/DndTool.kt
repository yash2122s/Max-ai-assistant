package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.DndAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class DndTool(private val dndAction: DndAction) : Tool {
    override val name: String = "dnd"
    override val supportedActions: Set<String> = setOf("SET_DND", "GET_DND")
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
            jsonPayload.put("action", request.action)
            
            if (request.action == "GET_DND") {
                val enabled = dndAction.isDndEnabled(context)
                return ToolResult(
                    success = true,
                    toolName = name,
                    message = "DND enabled status: $enabled",
                    metadata = JSONObject().put("dndEnabled", enabled),
                    verificationRequired = true
                )
            }
            
            dndAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "DND_ERROR",
                message = e.message ?: "DND operation failed"
            )
        }
    }
}
