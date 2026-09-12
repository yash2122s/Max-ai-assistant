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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Central lifecycle-driven visual feedback manager for MAX.
 * Manages Edge Lighting and Action Cursor overlays purely based on AutomationEventBus events.
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
    private var pendingDetachRunnable: Runnable? = null

    companion object {
        const val COMPLETION_DISPLAY_MS = 800L
        const val FAILURE_DISPLAY_MS = 1000L
        @Volatile
        var isTesting = false
    }

    fun startListening(scope: CoroutineScope) {
        eventJob?.cancel()
        eventJob = scope.launch(Dispatchers.Main) {
            AutomationEventBus.events.collectLatest { event ->
                handleAutomationEvent(event)
            }
        }
        Log.d(TAG, "MaxVisualFeedbackManager started listening to AutomationEventBus.")
    }

    fun stopListening() {
        eventJob?.cancel()
        eventJob = null
        cancelPendingDetach()
        detachOverlaysImmediately()
    }

    fun ensureOverlaysAttached() {
        cancelPendingDetach()
        if (isAttached) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context) && !isTesting) {
            Log.w(TAG, "Overlay permission not granted; visual feedback overlays disabled.")
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
            }

            val cursorParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            if (edgeLightingView == null) {
                edgeLightingView = EdgeLightingOverlayView(context)
            }
            if (cursorView == null) {
                cursorView = ActionCursorOverlayView(context)
            }

            windowManager.addView(edgeLightingView, edgeParams)
            windowManager.addView(cursorView, cursorParams)
            isAttached = true
            Log.d(TAG, "[UI_OVERLAY] Visual feedback overlays successfully attached to WindowManager (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE).")
        } catch (e: Exception) {
            Log.e(TAG, "[UI_OVERLAY] Error attaching visual feedback overlays: ${e.message}")
        }
    }

    fun handleAutomationEvent(event: AutomationEvent) {
        mainHandler.post {
            when (event) {
                is AutomationEvent.PlanningStarted,
                is AutomationEvent.ObservationStarted -> {
                    ensureOverlaysAttached()
                    edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.PLANNING)
                    cursorView?.showThinking()
                    MaxHapticFeedback.startPulse(context)
                }

                is AutomationEvent.ObservationCompleted -> {
                    // Subtle transition
                }

                is AutomationEvent.TargetGrounded -> {
                    ensureOverlaysAttached()
                    cursorView?.animateToTarget(event.bounds)
                    MaxHapticFeedback.tick(context)
                }

                is AutomationEvent.ActionStarted -> {
                    ensureOverlaysAttached()
                    edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.RUNNING)
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
                    edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.RUNNING)
                    cursorView?.pulseAction()
                    MaxHapticFeedback.actionPulse(context)
                }

                is AutomationEvent.VerificationStarted -> {
                    ensureOverlaysAttached()
                    edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.VERIFYING)
                    cursorView?.showThinking()
                }

                is AutomationEvent.ExecutionCancelled -> {
                    Log.i(TAG, "[UI_OVERLAY] ExecutionCancelled received. Detaching overlays immediately.")
                    cancelPendingDetach()
                    detachOverlaysImmediately()
                }

                is AutomationEvent.ExecutionFinished -> {
                    cursorView?.hideImmediately()
                    if (event.success) {
                        edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.SUCCESS)
                        MaxHapticFeedback.successWave(context)
                        scheduleDelayedDetach(COMPLETION_DISPLAY_MS)
                    } else {
                        edgeLightingView?.setMode(EdgeLightingOverlayView.Mode.FAILURE)
                        MaxHapticFeedback.failureAlert(context)
                        scheduleDelayedDetach(FAILURE_DISPLAY_MS)
                    }
                }

                else -> {
                    // Other events do not affect visual overlay
                }
            }
        }
    }

    private fun scheduleDelayedDetach(delayMs: Long) {
        cancelPendingDetach()
        val runnable = Runnable {
            Log.d(TAG, "[UI_OVERLAY] Completion animation elapsed. Detaching overlays to keep device 100% clean and idle.")
            detachOverlaysImmediately()
        }
        pendingDetachRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingDetach() {
        pendingDetachRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingDetachRunnable = null
        }
    }

    fun detachOverlaysImmediately() {
        cancelPendingDetach()
        edgeLightingView?.turnOffImmediately()
        cursorView?.hideImmediately()

        if (isAttached) {
            try {
                edgeLightingView?.let {
                    if (it.isAttachedToWindow) {
                        windowManager.removeViewImmediate(it)
                    }
                }
                cursorView?.let {
                    it.destroy()
                    if (it.isAttachedToWindow) {
                        windowManager.removeViewImmediate(it)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[UI_OVERLAY] Error removing overlay views: ${e.message}")
            } finally {
                isAttached = false
                edgeLightingView = null
                cursorView = null
                Log.d(TAG, "[UI_OVERLAY] Overlays detached cleanly from WindowManager.")
            }
        }
    }

    fun destroy() {
        stopListening()
    }
}
