package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.network.agent.WindowsToolExecutor
import org.json.JSONObject

class WindowsAgentTool : Tool {
    override val name: String = "WINDOWS_AGENT"
    
    override val supportedActions: Set<String> = setOf("WINDOWS_CMD")
    
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    
    override val capabilities: ToolCapabilities = ToolCapabilities(
        requiresNetwork = true,
        requiresAccessibility = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        return request.action.uppercase() == "WINDOWS_CMD"
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val argsObj = request.arguments
        
        // Extract command details passed by Gemini/UI
        val cmdAction = argsObj.get("cmd_action")?.asString ?: "dir"
        val path = argsObj.get("path")?.asString ?: "."
        val message = argsObj.get("message")?.asString ?: ""
        val program = argsObj.get("program")?.asString ?: ""

        val toolArgs = mutableMapOf<String, Any>()
        when (cmdAction) {
            "dir" -> toolArgs["path"] = path
            "cd" -> toolArgs["path"] = path
            "echo" -> toolArgs["message"] = message
            "where" -> toolArgs["program"] = program
        }

        Log.d("WindowsAgentTool", "Executing tool request action: cmd/$cmdAction args: $toolArgs")
        
        val resultJsonStr = WindowsToolExecutor.executeTool("cmd", cmdAction, toolArgs) { progress ->
            Log.d("WindowsAgentTool", "Tool execution progress: $progress")
        }

        return try {
            val resultObj = JSONObject(resultJsonStr)
            val status = resultObj.optString("status")
            val output = resultObj.optString("output", "")
            
            if (status == "success") {
                ToolResult(
                    success = true,
                    toolName = name,
                    message = output,
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
