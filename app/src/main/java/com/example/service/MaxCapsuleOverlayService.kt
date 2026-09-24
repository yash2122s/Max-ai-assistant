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
import com.example.MainActivity
import com.example.MyApplication
import com.example.R
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.data.preferences.SettingsManager
import com.example.network.ConnectionState
import com.example.network.model.GeminiModelMode
import com.example.ui.overlay.hud.DynamicHudOverlayView
import com.example.ui.overlay.hud.HudState
import com.example.ui.overlay.hud.HudStateMapper
import com.example.viewmodel.ChatViewModel
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.assistant.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Android Foreground Service hosting the redesigned 60 FPS Dynamic HUD Overlay.
 * Shown on top of other apps with multi-state animations, gestures, and Gemini 3.8 Live integration.
 */
class MaxCapsuleOverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var hudOverlayView: DynamicHudOverlayView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    // Real-time State Trackers
    private var currentSessionState = SessionState.IDLE
    private var currentConnectionState = ConnectionState.CONNECTED
    private var currentModelMode = GeminiModelMode.AUTO
    private var isAiSpeaking = false
    private var activeError: String? = null
    private var activeToolName: String? = null
    private var activeToolDescription: String? = null
    private var isWindowsAgent = false
    private var toolProgress: Float? = null
    private var currentRms: Float = 0f
    private var aiCaption: String? = null
    private var isReconnecting = false
    private var isMaxForeground = false

    private var temporaryErrorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Creating MaxCapsuleOverlayService...")
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission missing — stopping overlay service")
            stopSelf()
            return
        }
        ServiceRegistry.register(ServiceType.OVERLAY, this)
        startForegroundNotification()
        setupOverlayWindow()
        observeStateFlows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission missing — stopping overlay service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (hudOverlayView == null) {
            setupOverlayWindow()
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "max_capsule_channel"
        val channelName = "MAX Dynamic HUD Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("MAX AI Assistant Active")
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
        val initialWidthPx = (110 * density).toInt()
        val initialHeightPx = (36 * density).toInt()
        val topOffsetPx = getTopCutoutOffset(this)

        windowLayoutParams = buildLayoutParams(initialWidthPx, initialHeightPx, topOffsetPx)

        val hudView = DynamicHudOverlayView(this).apply {
            onBoundsChangeListener = { wPx, hPx ->
                updateWindowDimensions(wPx, hPx)
            }
            onPositionChangeListener = { dx, dy ->
                updateWindowPosition(dx, dy)
            }
            onInterruptRequest = {
                Log.d(TAG, "User tapped interrupt on Dynamic HUD Overlay")
                ChatViewModel.instance.onSpeechInterrupted()
            }
            onToggleMicRequest = {
                Log.d(TAG, "User toggled microphone from HUD Quick Controls")
                ChatViewModel.instance.toggleRecording()
            }
            onToggleModelModeRequest = {
                toggleFastDeepMode()
            }
            onOpenAppRequest = {
                openMaxApp()
            }
            onCloseOverlayRequest = {
                hudOverlayView?.visibility = View.GONE
            }
            onActivateVoiceLiveRequest = {
                Log.i(TAG, "User tapped capsule -> activating Voice Live session")
                val triggered = com.example.voice.session.JarvisVoiceInteractionService.triggerSession(android.os.Bundle(), 0)
                if (!triggered) {
                    Log.i(TAG, "JarvisVoiceInteractionService not bound, falling back to AssistantSessionManager")
                    com.example.voice.assistant.AssistantSessionManager.startSystemAssistantSession(this@MaxCapsuleOverlayService)
                }
            }
        }
        hudOverlayView = hudView

        try {
            windowManager?.addView(hudOverlayView, windowLayoutParams)
            Log.d(TAG, "Dynamic HUD Overlay view added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding Dynamic HUD to WindowManager", e)
        }
    }

    private fun updateWindowDimensions(widthPx: Int, heightPx: Int) {
        val params = windowLayoutParams ?: return
        val view = hudOverlayView ?: return
        if (params.width != widthPx || params.height != heightPx) {
            params.width = widthPx
            params.height = heightPx
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.updateViewLayout(view, params)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating HUD window bounds", e)
            }
        }
    }

    private fun updateWindowPosition(deltaX: Int, deltaY: Int) {
        val params = windowLayoutParams ?: return
        val view = hudOverlayView ?: return
        params.x = (params.x + deltaX).coerceIn(-400, 400)
        params.y = (params.y + deltaY).coerceIn(8, 2200)
        try {
            if (view.isAttachedToWindow) {
                windowManager?.updateViewLayout(view, params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating HUD position", e)
        }
    }

    private fun toggleFastDeepMode() {
        val settings = SettingsManager(this)
        val current = GeminiModelMode.fromString(settings.modelRoutingMode)
        val next = if (current == GeminiModelMode.DEEP) GeminiModelMode.FAST else GeminiModelMode.DEEP
        settings.modelRoutingMode = next.name
        currentModelMode = next
        Log.i(TAG, "HUD toggled model mode to: $next")
        pushStateUpdate()
    }

    private fun openMaxApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    private fun observeStateFlows() {
        // 1. App Foreground status (Hide overlay when MAX app itself is foreground)
        serviceScope.launch {
            MyApplication.isMaxForeground.collectLatest { isFg ->
                isMaxForeground = isFg
                hudOverlayView?.visibility = if (isFg) View.GONE else View.VISIBLE
            }
        }

        // 2. Assistant Session State & Model Mode
        serviceScope.launch {
            AssistantLifecycleManager.engine.collectLatest { engine ->
                engine?.stateMachine?.currentState?.collectLatest { state ->
                    currentSessionState = state
                    pushStateUpdate()
                }
            }
        }

        // 3. Assistant Event Bus (Captions, Audio RMS, Errors, Connections)
        serviceScope.launch {
            AssistantEventBus.events.collectLatest { event ->
                when (event) {
                    is AssistantEvent.AudioRmsChanged -> {
                        currentRms = event.rms
                        hudOverlayView?.updateRms(event.rms)
                    }
                    is AssistantEvent.TextTranscribed -> {
                        if (event.role == "Gemini") {
                            isAiSpeaking = true
                            aiCaption = event.text
                            pushStateUpdate()
                        } else {
                            isAiSpeaking = false
                            pushStateUpdate()
                        }
                    }
                    is AssistantEvent.TurnComplete -> {
                        isAiSpeaking = false
                        aiCaption = null
                        pushStateUpdate()
                    }
                    is AssistantEvent.ConnectionStateChanged -> {
                        currentConnectionState = event.state
                        isReconnecting = (event.state == ConnectionState.CONNECTING)
                        pushStateUpdate()
                    }
                    is AssistantEvent.ErrorOccurred -> {
                        activeError = event.message
                        pushStateUpdate()
                        temporaryErrorJob?.cancel()
                        temporaryErrorJob = serviceScope.launch {
                            delay(4000)
                            activeError = null
                            pushStateUpdate()
                        }
                    }
                    else -> {}
                }
            }
        }

        // 4. Automation Event Bus (Android & Windows Actions)
        serviceScope.launch {
            AutomationEventBus.events.collectLatest { event ->
                when (event) {
                    is AutomationEvent.ActionStarted,
                    is AutomationEvent.PlanningStarted,
                    is AutomationEvent.ObservationStarted,
                    is AutomationEvent.VerificationStarted,
                    is AutomationEvent.TypingStarted -> {
                        val parsed = HudStateMapper.parseAutomationEvent(event)
                        activeToolName = parsed.first
                        activeToolDescription = parsed.second
                        isWindowsAgent = parsed.third
                        pushStateUpdate()
                    }
                    is AutomationEvent.ExecutionFinished,
                    is AutomationEvent.ExecutionCancelled -> {
                        activeToolName = null
                        activeToolDescription = null
                        toolProgress = null
                        pushStateUpdate()
                    }
                    else -> {}
                }
            }
        }

        // 5. Live RMS Flow from ChatViewModel
        serviceScope.launch {
            ChatViewModel.rmsFlow.collectLatest { rms ->
                currentRms = rms
                hudOverlayView?.updateRms(rms)
            }
        }
    }

    private fun pushStateUpdate() {
        val settings = SettingsManager(this)
        currentModelMode = GeminiModelMode.fromString(settings.modelRoutingMode)

        val resolved = HudStateMapper.mapState(
            sessionState = currentSessionState,
            connectionState = currentConnectionState,
            currentModelMode = currentModelMode,
            isAiSpeaking = isAiSpeaking,
            activeError = activeError,
            isReconnecting = isReconnecting,
            reconnectingMessage = if (isReconnecting) "Reconnecting..." else null,
            activeToolName = activeToolName,
            activeToolDescription = activeToolDescription,
            isWindowsAgent = isWindowsAgent,
            toolProgress = toolProgress,
            rms = currentRms,
            aiCaption = aiCaption
        )

        hudOverlayView?.updateState(resolved)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destroying MaxCapsuleOverlayService...")
        serviceScope.cancel()
        temporaryErrorJob?.cancel()

        hudOverlayView?.destroy()
        hudOverlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeView(view)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error removing HUD view on destroy", e)
            }
        }
        hudOverlayView = null
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
        ): com.example.ui.overlay.CapsuleState {
            if (isMaxForeground) return com.example.ui.overlay.CapsuleState.HIDDEN
            if (isAiSpeaking || sessionState == SessionState.STREAMING_AUDIO) return com.example.ui.overlay.CapsuleState.SPEAKING
            if (isTemporaryExpanded || hasActiveError) return com.example.ui.overlay.CapsuleState.EXPANDED
            if (sessionState == SessionState.TOOL_EXECUTION) return com.example.ui.overlay.CapsuleState.PROCESSING
            if (sessionState == SessionState.LISTENING) return com.example.ui.overlay.CapsuleState.LISTENING
            return com.example.ui.overlay.CapsuleState.IDLE
        }
    }
}
