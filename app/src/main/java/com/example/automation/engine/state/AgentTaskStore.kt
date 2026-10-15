package com.example.automation.engine.state

import android.content.Context
import android.util.Log
import com.example.data.local.AgentApprovalRecord
import com.example.data.local.AgentTaskDao
import com.example.data.local.AgentTaskEventRecord
import com.example.data.local.AgentTaskRecord
import com.example.data.local.AgentTaskStepRecord
import com.example.data.local.AppDatabase
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import com.example.automation.engine.truth.TaskTransitionAuthority
import com.example.automation.state.ActionStep

/**
 * Small persistence boundary for active agent tasks.
 * Execution code remains the owner of transitions; this class records the authoritative
 * state into Room and derives parent task completion strictly from child-step states.
 */
class AgentTaskStore(
    private val dao: AgentTaskDao
) {
    companion object {
        private const val TAG = "AgentTaskStore"
    }

    constructor(context: Context) : this(
        AppDatabase.getDatabase(context.applicationContext).agentTaskDao()
    )

    suspend fun createTask(
        taskId: String, 
        goal: String, 
        totalSteps: Int = 1,
        requestId: String? = null,
        source: String? = null,
        requestHash: String? = null,
        deadlineAt: Long? = null
    ) {
        val now = System.currentTimeMillis()
        dao.upsertTask(
            AgentTaskRecord(
                taskId = taskId,
                goal = goal,
                status = "READY",
                currentStepIndex = 0,
                totalSteps = totalSteps,
                createdAt = now,
                updatedAt = now,
                requestId = requestId,
                source = source,
                requestHash = requestHash,
                deadlineAt = deadlineAt
            )
        )
    }

    suspend fun create(taskId: String, goal: String, steps: List<ActionStep>) {
        val now = System.currentTimeMillis()
        dao.upsertTask(
            AgentTaskRecord(
                taskId = taskId,
                goal = goal,
                status = "PLANNED",
                currentStepIndex = 0,
                totalSteps = steps.size,
                createdAt = now,
                updatedAt = now
            )
        )
        dao.upsertSteps(steps.mapIndexed { index, step ->
            AgentTaskStepRecord(
                taskId = taskId,
                stepId = step.stepId,
                actionType = step.actionType,
                stepIndex = index,
                status = "READY",
                payloadJson = com.google.gson.Gson().toJson(step),
                hasExternalSideEffects = com.example.automation.engine.execution.DurableRecoveryCoordinator.isIrreversibleExternalAction(step.actionType),
                updatedAt = now
            )
        })
    }

    suspend fun createFromStepRecords(taskId: String, goal: String, steps: List<AgentTaskStepRecord>) {
        val now = System.currentTimeMillis()
        dao.upsertTask(
            AgentTaskRecord(
                taskId = taskId,
                goal = goal,
                status = "PLANNED",
                currentStepIndex = 0,
                totalSteps = steps.size,
                createdAt = now,
                updatedAt = now
            )
        )
        dao.upsertSteps(steps)
    }

    suspend fun getTask(taskId: String): AgentTaskRecord? = dao.getTask(taskId)

    suspend fun getSteps(taskId: String): List<AgentTaskStepRecord> = dao.getSteps(taskId)

    suspend fun getStep(taskId: String, stepId: String): AgentTaskStepRecord? = dao.getStep(taskId, stepId)

    suspend fun getRecoverableTasks(): List<AgentTaskRecord> = dao.getRecoverableTasks()

    suspend fun replacePendingPlan(taskId: String, steps: List<ActionStep>, completedIds: Set<String>) {
        val task = requireNotNull(dao.getTask(taskId))
        val oldSteps = dao.getSteps(taskId)
        val retained = oldSteps.filter { it.stepId in completedIds }
        require(retained.size == completedIds.size && retained.all { it.verificationResult == "VERIFIED" })
        require(steps.none { it.stepId in completedIds })
        val now = System.currentTimeMillis()
        val version = task.planVersion + 1
        val replacement = retained + steps.mapIndexed { index, step ->
            AgentTaskStepRecord(taskId, step.stepId, step.actionType, retained.size + index, "READY",
                payloadJson = com.google.gson.Gson().toJson(step), updatedAt = now,
                hasExternalSideEffects = com.example.automation.engine.execution.DurableRecoveryCoordinator.isIrreversibleExternalAction(step.actionType))
        }
        dao.replacePlan(task.copy(planVersion = version, totalSteps = replacement.size, status = "RUNNING", updatedAt = now), replacement,
            AgentTaskEventRecord(java.util.UUID.randomUUID().toString(), taskId, eventType = "PLAN_REPLACED", status = "RUNNING",
                idempotencyKey = "$taskId:plan:$version", eventDataJson = com.google.gson.Gson().toJson(oldSteps)))
    }

    suspend fun recordStepWaitingInput(taskId: String, stepId: String, executionId: String, question: String) {
        val step = requireNotNull(dao.getStep(taskId, stepId))
        val task = requireNotNull(dao.getTask(taskId))
        val now = System.currentTimeMillis()
        dao.recordIdempotentTransition(task.copy(status = "WAITING_FOR_INPUT", currentStepId = stepId, updatedAt = now),
            step.copy(status = "WAITING_FOR_INPUT", executionId = executionId, failureReason = question, updatedAt = now))
    }

    suspend fun recordStepStarted(
        taskId: String,
        stepId: String,
        executionId: String,
        actionType: String = "ACTION",
        stepIndex: Int = 0
    ) {
        val now = System.currentTimeMillis()
        val existingStep = dao.getStep(taskId, stepId)
        val stepRecord = existingStep?.copy(
            status = "RUNNING",
            executionId = executionId,
            updatedAt = now
        ) ?: AgentTaskStepRecord(
            taskId = taskId,
            stepId = stepId,
            actionType = actionType,
            stepIndex = stepIndex,
            status = "RUNNING",
            executionId = executionId,
            updatedAt = now
        )
        dao.upsertSteps(listOf(stepRecord))
        val task = dao.getTask(taskId) ?: return
        dao.upsertTask(
            task.copy(
                status = "RUNNING",
                currentStepIndex = stepRecord.stepIndex,
                updatedAt = now
            )
        )
    }

    suspend fun recordStepWaitingApproval(
        taskId: String,
        stepId: String,
        executionId: String,
        actionType: String = "ACTION",
        stepIndex: Int = 0,
        approvalId: String? = null,
        payloadHash: String? = null,
        payloadJson: String? = null
    ) {
        val now = System.currentTimeMillis()
        val existingStep = dao.getStep(taskId, stepId)
        val stepRecord = existingStep?.copy(
            status = "WAITING_FOR_APPROVAL",
            executionId = executionId,
            approvalId = approvalId ?: existingStep.approvalId,
            approvalStatus = "PENDING",
            payloadHash = payloadHash ?: existingStep.payloadHash,
            payloadJson = payloadJson ?: existingStep.payloadJson,
            updatedAt = now
        ) ?: AgentTaskStepRecord(
            taskId = taskId,
            stepId = stepId,
            actionType = actionType,
            stepIndex = stepIndex,
            status = "WAITING_FOR_APPROVAL",
            executionId = executionId,
            approvalId = approvalId,
            approvalStatus = "PENDING",
            payloadHash = payloadHash,
            payloadJson = payloadJson,
            updatedAt = now
        )
        dao.upsertSteps(listOf(stepRecord))
        val task = dao.getTask(taskId) ?: return
        dao.upsertTask(
            task.copy(
                status = "WAITING_FOR_APPROVAL",
                approvalState = "PENDING",
                currentStepIndex = stepRecord.stepIndex,
                currentStepId = stepId,
                updatedAt = now
            )
        )
    }

    suspend fun recordStepResult(
        taskId: String,
        stepId: String,
        executionId: String,
        executorSuccess: Boolean,
        verifiedSuccess: Boolean,
        failureReason: String? = null,
        attemptCount: Int = 1,
        actionType: String = "ACTION",
        verificationId: String? = null,
        verificationEvidence: String? = null,
        executorResultJson: String? = null,
        idempotencyKey: String? = null,
        unverified: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        val existingStep = dao.getStep(taskId, stepId)
        val finalSuccess = executorSuccess && verifiedSuccess
        require(existingStep?.executionId == null || existingStep.executionId == executionId) { "Stale execution result rejected" }
        val stepStatus = if (finalSuccess) "SUCCEEDED" else if (unverified) "UNVERIFIED" else "FAILED"
        val verificationStr = if (verifiedSuccess) "VERIFIED" else if (unverified) "UNVERIFIED" else "FAILED"

        val stepRecord = existingStep?.copy(
            status = stepStatus,
            executionId = executionId,
            failureReason = failureReason,
            verificationResult = verificationStr,
            verificationId = verificationId ?: existingStep.verificationId,
            verificationEvidence = verificationEvidence ?: existingStep.verificationEvidence,
            executorResultJson = executorResultJson ?: existingStep.executorResultJson,
            idempotencyKey = idempotencyKey ?: existingStep.idempotencyKey,
            attemptCount = attemptCount,
            updatedAt = now
        ) ?: AgentTaskStepRecord(
            taskId = taskId,
            stepId = stepId,
            actionType = actionType,
            stepIndex = 0,
            status = stepStatus,
            executionId = executionId,
            failureReason = failureReason,
            verificationResult = verificationStr,
            verificationId = verificationId,
            verificationEvidence = verificationEvidence,
            executorResultJson = executorResultJson,
            idempotencyKey = idempotencyKey,
            attemptCount = attemptCount,
            updatedAt = now
        )

        dao.upsertSteps(listOf(stepRecord))

        // Record truth in coordinator
        ExecutionTruthCoordinator.recordExecution(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            actionName = stepRecord.actionType,
            executorSuccess = executorSuccess,
            verifiedSuccess = verifiedSuccess,
            status = if (finalSuccess) "COMPLETED" else stepStatus,
            failureReason = failureReason,
            message = if (finalSuccess) "Step completed and verified" else failureReason
        )
    }

    suspend fun updateTask(
        taskId: String,
        status: String,
        currentStepIndex: Int,
        totalSteps: Int,
        failureReason: String? = null,
        approvalState: String? = null,
        recoveryState: String? = null
    ) {
        val existing = dao.getTask(taskId) ?: return
        dao.upsertTask(
            existing.copy(
                status = status,
                currentStepIndex = currentStepIndex,
                totalSteps = totalSteps,
                failureReason = failureReason,
                approvalState = approvalState,
                recoveryState = recoveryState,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateStep(
        taskId: String,
        step: ActionStep,
        stepIndex: Int,
        status: String,
        executionId: String? = null,
        failureReason: String? = null,
        verificationResult: String? = null,
        attemptCount: Int = 0
    ) {
        val now = System.currentTimeMillis()
        val existingStep = dao.getStep(taskId, step.stepId)
        val updatedStep = existingStep?.copy(
            status = status,
            executionId = executionId ?: existingStep.executionId,
            failureReason = failureReason,
            verificationResult = verificationResult ?: existingStep.verificationResult,
            attemptCount = attemptCount,
            updatedAt = now
        ) ?: AgentTaskStepRecord(
            taskId = taskId,
            stepId = step.stepId,
            actionType = step.actionType,
            stepIndex = stepIndex,
            status = status,
            executionId = executionId,
            failureReason = failureReason,
            verificationResult = verificationResult,
            attemptCount = attemptCount,
            updatedAt = now
        )
        dao.upsertSteps(
            listOf(updatedStep)
        )
    }

    suspend fun markInterrupted(taskId: String, reason: String) {
        val now = System.currentTimeMillis()
        dao.updateStatus(
            taskId = taskId,
            status = "INTERRUPTED",
            reason = reason,
            updatedAt = now,
            interruptedAt = now
        )
    }

    suspend fun requestCancellation(taskId: String, reason: String) {
        dao.requestCancellation(taskId, reason, System.currentTimeMillis())
    }

    /** Owner cleanup: preserve verified effects, terminalize every unfinished step. */
    suspend fun terminatePending(taskId: String, status: String, reason: String) {
        require(status in setOf("FAILED", "CANCELLED", "UNVERIFIED", "BLOCKED"))
        val task = dao.getTask(taskId) ?: return
        if (task.status in setOf("COMPLETED", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED", "UNVERIFIED")) return
        val now = System.currentTimeMillis()
        val steps = dao.getSteps(taskId)
        dao.upsertSteps(steps.map { step ->
            if (step.status in setOf("SUCCEEDED", "SUCCESS", "COMPLETED", "FAILED", "CANCELLED", "UNVERIFIED", "BLOCKED")) step
            else step.copy(status = status, failureReason = reason, updatedAt = now)
        })
        dao.upsertTask(task.copy(status = status, failureReason = reason, terminalReason = reason,
            cancellationReason = if (status == "CANCELLED") reason else task.cancellationReason, updatedAt = now))
    }

    suspend fun resumeInterruptedTask(taskId: String): AgentTaskRecord? {
        val task = dao.getTask(taskId) ?: return null
        val now = System.currentTimeMillis()
        val resumed = task.copy(
            status = "RUNNING",
            interruptedAt = null,
            updatedAt = now
        )
        dao.upsertTask(resumed)
        return resumed
    }

    /**
     * Authoritative derivation of parent task status from child steps.
     * Enforces the invariant:
     * A task with:
     *   Step 1 = SUCCESS
     *   Step 2 = FAILED
     *   Step 3 = NOT_EXECUTED
     * must NEVER be reported as COMPLETED.
     */
    suspend fun deriveAndPersistTaskCompletion(taskId: String): AgentTaskRecord? {
        val task = dao.getTask(taskId) ?: return null
        val steps = dao.getSteps(taskId)
        val (derivedStatus, failureReason) = if (steps.size != task.totalSteps) {
            "UNVERIFIED" to "Persisted step count does not match the current plan"
        } else ExecutionTruthCoordinator.deriveParentStatusFromSteps(steps)
        val updated = task.copy(
            status = derivedStatus,
            failureReason = failureReason ?: task.failureReason,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsertTask(updated)
        return updated
    }

    // --- Phase B: Approvals ---
    suspend fun recordApproval(approval: AgentApprovalRecord) {
        dao.upsertApproval(approval)
    }

    suspend fun getApproval(approvalId: String): AgentApprovalRecord? = dao.getApproval(approvalId)

    suspend fun getPendingApprovalsForTask(taskId: String): List<AgentApprovalRecord> =
        dao.getPendingApprovalsForTask(taskId)

    suspend fun getAllPendingApprovals(): List<AgentApprovalRecord> =
        dao.getAllPendingApprovals()

    suspend fun consumeApproval(
        approvalId: String,
        status: String = "CONSUMED",
        consumedEventId: String? = null
    ): Boolean {
        val affected = dao.consumeApproval(
            approvalId = approvalId,
            status = status,
            consumedAt = System.currentTimeMillis(),
            consumedEventId = consumedEventId
        )
        return affected > 0
    }

    // --- Phase B: Events & Idempotency ---
    suspend fun recordEventIfNew(event: AgentTaskEventRecord): Boolean {
        return try {
            dao.insertEvent(event)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Duplicate or rejected event with idempotencyKey '${event.idempotencyKey}': ${e.message}")
            false
        }
    }

    suspend fun getEventByIdempotencyKey(key: String): AgentTaskEventRecord? =
        dao.getEventByIdempotencyKey(key)

    suspend fun getEventsForTask(taskId: String): List<AgentTaskEventRecord> =
        dao.getEventsForTask(taskId)

    // --- Phase C: Authoritative State Transition ---
    suspend fun transitionStep(
        taskId: String,
        stepId: String,
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
    ): TaskTransitionAuthority.TransitionResult {
        return TaskTransitionAuthority.transition(
            dao = dao,
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            targetStatus = targetStatus,
            actor = actor,
            failureReason = failureReason,
            verificationEvidence = verificationEvidence,
            verificationId = verificationId,
            executorResultJson = executorResultJson,
            payloadHash = payloadHash,
            payloadJson = payloadJson,
            idempotencyKey = idempotencyKey,
            attemptCount = attemptCount
        )
    }
}
