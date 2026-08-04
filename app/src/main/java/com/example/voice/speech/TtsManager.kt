package com.example.voice.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TtsManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("TtsManager", "US English TTS Language data missing or not supported.")
            }
            isReady = true
            Log.d("TtsManager", "Local TextToSpeech initialized successfully.")
        } else {
            Log.e("TtsManager", "Failed to initialize TextToSpeech (status: $status).")
        }
    }

    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "OFFLINE_TTS_ID")
        } else {
            Log.w("TtsManager", "TTS not ready or text is blank.")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            tts = null
            isReady = false
        }
    }
}
