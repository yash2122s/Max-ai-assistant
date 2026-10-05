package com.example.automation.tools

import com.example.automation.engine.ExecutionMetrics
import com.example.automation.verification.VerificationResult
import org.json.JSONObject

enum class ToolResultStatus {
    SUCCESS,
    FAILURE,
    NEEDS_INPUT,
    USER_ACTION_REQUIRED,
    PENDING_USER_ACTION,
    ACCEPTED
}

data class ToolResult(
    val success: Boolean,
    val toolName: String,
    val attemptCount: Int = 1,
    val errorCode: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
    val verificationRequired: Boolean = true,
    val verification: VerificationResult? = null,
    val metrics: ExecutionMetrics? = null,
    val metadata: JSONObject = JSONObject(),
    val status: ToolResultStatus = if (success) ToolResultStatus.SUCCESS else ToolResultStatus.FAILURE,
    val outputData: Map<String, Any?> = emptyMap(),
    val needsInputQuestion: String? = null,
    val needsInputOptions: List<String> = emptyList(),
    val targetParam: String? = null
) {
    val options: List<String> get() = needsInputOptions

    /** Dispatch acceptance and pending user input are never verified completion. */
    val isVerifiedSuccess: Boolean
        get() = success && status == ToolResultStatus.SUCCESS &&
            (verification?.success ?: !verificationRequired)

    companion object {
        fun success(
            toolName: String,
            message: String? = null,
            outputData: Map<String, Any?> = emptyMap(),
            metadata: JSONObject = JSONObject()
        ): ToolResult = ToolResult(
            success = true,
            toolName = toolName,
            message = message,
            status = ToolResultStatus.SUCCESS,
            outputData = outputData,
            metadata = metadata
        )

        fun failure(
            toolName: String,
            errorCode: String,
            message: String,
            retryable: Boolean = false
        ): ToolResult = ToolResult(
            success = false,
            toolName = toolName,
            errorCode = errorCode,
            message = message,
            retryable = retryable,
            status = ToolResultStatus.FAILURE
        )

        fun needsInput(
            toolName: String,
            question: String,
            options: List<String> = emptyList(),
            targetParam: String? = null
        ): ToolResult = ToolResult(
            success = false,
            toolName = toolName,
            message = question,
            status = ToolResultStatus.NEEDS_INPUT,
            needsInputQuestion = question,
            needsInputOptions = options,
            targetParam = targetParam
        )

        fun userActionRequired(
            toolName: String,
            message: String
        ): ToolResult = ToolResult(
            success = false,
            toolName = toolName,
            message = message,
            status = ToolResultStatus.USER_ACTION_REQUIRED
        )

        fun pendingUserAction(
            toolName: String,
            message: String
        ): ToolResult = ToolResult(
            success = false,
            toolName = toolName,
            message = message,
            status = ToolResultStatus.PENDING_USER_ACTION
        )
    }
}
