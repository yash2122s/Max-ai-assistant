package com.example.voice.session

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Android framework RecognitionService stub referenced by jarvis_voice_interaction.xml.
 * Speech recognition in MAX streams live PCM directly via GeminiWebSocketClient in AssistantEngine.
 * Does not allocate a second AudioRecord or duplicate speech recognition.
 */
open class JarvisRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "JarvisRecognition"
    }

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.d(TAG, "onStartListening called by external recognizer caller")
        // Inform caller that standard offline SpeechRecognizer is delegated to Gemini Live
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: Exception) {
            Log.w(TAG, "Error notifying listener", e)
        }
    }

    override fun onCancel(listener: Callback?) {
        Log.d(TAG, "onCancel called")
    }

    override fun onStopListening(listener: Callback?) {
        Log.d(TAG, "onStopListening called")
    }
}
