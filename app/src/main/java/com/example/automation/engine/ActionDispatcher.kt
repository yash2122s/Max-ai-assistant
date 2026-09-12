package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.engine.planning.TaskIntent
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.engine.execution.CapabilityRouter
import com.example.automation.engine.execution.VerificationEngine
import com.example.automation.engine.execution.TaskRecoveryEngine
import com.example.automation.engine.execution.LongTaskOrchestrator
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ActionDispatcher {
    private const val TAG = "ActionDispatcher"

    fun normalizeJson(json: JSONObject): JSONObject {
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
            "TAP", "CLICK" -> { json.put("action", "CLICK_ELEMENT") }
            "TYPE", "INPUT_TEXT", "ENTER_TEXT", "KEYBOARD_TYPE" -> { json.put("action", "TYPE_TEXT") }
            "ENTER", "KEY_ENTER", "IME_ENTER", "PRESS_ENTER" -> { json.put("action", "PRESS_ENTER") }
            "CLEAR_TEXT", "CLEAR_INPUT", "RESET_TEXT" -> { json.put("action", "CLEAR_TEXT") }
            "KEYBOARD_ACTION", "PRESS_KEY", "KEY_PRESS" -> { json.put("action", "PRESS_KEY") }
            "KEY_BACK" -> { json.put("action", "SYSTEM_ACTION"); json.put("system_action_str", "back") }
            "KEY_TAB", "KEY_SPACE", "KEY_DELETE", "KEY_BACKSPACE", "KEY_ESC", "KEY_HOME", "KEY_END",
            "KEY_ARROW_UP", "KEY_ARROW_DOWN", "KEY_ARROW_LEFT", "KEY_ARROW_RIGHT", "KEY_SHIFT", "KEY_CTRL", "KEY_ALT" -> {
                json.put("action", "PRESS_KEY")
                if (!json.has("key")) {
                    json.put("key", actionName.removePrefix("KEY_"))
                }
            }
            "SCROLL" -> { json.put("action", "SCROLL_SCREEN") }
        }
        return json
    }

    suspend fun dispatch(context: Context, json: JSONObject): Boolean {
        Log.d(TAG, "dispatch() - payload: $json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        if (actionName == "NONE") return false

        if (actionName == "TASK_INTENT") {
            val goal = normalized.optString("goal", "")
            val targetEntity = normalized.optString("target_entity").ifEmpty { null }
            val requiredOutcome = normalized.optString("required_outcome").ifEmpty { null }

            val parametersMap = mutableMapOf<String, String>()
            val paramsObj = normalized.optJSONObject("parameters")
            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    parametersMap[key] = paramsObj.optString(key, "")
                }
            }

            val taskIntent = TaskIntent(
                goal = goal,
                targetEntity = targetEntity,
                requiredOutcome = requiredOutcome,
                parameters = parametersMap
            )

            val capabilityRouter = CapabilityRouter(context)
            val verificationEngine = VerificationEngine(context)
            val taskRecoveryEngine = TaskRecoveryEngine()
            val taskPlanner = TaskPlanner()
            val longTaskOrchestrator = LongTaskOrchestrator(
                capabilityRouter = capabilityRouter,
                verificationEngine = verificationEngine,
                recoveryEngine = taskRecoveryEngine,
                taskPlanner = taskPlanner
            )

            val originalUtterance = normalized.optString("last_input_transcription").ifEmpty { goal }
            val plan = taskPlanner.createPlan(originalUtterance = originalUtterance, taskIntent = taskIntent)

            CoroutineScope(Dispatchers.IO).launch {
                longTaskOrchestrator.executeTask(plan)
            }
            return true
        }

        if (actionName == "CALL_PHONE") {
            val transcription = normalized.optString("last_input_transcription").trim().lowercase()
            val isMessagingUtterance = transcription.contains("whatsapp") || 
                transcription.contains("message") || 
                transcription.contains("msg") || 
                transcription.contains("text ") ||
                transcription.contains("saying ") ||
                transcription.contains("that i ") ||
                transcription.contains("tell ")
            val isExplicitCall = transcription.startsWith("call ") ||
                transcription.startsWith("dial ") ||
                transcription.contains("call on whatsapp") ||
                transcription.contains("whatsapp call")

            if (isMessagingUtterance && !isExplicitCall) {
                Log.w(TAG, "Rejecting CALL_PHONE at dispatch boundary because utterance is messaging: '$transcription'")
                if (transcription.contains("whatsapp")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_whatsapp_message")
                    normalized.put("target_entity", "WhatsApp")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatch(context, normalized)
                }
                return false
            }
        }

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

        if (actionName == "CALL_PHONE") {
            val transcription = normalized.optString("last_input_transcription").trim().lowercase()
            val isMessagingUtterance = transcription.contains("whatsapp") || 
                transcription.contains("message") || 
                transcription.contains("msg") || 
                transcription.contains("text ") ||
                transcription.contains("saying ") ||
                transcription.contains("that i ") ||
                transcription.contains("tell ")
            val isExplicitCall = transcription.startsWith("call ") ||
                transcription.startsWith("dial ") ||
                transcription.contains("call on whatsapp") ||
                transcription.contains("whatsapp call")

            if (isMessagingUtterance && !isExplicitCall) {
                Log.w(TAG, "Rejecting CALL_PHONE at dispatchWithResult boundary because utterance is messaging: '$transcription'")
                if (transcription.contains("whatsapp")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_whatsapp_message")
                    normalized.put("target_entity", "WhatsApp")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatchWithResult(context, normalized)
                }
            }
        }

        if (actionName == "TASK_INTENT") {
            val goal = normalized.optString("goal", "")
            val targetEntity = normalized.optString("target_entity").ifEmpty { null }
            val requiredOutcome = normalized.optString("required_outcome").ifEmpty { null }

            val parametersMap = mutableMapOf<String, String>()
            val paramsObj = normalized.optJSONObject("parameters")
            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    parametersMap[key] = paramsObj.optString(key, "")
                }
            }

            val taskIntent = TaskIntent(
                goal = goal,
                targetEntity = targetEntity,
                requiredOutcome = requiredOutcome,
                parameters = parametersMap
            )

            val capabilityRouter = CapabilityRouter(context)
            val verificationEngine = VerificationEngine(context)
            val taskRecoveryEngine = TaskRecoveryEngine()
            val taskPlanner = TaskPlanner()
            val longTaskOrchestrator = LongTaskOrchestrator(
                capabilityRouter = capabilityRouter,
                verificationEngine = verificationEngine,
                recoveryEngine = taskRecoveryEngine,
                taskPlanner = taskPlanner
            )

            // 1. Generate 3-Tier Plan
            val originalUtterance = normalized.optString("last_input_transcription").ifEmpty { goal }
            val plan = taskPlanner.createPlan(originalUtterance = originalUtterance, taskIntent = taskIntent)

            // 2. Dispatch to LongTaskOrchestrator without blocking TTS
            CoroutineScope(Dispatchers.IO).launch {
                longTaskOrchestrator.executeTask(plan)
            }

            // 3. Immediately unblock Gemini Live
            return JSONObject().apply {
                put("success", true)
                put("message", "Task dispatched to background orchestrator.")
                put("data", JSONObject().apply {
                    put("taskId", plan.id)
                    put("status", "RUNNING")
                })
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
