package com.example.automation.tools

import android.content.Context
import com.example.automation.actions.BatteryAction
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class BatteryTool(private val batteryAction: BatteryAction) : Tool {
    override val name: String = "battery"
    override val supportedActions: Set<String> = setOf("GET_BATTERY_STATUS")
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
            val info = batteryAction.getBatteryInfo(context)
            val pct = info.optInt("percentage", -1)
            val isCharging = info.optBoolean("isCharging", false)
            val isPowerSaveMode = info.optBoolean("isPowerSaveMode", false)
            
            val message = "Battery: $pct%, Charging: $isCharging, Power Save Mode: $isPowerSaveMode"
            
            ToolResult(
                success = true,
                toolName = name,
                message = message,
                metadata = info,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "BATTERY_ERROR",
                message = e.message ?: "Failed to query battery status"
            )
        }
    }
}
