package com.example.voice.hotword

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.example.voice.session.JarvisVoiceInteractionService

/**
 * Controller mediating between HotwordProvider and VoiceInteractionService.
 * When a hotword wake event occurs, it emits WAKE_REQUEST by showing the VoiceInteractionSession.
 * It never directly manipulates AudioRecorder or GeminiWebSocketClient.
 */
class AssistantWakeController(
    private val context: Context,
    private val hotwordProvider: HotwordProvider = SystemHotwordProvider(context)
) {
    companion object {
        private const val TAG = "AssistantWakeController"
    }

    fun start() {
        if (hotwordProvider.isSupported()) {
            Log.i(TAG, "Starting hotword listening...")
            hotwordProvider.startListening {
                handleWakeRequest()
            }
        } else {
            Log.d(TAG, "Hotword not supported: ${hotwordProvider.getSupportStatus()}")
        }
    }

    fun stop() {
        hotwordProvider.stopListening()
    }

    fun handleWakeRequest() {
        Log.i(TAG, "WAKE_REQUEST received: launching assistant session")
        val triggered = JarvisVoiceInteractionService.triggerSession(Bundle(), 0)
        if (!triggered) {
            Log.w(TAG, "Could not trigger VoiceInteractionSession for wake request")
        }
    }

    fun getHotwordSupportStatus(): HotwordSupportStatus = hotwordProvider.getSupportStatus()
}
