package com.example.automation.tools

import android.content.Context
import com.example.automation.agent.UiTask
import com.example.automation.agent.UiVerificationLoop
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONObject

/**
 * Kotlin backend Tool for dynamic UI-agent automation.
 * Invoked by Gemini Live or system intent when visual screen navigation is required.
 */
class UiAgentTool : Tool {
    override val name: String = "UI_AGENT_AUTOMATION"

    override val supportedActions: Set<String> = setOf(
        "UI_AGENT_AUTOMATION",
        "EXECUTE_UI_TASK",
        "DYNAMIC_UI_AUTOMATION"
    )

    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry

    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = true,
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
        if (!com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(context)) {
            return ToolResult(
                success = false,
                toolName = name,
                message = "MAX is currently OFF.",
                errorCode = "MAX_DISABLED",
                verificationRequired = false
            )
        }

        val args = request.arguments
        val goal = args.get("goal")?.asString
            ?: args.get("task")?.asString
            ?: args.get("query")?.asString
            ?: "Perform requested UI action"
        val targetPackage = args.get("package_name")?.asString
            ?: args.get("target_package")?.asString
        val maxSteps = args.get("max_steps")?.asInt ?: 8

        val task = UiTask(
            goal = goal,
            targetPackage = targetPackage,
            maxSteps = maxSteps
        )

        val loop = UiVerificationLoop(context)
        val result = loop.executeTask(task)

        val meta = JSONObject().apply {
            put("steps", result.stepsExecuted)
            put("provider", result.providerUsed)
            put("model", result.modelUsed)
            if (result.error != null) {
                put("error", result.error)
            }
        }

        return ToolResult(
            success = result.success,
            toolName = name,
            message = result.message,
            errorCode = if (!result.success) (result.error ?: "EXECUTION_FAILED") else null,
            metadata = meta,
            verificationRequired = false
        )
    }
}
