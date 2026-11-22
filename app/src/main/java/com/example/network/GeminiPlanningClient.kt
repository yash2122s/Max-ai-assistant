package com.example.network

import com.example.automation.state.ActionStep
import com.example.network.provider.GeminiPlanningProvider
import com.example.network.provider.PlanningRequest
import com.example.voice.vision.ScreenContextPayload

data class PlanningResult(
    val success: Boolean,
    val plan: List<ActionStep> = emptyList(),
    val errorMessage: String? = null
)

object GeminiPlanningClient {

    suspend fun generatePlan(
        goal: String,
        screenContext: ScreenContextPayload,
        apiKey: String,
        modelName: String = com.example.network.model.UiAgentModels.GEMINI_UI_AGENT_DEFAULT
    ): PlanningResult {
        val provider = GeminiPlanningProvider(
            apiKeyProvider = { apiKey }
        )
        val request = PlanningRequest(
            goal = goal,
            screenContext = screenContext,
            modelName = modelName
        )
        val response = provider.generatePlan(request)
        return PlanningResult(
            success = response.success,
            plan = response.plan,
            errorMessage = response.errorMessage
        )
    }
}
