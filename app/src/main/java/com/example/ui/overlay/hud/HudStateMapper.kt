package com.example.ui.overlay.hud

import com.example.automation.event.AutomationEvent
import com.example.network.ConnectionState
import com.example.network.model.GeminiModelMode
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.SessionState

/**
 * Pure mapper consolidating all MAX runtime and assistant events into a deterministic [HudState].
 */
object HudStateMapper {

    /**
     * Resolves the current HUD state given all active signals.
     */
    fun mapState(
        sessionState: SessionState,
        connectionState: ConnectionState,
        currentModelMode: GeminiModelMode,
        isAiSpeaking: Boolean,
        activeError: String?,
        isReconnecting: Boolean,
        reconnectingMessage: String?,
        activeToolName: String?,
        activeToolDescription: String?,
        isWindowsAgent: Boolean,
        toolProgress: Float?,
        rms: Float,
        aiCaption: String?
    ): HudState {
        // 1. Critical Errors
        if (!activeError.isNullOrBlank()) {
            return HudState.Error(activeError)
        }

        // 2. Reconnecting State
        if (isReconnecting || connectionState == ConnectionState.CONNECTING) {
            return HudState.Reconnecting(reconnectingMessage ?: "Reconnecting...")
        }

        if (connectionState == ConnectionState.FAILED) {
            return HudState.Error("Connection Failed")
        }

        // 3. Tool Execution State (Android automation or Windows Companion Agent)
        if (sessionState == SessionState.TOOL_EXECUTION || !activeToolName.isNullOrBlank()) {
            val name = activeToolName ?: "Executing Action"
            return HudState.ExecutingTool(
                toolName = name,
                actionDescription = activeToolDescription,
                progress = toolProgress,
                isWindowsAgent = isWindowsAgent || name.contains("windows", ignoreCase = true)
            )
        }

        // 4. Speaking State (AI audio stream active)
        if (isAiSpeaking || sessionState == SessionState.STREAMING_AUDIO) {
            return HudState.Speaking(
                mode = currentModelMode,
                rms = rms,
                caption = aiCaption
            )
        }

        // 5. Thinking / Reasoning State (Waiting for Gemini turn or planning)
        if (sessionState == SessionState.WAITING_FOR_MODEL || sessionState == SessionState.PROCESSING_AUDIO) {
            return HudState.Thinking(
                mode = currentModelMode
            )
        }

        // 6. Listening State (User speech capture)
        if (sessionState == SessionState.LISTENING) {
            return HudState.Listening(
                rms = rms
            )
        }

        // 7. Idle Default State
        return HudState.Idle
    }

    /**
     * Extracts tool description and target details from an automation event.
     */
    fun parseAutomationEvent(event: AutomationEvent): Triple<String, String?, Boolean> {
        return when (event) {
            is AutomationEvent.ActionStarted -> {
                val isWin = event.actionType.contains("windows", ignoreCase = true) || (event.target?.contains("windows", ignoreCase = true) == true)
                Triple(
                    event.actionType,
                    if (!event.target.isNullOrBlank()) "Target: ${event.target}" else null,
                    isWin
                )
            }
            is AutomationEvent.PlanningStarted -> {
                Triple("Goal Planner", event.goal, false)
            }
            is AutomationEvent.ObservationStarted -> {
                Triple("Screen Observation", "Analyzing UI hierarchy", false)
            }
            is AutomationEvent.VerificationStarted -> {
                Triple("Verifying Action", event.toolName, false)
            }
            is AutomationEvent.TypingStarted -> {
                Triple("Typing Text", "Target: ${event.targetField ?: "Input field"}", false)
            }
            else -> Triple("Automation", null, false)
        }
    }
}
