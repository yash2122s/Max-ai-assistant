package com.example.network

import android.util.Log
import org.json.JSONObject

object ToolDispatcher {
    private const val TAG = "ToolDispatcher"
    
    val supportedTools = setOf(
        "execute_automation", 
        "open_app", 
        "send_whatsapp_message", 
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
        "set_reminder",
        "create_reminder"
    )

    private val functionToActionMap = mapOf(
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
        "set_reminder" to "SET_REMINDER",
        "create_reminder" to "SET_REMINDER"
    )

    fun dispatch(functionCall: FunctionCall, lastInputTranscription: String): String {
        val name = functionCall.name
        val argsObj = JSONObject(functionCall.args?.toString() ?: "{}")
        argsObj.put("function_name", name)

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
            if (name == "set_dnd" && argsObj.has("enabled")) {
                argsObj.put("dndEnabled", argsObj.optBoolean("enabled", true))
            }
            if (name == "open_settings" && argsObj.has("target")) {
                argsObj.put("settingsTarget", argsObj.optString("target", "main"))
            }
            if (name == "system_action") {
                val actionVal = functionCall.args?.get("action")?.asString ?: ""
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
                val contactIdVal = functionCall.args?.get("contactId")?.asLong ?: -1L
                val phoneIdVal = functionCall.args?.get("phoneId")?.asLong ?: -1L
                argsObj.put("action", "CALL_PHONE")
                argsObj.put("contactId", contactIdVal)
                argsObj.put("phoneId", phoneIdVal)
            }
            "youtube_search" -> {
                val queryVal = functionCall.args?.get("query")?.asString ?: ""
                argsObj.put("action", "YOUTUBE_SEARCH")
                argsObj.put("query", queryVal)
            }
            "open_app" -> {
                val appName = functionCall.args?.get("app_name")?.asString ?: ""
                val normalizedApp = appName.trim().lowercase()
                val mappedSystemAction = when (normalizedApp) {
                    "home", "home screen", "homepage" -> "home"
                    "back" -> "back"
                    "recent", "recents", "recent apps" -> "recent"
                    "notifications", "notification" -> "notifications"
                    "quick settings", "quick_settings", "quicksetting" -> "quick_settings"
                    "screenshot", "take screenshot" -> "screenshot"
                    else -> null
                }
                
                if (mappedSystemAction != null) {
                    Log.d(TAG, "Legacy Normalization: converting open_app('$appName') to SYSTEM_ACTION '$mappedSystemAction'")
                    argsObj.put("action", "SYSTEM_ACTION")
                    argsObj.put("system_action_str", mappedSystemAction)
                    argsObj.put("function_name", "system_action")
                } else if (appName.equals("YouTube", ignoreCase = true)) {
                    val queryText = lastInputTranscription.lowercase()
                    val searchTriggers = listOf(
                        "play", "search", "watch", "song", "music", "sing", "listen",
                        "ప్లే", "వెతుకు", "చూడు", "పాట", "సాంగ్", "విను"
                    )
                    val containsTrigger = searchTriggers.any { queryText.contains(it) }
                    if (containsTrigger) {
                        val cleanedQuery = queryText
                            .replace("play", "")
                            .replace("search", "")
                            .replace("watch", "")
                            .replace("on youtube", "")
                            .replace("youtube lo", "")
                            .replace("యూట్యూబ్‌లో", "")
                            .replace("ప్లే చేయి", "")
                            .replace("ప్లే చెయ్", "")
                            .trim()
                        
                        if (cleanedQuery.isNotEmpty()) {
                            Log.d(TAG, "Local Fallback: converting open_app(YouTube) to YOUTUBE_SEARCH for query: '$cleanedQuery'")
                            argsObj.put("action", "YOUTUBE_SEARCH")
                            argsObj.put("query", cleanedQuery)
                            argsObj.put("function_name", "youtube_search")
                        } else {
                            argsObj.put("action", "OPEN_APP")
                            argsObj.put("app", appName)
                        }
                    } else {
                        argsObj.put("action", "OPEN_APP")
                        argsObj.put("app", appName)
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
