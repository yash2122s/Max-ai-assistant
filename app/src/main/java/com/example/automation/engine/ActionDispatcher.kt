package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

object ActionDispatcher {
    private const val TAG = "ActionDispatcher"

    private fun normalizeJson(json: JSONObject): JSONObject {
        val actionName = json.optString("action", "NONE")
        when (actionName) {
            "PERFORM_BACK" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "back") }
            "PERFORM_HOME" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "home") }
            "PERFORM_RECENT_APPS" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "recent") }
            "TAKE_SCREENSHOT", "SCREENSHOT" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "screenshot") }
            "VOLUME_UP" -> { json.put("action", "SET_VOLUME"); json.put("direction", "up") }
            "VOLUME_DOWN" -> { json.put("action", "SET_VOLUME"); json.put("direction", "down") }
            "BRIGHTNESS_UP" -> { json.put("action", "SET_BRIGHTNESS"); json.put("direction", "up") }
            "BRIGHTNESS_DOWN" -> { json.put("action", "SET_BRIGHTNESS"); json.put("direction", "down") }
            "DND_ON", "DND_OFF", "SILENT_MODE_ON", "SILENT_MODE_OFF" -> { json.put("action", "SET_RINGER_MODE") }
        }
        return json
    }

    fun dispatch(context: Context, json: JSONObject): Boolean {
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
            val result = runBlocking {
                ExecutionEngine.execute(context, request)
            }
            result.success
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatching action: $actionName", e)
            false
        }
    }

    fun dispatchWithResult(context: Context, json: JSONObject): String {
        Log.d(TAG, "dispatchWithResult() - payload: $json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        if (actionName == "NONE") {
            return JSONObject().apply {
                put("status", "error")
                put("reason", "Action was not handled by dispatcher")
            }.toString()
        }

        val request = ExecutionRequest(
            action = actionName,
            arguments = JsonParser.parseString(normalized.toString()).asJsonObject,
            source = ExecutionSource.GEMINI_LIVE
        )

        return try {
            val result = runBlocking {
                ExecutionEngine.execute(context, request)
            }
            val returnJson = JSONObject().apply {
                put("status", if (result.success) "success" else "error")
                if (!result.success) {
                    put("reason", result.message ?: "Execution failed")
                }
                val metaKeys = result.metadata.keys()
                while (metaKeys.hasNext()) {
                    val key = metaKeys.next()
                    put(key, result.metadata.get(key))
                }
            }
            returnJson.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatchWithResult: $actionName", e)
            JSONObject().apply {
                put("status", "error")
                put("reason", e.message ?: "Unknown error in ActionDispatcher")
            }.toString()
        }
    }
}
