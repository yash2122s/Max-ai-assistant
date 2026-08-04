package com.example.automation.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.network.agent.WindowsToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WindowsAgentTool : Tool {
    override val name: String = "WINDOWS_AGENT"
    
    override val supportedActions: Set<String> = setOf("WINDOWS_CMD", "WINDOWS_AGENT")
    
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    
    override val capabilities: ToolCapabilities = ToolCapabilities(
        requiresNetwork = true,
        requiresAccessibility = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val action = request.action.uppercase()
        return action == "WINDOWS_CMD" || action == "WINDOWS_AGENT"
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val argsObj = request.arguments
        val actionName = request.action.uppercase()
        
        val toolName: String
        val targetAction: String
        val toolArgs = mutableMapOf<String, Any>()

        if (actionName == "WINDOWS_CMD") {
            toolName = "cmd"
            targetAction = argsObj.get("cmd_action")?.asString ?: "dir"
            val path = argsObj.get("path")?.asString ?: "."
            val message = argsObj.get("message")?.asString ?: ""
            val program = argsObj.get("program")?.asString ?: ""
            when (targetAction) {
                "dir" -> toolArgs["path"] = path
                "cd" -> toolArgs["path"] = path
                "echo" -> toolArgs["message"] = message
                "where" -> toolArgs["program"] = program
            }
        } else {
            // New WINDOWS_AGENT namespace actions (v2.1)
            toolName = "windows_agent"
            targetAction = argsObj.get("agent_action")?.asString ?: ""
            
            // Forward all available parameters dynamically
            argsObj.keySet().forEach { key ->
                if (key != "agent_action") {
                    val element = argsObj.get(key)
                    if (element != null && !element.isJsonNull) {
                        if (element.isJsonPrimitive) {
                            val prim = element.asJsonPrimitive
                            when {
                                prim.isBoolean -> toolArgs[key] = prim.asBoolean
                                prim.isNumber -> toolArgs[key] = prim.asNumber
                                prim.isString -> toolArgs[key] = prim.asString
                            }
                        } else {
                            toolArgs[key] = element.toString()
                        }
                    }
                }
            }
        }

        Log.d("WindowsAgentTool", "Executing tool request tool: $toolName, action: $targetAction args: $toolArgs")
        
        val resultJsonStr = WindowsToolExecutor.executeTool(toolName, targetAction, toolArgs) { progress ->
            Log.d("WindowsAgentTool", "Tool execution progress: $progress")
        }


        return try {
            val resultObj = JSONObject(resultJsonStr)
            val status = resultObj.optString("status")
            val output = resultObj.optString("output", "")
            
            if (status == "success") {
                if (targetAction == "core.clipboard:get" && output.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Laptop Clipboard", output)
                            clipboard.setPrimaryClip(clip)
                            Log.d("WindowsAgentTool", "Auto-synced laptop clipboard to Android clipboard: $output")
                        } catch (e: Exception) {
                            Log.e("WindowsAgentTool", "Failed to set Android clipboard", e)
                        }
                    }
                }
                ToolResult(
                    success = true,
                    toolName = name,
                    message = if (targetAction == "core.clipboard:get") "Copied text from laptop clipboard to phone clipboard: \"$output\"" else output,
                    verificationRequired = false
                )
            } else {
                val errorObj = resultObj.optJSONObject("error")
                val errorCode = errorObj?.optString("code") ?: "EXECUTION_ERROR"
                val errorMessage = errorObj?.optString("message") ?: output
                val retryable = errorObj?.optBoolean("retryable") ?: false
                
                ToolResult(
                    success = false,
                    toolName = name,
                    message = errorMessage,
                    errorCode = errorCode,
                    retryable = retryable,
                    verificationRequired = false
                )
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                message = e.message ?: "Failed parsing agent response",
                errorCode = "PARSE_ERROR",
                verificationRequired = false
            )
        }
    }
}
