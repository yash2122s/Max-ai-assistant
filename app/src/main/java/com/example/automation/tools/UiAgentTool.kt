package com.example.automation.tools

import android.content.Context
import com.example.automation.agent.UiTask
import com.example.automation.agent.UiTaskResult
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
        if (request.cancellationToken.isCancelled) return false
        val args = request.arguments
        val goal = args.get("goal")?.asString
            ?: args.get("task")?.asString
            ?: args.get("query")?.asString
            ?: ""
        return goal.isNotBlank()
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        if (request.cancellationToken.isCancelled) {
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "CANCELLED",
                message = "Task cancelled before UI Agent execution",
                verificationRequired = true
            )
        }

        if (!com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(context)) {
            return ToolResult(
                success = false,
                toolName = name,
                message = "MAX is currently OFF.",
                errorCode = "MAX_DISABLED",
                verificationRequired = true
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
            id = request.taskId ?: java.util.UUID.randomUUID().toString(),
            goal = goal,
            targetPackage = targetPackage,
            maxSteps = maxSteps
        )

        val loop = UiVerificationLoop(context)
        val result = loop.executeTask(task, request.cancellationToken)

        return mapUiTaskResult(context, result)
    }
}

internal fun mapUiTaskResult(context: Context, result: UiTaskResult): ToolResult {
    val locallyVerifiedSuccess = result.success && result.completionVerified
    val failureCode = when {
        !result.success -> result.error ?: "EXECUTION_FAILED"
        !result.completionVerified -> "UNVERIFIED_COMPLETION"
        else -> null
    }
    val meta = JSONObject().apply {
        put("steps", result.stepsExecuted)
        put("provider", result.providerUsed)
        put("model", result.modelUsed)
        if (result.error != null) {
            put("error", result.error)
        }
    }

    val verification = if (locallyVerifiedSuccess) {
        com.example.automation.verification.VerificationResult(
            success = true,
            reason = result.message,
            retryRecommended = false,
            snapshot = com.example.automation.verification.DeviceContext.capture(context)
        )
    } else {
        com.example.automation.verification.VerificationResult(
            success = false,
            reason = result.error ?: result.message,
            retryRecommended = false,
            snapshot = com.example.automation.verification.DeviceContext.capture(context)
        )
    }

    return ToolResult(
        success = locallyVerifiedSuccess,
        toolName = "UI_AGENT_AUTOMATION",
        message = result.message,
        errorCode = failureCode,
        metadata = meta,
        verification = verification,
        verificationRequired = true,
        verified = locallyVerifiedSuccess
    )
}
