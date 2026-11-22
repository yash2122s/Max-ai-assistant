package com.example.voice.session

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.ui.platform.ComposeView
import com.example.ui.theme.MyApplicationTheme
import com.example.voice.assistant.AssistantSessionManager
import com.example.voice.ui.AssistantHud

/**
 * System Assistant Voice Interaction Session hosting MAX HUD.
 * Renders directly inside the Android VoiceInteractionSession system window,
 * avoiding arbitrary overlays or BadTokenExceptions.
 */
class JarvisVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val composeViewLifecycleOwner = ComposeViewLifecycleOwner()

    private fun applyTransparentWindow() {
        try {
            window?.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        } catch (e: Exception) {
            Log.w("JarvisVoiceSession", "Failed to configure transparent window: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("JarvisVoiceSession", "onCreate")
        composeViewLifecycleOwner.onCreate()
        applyTransparentWindow()
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        Log.d("JarvisVoiceSession", "onPrepareShow (showFlags=$showFlags)")
        applyTransparentWindow()
    }

    override fun onCreateContentView(): View {
        applyTransparentWindow()
        val composeView = ComposeView(context)
        composeView.setupComposeOwners(composeViewLifecycleOwner)
        composeView.setContent {
            MyApplicationTheme(useSurface = false) {
                AssistantHud(
                    onDismiss = { hide() }
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i("JarvisVoiceSession", "onShow - Activating MAX system assistant HUD and listening")
        applyTransparentWindow()
        composeViewLifecycleOwner.onStart()
        composeViewLifecycleOwner.onResume()

        val appCtx = context.applicationContext
        com.example.network.agent.WindowsToolExecutor.initialize(appCtx)

        // Initialize ChatViewModel state listeners
        val viewModel = AssistantSessionManager.viewModel
        val settings = com.example.data.preferences.SettingsManager(appCtx)
        val savedKey = settings.geminiApiKey.trim()
        val savedVoice = settings.liveVoiceName
        val savedLanguage = settings.languagePreference
        viewModel.initialize(appCtx, savedKey, savedVoice, savedLanguage)

        // Explicitly start system assistant voice session through canonical AssistantEngine
        AssistantSessionManager.startSystemAssistantSession(appCtx)
    }

    override fun onHide() {
        super.onHide()
        Log.i("JarvisVoiceSession", "onHide - Stopping system assistant voice listening")
        composeViewLifecycleOwner.onPause()
        composeViewLifecycleOwner.onStop()

        val appCtx = context.applicationContext
        AssistantSessionManager.stopSystemAssistantSession(appCtx)
    }

    override fun onBackPressed() {
        super.onBackPressed()
        Log.d("JarvisVoiceSession", "onBackPressed - Dismissing session")
        hide()
    }

    @Deprecated("Deprecated in Java", level = DeprecationLevel.WARNING)
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        Log.d("JarvisVoiceSession", "onHandleAssist: structure=${structure != null}, content=${content != null}")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("JarvisVoiceSession", "onDestroy")
        composeViewLifecycleOwner.onDestroy()
    }
}
