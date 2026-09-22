package com.example.voice.assistant

import android.content.Context
import android.util.Log
import com.example.data.preferences.SettingsManager
import com.example.viewmodel.ChatViewModel
import java.util.concurrent.atomic.AtomicBoolean

object AssistantSessionManager {
    private const val TAG = "AssistantSessionManager"
    private val isSessionActive = AtomicBoolean(false)

    val viewModel: ChatViewModel
        get() = ChatViewModel.instance

    fun getEngine(context: Context): AssistantEngine =
        AssistantLifecycleManager.getOrCreateEngine(context.applicationContext)

    /**
     * Entry point for system assistant invocation (VoiceInteractionSession, Assist intent, etc.)
     * Safely triggers the canonical AssistantEngine listening session.
     */
    fun startSystemAssistantSession(context: Context) {
        val appCtx = context.applicationContext
        Log.i(TAG, "Starting system assistant session")
        val settings = SettingsManager(appCtx)
        if (!settings.hasValidGeminiApiKey()) {
            Log.w(TAG, "Cannot start system assistant session: Gemini API Key missing")
            return
        }

        val engine = getEngine(appCtx)
        isSessionActive.set(true)

        // Ensure engine is running and connected
        engine.start()

        // Start listening through the thread-safe AudioStateGuard
        engine.startListening()
    }

    /**
     * Clean shutdown for system assistant session when HUD is hidden or dismissed.
     */
    fun stopSystemAssistantSession(context: Context) {
        val appCtx = context.applicationContext
        Log.i(TAG, "Stopping system assistant session")
        isSessionActive.set(false)
        val engine = AssistantLifecycleManager.getEngine()
        engine?.stopListening()
    }

    fun isSystemAssistantSessionActive(): Boolean = isSessionActive.get()
}
