package com.example.automation.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject

class BrightnessAction : BaseAction<JSONObject>() {
    override fun execute(context: Context, payload: JSONObject) {
        val direction = payload.optString("direction", "").lowercase()
        val valueStr = payload.optString("value", "")
        val percentStr = payload.optString("percent", "")
        
        // Target percentage
        var targetPercent = -1
        
        if (valueStr.isNotEmpty()) {
            targetPercent = valueStr.replace("%", "").toIntOrNull() ?: -1
        } else if (percentStr.isNotEmpty()) {
            targetPercent = percentStr.replace("%", "").toIntOrNull() ?: -1
        } else if (payload.has("percent")) {
            targetPercent = payload.optInt("percent", -1)
        } else if (payload.has("value")) {
            targetPercent = payload.optInt("value", -1)
        }

        if (Settings.System.canWrite(context)) {
            val contentResolver = context.contentResolver
            
            if (targetPercent in 0..100) {
                val targetVal = ((targetPercent / 100f) * 255).toInt()
                log("Setting system screen brightness to $targetVal / 255 ($targetPercent%)")
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
            } else {
                val current = try {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (e: Exception) {
                    128
                }
                
                when (direction) {
                    "up", "raise", "increase" -> {
                        val targetVal = (current + 25).coerceAtMost(255)
                        log("Increasing brightness to $targetVal")
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
                    }
                    "down", "lower", "decrease" -> {
                        val targetVal = (current - 25).coerceAtLeast(0)
                        log("Decreasing brightness to $targetVal")
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
                    }
                    else -> {
                        logError("Invalid brightness payload: $payload")
                    }
                }
            }
        } else {
            // Request permission dynamically
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "MAX needs Write Settings permission to adjust screen brightness", Toast.LENGTH_LONG).show()
            }
            try {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                logError("Failed to launch write settings permission intent", e)
            }
        }
    }
}
