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
import com.example.security.UnlockVerifier
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

object ActionDispatcher {
    private const val TAG = "ActionDispatcher"

    private data class InFlightDuplicate(
        val executionId: UUID,
        val startedAt: Long
    )

    private val duplicateLock = Any()
    private val inFlightDuplicates = mutableMapOf<String, InFlightDuplicate>()
    private const val DUPLICATE_ACTION_STALE_MS = 35_000L

    private fun duplicateTarget(json: JSONObject): String = listOf(
        "app", "app_name", "appName", "target", "query", "text"
    ).asSequence()
        .map { json.opt(it)?.toString().orEmpty().trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.lowercase()
        ?: ""

    private fun duplicateKey(actionName: String, target: String): String? {
        if (actionName !in setOf("OPEN_APP", "YOUTUBE_SEARCH")) return null
        return target.takeIf { it.isNotEmpty() }?.let { "$actionName|$it" }
    }

    private fun duplicateKey(actionName: String, json: JSONObject): String? =
        duplicateKey(actionName, duplicateTarget(json))

    private fun beginDuplicateExecution(
        actionName: String,
        json: JSONObject,
        executionId: UUID,
        now: Long = System.currentTimeMillis()
    ): InFlightDuplicate? {
        return beginDuplicateForTarget(actionName, duplicateTarget(json), executionId, now)
    }

    private fun beginDuplicateForTarget(
        actionName: String,
        target: String,
        executionId: UUID,
        now: Long
    ): InFlightDuplicate? {
        val key = duplicateKey(actionName, target) ?: return null
        synchronized(duplicateLock) {
            val existing = inFlightDuplicates[key]
            if (existing != null && now - existing.startedAt < DUPLICATE_ACTION_STALE_MS) {
                return existing
            }
            // A marker is only a guard for a live execution. Evicting an old
            // marker prevents an interrupted caller from blocking retries forever.
            inFlightDuplicates[key] = InFlightDuplicate(executionId, now)
            return null
        }
    }

    private fun endDuplicateExecution(actionName: String, json: JSONObject, executionId: UUID) {
        val key = duplicateKey(actionName, json) ?: return
        synchronized(duplicateLock) {
            if (inFlightDuplicates[key]?.executionId == executionId) {
                inFlightDuplicates.remove(key)
            }
        }
    }

    internal fun tryReserveDuplicateForTests(
        actionName: String,
        target: String,
        executionId: UUID = UUID.randomUUID(),
        now: Long = System.currentTimeMillis()
    ): UUID? {
        val existing = beginDuplicateForTarget(
            actionName,
            target.lowercase().trim(),
            executionId,
            now
        )
        return existing?.executionId
    }

    internal fun releaseDuplicateForTests(actionName: String, target: String, executionId: UUID) {
        endDuplicateForTarget(actionName, target.lowercase().trim(), executionId)
    }

    private fun endDuplicateForTarget(actionName: String, target: String, executionId: UUID) {
        val key = duplicateKey(actionName, target) ?: return
        synchronized(duplicateLock) {
            if (inFlightDuplicates[key]?.executionId == executionId) {
                inFlightDuplicates.remove(key)
            }
        }
    }

    private fun duplicateInFlightResult(actionName: String, target: String, executionId: UUID): String =
        JSONObject().apply {
            put("status", "in_progress")
            put("success", false)
            put("executionState", "RUNNING")
            put("executionId", executionId.toString())
            put("actionName", actionName)
            put("target", target)
            put("error", JSONObject().apply {
                put("code", "DUPLICATE_IN_FLIGHT")
                put("message", "An identical '$actionName' request is already executing.")
            })
            put("message", "An identical '$actionName' request is already executing.")
            put("data", JSONObject())
        }.toString()

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
        if (lower.contains("call log") || lower.contains("call history") || lower.contains("callback") || lower.contains("recall")) return false

        val hasLatinPrefix = Regex("""^(?:call|dial|phone|make\s+a\s+call)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        val hasLatinCallChey = Regex("""\b(?:call|phone)\s*(?:cheyyi|cheyandi|chey|cheyi)\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)

        val hasTeluguPrefix = lower.startsWith("కాల్ ") || lower.startsWith("ఫోన్ ") || lower.startsWith("డయల్ ")
        val hasTeluguCallSuffix = lower.contains("కాల్ చెయ్") || lower.contains("ఫోన్ చెయ్") ||
            lower.contains("డయల్ చెయ్") || lower.contains("కాల్ చేయి") ||
            lower.contains("కాల్ చెయ్యి") ||
            lower.contains("కాల్ చేయ్") ||
            lower.contains("కాల్ చేయండి") ||
            lower.contains("ఫోన్ చేయి") ||
            lower.contains("ఫోన్ చెయ్యి") ||
            lower.contains("ఫోన్ చేయ్") ||
            lower.contains("ఫోన్ చేయండి") ||
            lower.contains("డయల్ చేయి") ||
            lower.contains("డయల్ చెయ్యి") ||
            lower.contains("డయల్ చేయండి") ||
            ((lower.contains("చేయి") || lower.contains("చెయ్యి") || lower.contains("చేయ్") || lower.contains("చేయండి")) && (lower.contains("కాల్") || lower.contains("ఫోన్")))

        return hasLatinPrefix || hasLatinCallChey || hasTeluguPrefix || hasTeluguCallSuffix
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

        if (json.has("direct_automation")) {
            val direct = json.optJSONObject("direct_automation")
            if (direct != null) {
                val directType = direct.optString("type", "").uppercase()
                val mapped = when (directType) {
                    "WHATSAPP" -> "SEND_WHATSAPP_MESSAGE"
                    "TELEGRAM" -> "SEND_TELEGRAM_MESSAGE"
                    "SMS" -> "SEND_SMS"
                    else -> directType.ifEmpty { "NONE" }
                }
                json.put("action", mapped)
                actionName = mapped
                val keys = direct.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!json.has(k)) {
                        json.put(k, direct.get(k))
                    }
                }
            }
        }
        
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

    private fun assertUnlockedForAction(context: Context, actionName: String): Result<Unit> =
        UnlockVerifier.assertUnlockedForAction(context, actionName)

    private fun blockedByDeviceLock(actionName: String, failure: Result<Unit>): String {
        val message = failure.exceptionOrNull()?.message
            ?: "Device is locked; action '$actionName' was blocked."
        return JSONObject().apply {
            put("status", "blocked")
            put("success", false)
            put("executionState", "BLOCKED")
            put("actionName", actionName)
            put("errorCode", "DEVICE_LOCKED")
            put("message", message)
            put("error", JSONObject().apply {
                put("code", "DEVICE_LOCKED")
                put("message", message)
            })
            put("data", JSONObject())
        }.toString()
    }

    suspend fun dispatch(context: Context, json: JSONObject): Boolean {
        Log.d(TAG, "[DISPATCH] RECEIVED payload=$json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        // Phase F: Security-Critical Gate - Re-check unlock immediately before sensitive operations
        val unlockGate = assertUnlockedForAction(context, actionName)
        if (unlockGate.isFailure) {
            val failureMsg = unlockGate.exceptionOrNull()?.message ?: "Device is locked with keyguard."
            Log.w(TAG, failureMsg)
            return false
        }
        if (actionName == "NONE") return false

        val dispatchExecutionId = UUID.randomUUID()
        val duplicateInFlight = beginDuplicateExecution(actionName, normalized, dispatchExecutionId)
        if (duplicateInFlight != null) {
            Log.w(TAG, "[DISPATCH_DEBOUNCE] Rejecting duplicate in-flight $actionName for '${duplicateTarget(normalized)}'")
            return false
        }

        try {
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
            executionId = dispatchExecutionId,
            action = actionName,
            arguments = JsonParser.parseString(normalized.toString()).asJsonObject,
            source = ExecutionSource.MANUAL
        )

        return try {
            val result = withContext(Dispatchers.IO) {
                ExecutionEngine.execute(context, request)
            }
            result.isVerifiedSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatching action: $actionName", e)
            false
        } finally {
            endDuplicateExecution(actionName, normalized, dispatchExecutionId)
        }
        } finally {
            // Early-return branches are also executions; release their marker.
            endDuplicateExecution(actionName, normalized, dispatchExecutionId)
        }
    }

    suspend fun dispatchWithResult(context: Context, json: JSONObject): String {
        Log.d(TAG, "[DISPATCH] DISPATCH_WITH_RESULT_RECEIVED payload=$json")
        val normalized = normalizeJson(json)
        val actionName = normalized.optString("action", "NONE")
        val unlockGate = assertUnlockedForAction(context, actionName)
        if (unlockGate.isFailure) {
            val failureMsg = unlockGate.exceptionOrNull()?.message ?: "Device is locked with keyguard."
            Log.w(TAG, failureMsg)
            return blockedByDeviceLock(actionName, unlockGate)
        }
        val targetVal = normalized.optString("app").ifEmpty {
            normalized.optString("app_name").ifEmpty {
                normalized.optString("appName").ifEmpty {
                    normalized.optString("target").ifEmpty { normalized.optString("query") }
                }
            }
        }.lowercase().trim()
        if (actionName == "TASK_COMPLETED") {
            val summary = normalized.optString("summary", "Task completed and verified.")
            val requestedTaskId = normalized.optString("taskId").takeIf { it.isNotBlank() }
            // A model completion claim is never sufficient on its own. Even an
            // in-session voice task must have a correlated persisted task and
            // locally verified step evidence before completion is accepted.
            val decision = runCatching {
                com.example.automation.engine.execution.CompletionVerifier.verify(
                    context = context,
                    requestedTaskId = requestedTaskId,
                    goal = normalized.optString("goal").takeIf { it.isNotBlank() },
                    summary = summary
                )
            }.getOrElse { error ->
                com.example.automation.engine.execution.CompletionVerifier.Decision(
                    accepted = false,
                    reason = "Completion verification failed: ${error.message ?: "unknown error"}",
                    taskId = requestedTaskId
                )
            }
            return JSONObject().apply {
                put("status", if (decision.accepted) "success" else "blocked")
                put("success", decision.accepted)
                put("completionAccepted", decision.accepted)
                put("executionState", if (decision.accepted) "COMPLETED" else "BLOCKED")
                put("taskId", decision.taskId ?: JSONObject.NULL)
                put("foregroundPackage", decision.foregroundPackage ?: JSONObject.NULL)
                put("message", if (decision.accepted) decision.reason else "Completion rejected: ${decision.reason}")
            }.toString()
        }
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
        val executionId = executionIdStr?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()

        val duplicateInFlight = beginDuplicateExecution(actionName, normalized, executionId)
        if (duplicateInFlight != null) {
            Log.w(TAG, "[DISPATCH_DEBOUNCE] Rejecting duplicate in-flight $actionName for target '$targetVal'")
            return duplicateInFlightResult(actionName, targetVal, duplicateInFlight.executionId)
        }

        val request = ExecutionRequest(
            executionId = executionId,
            action = actionName,
            arguments = JsonParser.parseString(normalized.toString()).asJsonObject,
            source = ExecutionSource.GEMINI_LIVE,
            taskId = taskId,
            stepId = stepId,
            requestId = requestId
        )

        val executionStartTime = System.currentTimeMillis()
        return try {
            val result = withContext(Dispatchers.IO) {
                ExecutionEngine.execute(context, request)
            }
            val executionDuration = System.currentTimeMillis() - executionStartTime
            val toolStatus = if (result.isVerifiedSuccess) com.example.analytics.ActivityStatus.SUCCESS else com.example.analytics.ActivityStatus.FAILURE
            com.example.analytics.MaxActivityTracker.trackToolExecution(
                context = context,
                toolName = actionName,
                status = toolStatus,
                durationMs = executionDuration,
                errorCategory = if (!result.isVerifiedSuccess) (result.errorCode ?: "EXECUTION_FAILED") else null
            )
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
            }
            returnJson.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val executionDuration = System.currentTimeMillis() - executionStartTime
            com.example.analytics.MaxActivityTracker.trackToolExecution(
                context = context,
                toolName = actionName,
                status = com.example.analytics.ActivityStatus.FAILURE,
                durationMs = executionDuration,
                errorCategory = "DISPATCH_ERROR"
            )
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
        } finally {
            // Completion, failure, timeout, and cancellation all release the
            // live marker so a later request can retry normally.
            endDuplicateExecution(actionName, normalized, executionId)
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
