package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.WifiSettingsAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class WifiTool(private val wifiSettingsAction: WifiSettingsAction) : Tool {
    override val name: String = "wifi"
    override val supportedActions: Set<String> = setOf("SET_WIFI", "TOGGLE_WIFI", "OPEN_WIFI")
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
            wifiSettingsAction.execute(context, jsonPayload)
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = true,
                message = "Opened Wi-Fi settings page"
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "WIFI_SETTINGS_FAILED",
                message = e.message ?: "Failed to open WiFi settings"
            )
        }
    }
}
