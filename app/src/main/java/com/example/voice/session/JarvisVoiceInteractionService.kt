package com.example.voice.session

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Android System-Wide VoiceInteractionService for MAX.
 * Declared in AndroidManifest with android.permission.BIND_VOICE_INTERACTION.
 * Lightweight entry point managed by the Android OS when MAX is configured as Default Assistant.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {
    companion object {
        @Volatile
        var instance: JarvisVoiceInteractionService? = null
            private set

        fun triggerSession(args: Bundle = Bundle(), flags: Int = 0): Boolean {
            val svc = instance
            return if (svc != null) {
                try {
                    svc.showSession(args, flags)
                    true
                } catch (e: Exception) {
                    Log.e("JarvisVoiceInteractionService", "Failed to show session", e)
                    false
                }
            } else {
                Log.w("JarvisVoiceInteractionService", "VoiceInteractionService not active")
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("JarvisVoiceInteractionService", "onCreate: MAX VoiceInteractionService created")
        instance = this
    }

    override fun onReady() {
        try {
            super.onReady()
        } catch (e: Exception) {
            Log.w("JarvisVoiceInteractionService", "Framework VoiceInteractionManagerService unavailable: ${e.message}")
        }
        instance = this
        Log.i("JarvisVoiceInteractionService", "onReady: MAX VoiceInteractionService bound and ready")
    }

    override fun onShutdown() {
        try {
            super.onShutdown()
        } catch (e: Exception) {
            Log.w("JarvisVoiceInteractionService", "Framework shutdown error: ${e.message}")
        }
        Log.i("JarvisVoiceInteractionService", "onShutdown: MAX VoiceInteractionService shutting down")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("JarvisVoiceInteractionService", "onDestroy")
        instance = null
    }
}
