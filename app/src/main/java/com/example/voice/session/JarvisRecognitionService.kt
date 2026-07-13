package com.example.voice.session

import android.content.Intent
import android.speech.RecognitionService

class JarvisRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // No-op - we stream audio directly to the Gemini WebSocket instead of using standard engine
    }

    override fun onCancel(listener: Callback?) {
        // No-op
    }

    override fun onStopListening(listener: Callback?) {
        // No-op
    }
}
