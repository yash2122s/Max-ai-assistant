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
            // 1. Authorization pre-check (Android permission + execution capability).
            // P0-1: this is a capacity pre-flight only — full intent authorization is
            // performed by the canonical upstream gates (AssistantEngine.onExecuteAutomation
            // for Live calls). Execution itself now always flows through
            // ActionDispatcher → ExecutionEngine so voice actions receive the SAME
            // interpolation, retry, verification, timeout, metrics and events as
            // typed/scheduled actions. No direct tool.execute() is allowed here.
            if (!authorizationManager.authorize(actionName)) {
                onResult(JSONObject().apply {
                    put("status", "error")
                    put("reason", "Authorization failed or permission denied")
                }.toString())
                return@launch
            }

            // 2. Produce an ExecutionRequest (canonical shape). Legacy callers may
            // pass the original Live function-call JSON which omits the ACTION key;
            // ActionDispatcher.normalizeJson derives the canonical action from it.
            val canonicalArguments = if (arguments.has("action")) {
                arguments
            } else {
                JSONObject(arguments.toString()).put("action", actionName)
            }

            AssistantLogger.logInfo("Executing tool: $actionName for action: $actionName")
            AssistantEventBus.emit(AssistantEvent.ToolProgress(actionName, "Running..."))

            try {
                // 3. Canonical convergence: ActionDispatcher → ExecutionEngine.
                // dispatchWithResult returns the standard {success,message,error,data}
                // JSON contract with metadata flattened into "data".
                android.util.Log.d("VOICE_EXECUTION", "dispatching through ActionDispatcher (action=$actionName)")
                val resultJson = com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, canonicalArguments)

                // 4. Preserve the Gemini Live toolResponse contract:
                // {status: success|error, reason?, <metadata keys>...}
                val parsed = JSONObject(resultJson)
                val responseJson = JSONObject().apply {
                    put("status", if (parsed.optBoolean("success")) "success" else "error")
                    if (!parsed.optBoolean("success")) {
                        put("reason", parsed.optString("message").ifEmpty {
                            parsed.optJSONObject("error")?.optString("message") ?: "Failed executing tool"
                        })
                    }
                    parsed.optJSONObject("data")?.let { data ->
                        val keys = data.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, data.get(key))
                        }
                    }
                }

                AssistantLogger.logInfo("Tool $actionName execution completed with status: ${responseJson.optString("status")}")
                onResult(responseJson.toString())
            } catch (e: Exception) {
                AssistantLogger.logError("Error executing tool: $actionName", e)
                onResult(JSONObject().apply {
                    put("status", "error")
                    put("reason", e.message ?: "Unknown execution error")
                }.toString())
            }
        }
    }
}
