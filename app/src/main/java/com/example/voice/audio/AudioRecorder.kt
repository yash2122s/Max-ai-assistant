package com.example.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(
        audioProcessor: AudioProcessor,
        onAudioChunk: (ByteArray) -> Unit,
        onRmsChanged: (Float) -> Unit
    ) {
        if (isRecording) {
            Log.w("AudioRecorder", "Recording is already in progress, ignoring start request")
            return
        }

        val sampleRate = 16000 // Gemini Live standard 16kHz
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioRecorder", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            audioProcessor.reset()

            val buffer = ShortArray(bufferSize)
            recordingThread = Thread({
                while (isRecording) {
                    val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (shortsRead > 0) {
                        // Pass shorts to the processor for VAD/RMS
                        val rms = audioProcessor.processAudio(buffer, shortsRead)
                        onRmsChanged(rms)

                        // Convert short array into little-endian bytes for the Gemini Live API
                        val byteBuffer = ByteArray(shortsRead * 2)
                        for (i in 0 until shortsRead) {
                            val sample = buffer[i].toInt()
                            byteBuffer[i * 2] = (sample and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                        }
                        onAudioChunk(byteBuffer)
                    }
                }
            }, "AudioRecorder-Thread")
            recordingThread?.start()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error starting recording", e)
        }
    }

    @Synchronized
    fun stopRecording() {
        isRecording = false
        try {
            recordingThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.w("AudioRecorder", "Interrupted while joining recording thread", e)
        }
        recordingThread = null
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                    release()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error stopping recording", e)
        }
        audioRecord = null
    }

    fun isRecording(): Boolean = isRecording
}
