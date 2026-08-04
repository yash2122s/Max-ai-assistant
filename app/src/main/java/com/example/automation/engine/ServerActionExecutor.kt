package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.telegram.CloudSocketManager
import org.json.JSONArray
import org.json.JSONObject

object ServerActionExecutor {
    private const val TAG = "ServerActionExecutor"

    private val ALLOWED_ACTIONS = setOf(
        "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "WIFI_SETTINGS",
        "OPEN_APP", "CALL_PHONE", "SEND_MESSAGE",
        "SET_REMINDER", "GET_TIME", "SYSTEM_ACTION",
        "PERFORM_BACK", "PERFORM_HOME", "PERFORM_RECENT_APPS",
        "TAKE_SCREENSHOT", "GET_LOCATION", "SEND_LOCATION"
    )

    fun execute(context: Context, requestJson: JSONObject) {
        val requestId = requestJson.optString("request_id")
        val payload = requestJson.optJSONObject("payload")
        val actions = payload?.optJSONArray("actions")

        if (actions == null || actions.length() == 0) {
            sendResult(requestId, "failed", "no_actions_provided")
            return
        }

        val resultsArray = JSONArray()

        for (i in 0 until actions.length()) {
            val actionItem = actions.optJSONObject(i) ?: continue
            val actionId = actionItem.optString("action_id", "act_$i")
            val actionName = actionItem.optString("action", "NONE")
            val params = actionItem.optJSONObject("params") ?: JSONObject()

            if (!ALLOWED_ACTIONS.contains(actionName.uppercase())) {
                Log.w(TAG, "Server requested unsupported action: $actionName")
                val res = JSONObject()
                res.put("action_id", actionId)
                res.put("status", "unsupported")
                res.put("error", "Action not in allowlist")
                resultsArray.put(res)
                continue
            }

            try {
                Log.d(TAG, "Executing Action from Server: $actionName")
                // Map the server action name to the local ActionDispatcher format
                val dispatchJson = JSONObject()
                dispatchJson.put("action", actionName)
                
                // Copy params into dispatchJson (ActionDispatcher expects them flat)
                val keys = params.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    dispatchJson.put(key, params.get(key))
                }

                // Dispatch it locally via the existing ActionDispatcher
                val resultString = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    ActionDispatcher.dispatchWithResult(context, dispatchJson)
                }
                val resultJson = JSONObject(resultString)
                
                val res = JSONObject()
                res.put("action_id", actionId)
                
                val keysIterator = resultJson.keys()
                while (keysIterator.hasNext()) {
                    val key = keysIterator.next()
                    res.put(key, resultJson.get(key))
                }
                resultsArray.put(res)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action $actionName", e)
                val res = JSONObject()
                res.put("action_id", actionId)
                res.put("status", "failed")
                res.put("error", e.message ?: "Unknown error")
                resultsArray.put(res)
            }
        }

        sendResult(requestId, resultsArray)
    }

    private fun sendResult(requestId: String, resultsArray: JSONArray) {
        val payload = JSONObject().apply {
            put("type", "action_result")
            put("request_id", requestId)
            put("user_id", "default_user")
            val payloadObj = JSONObject().apply {
                put("results", resultsArray)
            }
            put("payload", payloadObj)
        }
        CloudSocketManager.sendMessage(payload.toString())
    }

    private fun sendResult(requestId: String, status: String, error: String) {
        val res = JSONObject().apply {
            put("action_id", "unknown")
            put("status", status)
            put("error", error)
        }
        val arr = JSONArray().put(res)
        sendResult(requestId, arr)
    }
}
