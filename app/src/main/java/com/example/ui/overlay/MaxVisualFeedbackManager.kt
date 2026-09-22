package com.example.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.network.ConnectionState
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Central lifecycle-driven visual feedback manager for MAX.
 * Manages Edge Lighting and Action Cursor overlays based on AutomationEventBus and AssistantEventBus events.
 * Fully decoupled from tool names and never intercepts touches or drives execution coordinates.
 */
class MaxVisualFeedbackManager(private val context: Context) {

    private val TAG = "MaxVisualFeedback"
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var edgeLightingView: EdgeLightingOverlayView? = null
    private var cursorView: ActionCursorOverlayView? = null
    var isAttached = false
        private set
    private var eventJob: Job? = null
    private var assistantJob: Job? = null
    private var pendingDetachRunnable: Runnable? = null
    private var isVoiceLiveActive = false

    companion object {
        const val COMPLETION_DISPLAY_MS = 800L
        const val FAILURE_DISPLAY_MS = 1000L
        @Volatile
        var isTesting = false
    }

    fun startListening(scope: CoroutineScope) {
        eventJob?.cancel()
        assistantJob?.cancel()

        // 1. Observe Automation Execution Events
        eventJob = scope.launch(Dispatchers.Main) {
            AutomationEventBus.events.collectLatest { event ->
                handleAutomationEvent(event)
            }
        }

        // 2. Observe Voice Live Assistant Events (Voice Conversation start/stop & RMS)
        assistantJob = scope.launch(Dispatchers.Main) {
            launch {
                AssistantEventBus.events.collectLatest { event ->
                    handleAssistantEvent(event)
                }
            }
            launch {
                AssistantLifecycleManager.engine.collectLatest { engine ->
                    engine?.stateMachine?.currentState?.collectLatest { sessionState ->
                        handleSessionState(sessionState)
                    }
                }
            }
        }

        Log.d(TAG, "MaxVisualFeedbackManager started listening to AutomationEventBus and AssistantEventBus.")
    }

    fun stopListening() {
        eventJob?.cancel()
        assistantJob?.cancel()
        eventJob = null
        assistantJob = null
        cancelPendingDetach()
        detachOverlaysImmediately()
    }

