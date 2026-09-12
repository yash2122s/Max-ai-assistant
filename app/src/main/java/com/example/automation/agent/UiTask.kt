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
    val error: String? = null
)
