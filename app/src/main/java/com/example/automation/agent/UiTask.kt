package com.example.automation.agent

import java.util.UUID

/**
 * Encapsulates a UI automation task to be executed by the UI Agent.
 */
data class UiTask(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val targetPackage: String? = null,
    val maxSteps: Int = 8,
    val stepTimeoutMs: Long = 10_000L,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Result of executing a UiTask through the observation-reasoning-action-verification loop.
 */
data class UiTaskResult(
    val taskId: String,
    val success: Boolean,
    val message: String,
    val stepsExecuted: Int,
    val providerUsed: String,
    val modelUsed: String,
    val actionsTaken: List<UiAction> = emptyList(),
    val error: String? = null,
    /** True only when the loop independently verified the completed task locally. */
    val completionVerified: Boolean = false
)

data class UiCompletionVerification(
    val verified: Boolean,
    val message: String,
    val errorCode: String? = null
)

/**
 * Local completion gate for model FinishTask(success=true) claims.
 *
 * The model may request completion, but it cannot provide the evidence that makes the
 * completion successful. The loop must have a successful, verified action and may also
 * enforce the task's existing targetPackage semantic when one was supplied.
 */
internal object UiTaskCompletionVerifier {
    fun verify(
        task: UiTask,
        finalObservation: UiObservation,
        stepHistory: List<UiStepRecord>
    ): UiCompletionVerification {
        if (com.example.automation.approval.ApprovalCoordinator.hasPending(task.id)) {
            return UiCompletionVerification(
                verified = false,
                message = "Task completion rejected: user confirmation is still pending.",
                errorCode = "WAITING_FOR_APPROVAL"
            )
        }

        val failedStep = stepHistory.firstOrNull { !it.action.isSuccess }
        if (failedStep != null) {
            return UiCompletionVerification(
                verified = false,
                message = "Task completion rejected: action at step ${failedStep.stepIndex} failed to execute.",
                errorCode = "ACTION_EXECUTION_FAILED"
            )
        }

        val unverifiedStep = stepHistory.firstOrNull { !it.verified }
        if (unverifiedStep != null) {
            return UiCompletionVerification(
                verified = false,
                message = "Task completion rejected: action at step ${unverifiedStep.stepIndex} was not locally verified.",
                errorCode = "ACTION_UNVERIFIED"
            )
        }

        if (stepHistory.none { it.action.isSuccess && it.verified }) {
            return UiCompletionVerification(
                verified = false,
                message = "Task completion rejected: no successfully verified action was observed.",
                errorCode = "UNVERIFIED_COMPLETION"
            )
        }

        val targetPackage = task.targetPackage?.takeIf { it.isNotBlank() }
        if (targetPackage != null && !finalObservation.foregroundPackage.equals(targetPackage, ignoreCase = true)) {
            return UiCompletionVerification(
                verified = false,
                message = "Task completion rejected: foreground package is ${finalObservation.foregroundPackage}, expected $targetPackage.",
                errorCode = "FINAL_GOAL_UNVERIFIED"
            )
        }

        return UiCompletionVerification(
            verified = true,
            message = "Task completion locally verified${targetPackage?.let { " in foreground package $it" } ?: ""}."
        )
    }
}
