package com.example.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log

class AudioRecorder {
    companion object {
        const val TARGET_SAMPLE_RATE = 16000 // Gemini Live standard client input: 16kHz mono PCM16
        private const val TAG = "AudioRecorder"

        /**
         * Linear interpolation resampler from [inRate] to [outRate].
         */
        fun resampleLinear(input: ShortArray, inputLength: Int, inRate: Int, outRate: Int): ShortArray {
            require(inRate > 0) { "inRate must be positive" }
            require(outRate > 0) { "outRate must be positive" }
            val safeInputLength = inputLength.coerceIn(0, input.size)
            if (inRate == outRate || safeInputLength <= 0) return input.copyOf(safeInputLength)
            val outLength = ((safeInputLength.toLong() * outRate) / inRate).toInt()
            if (outLength <= 0) return ShortArray(0)
            val output = ShortArray(outLength)
            val ratio = inRate.toDouble() / outRate.toDouble()
            for (i in 0 until outLength) {
                val inIndex = i * ratio
                val i1 = inIndex.toInt()
                val i2 = minOf(i1 + 1, safeInputLength - 1)
                val frac = inIndex - i1
                val sample = (input[i1] * (1.0 - frac) + input[i2] * frac).toInt()
                output[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return output
        }
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var actualSampleRate = TARGET_SAMPLE_RATE
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    fun getActualSampleRate(): Int = actualSampleRate

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(
        audioProcessor: AudioProcessor,
        onAudioChunk: (ByteArray) -> Unit,
        onRmsChanged: (Float) -> Unit
    ) {
        if (isRecording) {
            Log.w(TAG, "Recording is already in progress, ignoring start request")
            return
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Candidate rates: 16000 first (Gemini Live standard), fallback to 48000 or 44100 if hardware requires it
        val ratesToTry = intArrayOf(TARGET_SAMPLE_RATE, 48000, 44100)
        var selectedRecord: AudioRecord? = null
        var selectedRate = TARGET_SAMPLE_RATE
        var selectedBufferSize = 0

        for (rate in ratesToTry) {
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBuf > 0) {
                try {
                    val bufferSize = maxOf(minBuf * 2, (rate / 10) * 2) // at least 100ms internal buffer
                    val candidate = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        rate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        selectedRecord = candidate
                        selectedRate = rate
                        selectedBufferSize = minBuf
                        Log.i(TAG, "AudioRecord initialized successfully at rate $rate Hz (minBuf=$minBuf)")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed initializing AudioRecord at $rate Hz: ${e.message}")
                }
            }
        }

        if (selectedRecord == null || selectedRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed for all candidate rates")
            selectedRecord?.release()
            throw IllegalStateException("AudioRecord failed to initialize at supported sample rates")
        }

        audioRecord = selectedRecord
        actualSampleRate = selectedRate

        val audioSessionId = selectedRecord.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware AcousticEchoCanceler enabled successfully (sessionId=" + audioSessionId + ")")
                }
            } else {
                Log.w(TAG, "Hardware AcousticEchoCanceler is not available on this device")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize AcousticEchoCanceler: " + e.message)
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "Hardware NoiseSuppressor enabled successfully (sessionId=" + audioSessionId + ")")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to initialize NoiseSuppressor: " + e.message)
        }

        try {
            audioRecord?.startRecording()
            isRecording = true
            audioProcessor.reset()

            // 40ms read chunk sizing (e.g. 640 samples at 16kHz = 40ms, 1920 samples at 48kHz = 40ms)
            val chunkSamples = (actualSampleRate * 40) / 1000
            val readBufferSize = maxOf(chunkSamples, selectedBufferSize / 2)
            val buffer = ShortArray(readBufferSize)

            recordingThread = Thread({
                try {
                    while (isRecording) {
                    val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (shortsRead < 0) {
                        Log.w(TAG, "AudioRecord.read failed with code $shortsRead")
                        break
                    }
                    if (shortsRead > 0) {
                        // Resample to 16kHz if hardware captured at another rate
                        val targetShorts: ShortArray
                        val targetCount: Int
                        if (actualSampleRate == TARGET_SAMPLE_RATE) {
                            targetShorts = buffer
                            targetCount = shortsRead
                        } else {
                            targetShorts = resampleLinear(buffer, shortsRead, actualSampleRate, TARGET_SAMPLE_RATE)
                            targetCount = targetShorts.size
                        }

                        if (targetCount > 0) {
                            // Pass 16kHz shorts to processor for RMS energy
                            val rms = audioProcessor.processAudio(targetShorts, targetCount)
                            onRmsChanged(rms)

                            // Echo suppression: mute microphone chunks sent to Gemini Live while speaker is actively speaking
                            val isSpeakerActive = com.example.utils.AudioPlaybackManager.isPlaying()
                            if (isSpeakerActive) {
                                // Zero-out PCM data to silence self-voice feedback
                                val silentBuffer = ByteArray(targetCount * 2)
                                onAudioChunk(silentBuffer)
                            } else {
                                // Convert short array into little-endian bytes for the Gemini Live API (16kHz mono PCM16)
                                val byteBuffer = ByteArray(targetCount * 2)
                                for (i in 0 until targetCount) {
                                    val sample = targetShorts[i].toInt()
                                    byteBuffer[i * 2] = (sample and 0xFF).toByte()
                                    byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                                }
                                onAudioChunk(byteBuffer)
                            }
                        }
                    }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Audio recording thread failed", e)
                } finally {
                    synchronized(this@AudioRecorder) {
                        if (Thread.currentThread() === recordingThread) {
                            isRecording = false
                            audioRecord?.release()
                            audioRecord = null
                            recordingThread = null
                        }
                    }
                }
            }, "AudioRecorder-Thread")
            recordingThread?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            recordingThread = null
            throw e
        }
    }

    @Synchronized
    fun stopRecording() {
        isRecording = false
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while joining recording thread", e)
        }
        recordingThread = null
        try {
            echoCanceler?.apply {
                enabled = false
                release()
            }
            echoCanceler = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing echo canceler: " + e.message)
        }
        try {
            noiseSuppressor?.apply {
                enabled = false
                release()
            }
            noiseSuppressor = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing noise suppressor: " + e.message)
        }
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                    release()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
        audioRecord = null
    }

    fun isRecording(): Boolean = isRecording
}
