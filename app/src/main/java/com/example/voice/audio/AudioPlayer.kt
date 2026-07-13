package com.example.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.sqrt

class AudioPlayer(private val audioFocusManager: AudioFocusManager) {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    @Synchronized
    fun play(audioData: ByteArray, onRmsChanged: (Float) -> Unit = {}) {
        try {
            // Calculate RMS from PCM 16-bit byte array (mono)
            val shortsCount = audioData.size / 2
            if (shortsCount > 0) {
                var sum = 0.0
                for (i in 0 until shortsCount) {
                    val low = audioData[i * 2].toInt() and 0xFF
                    val high = audioData[i * 2 + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    sum += sample * sample
                }
                val rms = sqrt(sum / shortsCount).toFloat()
                val normalizedRms = (rms / 32767f).coerceIn(0f, 1f)
                onRmsChanged(normalizedRms)
            }

            if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                audioFocusManager.requestFocus()
                
                val sampleRate = 24000
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = minBufferSize.coerceAtLeast(10240 * 4)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()
                isPlaying = true
            }

            audioTrack?.write(audioData, 0, audioData.size)
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error playing audio data", e)
        }
    }

    @Synchronized
    fun stopAndFlush() {
        try {
            isPlaying = false
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                    flush()
                    release()
                }
            }
            audioTrack = null
            audioFocusManager.abandonFocus()
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Error flushing audio player", e)
        }
    }

    fun isPlaying(): Boolean = isPlaying
}
