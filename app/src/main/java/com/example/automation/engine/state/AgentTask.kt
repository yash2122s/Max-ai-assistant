package com.example.automation.engine.state

import java.util.UUID

/**
 * The core stateful representation of an active long-horizon user goal.
 * Enforces authoritative parent task completion derived strictly from child-step state.
 */
data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val originalIntent: String,
    val goal: String,
    var steps: List<TaskStep>,
    var currentStepIndex: Int = 0,
    var status: TaskStatus = TaskStatus.PLANNING,
    val context: TaskContext = TaskContext(originalIntent = originalIntent),
    /** Mutable lifecycle counters owned by LongTaskOrchestrator. */
    var retryCount: Int = 0,
    var replanCount: Int = 0,
    var consecutiveFailures: Int = 0,
    var cancellationRequested: Boolean = false,
    var waitingForUser: Boolean = false,
    var completionEvidence: String? = null
) {
    fun getCurrentStep(): TaskStep? {
        return if (currentStepIndex in steps.indices) steps[currentStepIndex] else null
    }

    /**
     * Advances task step index or resolves parent completion.
     * Invariant: A task can only reach COMPLETED if all steps succeeded.
     */
    fun advance(lastStepSuccess: Boolean = true) {
        if (!lastStepSuccess) {
            status = TaskStatus.FAILED
            return
        }
        if (currentStepIndex < steps.size - 1) {
            currentStepIndex++
        } else {
            val allSucceeded = steps.isNotEmpty() && steps.all { it.status == "SUCCESS" }
            status = if (allSucceeded) TaskStatus.COMPLETED else TaskStatus.FAILED
        }
    }
}
