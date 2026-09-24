package com.example.ui.overlay.hud

import com.example.network.model.GeminiModelMode

/**
 * State model representing the real-time presentation state of the MAX Dynamic HUD Overlay.
 */
sealed interface HudState {

    /**
     * Minimal collapsed presence when MAX is ready and idle.
     */
    data object Idle : HudState

    /**
     * Active microphone capture state with live voice amplitude RMS.
     */
    data class Listening(
        val rms: Float = 0f,
        val caption: String = "Listening..."
    ) : HudState

    /**
     * Gemini reasoning state (FAST or DEEP extended thinking).
     */
    data class Thinking(
        val mode: GeminiModelMode = GeminiModelMode.FAST,
        val detail: String = if (mode == GeminiModelMode.DEEP) "Extended Thinking" else "Processing"
    ) : HudState

    /**
     * Audio streaming and synthesis playback state.
     */
    data class Speaking(
        val mode: GeminiModelMode = GeminiModelMode.FAST,
        val rms: Float = 0f,
        val caption: String? = null
    ) : HudState

    /**
     * Active automation execution state (Android on-device action or Windows Companion Agent).
     */
    data class ExecutingTool(
        val toolName: String,
        val actionDescription: String? = null,
        val progress: Float? = null,
        val isWindowsAgent: Boolean = false
    ) : HudState

    /**
     * Temporary connection recovery / backoff state.
     */
    data class Reconnecting(
        val message: String = "Reconnecting..."
    ) : HudState

    /**
     * Critical session or WebSocket error state.
     */
    data class Error(
        val message: String
    ) : HudState
}

/**
 * View presentation mode for the HUD overlay.
 */
enum class HudViewMode {
    /** Ultra-compact floating pill. */
    COMPACT,

    /** Expanded floating card with detailed context and controls. */
    EXPANDED,

    /** Quick actions popup (Mute, Stop, Fast/Deep Toggle, Close, Open App). */
    QUICK_CONTROLS,

    /** Detailed session diagnostics and task context. */
    DETAILS
}
