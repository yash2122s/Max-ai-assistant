package com.example.automation.engine.truth

import android.content.Context
import android.util.Log
import com.example.automation.engine.state.AgentTaskStore
import com.example.data.local.AgentTaskDao
import com.example.data.local.AgentTaskEventRecord
import com.example.data.local.AgentTaskRecord
import com.example.data.local.AgentTaskStepRecord
import com.example.data.local.AppDatabase

/**
 * Phase C — Single authoritative transition and completion authority for MAX AI.
 *
 * Enforces the invariant:
 * task identity → step identity → execution identity → real execution
 * → awaited execution result → required verification → authoritative state transition
 * → persistence against same identity → user-facing completion.
 *
 * Only authoritative actors can perform designated lifecycle transitions.
 * Optimistic completions, callback-only completions, or invalid state jumps are strictly rejected.
 */
object TaskTransitionAuthority {
    private const val TAG = "TaskTransitionAuthority"

    // Supported lifecycle states
    const val STATUS_CREATED = "CREATED"
    const val STATUS_PLANNING = "PLANNING"
    const val STATUS_READY = "READY"
    const val STATUS_WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL"
    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_DENIED = "DENIED"
    const val STATUS_EXPIRED = "EXPIRED"
    const val STATUS_DISPATCHING = "DISPATCHING"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_VERIFYING = "VERIFYING"
    const val STATUS_SUCCEEDED = "SUCCEEDED"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"
    const val STATUS_TIMED_OUT = "TIMED_OUT"
    const val STATUS_INTERRUPTED = "INTERRUPTED"
    const val STATUS_RECOVERING = "RECOVERING"
    const val STATUS_BLOCKED = "BLOCKED"
    const val STATUS_WAITING_FOR_INPUT = "WAITING_FOR_INPUT"
    const val STATUS_UNVERIFIED = "UNVERIFIED"

    // Legal transitions map: CurrentStatus -> Set of allowed TargetStatuses
    private val LEGAL_TRANSITIONS: Map<String, Set<String>> = mapOf(
        STATUS_CREATED to setOf(STATUS_PLANNING, STATUS_READY, STATUS_FAILED, STATUS_CANCELLED),
        STATUS_PLANNING to setOf(STATUS_READY, STATUS_FAILED, STATUS_CANCELLED),
        STATUS_READY to setOf(STATUS_WAITING_FOR_APPROVAL, STATUS_DISPATCHING, STATUS_CANCELLED, STATUS_FAILED),
        STATUS_WAITING_FOR_APPROVAL to setOf(STATUS_APPROVED, STATUS_DENIED, STATUS_EXPIRED, STATUS_CANCELLED, STATUS_FAILED),
        STATUS_APPROVED to setOf(STATUS_DISPATCHING, STATUS_CANCELLED, STATUS_FAILED),
        STATUS_DENIED to setOf(STATUS_FAILED, STATUS_CANCELLED),
        STATUS_EXPIRED to setOf(STATUS_FAILED, STATUS_CANCELLED),
        STATUS_DISPATCHING to setOf(STATUS_RUNNING, STATUS_FAILED, STATUS_CANCELLED, STATUS_BLOCKED, STATUS_INTERRUPTED),
        STATUS_RUNNING to setOf(STATUS_VERIFYING, STATUS_WAITING_FOR_INPUT, STATUS_UNVERIFIED, STATUS_BLOCKED, STATUS_FAILED, STATUS_CANCELLED, STATUS_TIMED_OUT, STATUS_INTERRUPTED),
        STATUS_VERIFYING to setOf(STATUS_SUCCEEDED, STATUS_COMPLETED, STATUS_UNVERIFIED, STATUS_BLOCKED, STATUS_RECOVERING, STATUS_FAILED, STATUS_CANCELLED),
        STATUS_WAITING_FOR_INPUT to setOf(STATUS_READY, STATUS_CANCELLED, STATUS_FAILED),
        STATUS_UNVERIFIED to setOf(STATUS_VERIFYING, STATUS_BLOCKED, STATUS_CANCELLED, STATUS_FAILED),
        STATUS_RECOVERING to setOf(STATUS_DISPATCHING, STATUS_RUNNING, STATUS_BLOCKED, STATUS_FAILED, STATUS_CANCELLED),
        STATUS_INTERRUPTED to setOf(STATUS_RECOVERING, STATUS_RUNNING, STATUS_BLOCKED, STATUS_FAILED, STATUS_CANCELLED),
        STATUS_BLOCKED to setOf(STATUS_DISPATCHING, STATUS_FAILED, STATUS_CANCELLED)
    )

