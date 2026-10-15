package com.example.network

import android.util.Log
import org.json.JSONObject
import com.example.utils.asStringOrNull
import com.example.utils.asLongOrNull
import com.example.automation.engine.ActionDispatcher
import com.example.automation.engine.PendingCallManager
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.engine.DirectCallAuthorization

object ToolDispatcher {
    private const val TAG = "ToolDispatcher"
    
    val supportedTools = setOf(
        "task_intent",
        "execute_automation", 
        "open_app", 
        "send_whatsapp_message", 
        "open_whatsapp_chat",
        "navigate_whatsapp",
        "flashlight", 
        "schedule_task", 
        "cancel_task", 
        "list_scheduled_tasks", 
        "youtube_search", 
        "call_contact",
        "diagnostics",
        "create_contact",
        "windows_cmd",
        "windows_agent",
        "control_media",

        // New tools
        "get_battery_status",
        "get_dnd_status",
        "set_dnd",
        "get_calendar_events",
        "add_calendar_event",
        "take_screenshot",
        "take_photo",
        "run_adb_command",
        "get_bluetooth_status",
        "set_bluetooth",
        // Phase 1 tools
        "get_notifications",
        "reply_notification",
        "set_alarm",
        "set_timer",
        "control_media",
        "search_files",
        "toggle_wifi",
        // Phase 2 tools
        "open_settings",
        "get_device_status",
        "get_app_usage",
        "get_clipboard",
        "set_clipboard",
        "get_location",
        // Phase 3 tools
        "run_routine",
        "create_routine",
        "delete_routine",
        "list_routines",
        "log_period_start",
        "log_period_end",
        "log_period_note",
        "get_period_history",
        "get_period_prediction",
        "clear_all_period_data",
        "search_contact",
        "system_action",
        "save_memory",
        "forget_memory",
        "set_reminder",
        "create_reminder",
        "unlock_device",
        "click_element",
        "scroll_screen",
        "get_screen_context",
        "answer_call",
        "reject_call",
        "send_sms",
        "type_text",
        "press_enter",
        "press_key",
        "clear_text",
        "computer_use",
        "ui_agent",
        "wait",
        "delay",
        "inspect_screen",
        "spotify_smart_search",
        "instagram_bulk_unlike",
        "task_completed"
    )

    private val functionToActionMap = mapOf(
        "task_completed" to "TASK_COMPLETED",
        "computer_use" to "COMPUTER_USE",
        "ui_agent" to "COMPUTER_USE",
        "task_intent" to "TASK_INTENT",
        "get_screen_context" to "GET_SCREEN_CONTEXT",
        "click_element" to "CLICK_TEXT",
        "scroll_screen" to "SCROLL_SCREEN",
        "type_text" to "TYPE_TEXT",
        "press_enter" to "PRESS_ENTER",
        "press_key" to "PRESS_KEY",
        "clear_text" to "CLEAR_TEXT",
        "unlock_device" to "UNLOCK_DEVICE",
        "answer_call" to "ANSWER_CALL",
        "reject_call" to "REJECT_CALL",
        "send_sms" to "SEND_SMS",
        "send_whatsapp_message" to "SEND_WHATSAPP",
        "open_whatsapp_chat" to "OPEN_WHATSAPP_CHAT",
        "navigate_whatsapp" to "NAVIGATE_WHATSAPP",
        "get_battery_status" to "GET_BATTERY_STATUS",
        "get_dnd_status" to "GET_DND",
        "set_dnd" to "SET_DND",
        "get_calendar_events" to "GET_CALENDAR_EVENTS",
        "add_calendar_event" to "ADD_CALENDAR_EVENT",
        "take_screenshot" to "TAKE_SCREENSHOT",
        "take_photo" to "TAKE_PHOTO",
        "run_adb_command" to "RUN_ADB_COMMAND",
        "get_bluetooth_status" to "GET_BLUETOOTH_STATUS",
        "set_bluetooth" to "SET_BLUETOOTH",
        "create_contact" to "CREATE_CONTACT",
        "diagnostics" to "MAX_DIAGNOSTICS",
        "windows_cmd" to "WINDOWS_CMD",
        "windows_agent" to "WINDOWS_AGENT",

        // Phase 1 tools
        "get_notifications" to "GET_NOTIFICATIONS",
        "reply_notification" to "REPLY_NOTIFICATION",
        "set_alarm" to "SET_ALARM",
        "set_timer" to "SET_TIMER",
        "search_files" to "SEARCH_FILES",
        "toggle_wifi" to "TOGGLE_WIFI",
        // Phase 2 tools
        "open_settings" to "OPEN_SETTINGS",
        "get_device_status" to "GET_DEVICE_STATUS",
        "get_app_usage" to "GET_APP_USAGE",
        "get_clipboard" to "GET_CLIPBOARD",
        "set_clipboard" to "SET_CLIPBOARD",
        "get_location" to "GET_LOCATION",
        // Phase 3 tools
        "run_routine" to "RUN_ROUTINE",
        "create_routine" to "CREATE_ROUTINE",
        "delete_routine" to "DELETE_ROUTINE",
        "list_routines" to "LIST_ROUTINES",
        // Period tracker
        "log_period_start" to "LOG_PERIOD_START",
        "log_period_end" to "LOG_PERIOD_END",
        "log_period_note" to "LOG_PERIOD_NOTE",
        "get_period_history" to "GET_PERIOD_HISTORY",
        "get_period_prediction" to "GET_PERIOD_PREDICTION",
        "clear_all_period_data" to "CLEAR_ALL_PERIOD_DATA",
        "search_contact" to "SEARCH_CONTACT",
        "system_action" to "SYSTEM_ACTION",
        "save_memory" to "SAVE_MEMORY",
        "forget_memory" to "FORGET_MEMORY",
        "set_reminder" to "SET_REMINDER",
        "create_reminder" to "SET_REMINDER",
        "wait" to "WAIT",
        "delay" to "WAIT",
        "inspect_screen" to "INSPECT_SCREEN",
        "spotify_smart_search" to "PLAY_SPOTIFY",
        "instagram_bulk_unlike" to "BULK_UNLIKE"
    )

