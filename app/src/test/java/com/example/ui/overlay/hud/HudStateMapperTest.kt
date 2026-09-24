package com.example.ui.overlay.hud

import com.example.automation.event.AutomationEvent
import com.example.network.ConnectionState
import com.example.network.model.GeminiModelMode
import com.example.voice.assistant.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudStateMapperTest {

    @Test
    fun testMapState_IdleDefault() {
        val state = HudStateMapper.mapState(
            sessionState = SessionState.IDLE,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.AUTO,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0f,
            aiCaption = null
        )

        assertEquals(HudState.Idle, state)
    }

    @Test
    fun testMapState_Listening() {
        val state = HudStateMapper.mapState(
            sessionState = SessionState.LISTENING,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.FAST,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0.85f,
            aiCaption = null
        )

        assertTrue(state is HudState.Listening)
        assertEquals(0.85f, (state as HudState.Listening).rms, 0.001f)
    }

    @Test
    fun testMapState_ThinkingFastAndDeep() {
        // Fast mode thinking
        val fastThinking = HudStateMapper.mapState(
            sessionState = SessionState.WAITING_FOR_MODEL,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.FAST,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0f,
            aiCaption = null
        )

        assertTrue(fastThinking is HudState.Thinking)
        assertEquals(GeminiModelMode.FAST, (fastThinking as HudState.Thinking).mode)

        // Deep mode thinking (Extended Thinking)
        val deepThinking = HudStateMapper.mapState(
            sessionState = SessionState.PROCESSING_AUDIO,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.DEEP,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0f,
            aiCaption = null
        )

        assertTrue(deepThinking is HudState.Thinking)
        assertEquals(GeminiModelMode.DEEP, (deepThinking as HudState.Thinking).mode)
        assertEquals("Extended Thinking", (deepThinking as HudState.Thinking).detail)
    }

    @Test
    fun testMapState_SpeakingWithCaption() {
        val speaking = HudStateMapper.mapState(
            sessionState = SessionState.STREAMING_AUDIO,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.DEEP,
            isAiSpeaking = true,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0.6f,
            aiCaption = "Opening Chrome now"
        )

        assertTrue(speaking is HudState.Speaking)
        val spk = speaking as HudState.Speaking
        assertEquals(GeminiModelMode.DEEP, spk.mode)
        assertEquals(0.6f, spk.rms, 0.001f)
        assertEquals("Opening Chrome now", spk.caption)
    }

    @Test
    fun testMapState_ToolExecution_AndroidAndWindows() {
        // Android action
        val androidTool = HudStateMapper.mapState(
            sessionState = SessionState.TOOL_EXECUTION,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.FAST,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = "open_app",
            activeToolDescription = "Opening YouTube",
            isWindowsAgent = false,
            toolProgress = 0.5f,
            rms = 0f,
            aiCaption = null
        )

        assertTrue(androidTool is HudState.ExecutingTool)
        val tool = androidTool as HudState.ExecutingTool
        assertEquals("open_app", tool.toolName)
        assertEquals("Opening YouTube", tool.actionDescription)
        assertEquals(false, tool.isWindowsAgent)

        // Windows Agent action
        val windowsTool = HudStateMapper.mapState(
            sessionState = SessionState.CONNECTED,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.DEEP,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = "windows_agent",
            activeToolDescription = "Running terminal command",
            isWindowsAgent = true,
            toolProgress = 0.8f,
            rms = 0f,
            aiCaption = null
        )

        assertTrue(windowsTool is HudState.ExecutingTool)
        val win = windowsTool as HudState.ExecutingTool
        assertEquals("windows_agent", win.toolName)
        assertEquals(true, win.isWindowsAgent)
    }

    @Test
    fun testMapState_ErrorAndReconnecting() {
        // Active error takes precedence
        val errState = HudStateMapper.mapState(
            sessionState = SessionState.ERROR,
            connectionState = ConnectionState.CONNECTED,
            currentModelMode = GeminiModelMode.FAST,
            isAiSpeaking = false,
            activeError = "Microphone unavailable",
            isReconnecting = false,
            reconnectingMessage = null,
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0f,
            aiCaption = null
        )

        assertTrue(errState is HudState.Error)
        assertEquals("Microphone unavailable", (errState as HudState.Error).message)

        // Reconnecting state
        val reconState = HudStateMapper.mapState(
            sessionState = SessionState.IDLE,
            connectionState = ConnectionState.CONNECTING,
            currentModelMode = GeminiModelMode.FAST,
            isAiSpeaking = false,
            activeError = null,
            isReconnecting = true,
            reconnectingMessage = "Reconnecting to Gemini Live...",
            activeToolName = null,
            activeToolDescription = null,
            isWindowsAgent = false,
            toolProgress = null,
            rms = 0f,
            aiCaption = null
        )

        assertTrue(reconState is HudState.Reconnecting)
        assertEquals("Reconnecting to Gemini Live...", (reconState as HudState.Reconnecting).message)
    }

    @Test
    fun testParseAutomationEvent() {
        val execId = java.util.UUID.randomUUID()
        val actionEvent = AutomationEvent.ActionStarted(
            executionId = execId,
            actionType = "CLICK",
            target = "Settings Button"
        )
        val parsedAction = HudStateMapper.parseAutomationEvent(actionEvent)
        assertEquals("CLICK", parsedAction.first)
        assertEquals("Target: Settings Button", parsedAction.second)
        assertEquals(false, parsedAction.third)

        val winEvent = AutomationEvent.ActionStarted(
            executionId = execId,
            actionType = "windows_cmd",
            target = "cmd.exe"
        )
        val parsedWin = HudStateMapper.parseAutomationEvent(winEvent)
        assertEquals("windows_cmd", parsedWin.first)
        assertEquals(true, parsedWin.third)
    }
}
