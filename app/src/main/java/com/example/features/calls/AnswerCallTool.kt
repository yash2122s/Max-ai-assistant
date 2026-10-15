package com.example.features.calls

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolCapabilities
import com.example.automation.tools.ToolResult
import com.example.automation.verification.RetryPolicy
import com.example.features.calls.actions.AnswerCallAction
import org.json.JSONObject

class AnswerCallTool(private val answerCallAction: AnswerCallAction) : Tool {
    override val name: String = "answer_call"
    override val supportedActions: Set<String> = setOf("ANSWER_CALL")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        return try {
            answerCallAction.execute(context, JSONObject())
            ToolResult(
                success = true,
                toolName = name,
                verificationRequired = false
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "ANSWER_ERROR",
                message = e.message ?: "Failed to answer call"
            )
        }
    }
}
