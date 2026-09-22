package com.example.voice.audio

import kotlin.math.sqrt

/**
 * AudioProcessor performs RMS signal energy calculation for UI wave / neural orb animation.
 *
 * VAD POLICY (aligned with OLD golden reference):
 * - Client-side VAD EOS detection is DISABLED.
 * - Gemini Live server-side VAD handles all speech start/end detection automatically.
 * - Using realtimeInput (continuous audio stream) — no commitAudioTurn() needed from client.
 * - This removes the 400ms+ silence-timer latency that was causing chatbot-like behaviour.
 *
 * The onEndOfSpeech / onSilenceTimeout parameters are retained in the constructor
 * for backward compatibility but are never invoked.
 */
class AudioProcessor(
    var speechThreshold: Float = 0.035f,
    var silenceThreshold: Float = 0.018f,
    var silenceDurationMs: Long = 400L,
    var minSpeechDurationMs: Long = 180L,
    private val onEndOfSpeech: (() -> Unit)? = null,
    private val onSilenceTimeout: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "VAD"
    }

    private var isSpeaking = false
    private var speechAccumulatorMs = 0L
    private var silenceAccumulatorMs = 0L
    private val chunkDurationMs = 20L // Approx 320 shorts at 16kHz = 20ms

    fun processAudio(shorts: ShortArray, size: Int): Float {
        if (size <= 0) return 0f

        var sum = 0.0
        for (i in 0 until size) {
            sum += shorts[i] * shorts[i]
        }
        val rawRms = sqrt(sum / size).toFloat()
        val normalizedRms = (rawRms / 32767f).coerceIn(0f, 1f)

        if (normalizedRms >= speechThreshold) {
            silenceAccumulatorMs = 0L
            speechAccumulatorMs += chunkDurationMs
            if (speechAccumulatorMs >= minSpeechDurationMs) {
                isSpeaking = true
            }
        } else if (isSpeaking && normalizedRms >= silenceThreshold) {
            // Quiet trailing words / speech continuation
            silenceAccumulatorMs = 0L
        } else {
            // Silence
            if (isSpeaking) {
                silenceAccumulatorMs += chunkDurationMs
                if (silenceAccumulatorMs >= silenceDurationMs) {
                    isSpeaking = false
                    silenceAccumulatorMs = 0L
                    speechAccumulatorMs = 0L
                    onEndOfSpeech?.invoke()
                }
            } else {
                speechAccumulatorMs = 0L
            }
        }

        return normalizedRms
    }

    fun reset() {
        isSpeaking = false
        speechAccumulatorMs = 0L
        silenceAccumulatorMs = 0L
    }

    fun isSpeechDetected(): Boolean = isSpeaking
}

