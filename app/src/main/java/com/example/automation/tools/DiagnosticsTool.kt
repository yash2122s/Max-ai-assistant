package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsTool : Tool {
    override val name: String = "diagnostics"
    override val supportedActions: Set<String> = setOf("MAX_DIAGNOSTICS", "SHOW_REGISTERED_TOOLS")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = false,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val tools = ToolRegistry.getAllTools()
        val toolsArray = JSONArray()
        val builder = java.lang.StringBuilder()
        builder.append("MAX Diagnostics:\nRegistered Tools:\n")
        
        tools.forEach { tool ->
            toolsArray.put(JSONObject().apply {
                put("name", tool.name)
                put("actions", JSONArray(tool.supportedActions.toList()))
            })
            builder.append("- ${tool.name} -> ${tool.supportedActions.joinToString(", ")}\n")
        }

        return ToolResult(
            success = true,
            toolName = name,
            verificationRequired = false,
            message = builder.toString(),
            metadata = JSONObject().put("registered_tools", toolsArray)
        )
    }
}
