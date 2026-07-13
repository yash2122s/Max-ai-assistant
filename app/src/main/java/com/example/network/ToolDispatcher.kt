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
        "windows_cmd"
    )

    fun dispatch(functionCall: FunctionCall, lastInputTranscription: String): String {
        val name = functionCall.name
        val argsObj = JSONObject(functionCall.args?.toString() ?: "{}")
        argsObj.put("function_name", name)

        when (name) {
            "windows_cmd" -> {
                argsObj.put("action", "WINDOWS_CMD")
            }
            "create_contact" -> {
                argsObj.put("action", "CREATE_CONTACT")
            }
            "diagnostics" -> {
                argsObj.put("action", "MAX_DIAGNOSTICS")
            }
            "call_contact" -> {
                val contactVal = functionCall.args?.get("contact")?.asString ?: ""
                argsObj.put("action", "CALL_PHONE")
                argsObj.put("contact", contactVal)
            }
            "youtube_search" -> {
                val queryVal = functionCall.args?.get("query")?.asString ?: ""
                argsObj.put("action", "YOUTUBE_SEARCH")
                argsObj.put("query", queryVal)
            }
            "open_app" -> {
                val appName = functionCall.args?.get("app_name")?.asString ?: ""
                if (appName.equals("YouTube", ignoreCase = true)) {
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
