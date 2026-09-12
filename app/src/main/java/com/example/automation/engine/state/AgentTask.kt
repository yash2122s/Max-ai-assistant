package com.example.automation.engine.state

import java.util.UUID

/**
 * The core stateful representation of an active long-horizon user goal.
 */
data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val originalIntent: String,
    val goal: String,
    val steps: List<TaskStep>,
    var currentStepIndex: Int = 0,
    var status: TaskStatus = TaskStatus.PLANNING,
    val context: TaskContext = TaskContext(originalIntent = originalIntent)
) {
    fun getCurrentStep(): TaskStep? {
        return if (currentStepIndex in steps.indices) steps[currentStepIndex] else null
    }

    fun advance() {
        if (currentStepIndex < steps.size - 1) {
            currentStepIndex++
        } else {
            status = TaskStatus.COMPLETED
        }
    }
}
