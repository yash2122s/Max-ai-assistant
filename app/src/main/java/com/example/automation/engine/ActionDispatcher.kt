package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ActionDispatcher {
    private const val TAG = "ActionDispatcher"

    private fun normalizeJson(json: JSONObject): JSONObject {
        var actionName = json.optString("action", "NONE")
        
        // Normalization for legacy/fallback OPEN_APP calls
        if (actionName == "OPEN_APP") {
            val appVal = json.optString("app")
                .ifEmpty { json.optString("appName") }
                .ifEmpty { json.optString("app_name") }
                .trim().lowercase()
            
            val mappedSysAction = when (appVal) {
                "home", "home screen", "homepage" -> "home"
                "back" -> "back"
                "recent", "recents", "recent apps" -> "recent"
                "notifications", "notification" -> "notifications"
                "quick settings", "quick_settings", "quicksetting" -> "quick_settings"
                "screenshot", "take screenshot" -> "screenshot"
                else -> null
            }
            if (mappedSysAction != null) {
                json.put("action", "SYSTEM_ACTION")
                json.put("system_action_str", mappedSysAction)
                actionName = "SYSTEM_ACTION"
            }
        }

        when (actionName) {
            "PERFORM_BACK" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "back") }
            "PERFORM_HOME" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "home") }
            "PERFORM_RECENT_APPS" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "recent") }
            "PERFORM_NOTIFICATIONS" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "notifications") }
            "PERFORM_QUICK_SETTINGS" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "quick_settings") }
            "TAKE_SCREENSHOT", "SCREENSHOT" -> { json.put("action", "TAKE_SCREENSHOT") }
            "VOLUME_UP" -> { json.put("action", "SET_VOLUME"); json.put("direction", "up") }
            "VOLUME_DOWN" -> { json.put("action", "SET_VOLUME"); json.put("direction", "down") }
            "BRIGHTNESS_UP" -> { json.put("action", "SET_BRIGHTNESS"); json.put("direction", "up") }
            "BRIGHTNESS_DOWN" -> { json.put("action", "SET_BRIGHTNESS"); json.put("direction", "down") }
            "DND_ON" -> { json.put("action", "SET_DND"); json.put("dndEnabled", true) }
            "DND_OFF" -> { json.put("action", "SET_DND"); json.put("dndEnabled", false) }
            "SILENT_MODE_ON" -> { json.put("action", "SET_RINGER_MODE"); json.put("mode", "silent") }
            "SILENT_MODE_OFF" -> { json.put("action", "SET_RINGER_MODE"); json.put("mode", "normal") }
        }
        return json
    }

    suspend fun dispatch(context: Context, json: JSONObject): Boolean {
        Log.d(TAG, "dispatch() - payload: $json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        if (actionName == "NONE") return false

        val request = ExecutionRequest(
            action = actionName,
            arguments = JsonParser.parseString(normalized.toString()).asJsonObject,
            source = ExecutionSource.MANUAL
        )

        return try {
            val result = withContext(Dispatchers.IO) {
                ExecutionEngine.execute(context, request)
            }
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatching action: $actionName", e)
            false
        }
    }

    suspend fun dispatchWithResult(context: Context, json: JSONObject): String {
        Log.d(TAG, "dispatchWithResult() - payload: $json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        if (actionName == "NONE") {
            return JSONObject().apply {
                put("success", false)
                put("message", "Action was not handled by dispatcher")
                put("error", JSONObject().apply {
                    put("code", "ACTION_NOT_HANDLED")
                    put("message", "Action was not handled by dispatcher")
                })
                put("data", JSONObject())
            }.toString()
        }

        val request = ExecutionRequest(
            action = actionName,
            arguments = JsonParser.parseString(normalized.toString()).asJsonObject,
            source = ExecutionSource.GEMINI_LIVE
        )

        return try {
            val result = withContext(Dispatchers.IO) {
                ExecutionEngine.execute(context, request)
            }
            val returnJson = JSONObject().apply {
                put("success", result.success)
                put("message", result.message ?: (if (result.success) "Success" else "Execution failed"))
                if (!result.success) {
                    put("error", JSONObject().apply {
                        put("code", result.errorCode ?: "UNKNOWN_ERROR")
                        put("message", result.message ?: "Execution failed")
                    })
                } else {
                    put("error", JSONObject.NULL)
                }
                
                val dataObj = JSONObject()
                val metaKeys = result.metadata.keys()
                while (metaKeys.hasNext()) {
                    val key = metaKeys.next()
                    dataObj.put(key, result.metadata.get(key))
                }
                put("data", dataObj)
            }
            returnJson.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatchWithResult: $actionName", e)
            JSONObject().apply {
                put("success", false)
                put("message", e.message ?: "Unknown error in ActionDispatcher")
                put("error", JSONObject().apply {
                    put("code", "DISPATCH_ERROR")
                    put("message", e.message ?: "Unknown error in ActionDispatcher")
                })
                put("data", JSONObject())
            }.toString()
        }
    }
}