    fun ensureEdgeLightingAttached() {
        if (edgeLightingView != null && edgeLightingView?.isAttachedToWindow == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context) && !isTesting) {
            Log.w(TAG, "Overlay permission not granted; edge lighting overlay disabled.")
            return
        }

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val edgeParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                    setFitInsetsSides(0)
                }
            }

            if (edgeLightingView == null) {
                edgeLightingView = EdgeLightingOverlayView(context)
            }

            if (edgeLightingView?.isAttachedToWindow != true) {
                windowManager.addView(edgeLightingView, edgeParams)
                isAttached = true
                Log.d(TAG, "[UI_OVERLAY] EdgeLightingView attached cleanly for Voice Live (touch pass-through active).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[UI_OVERLAY] Error attaching EdgeLightingView: ${e.message}")
        }
    }

    fun ensureOverlaysAttached() {
        ensureEdgeLightingAttached()
        cancelPendingDetach()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context) && !isTesting) {
            return
        }

        try {
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            val cursorParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                    setFitInsetsSides(0)
                }
            }

            if (cursorView == null) {
                cursorView = ActionCursorOverlayView(context)
            }

            if (cursorView?.isAttachedToWindow != true) {
                windowManager.addView(cursorView, cursorParams)
                isAttached = true
                Log.d(TAG, "[UI_OVERLAY] ActionCursorOverlayView attached successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[UI_OVERLAY] Error attaching cursor view: ${e.message}")
        }
    }

    private fun handleAssistantEvent(event: AssistantEvent) {
        mainHandler.post {
            when (event) {
                is AssistantEvent.ConnectionStateChanged -> {
                    if (event.state == ConnectionState.DISCONNECTED || event.state == ConnectionState.FAILED) {
                        setVoiceLiveActive(false)
                    }
                }
                is AssistantEvent.AudioRmsChanged -> {
                    if (isVoiceLiveActive) {
                        edgeLightingView?.updateRms(event.rms)
                    }
                }
                else -> {}
            }
        }
    }

    private fun handleSessionState(sessionState: SessionState) {
        mainHandler.post {
            val isActiveVoiceState = when (sessionState) {
                SessionState.LISTENING,
                SessionState.STREAMING_AUDIO,
                SessionState.STREAMING_TEXT,
                SessionState.PROCESSING_AUDIO,
                SessionState.WAITING_FOR_MODEL -> true
                else -> false // IDLE, CONNECTED (standby), CONNECTING, ERROR, DISCONNECTING
            }

            if (isActiveVoiceState) {
                setVoiceLiveActive(true)
            } else {
                setVoiceLiveActive(false)
            }
        }
    }

    private fun setVoiceLiveActive(active: Boolean) {
        if (isVoiceLiveActive == active) return
        isVoiceLiveActive = active

        if (active) {
            ensureEdgeLightingAttached()
            edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.VOICE_LIVE)
            Log.d(TAG, "[UI_OVERLAY] Voice Live conversation STARTED -> Edge lighting fluid wave ON")
        } else {
            Log.d(TAG, "[UI_OVERLAY] Voice Live conversation ENDED -> Edge lighting OFF")
            edgeLightingView?.turnOffImmediately()
            if (cursorView?.isAttachedToWindow != true) {
                detachOverlaysImmediately()
            }
        }
    }

    fun handleAutomationEvent(event: AutomationEvent) {
        mainHandler.post {
            when (event) {
                is AutomationEvent.PlanningStarted,
                is AutomationEvent.ObservationStarted -> {
                    ensureOverlaysAttached()
                    if (!isVoiceLiveActive) {
                        edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.PLANNING)
                    }
                    cursorView?.showThinking()
                    MaxHapticFeedback.startPulse(context)
                }

                is AutomationEvent.ObservationCompleted -> {}

                is AutomationEvent.TargetGrounded -> {
                    ensureOverlaysAttached()
                    cursorView?.animateToTarget(event.bounds)
                    MaxHapticFeedback.tick(context)
                }

                is AutomationEvent.ActionStarted -> {
                    ensureOverlaysAttached()
                    if (!isVoiceLiveActive) {
                        edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.RUNNING)
                    }
                    if (event.actionType.contains("SWIPE", ignoreCase = true) ||
                        event.actionType.contains("SCROLL", ignoreCase = true) ||
                        event.actionType.contains("DRAG", ignoreCase = true)
                    ) {
                        cursorView?.showDragging()
                    } else {
                        cursorView?.pulseAction()
                    }
                    MaxHapticFeedback.actionPulse(context)
                }

                is AutomationEvent.TypingStarted -> {
                    ensureOverlaysAttached()
                    if (!isVoiceLiveActive) {
                        edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.RUNNING)
                    }
                    cursorView?.pulseAction()
                    MaxHapticFeedback.actionPulse(context)
                }

                is AutomationEvent.VerificationStarted -> {
                    ensureOverlaysAttached()
                    if (!isVoiceLiveActive) {
                        edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.VERIFYING)
                    }
                    cursorView?.showThinking()
                }

                is AutomationEvent.ExecutionCancelled -> {
                    Log.i(TAG, "[UI_OVERLAY] ExecutionCancelled received. Detaching overlays immediately.")
                    cancelPendingDetach()
                    detachCursorView()
                    if (!isVoiceLiveActive) {
                        detachOverlaysImmediately()
                    }
                }

                is AutomationEvent.ExecutionFinished -> {
                    cursorView?.hideImmediately()
                    detachCursorView()
                    if (!isVoiceLiveActive) {
                        if (event.success) {
                            edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.SUCCESS)
                            MaxHapticFeedback.successWave(context)
                            scheduleDelayedDetach(COMPLETION_DISPLAY_MS)
                        } else {
                            edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.FAILURE)
                            MaxHapticFeedback.failureAlert(context)
                            scheduleDelayedDetach(FAILURE_DISPLAY_MS)
                        }
                    } else {
                        // If voice live is active, show success/failure briefly on edge lighting then return to voice live wave
                        val mode = if (event.success) EdgeLightingOverlayView.Mode.SUCCESS else EdgeLightingOverlayView.Mode.FAILURE
                        edgeLightingView?.setMode(mode)
                        mainHandler.postDelayed({
                            if (isVoiceLiveActive) {
                                edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.VOICE_LIVE)
                            }
                        }, COMPLETION_DISPLAY_MS)
                    }
                }

                else -> {}
            }
        }
    }

    private fun scheduleDelayedDetach(delayMs: Long) {
        cancelPendingDetach()
        val runnable = Runnable {
            if (!isVoiceLiveStateActive()) {
                Log.d(TAG, "[UI_OVERLAY] Completion animation elapsed. Detaching overlays cleanly.")
                detachOverlaysImmediately()
            }
        }
        pendingDetachRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun isVoiceLiveStateActive(): Boolean = isVoiceLiveActive

    private fun cancelPendingDetach() {
        pendingDetachRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingDetachRunnable = null
        }
    }

    fun detachOverlaysImmediately() {
        detachCursorView()
        if (isVoiceLiveActive) return // Keep edge lighting attached if voice live is active
        cancelPendingDetach()
        edgeLightingView?.turnOffImmediately()
        
        if (isAttached) {
            try {
                edgeLightingView?.let {
                    if (it.isAttachedToWindow) {
                        windowManager.removeViewImmediate(it)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UI_OVERLAY] Error removing overlay views: ${e.message}")
            } finally {
                isAttached = isVoiceLiveActive
                if (!isVoiceLiveActive) {
                    edgeLightingView = null
                }
                Log.d(TAG, "[UI_OVERLAY] Overlays detached cleanly from WindowManager.")
            }
        }
    }

    private fun detachCursorView() {
        cursorView?.let {
            it.destroy()
            try {
                if (it.isAttachedToWindow) {
                    windowManager.removeViewImmediate(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing cursorView: ${e.message}")
            } finally {
                cursorView = null
            }
        }
    }

    fun destroy() {
        stopListening()
    }
}
