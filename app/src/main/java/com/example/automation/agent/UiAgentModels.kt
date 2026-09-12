package com.example.automation.agent

/**
 * Information regarding a compatible model available on the UI Agent provider.
 */
data class UiAgentModelInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val supportsVision: Boolean = true,
    val supportsToolCalling: Boolean = true,
    val contextLength: Int = 128000,
    val isPreferred: Boolean = false
)

/**
 * Result from testing the UI Agent connection with an API key and model.
 */
data class UiAgentTestResult(
    val success: Boolean,
    val message: String,
    val provider: String,
    val model: String,
    val latencyMs: Long = 0L,
    val errorCode: String? = null
)

/**
 * Historical record of an action step executed during a UI task.
 */
data class UiStepRecord(
    val stepIndex: Int,
    val action: UiAction,
    val observationBefore: UiObservation?,
    val observationAfter: UiObservation?,
    val verified: Boolean,
    val verificationNote: String? = null
)

/**
 * Reasoning outcome from an AI model provider indicating the next action to take.
 */
sealed class UiAgentReasoningResult {
    data class ActionDecision(
        val action: UiAction,
        val reasoning: String,
        val estimatedConfidence: Float = 1.0f
    ) : UiAgentReasoningResult()

    data class FinishTask(
        val success: Boolean,
        val message: String,
        val reasoning: String
    ) : UiAgentReasoningResult()

    data class Error(
        val message: String,
        val errorCode: String? = null,
        val retryable: Boolean = true
    ) : UiAgentReasoningResult()
}
