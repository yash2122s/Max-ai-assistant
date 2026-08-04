package com.example.automation.tools

import android.content.Context
import com.example.automation.ShizukuExecutor
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class ShizukuTool : Tool {
    override val name: String = "shizuku"
    override val supportedActions: Set<String> = setOf("RUN_ADB_COMMAND")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val command = request.arguments.get("command")?.asString ?: ""
        return command.isNotEmpty()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            val command = request.arguments.get("command")?.asString ?: ""
            val result = ShizukuExecutor.runCommand(command)
            ToolResult(
                success = !result.startsWith("Security Block:") && !result.contains("not available"),
                toolName = name,
                message = result,
                verificationRequired = true
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "SHIZUKU_ERROR",
                message = e.message ?: "Failed to run ADB command"
            )
        }
    }
}
