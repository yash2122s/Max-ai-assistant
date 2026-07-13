package com.example.automation.event

import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionState
import com.example.automation.tools.ToolResult

sealed class AutomationEvent(open val executionId: java.util.UUID) {
    data class ExecutionStarted(override val executionId: java.util.UUID, val request: ExecutionRequest) : AutomationEvent(executionId)
    data class ToolStarted(override val executionId: java.util.UUID, val toolName: String) : AutomationEvent(executionId)
    data class ToolFinished(override val executionId: java.util.UUID, val toolName: String, val result: ToolResult) : AutomationEvent(executionId)
    data class ToolFailed(override val executionId: java.util.UUID, val toolName: String, val errorCode: String, val message: String) : AutomationEvent(executionId)
    data class StateChanged(override val executionId: java.util.UUID, val newState: ExecutionState) : AutomationEvent(executionId)
    data class VerificationPassed(override val executionId: java.util.UUID, val toolName: String) : AutomationEvent(executionId)
    data class VerificationFailed(override val executionId: java.util.UUID, val toolName: String, val reason: String) : AutomationEvent(executionId)
    data class RetryStarted(override val executionId: java.util.UUID, val toolName: String, val attempt: Int) : AutomationEvent(executionId)
    data class RetryFinished(override val executionId: java.util.UUID, val toolName: String, val success: Boolean) : AutomationEvent(executionId)
    data class ExecutionFinished(override val executionId: java.util.UUID, val success: Boolean) : AutomationEvent(executionId)
}
