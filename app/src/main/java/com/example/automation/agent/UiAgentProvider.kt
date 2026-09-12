package com.example.automation.agent

import android.content.Context

/**
 * Provider-independent interface for UI Automation reasoning agents.
 */
interface UiAgentProvider {
    /**
     * Unique identifier for this provider (e.g., "openrouter", "gemini").
     */
    val id: String

    /**
     * Human-readable display name.
     */
    val displayName: String

    /**
     * Checks if this provider is configured with the necessary credentials.
     */
    suspend fun isConfigured(context: Context): Boolean

    /**
     * Tests the provider connection and validates tool-calling and model availability.
     */
    suspend fun testConnection(context: Context, apiKey: String, model: String): UiAgentTestResult

    /**
     * Returns a list of models that satisfy MAX UI-Agent capabilities (tool calling + vision).
     */
    suspend fun getCompatibleModels(context: Context, apiKey: String): List<UiAgentModelInfo>

    /**
     * Reasons about the next UI action given the task goal, current observation, and step history.
     */
    suspend fun reasonNextAction(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>,
        apiKey: String,
        model: String
    ): UiAgentReasoningResult
}