    // Authorized actors permitted to declare success
    private val SUCCESS_AUTHORITIES = setOf(
        "Verifier",
        "VERIFIER",
        "ActionVerifier",
        "CallVerifier",
        "VerificationCoordinator",
        "ExecutionTruthCoordinator",
        "ExecutionEngine"
    )

    // Authorized actors permitted to declare approval resolutions
    private val APPROVAL_AUTHORITIES = setOf(
        "ApprovalCoordinator",
        "UserPrompt"
    )

    sealed class TransitionResult {
        data class Success(
            val taskId: String,
            val stepId: String?,
            val newStatus: String,
            val taskRecord: AgentTaskRecord,
            val stepRecord: AgentTaskStepRecord?
        ) : TransitionResult()

        data class Rejected(
            val taskId: String,
            val stepId: String?,
            val reason: String,
            val currentStatus: String?
        ) : TransitionResult()
    }

    /**
     * Executes an authoritative transition for a step and/or parent task with strict
     * lifecycle, actor, and identity validation.
     */
    suspend fun transition(
        dao: AgentTaskDao,
        taskId: String,
        stepId: String? = null,
        executionId: String? = null,
        targetStatus: String,
        actor: String,
        failureReason: String? = null,
        verificationEvidence: String? = null,
        verificationId: String? = null,
        executorResultJson: String? = null,
        payloadHash: String? = null,
        payloadJson: String? = null,
        idempotencyKey: String? = null,
        attemptCount: Int? = null
    ): TransitionResult {
        val now = System.currentTimeMillis()
        val task = dao.getTask(taskId)
        if (task == null) {
            val msg = "Task '$taskId' does not exist in store."
            Log.e(TAG, msg)
            return TransitionResult.Rejected(taskId, stepId, msg, null)
        }

        // Validate actor authority for terminal success
        if (targetStatus == STATUS_SUCCEEDED || targetStatus == STATUS_COMPLETED) {
            if (!SUCCESS_AUTHORITIES.any { it.equals(actor, ignoreCase = true) }) {
                val msg = "Actor '$actor' is not authorized to write terminal success '$targetStatus'."
                Log.e(TAG, msg)
                return TransitionResult.Rejected(taskId, stepId, msg, task.status)
            }
        }

        // Validate actor authority for approval transitions
        if (targetStatus in setOf(STATUS_APPROVED, STATUS_DENIED, STATUS_EXPIRED)) {
            if (actor !in APPROVAL_AUTHORITIES) {
                val msg = "Actor '$actor' is not authorized for approval transition to '$targetStatus'."
                Log.e(TAG, msg)
                return TransitionResult.Rejected(taskId, stepId, msg, task.status)
            }
        }

        // If transitioning a specific step
        var updatedStep: AgentTaskStepRecord? = null
        if (stepId != null) {
            val existingStep = dao.getStep(taskId, stepId)
            val currentStepStatus = existingStep?.status ?: STATUS_READY

            // Check legal transition for step
            if (!isLegalTransition(currentStepStatus, targetStatus)) {
                val msg = "Illegal step transition from '$currentStepStatus' to '$targetStatus' for step '$stepId'."
                Log.e(TAG, msg)
                return TransitionResult.Rejected(taskId, stepId, msg, currentStepStatus)
            }

            // Check executionId correlation if existing step already has one
            if (existingStep?.executionId != null && executionId != null && existingStep.executionId != executionId) {
                // If it's a new attempt, ensure status was recovering or interrupted
                if (currentStepStatus != STATUS_RECOVERING && currentStepStatus != STATUS_INTERRUPTED && currentStepStatus != STATUS_WAITING_FOR_APPROVAL) {
                    val msg = "ExecutionId mismatch for step '$stepId': existing='${existingStep.executionId}', incoming='$executionId'."
                    Log.w(TAG, msg)
                    return TransitionResult.Rejected(taskId, stepId, msg, currentStepStatus)
                }
            }

            if (targetStatus in setOf(STATUS_SUCCEEDED, STATUS_COMPLETED) &&
                (executionId.isNullOrBlank() || (verificationEvidence ?: existingStep?.verificationEvidence).isNullOrBlank())) {
                return TransitionResult.Rejected(taskId, stepId, "Success requires correlated execution and verification evidence", currentStepStatus)
            }

            val stepVer = (existingStep?.stepVersion ?: 0) + 1
            val attempts = attemptCount ?: (existingStep?.attemptCount ?: 1)

            val newStepRecord = existingStep?.copy(
                status = targetStatus,
                executionId = executionId ?: existingStep.executionId,
                failureReason = failureReason ?: existingStep.failureReason,
                verificationResult = if (targetStatus in setOf(STATUS_SUCCEEDED, STATUS_COMPLETED)) "VERIFIED" else existingStep.verificationResult,
                verificationId = verificationId ?: existingStep.verificationId,
                verificationEvidence = verificationEvidence ?: existingStep.verificationEvidence,
                executorResultJson = executorResultJson ?: existingStep.executorResultJson,
                payloadHash = payloadHash ?: existingStep.payloadHash,
                payloadJson = payloadJson ?: existingStep.payloadJson,
                idempotencyKey = idempotencyKey ?: existingStep.idempotencyKey,
                stepVersion = stepVer,
                attemptCount = attempts,
                updatedAt = now
            ) ?: AgentTaskStepRecord(
                taskId = taskId,
                stepId = stepId,
                actionType = "ACTION",
                stepIndex = 0,
                status = targetStatus,
                executionId = executionId,
                failureReason = failureReason,
                verificationResult = if (targetStatus in setOf(STATUS_SUCCEEDED, STATUS_COMPLETED)) "VERIFIED" else null,
                verificationId = verificationId,
                verificationEvidence = verificationEvidence,
                executorResultJson = executorResultJson,
                payloadHash = payloadHash,
                payloadJson = payloadJson,
                idempotencyKey = idempotencyKey,
                stepVersion = 1,
                attemptCount = attempts,
                updatedAt = now
            )

            updatedStep = newStepRecord
            dao.upsertSteps(listOf(newStepRecord))
        }

        // Derive parent task status strictly from child steps
        val steps = dao.getSteps(taskId)
        val (derivedParentStatus, derivedFailureReason) = ExecutionTruthCoordinator.deriveParentStatusFromSteps(steps)

        val updatedTask = task.copy(
            status = derivedParentStatus,
            currentStepId = stepId ?: task.currentStepId,
            failureReason = derivedFailureReason ?: failureReason ?: task.failureReason,
            terminalReason = if (derivedParentStatus in setOf(STATUS_COMPLETED, STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED)) derivedFailureReason else null,
            updatedAt = now
        )
        dao.upsertTask(updatedTask)

        // Idempotent event recording
        if (idempotencyKey != null) {
            try {
                dao.insertEvent(
                    AgentTaskEventRecord(
                        eventId = java.util.UUID.randomUUID().toString(),
                        taskId = taskId,
                        stepId = stepId,
                        executionId = executionId,
                        eventType = "TRANSITION_$targetStatus",
                        status = targetStatus,
                        idempotencyKey = idempotencyKey,
                        eventDataJson = failureReason ?: verificationEvidence,
                        timestamp = now
                    )
                )
            } catch (e: Exception) {
                Log.d(TAG, "Event with idempotencyKey '$idempotencyKey' already recorded.")
            }
        }

        // Record truth in coordinator
        ExecutionTruthCoordinator.recordExecution(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId ?: "authority_exec",
            actionName = updatedStep?.actionType ?: "TASK_TRANSITION",
            executorSuccess = targetStatus in setOf(STATUS_SUCCEEDED, STATUS_COMPLETED),
            verifiedSuccess = targetStatus in setOf(STATUS_SUCCEEDED, STATUS_COMPLETED),
            failureReason = failureReason ?: derivedFailureReason,
            message = if (targetStatus in setOf(STATUS_SUCCEEDED, STATUS_COMPLETED)) "Verified successful transition" else (failureReason ?: derivedFailureReason),
            status = targetStatus
        )

        return TransitionResult.Success(
            taskId = taskId,
            stepId = stepId,
            newStatus = targetStatus,
            taskRecord = updatedTask,
            stepRecord = updatedStep
        )
    }

    /**
     * Checks whether a status transition is permitted by the lifecycle state machine.
     */
    fun isLegalTransition(currentStatus: String, targetStatus: String): Boolean {
        // Identity transition is idempotent
        if (currentStatus == targetStatus) return true
        val allowed = LEGAL_TRANSITIONS[currentStatus] ?: return false
        return targetStatus in allowed
    }
}
