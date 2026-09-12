package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MyApplication
import com.example.R
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.ui.overlay.CapsuleState
import com.example.ui.overlay.DynamicCapsuleView
import com.example.viewmodel.ChatViewModel
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.*

class MaxCapsuleOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var capsuleView: DynamicCapsuleView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private var temporaryCollapseJob: Job? = null
    private var isTemporaryExpanded = false
    private var temporaryTextPayload: String? = null
    private var currentSessionState = SessionState.IDLE
    private var isAiSpeaking = false
    private var hasActiveError = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating MaxCapsuleOverlayService...")
        ServiceRegistry.register(ServiceType.OVERLAY, this)
        startForegroundNotification()
        setupOverlayWindow()
        observeStateFlows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission missing — overlay suspended")
            return START_STICKY
        }
        if (capsuleView == null) {
            setupOverlayWindow()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "max_capsule_channel"
        val channelName = "MAX Dynamic Island Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MAX Capsule Active")
            .setContentText("Dynamic HUD overlay is ready.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlayWindow() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot setup overlay: Settings.canDrawOverlays is false")
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val initialWidthPx = (125 * density).toInt()
        val initialHeightPx = (38 * density).toInt()
        val topOffsetPx = getTopCutoutOffset(this)

        windowLayoutParams = buildLayoutParams(initialWidthPx, initialHeightPx, topOffsetPx)

        val capsule = DynamicCapsuleView(this)
        capsule.setOnBoundsChangeListener { widthPx, heightPx ->
            updateWindowDimensions(widthPx, heightPx)
        }
        capsuleView = capsule

        try {
            windowManager?.addView(capsuleView, windowLayoutParams)
            Log.d(TAG, "Dynamic Capsule overlay view added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding Dynamic Capsule to WindowManager", e)
        }
    }

    private fun updateWindowDimensions(widthPx: Int, heightPx: Int) {
        val params = windowLayoutParams ?: return
        val view = capsuleView ?: return
        if (params.width != widthPx || params.height != heightPx) {
            params.width = widthPx
            params.height = heightPx
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.updateViewLayout(view, params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating capsule window bounds", e)
            }
        }
    }

    private fun observeStateFlows() {
        // 1. Observe Foreground/Background status
        serviceScope.launch {
            MyApplication.isMaxForeground.collect { isForeground ->
                refreshState()
            }
        }

        // 2. Observe Voice Session State
        serviceScope.launch {
            AssistantLifecycleManager.engine.collect { engine ->
                engine?.stateMachine?.currentState?.collect { state ->
                    currentSessionState = state
                    refreshState()
                }
            }
        }

        // 3. Observe Assistant Events (Captions, Audio RMS, Errors)
        serviceScope.launch {
            AssistantEventBus.events.collect { event ->
                when (event) {
                    is AssistantEvent.AudioRmsChanged -> {
                        capsuleView?.updateRms(event.rms)
                    }
                    is AssistantEvent.TextTranscribed -> {
                        if (event.role == "You" && event.text.isNotBlank()) {
                            triggerTemporaryExpanded("You: ${event.text}")
                        }
                    }
                    is AssistantEvent.ErrorOccurred -> {
                        hasActiveError = true
                        triggerTemporaryExpanded("Error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }

        // 4. Observe Live RMS Flow
        serviceScope.launch {
            ChatViewModel.rmsFlow.collect { rms ->
                capsuleView?.updateRms(rms)
            }
        }
    }

    private fun triggerTemporaryExpanded(caption: String) {
        isTemporaryExpanded = true
        temporaryTextPayload = caption
        refreshState()

        temporaryCollapseJob?.cancel()
        temporaryCollapseJob = serviceScope.launch {
            delay(3500)
            isTemporaryExpanded = false
            hasActiveError = false
            temporaryTextPayload = null
            refreshState()
        }
    }

    private fun refreshState() {
        val resolved = resolveCapsuleState(
            isMaxForeground = MyApplication.isMaxForeground.value,
            sessionState = currentSessionState,
            isAiSpeaking = isAiSpeaking,
            hasActiveError = hasActiveError,
            isTemporaryExpanded = isTemporaryExpanded
        )

        capsuleView?.updateState(resolved, temporaryTextPayload)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying MaxCapsuleOverlayService...")
        serviceScope.cancel()
        temporaryCollapseJob?.cancel()

        capsuleView?.destroy()
        capsuleView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing capsule view on destroy", e)
            }
        }
        capsuleView = null
    }

    companion object {
        private const val TAG = "MaxCapsuleOverlay"
        private const val NOTIFICATION_ID = 4040

        fun buildLayoutParams(widthPx: Int, heightPx: Int, yOffsetPx: Int): WindowManager.LayoutParams {
            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            return WindowManager.LayoutParams(
                widthPx,
                heightPx,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = yOffsetPx
            }
        }

        fun getTopCutoutOffset(context: Context): Int {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId)
            } else {
                (28 * context.resources.displayMetrics.density).toInt()
            }
            return (statusBarHeight / 3).coerceAtLeast(8)
        }

        fun resolveCapsuleState(
            isMaxForeground: Boolean,
            sessionState: SessionState,
            isAiSpeaking: Boolean,
            hasActiveError: Boolean,
            isTemporaryExpanded: Boolean
        ): CapsuleState {
            if (isMaxForeground) return CapsuleState.HIDDEN
            if (isAiSpeaking || sessionState == SessionState.STREAMING_AUDIO) return CapsuleState.SPEAKING
            if (isTemporaryExpanded || hasActiveError) return CapsuleState.EXPANDED
            if (sessionState == SessionState.TOOL_EXECUTION) return CapsuleState.PROCESSING
            if (sessionState == SessionState.LISTENING) return CapsuleState.LISTENING
            return CapsuleState.IDLE
        }
    }
}
