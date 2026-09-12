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
        val action = payload.optString("action", "").uppercase()
        val valueStr = payload.optString("value", "")
        val percentStr = payload.optString("percent", "")
        val brightnessStr = payload.optString("brightness", "")
        val levelStr = payload.optString("level", "")
        
        fun parseNumber(s: String): Int? {
            if (s.isBlank()) return null
            val digits = s.replace("%", "").trim()
            return digits.toIntOrNull()
        }

        // Target percentage (0..100)
        var targetPercent = parseNumber(valueStr)
            ?: parseNumber(percentStr)
            ?: parseNumber(brightnessStr)
            ?: parseNumber(levelStr)
            ?: if (payload.has("percent")) payload.optInt("percent", -1)
            else if (payload.has("brightness")) payload.optInt("brightness", -1)
            else if (payload.has("level")) payload.optInt("level", -1)
            else if (payload.has("value")) payload.optInt("value", -1)
            else -1

        if (Settings.System.canWrite(context)) {
            val contentResolver = context.contentResolver
            
            if (targetPercent in 0..100) {
                val targetVal = ((targetPercent / 100f) * 255).toInt().coerceIn(0, 255)
                log("Setting system screen brightness to $targetVal / 255 ($targetPercent%)")
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
            } else {
                val current = try {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (e: Exception) {
                    128
                }
                
                if (action == "BRIGHTNESS_UP" || direction in listOf("up", "raise", "increase")) {
                    val targetVal = (current + 25).coerceAtMost(255)
                    log("Increasing brightness to $targetVal")
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
                } else if (action == "BRIGHTNESS_DOWN" || direction in listOf("down", "lower", "decrease")) {
                    val targetVal = (current - 25).coerceAtLeast(0)
                    log("Decreasing brightness to $targetVal")
                    Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
                } else {
                    throw IllegalArgumentException("Invalid brightness payload: $payload")
                }
            }
        } else {
            // Request permission dynamically and throw exception so error is not masked
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
            throw IllegalStateException("Write Settings permission not granted for MAX. Prompted user to allow.")
        }
    }
}
