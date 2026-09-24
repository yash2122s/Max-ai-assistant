package com.example.automation.tools

import android.content.Context
import com.example.automation.computeruse.ComputerUseEngine
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

class ComputerUseTool : Tool {
    override val name: String = "computer_use"
    override val supportedActions: Set<String> = setOf("COMPUTER_USE", "UI_AGENT", "DYNAMIC_UI_TASK")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean {
        val args = request.arguments
        val goal = args.get("goal")?.asString
            ?: args.get("task")?.asString
            ?: args.get("query")?.asString
            ?: ""
        return goal.isNotBlank()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val goal = args.get("goal")?.asString
            ?: args.get("task")?.asString
            ?: args.get("query")?.asString
            ?: "Perform requested UI action"
        val targetApp = args.get("target_app")?.asString
            ?: args.get("app")?.asString
            ?: args.get("target_entity")?.asString

        val result = ComputerUseEngine.execute(
            context = context,
            goal = goal,
            targetApp = targetApp
        )

        val meta = JSONObject().apply {
            put("steps", result.stepsExecuted)
            if (result.verifiedOutcome != null) {
                put("verifiedOutcome", result.verifiedOutcome)
            }
        }

        return ToolResult(
            success = result.success,
            toolName = name,
            message = result.message,
            errorCode = result.error,
            metadata = meta,
            verificationRequired = false
        )
    }
}
