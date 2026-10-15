package com.example.automation.engine.execution

import android.util.Log
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.TaskTransitionAuthority
import com.example.data.local.AgentTaskRecord
import com.example.data.local.AgentTaskStepRecord

/**
 * Phase G: Durable Orchestration & Crash Recovery.
 *
 * Enforces the invariant:
 * On process death, service restart, or accessibility restart:
 * load non-terminal task
 * -> identify interrupted execution
 * -> inspect evidence
 * -> determine external side-effect certainty
 * -> resume only safe/idempotent work
 * -> otherwise BLOCK or require user decision.
 *
 * NEVER blindly duplicate calls, messages, SMS, or remote commands!
 */
object DurableRecoveryCoordinator {
    private const val TAG = "DurableRecovery"

    val IRREVERSIBLE_EXTERNAL_ACTIONS = setOf(
        "CALL_PHONE",
        "CALL",
        "SEND_WHATSAPP",
        "SEND_WHATSAPP_MESSAGE",
        "SEND_TELEGRAM",
        "SEND_SMS",
        "EXECUTE_SHELL",
        "PAY",
        "TRANSFER"
    )

    sealed class RecoveryDecision {
        data class ResumeSafeStep(val task: AgentTaskRecord, val step: AgentTaskStepRecord) : RecoveryDecision()
        data class BlockAmbiguousSideEffect(val task: AgentTaskRecord, val step: AgentTaskStepRecord, val reason: String) : RecoveryDecision()
        data class AwaitPendingApproval(val task: AgentTaskRecord, val step: AgentTaskStepRecord?) : RecoveryDecision()
        data class AlreadyCompleted(val task: AgentTaskRecord) : RecoveryDecision()
        data class Unrecoverable(val task: AgentTaskRecord, val reason: String) : RecoveryDecision()
    }

    /**
     * Inspects a recoverable task and determines the safe, deterministic recovery strategy.
     */
    fun evaluateRecovery(task: AgentTaskRecord, steps: List<AgentTaskStepRecord>): RecoveryDecision {
        if (steps.isEmpty()) {
            return RecoveryDecision.Unrecoverable(task, "Task has no persisted execution steps")
        }

        // Check if all steps already succeeded
        if (steps.size == task.totalSteps && steps.all {
                it.status in setOf("SUCCESS", TaskTransitionAuthority.STATUS_SUCCEEDED, TaskTransitionAuthority.STATUS_COMPLETED) &&
                    it.verificationResult == "VERIFIED"
            }) {
            return RecoveryDecision.AlreadyCompleted(task)
        }

        // Check if there is a step waiting for approval
        val waitingApproval = steps.firstOrNull { it.status == TaskTransitionAuthority.STATUS_WAITING_FOR_APPROVAL }
        if (waitingApproval != null) {
            return RecoveryDecision.AwaitPendingApproval(task, waitingApproval)
        }
        if (steps.any { it.status == "WAITING_FOR_INPUT" }) {
            return RecoveryDecision.Unrecoverable(task, "Waiting for task-scoped user input; cannot resume automatically")
        }

        // Find the in-flight interrupted step
        val interruptedStep = steps.firstOrNull {
            it.status in setOf(
                TaskTransitionAuthority.STATUS_RUNNING,
                TaskTransitionAuthority.STATUS_DISPATCHING,
                TaskTransitionAuthority.STATUS_VERIFYING,
                TaskTransitionAuthority.STATUS_RECOVERING,
                TaskTransitionAuthority.STATUS_INTERRUPTED,
                "UNVERIFIED"
            )
        } ?: steps.firstOrNull { it.status in setOf("PENDING", "READY") }

        if (interruptedStep == null) {
            return RecoveryDecision.Unrecoverable(task, "No pending or in-flight step found")
        }

        val action = interruptedStep.actionType.uppercase().trim()
        val isIrreversible = interruptedStep.hasExternalSideEffects || isIrreversibleExternalAction(action)

        // If the action is an irreversible external side effect and was already in progress:
        if (isIrreversible) {
            val wasInProgress = interruptedStep.status in setOf(
                TaskTransitionAuthority.STATUS_RUNNING,
                TaskTransitionAuthority.STATUS_DISPATCHING,
                TaskTransitionAuthority.STATUS_VERIFYING
            ) || (interruptedStep.executionId != null && interruptedStep.status != "READY")

            if (wasInProgress) {
                // Evidence inspection: was it verified?
                // Ambiguous! Could have already placed call or sent message
                val reason = "Interrupted during external side-effect '$action'. Execution was in status '${interruptedStep.status}' (execId=${interruptedStep.executionId}). Duplicate execution blocked to prevent duplicate side effects."
                Log.w(TAG, reason)
                return RecoveryDecision.BlockAmbiguousSideEffect(task, interruptedStep, reason)
            }
        }

        if (interruptedStep.status == "UNVERIFIED") {
            return RecoveryDecision.BlockAmbiguousSideEffect(task, interruptedStep, "Outcome is unverified; re-observe before deciding whether a retry is safe")
        }

        // Safe / Idempotent work or not-yet-started step
        return RecoveryDecision.ResumeSafeStep(task, interruptedStep)
    }

    fun isIrreversibleExternalAction(action: String): Boolean {
        val upper = action.uppercase().trim()
        return IRREVERSIBLE_EXTERNAL_ACTIONS.any { upper.contains(it) }
    }

    /**
     * Executes the recovery audit and applies authoritative state transitions in Room.
     */
    suspend fun recoverInterruptedTasks(store: AgentTaskStore): List<RecoveryDecision> {
        val recoverable = store.getRecoverableTasks()
        val decisions = mutableListOf<RecoveryDecision>()

        for (task in recoverable) {
            val steps = store.getSteps(task.taskId)
            val decision = evaluateRecovery(task, steps)
            decisions.add(decision)

            when (decision) {
                is RecoveryDecision.BlockAmbiguousSideEffect -> {
                    store.transitionStep(
                        taskId = task.taskId,
                        stepId = decision.step.stepId,
                        executionId = decision.step.executionId,
                        targetStatus = TaskTransitionAuthority.STATUS_BLOCKED,
                        actor = "TaskRecoveryEngine",
                        failureReason = decision.reason
                    )
                }
                is RecoveryDecision.ResumeSafeStep -> {
                    store.transitionStep(
                        taskId = task.taskId,
                        stepId = decision.step.stepId,
                        executionId = decision.step.executionId,
                        targetStatus = TaskTransitionAuthority.STATUS_RECOVERING,
                        actor = "TaskRecoveryEngine",
                        attemptCount = decision.step.attemptCount + 1
                    )
                }
                is RecoveryDecision.AlreadyCompleted -> {
                    store.deriveAndPersistTaskCompletion(task.taskId)
                }
                is RecoveryDecision.AwaitPendingApproval -> {
                    // Preserved in WAITING_FOR_APPROVAL
                }
                is RecoveryDecision.Unrecoverable -> {
                    store.transitionStep(
                        taskId = task.taskId,
                        stepId = steps.firstOrNull()?.stepId ?: "step_0",
                        targetStatus = TaskTransitionAuthority.STATUS_FAILED,
                        actor = "TaskRecoveryEngine",
                        failureReason = decision.reason
                    )
                }
            }
        }

        return decisions
    }
}
