package com.example.network.model

import java.util.UUID

/**
 * Operating modes for Gemini Live in MAX.
 */
enum class GeminiModelMode {
    /** Auto-routes between Fast and Deep based on task classification and heuristics. */
    AUTO,

    /** Low-latency, real-time voice streaming for conversational QA and direct tool actions. */
    FAST,

    /** Extended thinking mode for complex reasoning, multi-step planning, code debugging, and deep screen diagnosis. */
    DEEP;

    companion object {
        fun fromString(value: String?): GeminiModelMode {
            return when (value?.trim()?.uppercase()) {
                "FAST" -> FAST
                "DEEP" -> DEEP
                else -> AUTO
            }
        }
    }
}

/**
 * Centralized source of truth for Gemini Live model names across MAX.
 * DEDICATED SOLELY TO REAL-TIME VOICE STREAMING (Do not use for UI agent REST tasks).
 */
object GeminiLiveModels {
    const val FAST = "gemini-3.8-live"
    const val DEEP = "gemini-3.8-live-extended-thinking"
    const val FALLBACK = "gemini-3.1-flash-live-preview"

    fun modelFor(mode: GeminiModelMode): String {
        return when (mode) {
            GeminiModelMode.FAST -> FAST
            GeminiModelMode.DEEP -> DEEP
            GeminiModelMode.AUTO -> FAST
        }
    }

    fun isDeepModel(modelName: String): Boolean {
        val sanitized = modelName.substringAfter("models/").trim()
        return sanitized.equals(DEEP, ignoreCase = true) || sanitized.contains("extended-thinking", ignoreCase = true)
    }
}

/** Explicit session-level thinking effort; never a request to expose private reasoning. */
enum class LiveThinkingLevel { LOW, MEDIUM, HIGH }

/**
 * Centralized source of truth for UI Automation Agent reasoning models.
 * DEDICATED TO MULTIMODAL SCREEN REASONING & REST generateContent ENDPOINTS.
 * Kept strictly isolated from real-time Gemini Live voice pipelines.
 */
object UiAgentModels {
    /** Primary Gemini REST model for fast multimodal visual grounding and function calling. */
    const val GEMINI_UI_AGENT_DEFAULT = "gemini-2.5-flash"

    /** Fallback Gemini REST model if the default is unavailable on the account. */
    const val GEMINI_UI_AGENT_FALLBACK = "gemini-3.5-flash"

    /** List of obsolete/retired model identifiers that must never be dispatched for UI Agent. */
    val DISALLOWED_OBSOLETE_MODELS = setOf(
        "gemini-2.0-flash",
        "gemini-2.0-flash-001",
        "gemini-2.0-flash-exp",
        "gemini-2.0-flash-thinking-exp",
        "gemini-2.0-pro-exp",
        "models/gemini-2.0-flash",
        "models/gemini-2.0-flash-001",
        "google/gemini-2.0-flash-001",
        "2.0-flash",
        "gemini-1.5-flash",
        "gemini-1.5-flash-8b",
        "gemini-1.5-flash-latest",
        "gemini-1.5-pro",
        "gemini-1.5-pro-latest",
        "gemini-1.0-pro",
        // Live voice models must never be used for REST generateContent UI agent:
        "gemini-3.8-live",
        "gemini-3.8-live-extended-thinking",
        "gemini-3.1-flash-live-preview"
    )

    /**
     * Sanitizes a requested UI Agent model ID.
     * Replaces empty, null, or obsolete/deprecated model names with the supported default.
     */
    fun sanitizeModelName(modelName: String?): String {
        val trimmed = modelName?.trim() ?: ""
        if (trimmed.isBlank()) return GEMINI_UI_AGENT_DEFAULT

        val lower = trimmed.lowercase()
        val withoutPrefix = lower.removePrefix("models/").removePrefix("google/")

        if (DISALLOWED_OBSOLETE_MODELS.contains(lower) || 
            DISALLOWED_OBSOLETE_MODELS.contains(withoutPrefix) ||
            lower.contains("2.0-flash") ||
            lower.contains("1.5-flash") ||
            lower.contains("1.5-pro")) {
            return GEMINI_UI_AGENT_DEFAULT
        }

        return trimmed
    }
}

/**
 * Contextual metadata passed to ModelRouter to determine the optimal Gemini model mode.
 */
data class TaskContext(
    val query: String = "",
    val explicitModeOverride: GeminiModelMode? = null,
    val isMultiStep: Boolean = false,
    val requiresCodingOrDebug: Boolean = false,
    val requiresDeepVisionAnalysis: Boolean = false,
    val priorCompletedActionsCount: Int = 0
)

/**
 * Compact handoff payload transferred when MAX transitions a task from FAST to DEEP mode.
 */
data class ModelHandoffContext(
    val taskId: String = UUID.randomUUID().toString(),
    val conversationSummary: String = "",
    val currentObjective: String = "",
    val completedActions: List<String> = emptyList(),
    val pendingActions: List<String> = emptyList(),
    val toolState: Map<String, Any> = emptyMap(),
    val screenContextSummary: String? = null
) {
    /**
     * Formats this handoff context into a concise markdown injection for system instructions.
     */
    fun toSystemInstructionPrompt(): String {
        val sb = StringBuilder()
        sb.append("\n# TASK CONTINUITY & HANDOFF CONTEXT\n")
        sb.append("Task ID: ").append(taskId).append("\n")
        if (currentObjective.isNotBlank()) {
            sb.append("Current Objective: ").append(currentObjective).append("\n")
        }
        if (conversationSummary.isNotBlank()) {
            sb.append("Context Summary: ").append(conversationSummary).append("\n")
        }
        if (completedActions.isNotEmpty()) {
            sb.append("Completed Actions: ").append(completedActions.joinToString(", ")).append("\n")
        }
        if (pendingActions.isNotEmpty()) {
            sb.append("Pending Actions: ").append(pendingActions.joinToString(", ")).append("\n")
        }
        if (screenContextSummary != null && screenContextSummary.isNotBlank()) {
            sb.append("Screen State: ").append(screenContextSummary).append("\n")
        }
        return sb.toString()
    }
}
