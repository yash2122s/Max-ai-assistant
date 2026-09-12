package com.example.automation.engine.state

import com.example.automation.models.UiStep

/**
 * A single bounded execution unit within a larger task, maintaining its own verification and retry state.
 */
data class TaskStep(
    val id: String,
    val action: UiStep,
    val expectedOutcome: ExpectedOutcome?,
    var retryCount: Int = 0,
    val maxRetries: Int = 2
) {
    fun requiresRecovery(): Boolean = retryCount < maxRetries
}
