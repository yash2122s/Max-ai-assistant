package com.example.utils

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    @SuppressLint("MissingPermission")
    fun startRecording(onAudioChunk: (ByteArray) -> Unit) {
        val sampleRate = 16000 // Gemini requires 16kHz
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
            
            audioRecord?.startRecording()
            isRecording = true
            
            val buffer = ByteArray(bufferSize)
            Thread {
                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        onAudioChunk(buffer.copyOf(bytesRead))
                        
                        val shortsCount = bytesRead / 2
                        if (shortsCount > 0) {
                            var sum = 0.0
                            for (i in 0 until shortsCount) {
                                val low = buffer[i * 2].toInt() and 0xFF
                                val high = buffer[i * 2 + 1].toInt()
                                val sample = ((high shl 8) or low).toShort()
                                sum += sample * sample
                            }
                            val rms = kotlin.math.sqrt(sum / shortsCount).toFloat()
                            val normalizedRms = (rms / 32767f).coerceIn(0f, 1f)
                            com.example.viewmodel.ChatViewModel.updateRms(normalizedRms)
                        }
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e("AudioRecorder", "Error starting recording", e)
        }
    }
    
    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
             Log.e("AudioRecorder", "Error stopping recording", e)
        }
        audioRecord = null
    }
}

private var globalAudioTrack: AudioTrack? = null

fun playAudioResponse(audioData: ByteArray) {
    Log.d("HUD_TEST", "playAudioResponse called, bytes=${audioData.size}")
    try {
        if (globalAudioTrack == null || globalAudioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.d("HUD_TEST", "Initializing global AudioTrack at 24kHz")
            val minBufferSize = AudioTrack.getMinBufferSize(
                24000, 
                AudioFormat.CHANNEL_OUT_MONO, 
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBufferSize.coerceAtLeast(10240 * 4)
            
            globalAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            Log.d("HUD_TEST", "AudioTrack created. State=${globalAudioTrack?.state}, playState=${globalAudioTrack?.playState}")
            globalAudioTrack?.play()
        }
        
        val result = globalAudioTrack?.write(audioData, 0, audioData.size) ?: -1
        Log.d("HUD_TEST", "AudioTrack write returned=$result (expected=${audioData.size})")
    } catch (e: Exception) {
        Log.e("AudioPlayback", "Error playing audio", e)
    }
}
