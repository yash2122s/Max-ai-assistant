package com.example.voice.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognizerManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    var onResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            stopListening()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("SpeechRecognizer", "Ready for speech input.")
                }
                override fun onBeginningOfSpeech() {
                    Log.d("SpeechRecognizer", "Beginning of speech.")
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d("SpeechRecognizer", "End of speech.")
                }
                override fun onError(error: Int) {
                    Log.w("SpeechRecognizer", "Speech error code: $error")
                    onError?.invoke("Speech error code: $error")
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull() ?: ""
                    Log.d("SpeechRecognizer", "Recognized spoken text: '$spokenText'")
                    onResult?.invoke(spokenText)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(intent)
        } else {
            Log.e("SpeechRecognizer", "Speech recognition not available on device.")
            onError?.invoke("Speech recognition not available")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            speechRecognizer = null
        }
    }
}
