package com.example.voice.session

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import androidx.compose.ui.platform.ComposeView
import com.example.ui.theme.MyApplicationTheme
import com.example.voice.assistant.AssistantLifecycleManager
import com.example.voice.ui.AssistantHud

class JarvisVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val composeViewLifecycleOwner = ComposeViewLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        Log.d("JarvisVoiceSession", "onCreate")
        composeViewLifecycleOwner.onCreate()
    }

    override fun onCreateContentView(): View {
        val composeView = ComposeView(context)
        composeView.setupComposeOwners(composeViewLifecycleOwner)
        composeView.setContent {
            MyApplicationTheme {
                AssistantHud(
                    onDismiss = { hide() }
                )
            }
        }
        return composeView
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d("JarvisVoiceSession", "onShow - Initializing ChatViewModel and auto-listening")
        composeViewLifecycleOwner.onStart()
        composeViewLifecycleOwner.onResume()
        com.example.network.agent.WindowsToolExecutor.initialize(context)
        
        val viewModel = com.example.voice.assistant.AssistantSessionManager.viewModel
        if (viewModel.appContext == null) {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val savedKey = prefs.getString("api_key", "") ?: ""
            val savedVoice = prefs.getString("voice_name", "Aoede") ?: "Aoede"
            val savedLanguage = prefs.getString("response_language", "Tenglish") ?: "Tenglish"
            val initialKey = if (savedKey.isNotEmpty()) savedKey else com.example.BuildConfig.GEMINI_API_KEY
            viewModel.initialize(context.applicationContext, initialKey, savedVoice, savedLanguage)
        }
        
        if (!viewModel.uiState.value.isRecording) {
            viewModel.toggleRecording()
        }
    }

    override fun onHide() {
        super.onHide()
        Log.d("JarvisVoiceSession", "onHide - Pausing recording")
        composeViewLifecycleOwner.onPause()
        composeViewLifecycleOwner.onStop()
        
        val viewModel = com.example.voice.assistant.AssistantSessionManager.viewModel
        if (viewModel.uiState.value.isRecording) {
            viewModel.toggleRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        composeViewLifecycleOwner.onDestroy()
    }
}
