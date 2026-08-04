package com.example.automation.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class AlarmTool : Tool {
    private val TAG = "AlarmTool"
    override val name: String = "alarm"
    override val supportedActions: Set<String> = setOf("SET_ALARM", "SET_TIMER")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val action = request.action
        val args = JSONObject(request.arguments.toString())

        return try {
            when (action) {
                "SET_ALARM" -> {
                    val hour = args.optInt("hour", -1)
                    val minutes = args.optInt("minutes", -1)
                    val message = args.optString("message", "Alarm set by MAX")

                    if (hour < 0 || hour > 23 || minutes < 0 || minutes > 59) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INVALID_ARGUMENTS",
                            message = "Valid hour (0-23) and minutes (0-59) are required."
                        )
                    }

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                        putExtra(AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    val formattedTime = String.format("%02d:%02d", hour, minutes)
                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Set alarm for $formattedTime with label: '$message'."
                    )
                }
                "SET_TIMER" -> {
                    val seconds = args.optInt("seconds", -1)
                    val message = args.optString("message", "Timer set by MAX")

                    if (seconds <= 0) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INVALID_ARGUMENTS",
                            message = "Valid timer duration in seconds (greater than 0) is required."
                        )
                    }

                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    val mins = seconds / 60
                    val secs = seconds % 60
                    val durationText = if (mins > 0) "$mins minutes and $secs seconds" else "$secs seconds"
                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Started a countdown timer for $durationText with label: '$message'."
                    )
                }
                else -> {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "UNSUPPORTED_ACTION",
                        message = "Unsupported alarm action: $action"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting alarm/timer: $action", e)
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "ALARM_ERROR",
                message = e.message ?: "Failed to set alarm/timer on device"
            )
        }
    }
}
