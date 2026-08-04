package com.example.automation.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class SettingsSearchTool : Tool {
    private val TAG = "SettingsSearchTool"
    override val name: String = "settings_search"
    override val supportedActions: Set<String> = setOf("OPEN_SETTINGS")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    private val targetIntentMap = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "date_time" to Settings.ACTION_DATE_SETTINGS,
        "main" to Settings.ACTION_SETTINGS
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = JSONObject(request.arguments.toString())
        val target = args.optString("settingsTarget", "main").lowercase().trim()

        return try {
            val actionIntentStr = targetIntentMap[target] ?: Settings.ACTION_SETTINGS
            
            val intent = if (target == "battery") {
                try {
                    Intent(Intent.ACTION_POWER_USAGE_SUMMARY).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                } catch (e: Exception) {
                    Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                }
            } else {
                Intent(actionIntentStr).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            try {
                context.startActivity(intent)
                ToolResult(
                    success = true,
                    toolName = name,
                    message = "Opened settings page for: '$target'."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start settings target activity: $target", e)
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                ToolResult(
                    success = true,
                    toolName = name,
                    message = "Target settings screen not supported on this device. Fallback to main Settings page."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening settings", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SETTINGS_ERROR",
                message = e.message ?: "Failed to open device settings"
            )
        }
    }
}
