package com.example.automation.engine.planning

/**
 * Structured intent extracted directly from Gemini Live tool calls, 
 * completely decoupled from the conversational TTS audio stream.
 */
data class TaskIntent(
    val goal: String,
    val targetEntity: String?, // Canonical app or setting (e.g., "com.android.chrome")
    val requiredOutcome: String?,
    val parameters: Map<String, String> = emptyMap()
)