    fun dispatch(functionCall: FunctionCall, lastInputTranscription: String): String {
        val name = functionCall.name
        val argsObj = JSONObject(functionCall.args?.toString() ?: "{}")
        argsObj.put("function_name", name)
        if (lastInputTranscription.isNotBlank()) {
            argsObj.put("last_input_transcription", lastInputTranscription)
        }

        if (name == "control_media") {
            val mediaAction = argsObj.optString("media_action", "PLAY_MEDIA")
            argsObj.put("action", mediaAction)
            return argsObj.toString()
        }

        // Try mapping via the data-driven registry first
        val action = functionToActionMap[name]
        if (action != null) {
            argsObj.put("action", action)
            // Perform basic parameter normalization
            if (name == "task_completed") {
                val summary = argsObj.optString("summary", "Task completed and verified.")
                argsObj.put("summary", summary)
            }
            if (name == "send_whatsapp_message" || name == "open_whatsapp_chat") {
                val contact = argsObj.optString("contact").ifEmpty { argsObj.optString("name") }
                val message = argsObj.optString("message").ifEmpty { argsObj.optString("text") }
                argsObj.put("contact", contact)
                argsObj.put("message", message)
                argsObj.put("text", message)
            }
            if (name == "navigate_whatsapp") {
                val screen = argsObj.optString("screen").ifEmpty { argsObj.optString("tab").ifEmpty { argsObj.optString("target") } }
                argsObj.put("screen", screen)
            }
            if (name == "send_sms") {
                val num = argsObj.optString("number").ifEmpty { argsObj.optString("phone") }
                val msg = argsObj.optString("message").ifEmpty { argsObj.optString("text") }
                argsObj.put("number", num)
                argsObj.put("message", msg)
            }
            if (name == "set_dnd" && argsObj.has("enabled")) {
                argsObj.put("dndEnabled", argsObj.optBoolean("enabled", true))
            }
            if (name == "open_settings" && argsObj.has("target")) {
                argsObj.put("settingsTarget", argsObj.optString("target", "main"))
            }
            if (name == "system_action") {
                val actionVal = functionCall.args?.get("action").asStringOrNull() ?: ""
                argsObj.put("system_action_str", actionVal)
            }
            if (name == "run_routine") {
                if (argsObj.has("routine_name")) argsObj.put("routineName", argsObj.optString("routine_name"))
                if (argsObj.has("dry_run")) argsObj.put("dryRun", argsObj.optBoolean("dry_run"))
            }
            if (name == "create_routine") {
                if (argsObj.has("routine_name")) argsObj.put("routineName", argsObj.optString("routine_name"))
            }
            if (name == "delete_routine") {
                if (argsObj.has("routine_name")) argsObj.put("routineName", argsObj.optString("routine_name"))
            }
            return argsObj.toString()
        }

        // Special handling / fallback cases
        when (name) {
            "call_contact" -> {
                val isWhatsAppCall = ActionDispatcher.isWhatsAppCallUtterance(lastInputTranscription)
                val isMessaging = ActionDispatcher.isMessagingUtterance(lastInputTranscription)
                val isExplicitCellularCall = ActionDispatcher.isExplicitCallUtterance(lastInputTranscription)

                if (isWhatsAppCall) {
                    Log.w(TAG, "Intercepted call_contact tool call for WhatsApp call utterance: '$lastInputTranscription'")
                    argsObj.put("action", "TASK_INTENT")
                    argsObj.put("goal", "whatsapp_call")
                    argsObj.put("target_entity", "WhatsApp")
                    argsObj.put("required_outcome", "CallPlaced")
                    val params = JSONObject()
                    if (argsObj.has("contact")) params.put("contact", argsObj.optString("contact"))
                    argsObj.put("parameters", params)
                    argsObj.put("function_name", "task_intent")
                    argsObj.put("unsupported_reason", "WhatsApp call execution is not supported by the existing runtime.")
                } else if (isMessaging && !isExplicitCellularCall) {
                    Log.w(TAG, "Intercepted spurious call_contact tool call for messaging utterance: '$lastInputTranscription'")
                    val lower = lastInputTranscription.trim().lowercase()
                    if (lower.contains("whatsapp") || lower.contains("వాట్సాప్")) {
                        argsObj.put("action", "TASK_INTENT")
                        argsObj.put("goal", "send_whatsapp_message")
                        argsObj.put("target_entity", "WhatsApp")
                        argsObj.put("required_outcome", "MessageSent")
                        val params = JSONObject()
                        if (argsObj.has("contact")) params.put("contact", argsObj.optString("contact"))
                        if (argsObj.has("message")) params.put("message", argsObj.optString("message"))
                        argsObj.put("parameters", params)
                        argsObj.put("function_name", "task_intent")
                    } else if (lower.contains("telegram") || lower.contains("టెలిగ్రామ్")) {
                        argsObj.put("action", "TASK_INTENT")
                        argsObj.put("goal", "send_telegram_message")
                        argsObj.put("target_entity", "Telegram")
                        argsObj.put("required_outcome", "MessageSent")
                        val params = JSONObject()
                        if (argsObj.has("contact")) params.put("contact", argsObj.optString("contact"))
                        if (argsObj.has("message")) params.put("message", argsObj.optString("message"))
                        argsObj.put("parameters", params)
                        argsObj.put("function_name", "task_intent")
                    } else if (lower.contains("instagram")) {
                        argsObj.put("action", "TASK_INTENT")
                        argsObj.put("goal", "send_instagram_message")
                        argsObj.put("target_entity", "Instagram")
                        argsObj.put("required_outcome", "MessageSent")
                        val params = JSONObject()
                        if (argsObj.has("contact")) params.put("contact", argsObj.optString("contact"))
                        if (argsObj.has("message")) params.put("message", argsObj.optString("message"))
                        argsObj.put("parameters", params)
                        argsObj.put("function_name", "task_intent")
                    } else {
                        Log.w(TAG, "Generic messaging utterance did not mention WhatsApp, rejecting CALL_PHONE")
                        argsObj.put("action", "NONE")
                        argsObj.put("error", "CALL_PHONE rejected: utterance is messaging but target app was not specified.")
                    }
                } else {
                    val contactIdVal = functionCall.args?.get("contactId").asLongOrNull() ?: -1L
                    val phoneIdVal = functionCall.args?.get("phoneId").asLongOrNull() ?: -1L
                    val contactVal = functionCall.args?.get("contact").asStringOrNull() ?: ""
                    val phoneAliases = listOf("phoneNumber", "number").mapNotNull { key -> functionCall.args?.get(key).asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() } }
                    val phoneNumberVal = phoneAliases.firstOrNull() ?: ""
                    val conflictingAliases = phoneAliases.distinct().size > 1

                    // Never trust a model-provided authorization flag. Derive the
                    // one-shot direct-number authorization only from the actual
                    // user transcription recognized as an explicit cellular call.
                    argsObj.remove("explicit_user_call")
                    argsObj.remove("direct_call_authorization")
                    val directTarget = phoneNumberVal.ifBlank { contactVal }
                    val contactIsSameNumber = contactVal.isBlank() ||
                        (DirectCallAuthorization.normalize(contactVal) != null &&
                            DirectCallAuthorization.normalize(contactVal) == DirectCallAuthorization.normalize(directTarget))
                    val directFastPath = !conflictingAliases && contactIsSameNumber &&
                        contactIdVal == -1L && phoneIdVal == -1L && directTarget.isNotBlank()
                    if (directFastPath) {
                        DirectCallAuthorization.issue(lastInputTranscription, directTarget)?.let {
                            argsObj.put("direct_call_authorization", it)
                        }
                    }

                    // Check if explicit user confirmation is present in utterance
                    val userReply = ApprovalCoordinator.parseReply(lastInputTranscription)
                    if (userReply == ApprovalCoordinator.ReplyIntent.DENY) {
                        PendingCallManager.cancel()
                        argsObj.put("action", "NONE")
                        argsObj.put("cancelled", true)
                        argsObj.put("error", "Call cancelled by user.")
                        return argsObj.toString()
                    }

                    if (userReply == ApprovalCoordinator.ReplyIntent.APPROVE) {
                        PendingCallManager.confirmPendingCall(
                            if (contactIdVal != -1L) contactIdVal else null,
                            if (phoneIdVal != -1L) phoneIdVal else null
                        )
                        argsObj.put("confirmed", true)
                        argsObj.put("user_confirmed", true)
                    } else {
                        // Reject model-controlled confirmed flags without user approval
                        argsObj.remove("confirmed")
                        argsObj.remove("user_confirmed")
                    }

                    val originalIntent = PendingCallManager.getOriginalIntent()
                    if (!originalIntent.isNullOrBlank()) {
                        argsObj.put("original_user_intent", originalIntent)
                    }

                    argsObj.put("action", "CALL_PHONE")
                    argsObj.put("contactId", contactIdVal)
                    argsObj.put("phoneId", phoneIdVal)
                    if (contactVal.isNotEmpty()) argsObj.put("contact", contactVal)
                    if (phoneNumberVal.isNotEmpty()) argsObj.put("phoneNumber", phoneNumberVal)
                }
            }
            "youtube_search" -> {
                val queryVal = functionCall.args?.get("query").asStringOrNull() ?: ""
                argsObj.put("action", "YOUTUBE_SEARCH")
                argsObj.put("query", queryVal)
            }
            "open_app" -> {
                val appName = functionCall.args?.get("app_name").asStringOrNull() ?: ""
                val normalizedApp = appName.trim().lowercase()
                val mappedSystemAction = when {
                    normalizedApp in listOf("home", "home screen", "homepage", "homescreen", "home screen ki vellu", "home ki vellu") ||
                    normalizedApp.contains("హోమ్") || normalizedApp.contains("హోం") -> "home"
                    normalizedApp in listOf("back", "go back") || normalizedApp.contains("వెనక్కి") || normalizedApp.contains("వెనుకకు") -> "back"
                    normalizedApp in listOf("recent", "recents", "recent apps") || normalizedApp.contains("రీసెంట్") -> "recent"
                    normalizedApp in listOf("notifications", "notification") || normalizedApp.contains("నోటిఫికేషన్") -> "notifications"
                    normalizedApp in listOf("quick settings", "quick_settings", "quicksetting") || normalizedApp.contains("క్విక్ సెట్టింగ్స్") -> "quick_settings"
                    normalizedApp in listOf("screenshot", "take screenshot") || normalizedApp.contains("స్క్రీన్‌షాట్") || normalizedApp.contains("స్క్రీన్ షాట్") -> "screenshot"
                    else -> null
                }
                
                val isWhatsAppScreen = when {
                    normalizedApp in listOf("whatsapp status", "whatsapp updates", "whatsapp status screen", "whatsapp update") ||
                    normalizedApp.contains("వాట్సాప్ స్టేటస్") || normalizedApp.contains("వాట్సాప్ అప్‌డేట్స్") -> "status"
                    normalizedApp in listOf("whatsapp calls", "whatsapp call screen", "whatsapp calls screen") ||
                    normalizedApp.contains("వాట్సాప్ కాల్స్") -> "calls"
                    normalizedApp in listOf("whatsapp settings", "whatsapp setting") ||
                    normalizedApp.contains("వాట్సాప్ సెట్టింగ్స్") -> "settings"
                    normalizedApp in listOf("whatsapp communities", "whatsapp community") ||
                    normalizedApp.contains("వాట్సాప్ కమ్యూనిటీస్") -> "communities"
                    normalizedApp in listOf("whatsapp new chat", "whatsapp newchat") ||
                    normalizedApp.contains("వాట్సాప్ న్యూ చాట్") -> "new_chat"
                    normalizedApp in listOf("whatsapp chats", "whatsapp chat list") ||
                    normalizedApp.contains("వాట్సాప్ చాట్స్") -> "chats"
                    normalizedApp in listOf("whatsapp starred messages", "whatsapp starred") ||
                    normalizedApp.contains("వాట్సాప్ స్టార్డ్") -> "starred_messages"
                    normalizedApp in listOf("whatsapp linked devices", "whatsapp linked") ||
                    normalizedApp.contains("వాట్సాప్ లింక్డ్") -> "linked_devices"
                    else -> null
                }

                if (mappedSystemAction != null) {
                    Log.d(TAG, "Legacy Normalization: converting open_app('$appName') to SYSTEM_ACTION '$mappedSystemAction'")
                    argsObj.put("action", "SYSTEM_ACTION")
                    argsObj.put("system_action_str", mappedSystemAction)
                    argsObj.put("function_name", "system_action")
                } else if (isWhatsAppScreen != null) {
                    Log.d(TAG, "Legacy Normalization: converting open_app('$appName') to NAVIGATE_WHATSAPP screen='$isWhatsAppScreen'")
                    argsObj.put("action", "NAVIGATE_WHATSAPP")
                    argsObj.put("screen", isWhatsAppScreen)
                    argsObj.put("function_name", "navigate_whatsapp")
                } else if (normalizedApp in listOf("youtube", "yt", "యూట్యూబ్", "యూట్యూబ్లో", "యూట్యూబ్‌లో") || 
                    normalizedApp.contains("youtube") || normalizedApp.contains("యూట్యూబ్")) {
                    val queryText = lastInputTranscription.lowercase()
                    val searchTriggers = listOf(
                        "play", "search", "watch", "song", "music", "sing", "listen",
                        "ప్లే", "వెతుకు", "చూడు", "పాట", "సాంగ్", "విను"
                    )
                    val containsTrigger = searchTriggers.any { trigger ->
                        if (trigger.all { it.isLetterOrDigit() && it.code < 128 }) {
                            Regex("""(?i)\b${Regex.escape(trigger)}\b""").containsMatchIn(queryText)
                        } else {
                            queryText.contains(trigger)
                        }
                    }
                    if (containsTrigger) {
                        var cleaned = queryText
                            .replace(Regex("""(?i)\b(?:on|in)\s+youtube\b"""), "")
                            .replace(Regex("""(?i)\byoutube\s+lo\b"""), "")
                            .replace(Regex("""(?i)\byoutube\b"""), "")
                            .replace(Regex("""(?i)\byt\b"""), "")
                            .replace(Regex("""(?i)(?:యూట్యూబ్‌లో|యూట్యూబ్\s*లో|యూట్యూబ్లో|యూట్యూబ్)"""), "")
                            .replace(Regex("""(?i)\b(?:play|search|watch|sing|listen\s+to|listen)\b"""), "")
                            .replace(Regex("""(?i)(?:ప్లే\s*చేయి|ప్లే\s*చెయ్|ప్లే|వెతుకు|చూడు|పాట|సాంగ్|విను|చేయి|చెయ్)"""), "")
                            .replace(Regex("""\s+"""), " ")
                            .trim()

                        if (cleaned.isNotEmpty()) {
                            Log.d(TAG, "Local Fallback: converting open_app($appName) to YOUTUBE_SEARCH for query: '$cleaned'")
                            argsObj.put("action", "YOUTUBE_SEARCH")
                            argsObj.put("query", cleaned)
                            argsObj.put("function_name", "youtube_search")
                        } else {
                            argsObj.put("action", "OPEN_APP")
                            argsObj.put("app", "YouTube")
                        }
                    } else {
                        argsObj.put("action", "OPEN_APP")
                        argsObj.put("app", "YouTube")
                    }
                } else {
                    argsObj.put("action", "OPEN_APP")
                    argsObj.put("app", appName)
                }
            }
        }
        return argsObj.toString()
    }
}
