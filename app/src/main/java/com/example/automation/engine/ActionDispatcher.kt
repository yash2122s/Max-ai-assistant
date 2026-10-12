package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.computeruse.ComputerUseEngine
import com.example.automation.engine.planning.TaskIntent
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.engine.execution.CapabilityRouter
import com.example.automation.engine.execution.VerificationEngine
import com.example.automation.engine.execution.TaskRecoveryEngine
import com.example.automation.engine.execution.LongTaskOrchestrator
import com.example.core.lifecycle.MaxRuntimeManager
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ActionDispatcher {
    private const val TAG = "ActionDispatcher"

    fun isWhatsAppCallUtterance(text: String): Boolean {
        val lower = text.trim().lowercase()
        val hasWhatsApp = lower.contains("whatsapp") || lower.contains("వాట్సాప్")
        if (!hasWhatsApp) return false
        if (lower.contains("message") || lower.contains("msg") || lower.contains("text ") || lower.contains("మెసేజ్")) {
            return false
        }
        val hasCall = lower.startsWith("call ") || lower.startsWith("dial ") ||
            lower.contains("whatsapp call") || lower.contains("call on whatsapp") ||
            lower.contains("call via whatsapp") || lower.contains("call through whatsapp") ||
            lower.contains("డయల్") || lower.contains("కాల్") || lower.contains("ఫోన్")
        return hasCall
    }

    fun isExplicitCallUtterance(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (isWhatsAppCallUtterance(lower)) return false
        return lower.startsWith("call ") ||
            lower.startsWith("dial ") ||
            lower.startsWith("make a call") ||
            lower.startsWith("phone ") ||
            lower.startsWith("కాల్ ") ||
            lower.startsWith("ఫోన్ ") ||
            lower.startsWith("డయల్ ") ||


            lower.contains("phone chey") ||
            lower.contains("phone cheyyi") ||
            lower.contains("call chey") ||
            lower.contains("call cheyyi") ||
            lower.contains("కాల్ చేయి") ||
            lower.contains("కాల్ చెయ్") ||
            lower.contains("కాల్ చేయండి") ||
            lower.contains("ఫోన్ చేయి") ||
            lower.contains("ఫోన్ చెయ్") ||
            lower.contains("ఫోన్ చేయండి")
    }

    fun isMessagingUtterance(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower.contains("whatsapp") ||
            lower.contains("message") ||
            lower.contains("msg") ||
            lower.contains("text ") ||
            lower.contains("saying ") ||
            lower.contains("that i ") ||
            lower.contains("tell ") ||
            lower.contains("send ") ||
            lower.contains("పంపు") ||
            lower.contains("పంపివ్వు") ||
            lower.contains("పెట్టు") ||
            lower.contains("మెసేజ్") ||
            lower.contains("వాట్సాప్")
    }

    fun isUiNavigationTask(targetEntity: String?, goal: String): Boolean {
        val targetLower = targetEntity?.trim()?.lowercase() ?: ""
        val goalLower = goal.trim().lowercase()

        // Pure native hardware / device settings bypass
        val isHardwareOrDirectSetting = targetLower in setOf("wifi", "bluetooth", "flashlight", "torch", "volume", "brightness", "dnd", "ringer") ||
            goalLower.contains("flashlight") ||
            goalLower.contains("torch") ||
            goalLower.contains("wifi") ||
            goalLower.contains("bluetooth") ||
            goalLower.contains("toggle_setting") ||
            goalLower.contains("set_volume") ||
            goalLower.contains("set_brightness") ||
            goalLower.contains("set_dnd") ||
            goalLower.contains("set_ringer")

        if (isHardwareOrDirectSetting) {
            return false
        }

        // Inverted routing rule: default any app entity or UI interaction to ComputerUseEngine
        return targetLower.isNotEmpty() ||
            goalLower.contains("search") ||
            goalLower.contains("profile") ||
            goalLower.contains("account") ||
            goalLower.contains("screen") ||
            goalLower.contains("inspect") ||
            goalLower.contains("sticker") ||
            goalLower.contains("reaction") ||
            goalLower.contains("click") ||
            goalLower.contains("tap") ||
            goalLower.contains("press") ||
            goalLower.contains("scroll") ||
            goalLower.contains("navigate") ||
            goalLower.contains("open") ||
            goalLower.contains("type") ||
            goalLower.contains("send") ||
            goalLower.contains("select")
    }

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
        Log.d(TAG, "[DISPATCH] RECEIVED payload=$json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        if (actionName == "NONE") return false

        // Phase F: Security-Critical Gate - Re-check unlock immediately before sensitive operations
        val unlockGate = com.example.security.UnlockVerifier.assertUnlockedForAction(context, actionName)
        if (unlockGate.isFailure) {
            val failureMsg = unlockGate.exceptionOrNull()?.message ?: "Device is locked with keyguard."
            Log.w(TAG, failureMsg)
            return false
        }

        if (actionName == "TASK_COMPLETED") {
            val summary = normalized.optString("summary").ifEmpty {
                normalized.optString("message", "Task completed successfully.")
            }
            Log.i(TAG, "[DISPATCH] TASK_COMPLETED: '$summary'")
            return true
        }

        if (actionName == "INSPECT_SCREEN" || actionName == "GET_SCREEN_CONTEXT") {
            return true
        }

        if (actionName == "COMPUTER_USE" || actionName == "UI_AGENT") {
            Log.i(TAG, "[DISPATCH] COMPUTER_USE_RECEIVED actionName=$actionName")
            val goal = normalized.optString("goal").ifEmpty {
                normalized.optString("task").ifEmpty {
                    normalized.optString("query", "Perform requested UI action")
                }
            }
            val targetApp = normalized.optString("target_app").ifEmpty {
                normalized.optString("app").ifEmpty {
                    normalized.optString("target_entity").ifEmpty { null }
                }
            }
            Log.i(TAG, "[DISPATCH] COMPUTER_USE_STARTED goal='$goal' targetApp='$targetApp'")
            val result = ComputerUseEngine.execute(context, goal, targetApp)
            Log.i(TAG, "[DISPATCH] COMPUTER_USE_RETURNED success=${result.success} error=${result.error} steps=${result.stepsExecuted} message='${result.message}'")
            return result.success
        }

        if (actionName == "TASK_INTENT") {
            resolveAtomicIntent(context, normalized)?.let { return dispatch(context, it) }
            val goal = normalized.optString("goal", "")
            val targetEntity = normalized.optString("target_entity").ifEmpty { null }
            val requiredOutcome = normalized.optString("required_outcome").ifEmpty { null }

            val isUiNav = isUiNavigationTask(targetEntity, goal)
            if (isUiNav) {
                val isMessagingApp = targetEntity?.lowercase() in setOf("whatsapp", "telegram")
                if (!isMessagingApp || MaxRuntimeManager.useComputerUseForMessaging) {
                    val result = ComputerUseEngine.execute(context, goal, targetEntity)
                    if (result.success || !isMessagingApp) {
                        return result.success
                    }
                    Log.w(TAG, "ComputerUseEngine failed for $targetEntity, falling back to TaskPlanner")
                }
            }

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
                context = context,
                capabilityRouter = capabilityRouter,
                verificationEngine = verificationEngine,
                recoveryEngine = taskRecoveryEngine,
                taskPlanner = taskPlanner
            )

            val originalUtterance = normalized.optString("last_input_transcription").ifEmpty { goal }
            val plan = taskPlanner.createPlan(originalUtterance = originalUtterance, taskIntent = taskIntent)

            val taskResult = withContext(Dispatchers.IO) {
                longTaskOrchestrator.executeTask(plan)
            }
            return taskResult.success
        }

        if (actionName == "CALL_PHONE") {
            val transcription = normalized.optString("last_input_transcription").trim().lowercase()
            if (isWhatsAppCallUtterance(transcription)) {
                Log.w(TAG, "Rejecting CALL_PHONE at dispatch boundary because utterance is a WhatsApp call: '$transcription'")
                return false
            }
            val messaging = isMessagingUtterance(transcription)
            val explicitCall = isExplicitCallUtterance(transcription)

            if (messaging && !explicitCall) {
                Log.w(TAG, "Rejecting CALL_PHONE at dispatch boundary because utterance is messaging: '$transcription'")
                if (transcription.contains("whatsapp") || transcription.contains("వాట్సాప్")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_whatsapp_message")
                    normalized.put("target_entity", "WhatsApp")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatch(context, normalized)
                } else if (transcription.contains("instagram")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_instagram_message")
                    normalized.put("target_entity", "Instagram")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatch(context, normalized)
                } else if (transcription.contains("telegram") || transcription.contains("టెలిగ్రామ్")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_telegram_message")
                    normalized.put("target_entity", "Telegram")
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
        Log.d(TAG, "[DISPATCH] DISPATCH_WITH_RESULT_RECEIVED payload=$json")
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
            if (isWhatsAppCallUtterance(transcription)) {
                Log.w(TAG, "Rejecting CALL_PHONE at dispatchWithResult boundary because utterance is a WhatsApp call: '$transcription'")
                return JSONObject().apply {
                    put("status", "unsupported")
                    put("success", false)
                    put("executionState", "UNSUPPORTED")
                    put("errorCode", "WHATSAPP_CALL_UNSUPPORTED")
                    put("message", "WhatsApp voice call automation is not supported by the existing runtime.")
                    put("error", JSONObject().apply {
                        put("code", "WHATSAPP_CALL_UNSUPPORTED")
                        put("message", "WhatsApp voice call automation is not supported by the existing runtime.")
                    })
                    put("data", JSONObject())
                }.toString()
            }
            val messaging = isMessagingUtterance(transcription)
            val explicitCall = isExplicitCallUtterance(transcription)

            if (messaging && !explicitCall) {
                Log.w(TAG, "Rejecting CALL_PHONE at dispatchWithResult boundary because utterance is messaging: '$transcription'")
                if (transcription.contains("whatsapp") || transcription.contains("వాట్సాప్")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_whatsapp_message")
                    normalized.put("target_entity", "WhatsApp")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatchWithResult(context, normalized)
                } else if (transcription.contains("instagram")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_instagram_message")
                    normalized.put("target_entity", "Instagram")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatchWithResult(context, normalized)
                } else if (transcription.contains("telegram") || transcription.contains("టెలిగ్రామ్")) {
                    normalized.put("action", "TASK_INTENT")
                    normalized.put("goal", "send_telegram_message")
                    normalized.put("target_entity", "Telegram")
                    normalized.put("required_outcome", "MessageSent")
                    return dispatchWithResult(context, normalized)
                }
                // Do not fall through to ExecutionEngine. A generic messaging
                // utterance must never be executed as CALL_PHONE merely because
                // no supported messaging platform was detected.
                return JSONObject().apply {
                    put("status", "error")
                    put("success", false)
                    put("executionState", "REJECTED")
                    put("message", "Phone call rejected: the request is a message, not a phone call")
                    put("error", JSONObject().apply {
                        put("code", "CALL_PHONE_MESSAGING_CONFLICT")
                        put("message", "Phone call rejected: the request is a message, not a phone call")
                    })
                    put("data", JSONObject())
                }.toString()
            }
        }

        if (actionName == "TASK_COMPLETED") {
            val summary = normalized.optString("summary").ifEmpty {
                normalized.optString("message", "Task completed successfully.")
            }
            Log.i(TAG, "[DISPATCH] DWR_TASK_COMPLETED: '$summary'")
            return JSONObject().apply {
                put("status", "success")
                put("success", true)
                put("executionState", "COMPLETED")
                put("message", summary)
                put("actionName", "TASK_COMPLETED")
                put("system_directive", "TASK COMPLETED: Confirm completion to the user in a brief natural sentence: '$summary'.")
                put("data", JSONObject().apply {
                    put("summary", summary)
                    put("completed", true)
                })
            }.toString()
        }

        if (actionName == "INSPECT_SCREEN" || actionName == "GET_SCREEN_CONTEXT") {
            val summary = com.example.service.JarvisAccessibilityService.getFormattedScreenSummary()
            return JSONObject().apply {
                put("status", "success")
                put("success", true)
                put("executionState", "COMPLETED")
                put("message", "Screen inspected successfully.")
                put("actionName", actionName)
                put("screen_summary", summary)
                put("data", JSONObject().apply {
                    put("screen_summary", summary)
                })
            }.toString()
        }

        if (actionName == "COMPUTER_USE" || actionName == "UI_AGENT") {
            Log.i(TAG, "[DISPATCH] DWR_COMPUTER_USE_RECEIVED actionName=$actionName")
            val goal = normalized.optString("goal").ifEmpty {
                normalized.optString("task").ifEmpty {
                    normalized.optString("query", "Perform requested UI action")
                }
            }
            val targetApp = normalized.optString("target_app").ifEmpty {
                normalized.optString("app").ifEmpty {
                    normalized.optString("target_entity").ifEmpty { null }
                }
            }
            Log.i(TAG, "[DISPATCH] DWR_COMPUTER_USE_STARTED goal='$goal' targetApp='$targetApp'")
            val result = ComputerUseEngine.execute(context, goal, targetApp)
            Log.i(TAG, "[DISPATCH] DWR_COMPUTER_USE_RETURNED success=${result.success} error=${result.error} steps=${result.stepsExecuted}")
            return JSONObject().apply {
                put("status", if (result.success) "success" else "error")
                put("success", result.success)
                put("executionState", if (result.success) "COMPLETED" else "FAILED")
                put("message", result.message)
                if (!result.success) {
                    put("error", JSONObject().apply {
                        put("code", result.error ?: "COMPUTER_USE_FAILED")
                        put("message", result.message)
                    })
                } else {
                    put("error", JSONObject.NULL)
                }
                put("data", JSONObject().apply {
                    put("stepsExecuted", result.stepsExecuted)
                    if (result.verifiedOutcome != null) {
                        put("verifiedOutcome", result.verifiedOutcome)
                    }
                })
            }.toString()
        }

        if (actionName == "TASK_INTENT") {
            resolveAtomicIntent(context, normalized)?.let { return dispatchWithResult(context, it) }
            val goal = normalized.optString("goal", "")
            val targetEntity = normalized.optString("target_entity").ifEmpty { null }
            val requiredOutcome = normalized.optString("required_outcome").ifEmpty { null }

            val isUiNav = isUiNavigationTask(targetEntity, goal)
            if (isUiNav) {
                val isMessagingApp = targetEntity?.lowercase() in setOf("whatsapp", "telegram")
                if (!isMessagingApp || MaxRuntimeManager.useComputerUseForMessaging) {
                    val result = ComputerUseEngine.execute(context, goal, targetEntity)
                    if (result.success || !isMessagingApp) {
                        return JSONObject().apply {
                            put("status", if (result.success) "success" else "error")
                            put("success", result.success)
                            put("executionState", if (result.success) "COMPLETED" else "FAILED")
                            put("message", result.message)
                            if (!result.success) {
                                put("error", JSONObject().apply {
                                    put("code", result.error ?: "TASK_FAILED")
                                    put("message", result.message)
                                })
                            } else {
                                put("error", JSONObject.NULL)
                            }
                            put("data", JSONObject().apply {
                                put("stepsExecuted", result.stepsExecuted)
                                if (result.verifiedOutcome != null) {
                                    put("verifiedOutcome", result.verifiedOutcome)
                                }
                            })
                        }.toString()
                    }
                    Log.w(TAG, "ComputerUseEngine failed for $targetEntity, falling back to TaskPlanner")
                }
            }

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
                context = context,
                capabilityRouter = capabilityRouter,
                verificationEngine = verificationEngine,
                recoveryEngine = taskRecoveryEngine,
                taskPlanner = taskPlanner
            )

            val originalUtterance = normalized.optString("last_input_transcription").ifEmpty { goal }
            val plan = taskPlanner.createPlan(originalUtterance = originalUtterance, taskIntent = taskIntent)

            val taskResult = withContext(Dispatchers.IO) {
                longTaskOrchestrator.executeTask(plan)
            }

            return JSONObject().apply {
                put("status", if (taskResult.success) "success" else "error")
                put("success", taskResult.success)
                put("executionState", if (taskResult.success) "COMPLETED" else "FAILED")
                put("message", taskResult.message ?: (if (taskResult.success) "Task completed" else "Task failed"))
                put("data", JSONObject().apply {
                    put("taskId", plan.id)
                    put("executedSteps", taskResult.executedSteps)
                    put("totalSteps", taskResult.totalSteps)
                })
            }.toString()
        }

        val taskId = normalized.optString("taskId").ifEmpty { null }
        val stepId = normalized.optString("stepId").ifEmpty { null }
        val requestId = normalized.optString("requestId").ifEmpty { null }
        val executionIdStr = normalized.optString("executionId").ifEmpty { null }
        val executionId = executionIdStr?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            ?: java.util.UUID.randomUUID()

        val request = ExecutionRequest(
            executionId = executionId,
            action = actionName,
            arguments = JsonParser.parseString(normalized.toString()).asJsonObject,
            source = ExecutionSource.GEMINI_LIVE,
            taskId = taskId,
            stepId = stepId,
            requestId = requestId
        )

        return try {
            val result = withContext(Dispatchers.IO) {
                ExecutionEngine.execute(context, request)
            }
            val returnJson = JSONObject().apply {
                val isSuccess = result.isVerifiedSuccess
                val isNeedsInput = result.status == com.example.automation.tools.ToolResultStatus.NEEDS_INPUT
                val isUserActionRequired = result.status == com.example.automation.tools.ToolResultStatus.USER_ACTION_REQUIRED
                val isCallRequested = result.status == com.example.automation.tools.ToolResultStatus.ACCEPTED ||
                    result.verification?.reason?.startsWith("CALL_REQUESTED") == true ||
                    result.errorCode == "CALL_REQUESTED" ||
                    result.metadata.optString("status") == "CALL_REQUESTED"

                val statusStr = when {
                    isSuccess -> "success"
                    isCallRequested -> "accepted"
                    isNeedsInput -> "needs_input"
                    isUserActionRequired -> "user_action_required"
                    else -> "error"
                }

                val execStateStr = when {
                    result.errorCode == "EXECUTION_CANCELLED" -> "CANCELLED"
                    isSuccess -> "COMPLETED"
                    isCallRequested -> "CALL_REQUESTED"
                    isNeedsInput -> "WAITING_FOR_INPUT"
                    isUserActionRequired -> "BLOCKED"
                    result.success || result.errorCode in setOf("VERIFICATION_FAILED", "EXECUTION_TIMEOUT", "TERMINAL_PERSISTENCE_FAILED") -> "UNVERIFIED"
                    else -> "FAILED"
                }

                put("status", statusStr)
                put("success", isSuccess)
                put("taskId", result.metadata.opt("taskId") ?: request.taskId ?: JSONObject.NULL)
                put("stepId", result.metadata.opt("stepId") ?: request.stepId ?: JSONObject.NULL)
                put("executionId", request.executionId.toString())
                put("actionName", request.action)
                put("target", normalized.optString("target", normalized.optString("app_name")))
                put("executorSuccess", result.metadata.optBoolean("executorSuccess", result.success))
                put("verificationStatus", if (isSuccess) "VERIFIED" else "UNVERIFIED")
                put("evidence", result.verification?.reason ?: JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
                put("executionState", execStateStr)
                put("message", result.message ?: (if (result.success) "Success" else "Execution failed"))
                if (isNeedsInput) {
                    put("question", result.needsInputQuestion ?: result.message)
                    put("options", org.json.JSONArray(result.needsInputOptions))
                    put("targetParam", result.targetParam)
                }
                if (!result.success && !isNeedsInput && !isCallRequested) {
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
                result.outputData.forEach { (k, v) ->
                    dataObj.put(k, v)
                }
                put("data", dataObj)

                val uiActions = setOf("CLICK_ELEMENT", "TYPE_TEXT", "SCROLL_SCREEN", "OPEN_APP", "PRESS_KEY", "PRESS_ENTER", "CLEAR_TEXT", "SYSTEM_ACTION", "CLICK_TEXT")
                if (actionName in uiActions) {
                    val summary = com.example.service.JarvisAccessibilityService.getFormattedScreenSummary()
                    put("screen_summary", summary)
                    if (isSuccess) {
                        put("system_directive", "ACTION $actionName EXECUTED. Inspect the fresh screen_summary. If the overall goal is not yet finished, take the next concrete action. NEVER call task_completed until the ultimate goal is fully achieved on screen!")
                    }
                }
            }
            returnJson.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatchWithResult: $actionName", e)
            JSONObject().apply {
                put("status", "error")
                put("success", false)
                put("executionState", "FAILED")
                put("message", e.message ?: "Unknown error in ActionDispatcher")
                put("error", JSONObject().apply {
                    put("code", "DISPATCH_ERROR")
                    put("message", e.message ?: "Unknown error in ActionDispatcher")
                })
                put("data", JSONObject())
            }.toString()
        }
    }

    private fun resolveAtomicIntent(context: Context, json: JSONObject): JSONObject? {
        val goal = json.optString("last_input_transcription").ifBlank { json.optString("goal") }
        val steps = GoalPlanner.resolveFastPathPlan(goal, context) ?: return null
        if (!GoalPlanner.isSimplePlan(steps) || GoalPlanner.validatePlan(steps, goal) != null) return null
        val request = ExecutionRequest.fromStep(steps.single(), ExecutionSource.GEMINI_LIVE, CancellationToken())
        return JSONObject(request.arguments.toString()).apply {
            put("action", request.action)
            put("last_input_transcription", goal)
            listOf("taskId", "stepId", "executionId", "requestId").forEach { key -> if (json.has(key)) put(key, json.get(key)) }
        }
    }
}
