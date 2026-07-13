package com.example.voice.session

import android.service.voice.VoiceInteractionService
import com.example.voice.assistant.AssistantLifecycleManager

class JarvisVoiceInteractionService : VoiceInteractionService() {
    companion object {
        var instance: JarvisVoiceInteractionService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Start bound engine service
        AssistantLifecycleManager.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AssistantLifecycleManager.destroy(this)
    }
}
