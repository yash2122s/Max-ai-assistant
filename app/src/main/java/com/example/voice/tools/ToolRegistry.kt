package com.example.voice.tools

import android.content.Context
import com.example.automation.tools.ToolRegistry
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import com.example.voice.assistant.AssistantLogger
import kotlinx.coroutines.*
import org.json.JSONObject

class AssistantToolRegistry(
    private val context: Context,
    private val authorizationManager: AuthorizationManager
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun executeToolAsync(
        actionName: String,
        arguments: JSONObject,
        onResult: (String) -> Unit
    ) {
        scope.launch {
            // 1. Authorization check
            if (!authorizationManager.authorize(actionName)) {
                onResult(JSONObject().apply {
                    put("status", "error")
                    put("reason", "Authorization failed or permission denied")
                }.toString())
                return@launch
            }

            // 2. Resolve matching tool in the plugin registry
            val tool = ToolRegistry.getToolForAction(actionName)
            if (tool == null) {
                // If not found in the custom registry, delegate to the legacy ActionDispatcher fallback
                AssistantLogger.logWarn("No plugin tool registered for action: $actionName. Falling back to ActionDispatcher.")
                AssistantEventBus.emit(AssistantEvent.ToolProgress(actionName, "Running..."))
                val res = com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, arguments)
                onResult(res)
                return@launch
            }

            // 3. Asymmetric execution with state updates
            AssistantLogger.logInfo("Executing tool: ${tool.name} for action: $actionName")
            AssistantEventBus.emit(AssistantEvent.ToolProgress(tool.name, "Executing ${tool.name}..."))
            
            try {
                val request = com.example.automation.engine.ExecutionRequest(
                    action = actionName,
                    arguments = com.google.gson.JsonParser.parseString(arguments.toString()).asJsonObject,
                    source = com.example.automation.engine.ExecutionSource.GEMINI_LIVE
                )
                
                val result = tool.execute(context, request)
                val status = if (result.success) "success" else "error"
                val responseJson = JSONObject().apply {
                    put("status", status)
                    if (!result.success) {
                        put("reason", result.message ?: "Failed executing tool")
                    }
                    val meta = result.metadata
                    if (meta != null) {
                        val keys = meta.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, meta.get(key))
                        }
                    }
                }
                
                AssistantLogger.logInfo("Tool ${tool.name} execution completed with status: $status")
                onResult(responseJson.toString())
            } catch (e: Exception) {
                AssistantLogger.logError("Error executing tool: ${tool.name}", e)
                onResult(JSONObject().apply {
                    put("status", "error")
                    put("reason", e.message ?: "Unknown execution error")
                }.toString())
            }
        }
    }
}
