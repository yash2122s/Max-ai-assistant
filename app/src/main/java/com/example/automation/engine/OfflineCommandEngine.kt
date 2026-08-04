package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.tools.ToolResult
import com.google.gson.JsonObject

object OfflineCommandEngine {
    private const val TAG = "OfflineCommandEngine"

    suspend fun executeIfMatched(context: Context, rawText: String): ToolResult? {
        val query = rawText.trim().lowercase()
        if (query.isBlank()) return null

        Log.d(TAG, "Evaluating query for offline command match: '$query'")

        // 1. Phone Unlock Patterns
        if (query.contains("unlock my phone") || query.contains("unlock phone") ||
            query.contains("unlock device") || query.contains("open sesame") ||
            query.contains("phone unlock") || query.contains("unlock mobile")) {
            Log.d(TAG, "Matched Offline Action: UNLOCK_DEVICE")
            return ExecutionEngine.execute(context, ExecutionRequest(action = "UNLOCK_DEVICE"))
        }

        // 2. Flashlight Turn ON Patterns
        if (query.contains("turn on flashlight") || query.contains("flashlight on") ||
            query.contains("torch on") || query.contains("light on") || query.contains("turn on torch")) {
            Log.d(TAG, "Matched Offline Action: FLASHLIGHT_ON")
            val args = JsonObject().apply { addProperty("enabled", true) }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "FLASHLIGHT_ON", arguments = args))
        }

        // 3. Flashlight Turn OFF Patterns
        if (query.contains("turn off flashlight") || query.contains("flashlight off") ||
            query.contains("torch off") || query.contains("light off") || query.contains("turn off torch")) {
            Log.d(TAG, "Matched Offline Action: FLASHLIGHT_OFF")
            val args = JsonObject().apply { addProperty("enabled", false) }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "FLASHLIGHT_OFF", arguments = args))
        }

        // 4. Open App Patterns
        if (query.startsWith("open ") || query.startsWith("launch ")) {
            val appName = query.substringAfter("open ").substringAfter("launch ").trim()
            if (appName.isNotBlank() && appName !in listOf("sesame", "lock")) {
                Log.d(TAG, "Matched Offline Action: OPEN_APP ($appName)")
                val args = JsonObject().apply { addProperty("app_name", appName) }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "OPEN_APP", arguments = args))
            }
        }

        // 5. System Navigation Actions
        when {
            query == "go home" || query == "home" || query == "home screen" -> {
                val args = JsonObject().apply { addProperty("system_action_str", "home") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            query == "go back" || query == "back" -> {
                val args = JsonObject().apply { addProperty("system_action_str", "back") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            query.contains("recent apps") || query == "recents" || query == "recent" -> {
                val args = JsonObject().apply { addProperty("system_action_str", "recent") }
                return ExecutionEngine.execute(context, ExecutionRequest(action = "SYSTEM_ACTION", arguments = args))
            }
            query.contains("take screenshot") || query == "screenshot" -> {
                return ExecutionEngine.execute(context, ExecutionRequest(action = "TAKE_SCREENSHOT"))
            }
        }

        // 6. Battery Status Patterns
        if (query.contains("battery") || query.contains("charge percentage") || query.contains("battery level")) {
            Log.d(TAG, "Matched Offline Action: GET_BATTERY_STATUS")
            return ExecutionEngine.execute(context, ExecutionRequest(action = "GET_BATTERY_STATUS"))
        }

        // 7. Bluetooth Controls
        if (query.contains("turn on bluetooth") || query.contains("enable bluetooth") || query.contains("bluetooth on")) {
            val args = JsonObject().apply { addProperty("enabled", true) }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "SET_BLUETOOTH", arguments = args))
        }
        if (query.contains("turn off bluetooth") || query.contains("disable bluetooth") || query.contains("bluetooth off")) {
            val args = JsonObject().apply { addProperty("enabled", false) }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "SET_BLUETOOTH", arguments = args))
        }

        // 8. Do Not Disturb (DND)
        if (query.contains("turn on dnd") || query.contains("enable dnd") || query.contains("dnd on") || query.contains("do not disturb")) {
            val args = JsonObject().apply { addProperty("enabled", true) }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "SET_DND", arguments = args))
        }
        if (query.contains("turn off dnd") || query.contains("disable dnd") || query.contains("dnd off")) {
            val args = JsonObject().apply { addProperty("enabled", false) }
            return ExecutionEngine.execute(context, ExecutionRequest(action = "SET_DND", arguments = args))
        }

        Log.d(TAG, "No offline command pattern matched for: '$query'")
        return null
    }
}
