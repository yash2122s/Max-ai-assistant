package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.BrightnessAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class BrightnessTool(private val brightnessAction: BrightnessAction) : Tool {
    override val name: String = "brightness"
    override val supportedActions: Set<String> = setOf("SET_BRIGHTNESS", "BRIGHTNESS_UP", "BRIGHTNESS_DOWN")
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
            brightnessAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "BRIGHTNESS_ERROR",
                message = e.message ?: "Brightness operation failed"
            )
        }
    }
}
