package com.example.voice.audio

import com.example.voice.assistant.AssistantLogger
import kotlin.math.sqrt

class AudioProcessor(
    private val onSilenceTimeout: () -> Unit
) {
    private var silenceStartTime: Long = 0L
    private val SILENCE_THRESHOLD = 0.02f // RMS threshold for voice detection
    private val SILENCE_TIMEOUT_MS = 30000L // 30 seconds of silence triggers automatic mute/timeout

    fun processAudio(shorts: ShortArray, size: Int): Float {
        if (size <= 0) return 0f

        var sum = 0.0
        for (i in 0 until size) {
            sum += shorts[i] * shorts[i]
        }
        val rms = sqrt(sum / size).toFloat()
        val normalizedRms = (rms / 32767f).coerceIn(0f, 1f)

        // Voice Activity Detection (VAD) / Silence Timeout checks
        if (normalizedRms > SILENCE_THRESHOLD) {
            silenceStartTime = 0L // Reset silence timer when speech is active
        } else {
            if (silenceStartTime == 0L) {
                silenceStartTime = System.currentTimeMillis()
            } else {
                val duration = System.currentTimeMillis() - silenceStartTime
                if (duration >= SILENCE_TIMEOUT_MS) {
                    silenceStartTime = 0L
                    AssistantLogger.logInfo("Silence timeout reached (no speech detected for ${SILENCE_TIMEOUT_MS}ms)")
                    onSilenceTimeout()
                }
            }
        }

        return normalizedRms
    }

    fun reset() {
        silenceStartTime = 0L
    }
}
