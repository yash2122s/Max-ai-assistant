package com.example.automation.engine.execution

import android.content.Context
import com.example.automation.approval.ApprovalCoordinator
import com.example.automation.computeruse.ScreenObservationProvider
import com.example.automation.engine.TaskCancellationCoordinator
import com.example.automation.engine.state.AgentTaskStore

/**
 * Local completion gate for the model's task_completed tool.
 *
 * Model text is only a request to check completion. It is never evidence of
 * completion and it never changes task state by itself.
 */
object CompletionVerifier {
    data class Decision(
        val accepted: Boolean,
        val reason: String,
        val taskId: String? = null,
        val foregroundPackage: String? = null
    )

    private val terminalStatuses = setOf("COMPLETED", "SUCCEEDED", "FAILED", "BLOCKED", "CANCELLED", "UNVERIFIED")
    private val unfinishedStatuses = setOf("RUNNING", "VERIFYING", "WAITING_FOR_INPUT", "WAITING_FOR_APPROVAL", "DISPATCHING")

    suspend fun verify(
        context: Context,
        requestedTaskId: String?,
        goal: String?,
        summary: String?
    ): Decision {
        val taskId = requestedTaskId?.takeIf { it.isNotBlank() }
            ?: TaskCancellationCoordinator.getActiveTaskId()
            ?: return Decision(false, "No active task identity was supplied for completion.")

        val store = AgentTaskStore(context.applicationContext)
        val task = store.getTask(taskId)
            ?: return Decision(false, "Active task '$taskId' was not found.", taskId)
        if (task.status in terminalStatuses) {
            return Decision(task.status == "COMPLETED", "Task is already terminal: ${task.status}.", taskId)
        }
        if (!goal.isNullOrBlank() && task.goal.isNotBlank() && !goalsCompatible(task.goal, goal)) {
            return Decision(false, "Completion goal does not match the active task.", taskId)
        }

        val steps = store.getSteps(taskId)
        if (steps.isEmpty() || steps.size != task.totalSteps) {
            return Decision(false, "The active task does not have a complete persisted step set.", taskId)
        }
        if (steps.any { it.status in unfinishedStatuses }) {
            return Decision(false, "An action is still running or waiting for user input.", taskId)
        }
        if (steps.any { it.status !in setOf("SUCCEEDED", "SUCCESS", "COMPLETED") || it.verificationResult != "VERIFIED" }) {
            return Decision(false, "Every required step must succeed with local verification evidence.", taskId)
        }
        if (steps.any { it.status == "UNVERIFIED" || it.verificationResult == "UNVERIFIED" || it.hasExternalSideEffects && it.verificationResult != "VERIFIED" }) {
            return Decision(false, "An external side effect is still unverified.", taskId)
        }
        if (ApprovalCoordinator.hasPending(taskId)) {
            return Decision(false, "User confirmation is still pending.", taskId)
        }

        val observation = ScreenObservationProvider.captureObservation(
            context = context.applicationContext,
            captureScreenshot = false,
            taskId = "completion_$taskId"
        )
        if (observation.foregroundPackage.isBlank()) {
            return Decision(false, "The current foreground application could not be verified.", taskId)
        }
        if (observation.visibleTexts.isEmpty() && observation.interactiveElements.isEmpty()) {
            return Decision(false, "The current screen contains no reliable completion evidence.", taskId, observation.foregroundPackage)
        }
        val persisted = store.deriveAndPersistTaskCompletion(taskId)
        if (persisted?.status != "COMPLETED") {
            return Decision(false, "Persisted task state did not derive COMPLETED.", taskId, observation.foregroundPackage)
        }
        return Decision(true, summary?.takeIf { it.isNotBlank() } ?: "Task completed with locally verified evidence.", taskId, observation.foregroundPackage)
    }

    private fun goalsCompatible(active: String, requested: String): Boolean {
        val normalize = { value: String ->
            value.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+"))
                .filter { it.length >= 3 }
                .toSet()
        }
        val activeWords = normalize(active)
        val requestedWords = normalize(requested)
        return requestedWords.isEmpty() || activeWords.intersect(requestedWords).isNotEmpty()
    }
}
