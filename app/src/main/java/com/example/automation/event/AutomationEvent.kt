package com.example.automation.event

import android.graphics.Rect
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionState
import com.example.automation.state.ActionStep
import com.example.automation.tools.ToolResult
import java.util.UUID

sealed class AutomationEvent(open val executionId: UUID) {
    data class PlanningStarted(override val executionId: UUID, val goal: String) : AutomationEvent(executionId)
    data class PlanCreated(override val executionId: UUID, val steps: List<ActionStep>) : AutomationEvent(executionId)
    data class StepProgress(
        override val executionId: UUID,
        val stepIndex: Int,
        val totalSteps: Int,
        val actionType: String,
        val targetText: String?,
        val status: String
    ) : AutomationEvent(executionId)
    data class ObservationStarted(override val executionId: UUID) : AutomationEvent(executionId)
    data class ObservationCompleted(
        override val executionId: UUID,
        val packageName: String?,
        val nodeCount: Int,
        val visibleSummary: String? = null
    ) : AutomationEvent(executionId)
    data class TargetGrounded(
        override val executionId: UUID,
        val bounds: Rect,
        val label: String?,
        val actionType: String
    ) : AutomationEvent(executionId)
    data class ActionStarted(
        override val executionId: UUID,
        val actionType: String,
        val target: String?
    ) : AutomationEvent(executionId)
    data class TypingStarted(
        override val executionId: UUID,
        val text: String,
        val targetField: String?
    ) : AutomationEvent(executionId)
    data class VerificationStarted(
        override val executionId: UUID,
        val toolName: String
    ) : AutomationEvent(executionId)
    data class ExecutionStarted(override val executionId: UUID, val request: ExecutionRequest) : AutomationEvent(executionId)
    data class ToolStarted(override val executionId: UUID, val toolName: String) : AutomationEvent(executionId)
    data class ToolFinished(override val executionId: UUID, val toolName: String, val result: ToolResult) : AutomationEvent(executionId)
    data class ToolFailed(override val executionId: UUID, val toolName: String, val errorCode: String, val message: String) : AutomationEvent(executionId)
    data class StateChanged(override val executionId: UUID, val newState: ExecutionState) : AutomationEvent(executionId)
    data class VerificationPassed(override val executionId: UUID, val toolName: String) : AutomationEvent(executionId)
    data class VerificationFailed(override val executionId: UUID, val toolName: String, val reason: String) : AutomationEvent(executionId)
    data class RetryStarted(override val executionId: UUID, val toolName: String, val attempt: Int) : AutomationEvent(executionId)
    data class RetryFinished(override val executionId: UUID, val toolName: String, val success: Boolean) : AutomationEvent(executionId)
    data class NeedsUserInput(
        override val executionId: UUID,
        val stepId: String,
        val question: String,
        val options: List<String> = emptyList(),
        val targetParam: String? = null
    ) : AutomationEvent(executionId)
    data class ExecutionCancelled(override val executionId: UUID, val reason: String? = null) : AutomationEvent(executionId)
    data class ExecutionFinished(override val executionId: UUID, val success: Boolean) : AutomationEvent(executionId)
}
