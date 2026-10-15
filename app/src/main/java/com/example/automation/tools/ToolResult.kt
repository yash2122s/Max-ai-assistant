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
    ACCEPTED,
    ATTEMPTED_UNVERIFIED,
    OUTCOME_UNKNOWN,
    COMPLETED,
    RUNNING,
    BLOCKED
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
    val targetParam: String? = null,

    // JARVIS migration — verification & state
    val verified: Boolean? = null,                     // true/false/null(unknown)
    val currentStateSummary: String = "",              // fresh UI snapshot for next prompt
    val requiresUserConfirmation: Boolean = false      // sensitive-action gate
) {
    val options: List<String> get() = needsInputOptions

    /** Dispatch acceptance, unverified attempts, and pending user input are never verified completion. */
    val isVerifiedSuccess: Boolean
        get() = success && (status == ToolResultStatus.SUCCESS || status == ToolResultStatus.COMPLETED) &&
            if (verificationRequired) (verification != null && verification.success) else true

    /** Serialize for the model, matching JARVIS's TaskActionResult.toJsonObject() */
    fun toModelJson(): JSONObject = JSONObject().apply {
        put("success", success)
        message?.let { put("message", it) }
        errorCode?.let { put("errorCode", it) }
        put("status", status.name)
        if (verificationRequired) put("verificationRequired", true)
        verified?.let { put("verified", it) }
        if (currentStateSummary.isNotBlank()) put("current_state", currentStateSummary)
        if (requiresUserConfirmation) put("requires_confirmation", true)
        needsInputQuestion?.let { put("needs_input", it) }
    }

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

        fun accepted(
            toolName: String,
            message: String? = null,
            metadata: JSONObject = JSONObject()
        ): ToolResult = ToolResult(
            success = true,
            toolName = toolName,
            message = message,
            status = ToolResultStatus.ACCEPTED,
            verificationRequired = true,
            metadata = metadata
        )

        fun attemptedUnverified(
            toolName: String,
            message: String? = null,
            metadata: JSONObject = JSONObject()
        ): ToolResult = ToolResult(
            success = true,
            toolName = toolName,
            message = message,
            status = ToolResultStatus.ATTEMPTED_UNVERIFIED,
            verificationRequired = true,
            metadata = metadata
        )

        fun outcomeUnknown(
            toolName: String,
            message: String? = null,
            metadata: JSONObject = JSONObject()
        ): ToolResult = ToolResult(
            success = false,
            toolName = toolName,
            message = message,
            status = ToolResultStatus.OUTCOME_UNKNOWN,
            verificationRequired = true,
            metadata = metadata
        )
    }
}

