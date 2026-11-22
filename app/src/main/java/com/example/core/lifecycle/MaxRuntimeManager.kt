package com.example.core.lifecycle

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.service.JarvisAccessibilityService
import com.example.service.MaxCapsuleOverlayService
import com.example.features.telegram.TelegramBotService
import com.example.service.VoiceForegroundService
import com.example.viewmodel.ChatViewModel
import com.example.voice.assistant.AssistantLifecycleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runtime Ownership Contract Controller for MAX.
 *
 * OWNERSHIP PRINCIPLE:
 *  - MAX UI -> RuntimeController -> MaxRuntimeManager -> Owned Resources
 *  - MAX OFF != AccessibilityService disabled at OS level
 *  - MAX OFF = All MAX-owned runtime work stopped (voice capture, AI streams,
 *    automation loops, timers, workers, reconnect jobs, foreground overlays).
 *
 * Each owned resource implements explicit START / STOP / CANCEL / RESTART / STATUS.
 */
object MaxRuntimeManager {
    private const val TAG = "MaxRuntimeManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val transitionLock = Any()
    @Volatile private var transitionGeneration = 0L

    private val _isMaxEnabledFlow = MutableStateFlow(true)
    val isMaxEnabledFlow: StateFlow<Boolean> = _isMaxEnabledFlow.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    var useComputerUseForMessaging: Boolean = true

    /**
     * Initializes the runtime state from persistent settings.
     */
    fun initialize(context: Context) {
        if (initialized) return
        val settings = SettingsManager(context)
        val enabled = settings.isMaxEnabled
        _isMaxEnabledFlow.value = enabled
        initialized = true
        Log.i(TAG, "MaxRuntimeManager initialized with state: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    /**
     * Checks if MAX global runtime is enabled.
     */
    fun isMaxEnabled(context: Context): Boolean {
        if (!initialized) initialize(context)
        return _isMaxEnabledFlow.value
    }

    /**
     * Globally enables MAX and safely resumes required services asynchronously.
     */
    fun enableMax(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsManager(appContext)
        settings.isMaxEnabled = true
        _isMaxEnabledFlow.value = true
        Log.i(TAG, "MAX master state changed -> ENABLED")

        val generation = synchronized(transitionLock) { ++transitionGeneration }
        // Resume required services asynchronously, but ignore stale work when
        // the user toggles MAX again before the previous transition runs.
        scope.launch {
            synchronized(transitionLock) {
                if (generation == transitionGeneration && _isMaxEnabledFlow.value) {
                    startRequiredServices(appContext)
                }
            }
        }
    }

    /**
     * Globally disables MAX and safely suspends all non-essential background activity.
     */
    fun disableMax(context: Context) {
        val appContext = context.applicationContext
        val settings = SettingsManager(appContext)
        settings.isMaxEnabled = false
        _isMaxEnabledFlow.value = false
        Log.i(TAG, "MAX master state changed -> DISABLED (Halting all MAX-owned runtime work)")

        val generation = synchronized(transitionLock) { ++transitionGeneration }
        // Stop active non-essential services safely. A queued stop must not
        // turn MAX off after a newer enable request.
        scope.launch {
            synchronized(transitionLock) {
                if (generation == transitionGeneration && !_isMaxEnabledFlow.value) {
                    stopActiveServices(appContext)
                }
            }
        }
    }

    /**
     * Helper to set master enabled/disabled state.
     */
    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        if (enabled) enableMax(context) else disableMax(context)
    }

    /**
     * Called on device boot or application startup to restore runtime state safely.
     */
    fun restoreRuntimeState(context: Context) {
        val appContext = context.applicationContext
        initialize(appContext)
        if (!isMaxEnabled(appContext)) {
            Log.i(TAG, "MAX is DISABLED; skipping background services startup on boot/init")
            return
        }

        Log.i(TAG, "MAX is ENABLED; restoring runtime services on boot/init")
        val generation = synchronized(transitionLock) { ++transitionGeneration }
        scope.launch {
            synchronized(transitionLock) {
                if (generation == transitionGeneration && _isMaxEnabledFlow.value) {
                    startRequiredServices(appContext)
                }
            }
        }
    }

    /**
     * Safely starts or resumes essential MAX background services when MAX is ON.
     */
    private fun startRequiredServices(context: Context) {
        val settings = SettingsManager(context)

        // 1. AssistantEngine Lifecycle
        try {
            AssistantLifecycleManager.resume()
            Log.d(TAG, "AssistantLifecycleManager resumed")
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming AssistantLifecycleManager: ${e.message}")
        }

        // 2. Telegram Bot Service (only if configured and enabled in user settings)
        if (settings.isTelegramBotEnabled &&
            settings.telegramBotToken.isNotBlank() &&
            settings.telegramChatId.isNotBlank()
        ) {
            try {
                val serviceIntent = Intent(context, TelegramBotService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d(TAG, "TelegramBotService resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting TelegramBotService: ${e.message}")
            }
        }

        // 3. Dynamic Capsule Overlay Service (only if permission granted and overlay enabled)
        if (settings.isOverlayEnabled && Settings.canDrawOverlays(context)) {
            try {
                val overlayIntent = Intent(context, MaxCapsuleOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
                Log.d(TAG, "MaxCapsuleOverlayService resumed")
            } catch (e: Exception) {
                Log.w(TAG, "Error starting MaxCapsuleOverlayService: ${e.message}")
            }
        }
    }

    /**
     * Safely stops or suspends all MAX-owned runtime activity when MAX is OFF.
     * Note: OS-level services (such as AccessibilityService) remain bound to Android OS
     * to avoid user permission loss, but ignore all event processing while MAX is OFF.
     */
    private fun stopActiveServices(context: Context) {
        // 1. Stop voice recording and speech session
        try {
            ChatViewModel.instance.stopRecording()
            Log.d(TAG, "ChatViewModel voice recording stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping ChatViewModel recording: ${e.message}")
        }

        // 2. Stop/Sleep Assistant Lifecycle (closes Gemini WebSocket, abandons audio focus, stops playback)
        try {
            AssistantLifecycleManager.sleep()
            AssistantLifecycleManager.stop()
            Log.d(TAG, "AssistantLifecycleManager suspended and Gemini WebSocket closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AssistantLifecycleManager: ${e.message}")
        }

        // 3. Stop Voice Foreground Service
        try {
            val voiceSvc = Intent(context, VoiceForegroundService::class.java)
            context.stopService(voiceSvc)
            Log.d(TAG, "VoiceForegroundService stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping VoiceForegroundService: ${e.message}")
        }

        // 4. Stop Telegram Bot Service
        try {
            val telegramSvc = Intent(context, TelegramBotService::class.java)
            context.stopService(telegramSvc)
            Log.d(TAG, "TelegramBotService stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TelegramBotService: ${e.message}")
        }

        // 5. Stop MaxCapsuleOverlayService
        try {
            val overlaySvc = Intent(context, MaxCapsuleOverlayService::class.java)
            context.stopService(overlaySvc)
            Log.d(TAG, "MaxCapsuleOverlayService stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MaxCapsuleOverlayService: ${e.message}")
        }

        // 6. Reset any pending task in Accessibility Service
        try {
            JarvisAccessibilityService.pendingTask = null
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting JarvisAccessibilityService pending task: ${e.message}")
        }
    }
}
