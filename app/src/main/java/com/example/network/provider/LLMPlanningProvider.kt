package com.example.network.provider

import com.example.automation.state.ActionStep
import com.example.voice.vision.ScreenContextPayload

data class TokenUsage(
    val promptTokens: Int,
    val candidateTokens: Int,
    val totalTokens: Int
)

data class PlanningRequest(
    val goal: String,
    val screenContext: ScreenContextPayload,
    val modelName: String? = null
)

data class PlanningResponse(
    val success: Boolean,
    val plan: List<ActionStep> = emptyList(),
    val errorMessage: String? = null,
    val rawResponse: String? = null,
    val tokenUsage: TokenUsage? = null,
    val durationMs: Long = 0L
)

interface LLMPlanningProvider {
    val providerId: String
    val defaultModel: String

    suspend fun generatePlan(request: PlanningRequest): PlanningResponse
}
