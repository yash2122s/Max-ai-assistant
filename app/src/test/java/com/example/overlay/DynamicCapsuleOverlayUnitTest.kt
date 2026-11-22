package com.example.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.example.MyApplication
import com.example.service.MaxCapsuleOverlayService
import com.example.ui.overlay.CapsuleState
import com.example.ui.overlay.DynamicCapsuleView
import com.example.voice.assistant.SessionState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DynamicCapsuleOverlayUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MyApplication.setForegroundForTesting(false)
    }

    @Test
    fun test1_StatePriority_SpeakingOverridesEverything() {
        val state = MaxCapsuleOverlayService.resolveCapsuleState(
            isMaxForeground = false,
            sessionState = SessionState.STREAMING_AUDIO,
            isAiSpeaking = true,
            hasActiveError = true,
            isTemporaryExpanded = true
        )
        assertEquals(CapsuleState.SPEAKING, state)
    }

    @Test
    fun test2_StatePriority_ExpandedOverridesProcessingAndListening() {
        val state = MaxCapsuleOverlayService.resolveCapsuleState(
            isMaxForeground = false,
            sessionState = SessionState.TOOL_EXECUTION,
            isAiSpeaking = false,
            hasActiveError = false,
            isTemporaryExpanded = true
        )
        assertEquals(CapsuleState.EXPANDED, state)
    }

    @Test
    fun test3_StatePriority_ProcessingOverridesListening() {
        val state = MaxCapsuleOverlayService.resolveCapsuleState(
            isMaxForeground = false,
            sessionState = SessionState.TOOL_EXECUTION,
            isAiSpeaking = false,
            hasActiveError = false,
            isTemporaryExpanded = false
        )
        assertEquals(CapsuleState.PROCESSING, state)
    }

    @Test
    fun test4_StatePriority_ListeningOverridesIdle() {
        val state = MaxCapsuleOverlayService.resolveCapsuleState(
            isMaxForeground = false,
            sessionState = SessionState.LISTENING,
            isAiSpeaking = false,
            hasActiveError = false,
            isTemporaryExpanded = false
        )
        assertEquals(CapsuleState.LISTENING, state)
    }

    @Test
    fun test5_StatePriority_DefaultIsIdle() {
        val state = MaxCapsuleOverlayService.resolveCapsuleState(
            isMaxForeground = false,
            sessionState = SessionState.CONNECTED,
            isAiSpeaking = false,
            hasActiveError = false,
            isTemporaryExpanded = false
        )
        assertEquals(CapsuleState.IDLE, state)
    }

    @Test
    fun test6_HiddenWhenMaxForeground() {
        val state = MaxCapsuleOverlayService.resolveCapsuleState(
            isMaxForeground = true,
            sessionState = SessionState.LISTENING,
            isAiSpeaking = true,
            hasActiveError = true,
            isTemporaryExpanded = true
        )
        assertEquals(CapsuleState.HIDDEN, state)
    }

    @Test
    fun test7_LayoutParams_BuildsCenteredTopFlags() {
        val params = MaxCapsuleOverlayService.buildLayoutParams(
            widthPx = 200,
            heightPx = 60,
            yOffsetPx = 25
        )
        assertEquals(200, params.width)
        assertEquals(60, params.height)
        assertEquals(25, params.y)
        assertEquals(0, params.x)
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, params.gravity)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0)
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0)
    }

    @Test
    fun test8_TopCutoutOffsetCalculation() {
        val offset = MaxCapsuleOverlayService.getTopCutoutOffset(context)
        assertTrue("Top offset must be positive and notch-aligned", offset >= 8)
    }

    @Test
    fun test9_DynamicCapsuleView_StateTransitions() {
        val view = DynamicCapsuleView(context)
        assertEquals(CapsuleState.IDLE, view.currentState)
        assertEquals(View.VISIBLE, view.visibility)

        view.updateState(CapsuleState.LISTENING)
        assertEquals(CapsuleState.LISTENING, view.currentState)
        assertEquals(View.VISIBLE, view.visibility)

        view.updateState(CapsuleState.PROCESSING)
        assertEquals(CapsuleState.PROCESSING, view.currentState)

        view.updateState(CapsuleState.SPEAKING)
        assertEquals(CapsuleState.SPEAKING, view.currentState)

        view.updateState(CapsuleState.EXPANDED, "You: Open Calculator")
        assertEquals(CapsuleState.EXPANDED, view.currentState)

        view.updateState(CapsuleState.HIDDEN)
        assertEquals(CapsuleState.HIDDEN, view.currentState)
        assertEquals(View.GONE, view.visibility)

        view.destroy()
    }

    @Test
    fun test10_DynamicCapsuleView_RmsVisualizerUpdate() {
        val view = DynamicCapsuleView(context)
        view.updateState(CapsuleState.LISTENING)

        // Verify updateRms does not crash and smoothly updates
        view.updateRms(0.0f)
        view.updateRms(0.75f)
        view.updateRms(1.0f)
        view.updateRms(1.5f) // Clamped to 1.0f

        view.destroy()
    }

    @Test
    fun test11_ForegroundTracking_UpdatesStateFlow() {
        MyApplication.setForegroundForTesting(false)
        assertFalse(MyApplication.isMaxForeground.value)

        MyApplication.setForegroundForTesting(true)
        assertTrue(MyApplication.isMaxForeground.value)
    }

    @Test
    fun test12_DynamicCapsuleView_SpeakingStateAudioReactivity() {
        val view = DynamicCapsuleView(context)
        view.updateState(CapsuleState.SPEAKING)
        assertEquals(CapsuleState.SPEAKING, view.currentState)

        // Multiple consecutive RMS pulses to test exponential smoothing
        view.updateRms(0.2f)
        view.updateRms(0.6f)
        view.updateRms(0.9f)
        view.updateRms(0.0f)

        view.destroy()
    }

    @Test
    fun test13_DynamicCapsuleView_BoundsChangeListenerTriggered() {
        val view = DynamicCapsuleView(context)
        var latestWidth = 0
        var latestHeight = 0

        view.setOnBoundsChangeListener { w, h ->
            latestWidth = w
            latestHeight = h
        }

        view.updateState(CapsuleState.EXPANDED, "Opening WhatsApp...")
        assertEquals(CapsuleState.EXPANDED, view.currentState)
        view.destroy()
    }

    @Test
    fun test14_OverlayHiddenWhenConnectedStandbyInVoiceOnlyMode() {
        // Core bug fix: When connected in background, overlay must NOT show on screen
        val isVisible = MaxCapsuleOverlayService.shouldOverlayBeVisible(
            isMaxForeground = false,
            isOverlayEnabled = true,
            overlayOnlyInVoiceMode = true,
            sessionState = SessionState.CONNECTED,
            isAiSpeaking = false,
            isRecording = false,
            isToolExecuting = false,
            isAssistantSessionActive = false,
            hasActiveError = false
        )
        assertFalse("Overlay must stay hidden in standby CONNECTED state to save battery", isVisible)
    }

    @Test
    fun test15_OverlayVisibleDuringActiveVoiceConversation() {
        // Listening
        val listeningVisible = MaxCapsuleOverlayService.shouldOverlayBeVisible(
            isMaxForeground = false,
            isOverlayEnabled = true,
            overlayOnlyInVoiceMode = true,
            sessionState = SessionState.LISTENING,
            isAiSpeaking = false,
            isRecording = true,
            isToolExecuting = false,
            isAssistantSessionActive = false,
            hasActiveError = false
        )
        assertTrue("Overlay must be visible when listening", listeningVisible)

        // Streaming audio (speaking)
        val speakingVisible = MaxCapsuleOverlayService.shouldOverlayBeVisible(
            isMaxForeground = false,
            isOverlayEnabled = true,
            overlayOnlyInVoiceMode = true,
            sessionState = SessionState.STREAMING_AUDIO,
            isAiSpeaking = true,
            isRecording = false,
            isToolExecuting = false,
            isAssistantSessionActive = false,
            hasActiveError = false
        )
        assertTrue("Overlay must be visible when model is speaking", speakingVisible)

        // Waiting for model (thinking)
        val thinkingVisible = MaxCapsuleOverlayService.shouldOverlayBeVisible(
            isMaxForeground = false,
            isOverlayEnabled = true,
            overlayOnlyInVoiceMode = true,
            sessionState = SessionState.WAITING_FOR_MODEL,
            isAiSpeaking = false,
            isRecording = false,
            isToolExecuting = false,
            isAssistantSessionActive = false,
            hasActiveError = false
        )
        assertTrue("Overlay must be visible when waiting for model", thinkingVisible)
    }

    @Test
    fun test16_OverlayHiddenWhenMasterSwitchDisabled() {
        val isVisible = MaxCapsuleOverlayService.shouldOverlayBeVisible(
            isMaxForeground = false,
            isOverlayEnabled = false,
            overlayOnlyInVoiceMode = true,
            sessionState = SessionState.LISTENING,
            isAiSpeaking = true,
            isRecording = true,
            isToolExecuting = false,
            isAssistantSessionActive = false,
            hasActiveError = false
        )
        assertFalse("Overlay must never be visible if isOverlayEnabled is false", isVisible)
    }

    @Test
    fun test17_OverlayHiddenWhenAppInForeground() {
        val isVisible = MaxCapsuleOverlayService.shouldOverlayBeVisible(
            isMaxForeground = true,
            isOverlayEnabled = true,
            overlayOnlyInVoiceMode = true,
            sessionState = SessionState.LISTENING,
            isAiSpeaking = true,
            isRecording = true,
            isToolExecuting = false,
            isAssistantSessionActive = false,
            hasActiveError = false
        )
        assertFalse("Overlay must be hidden when MAX app is foreground", isVisible)
    }
}
