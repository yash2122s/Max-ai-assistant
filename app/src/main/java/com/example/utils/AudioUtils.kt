package com.example.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AudioPlaybackManager {
    private const val TAG = "AudioPlaybackManager"
    private var globalAudioTrack: AudioTrack? = null
    private val _isPlayingFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isPlayingFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isPlayingFlow.asStateFlow()
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null

    private val audioQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile
    private var isWorkerRunning = false
    private var workerThread: Thread? = null
    private val lock = Any()

    private fun ensureWorkerRunning() {
        synchronized(lock) {
            if (workerThread == null || !workerThread!!.isAlive) {
                isWorkerRunning = true
                workerThread = Thread({
                    runPlaybackLoop()
                }, "MAX-AudioPlaybackWorker").apply {
                    isDaemon = true
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            }
        }
    }

    private fun runPlaybackLoop() {
        Log.d(TAG, "AudioPlaybackWorker started")
        while (isWorkerRunning) {
            try {
                val chunk = audioQueue.poll(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk != null && chunk.isNotEmpty()) {
                    ensureAudioTrackInitialized()

                    if (globalAudioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        globalAudioTrack?.play()
                    }

                    if (!_isPlayingFlow.value) {
                        _isPlayingFlow.value = true
                        onPlaybackStateChanged?.invoke(true)
                        android.util.Log.d("VoiceLatency", "[VoiceLatency] playback_start ts=${System.currentTimeMillis()}")
                    }

                    val written = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        globalAudioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) ?: -1
                    } else {
                        globalAudioTrack?.write(chunk, 0, chunk.size) ?: -1
                    }
                } else {
                    // Queue idle for 400ms => current speech turn playback finished
                    if (_isPlayingFlow.value && audioQueue.isEmpty()) {
                        _isPlayingFlow.value = false
                        onPlaybackStateChanged?.invoke(false)
                        Log.d(TAG, "Audio playback finished, idle state restored")
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted on stopAndFlush
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio playback loop", e)
            }
        }
        Log.d(TAG, "AudioPlaybackWorker exited")
    }

    private fun ensureAudioTrackInitialized() {
        if (globalAudioTrack == null || globalAudioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            val minBufferSize = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // Low-latency ~100ms hardware buffer (4096-8192 bytes) prevents underflow without buffering delay
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            Log.d(TAG, "Initializing AudioTrack at 24kHz mono 16-bit (bufferSize=$bufferSize)")
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
        }
    }

    fun play(audioData: ByteArray, onRmsChanged: (Float) -> Unit = {}) {
        if (audioData.isEmpty()) return

        try {
            // Fast RMS computation for visual HUD wave
            val shortsCount = audioData.size / 2
            if (shortsCount > 0) {
                var sum = 0.0
                for (i in 0 until shortsCount) {
                    val low = audioData[i * 2].toInt() and 0xFF
                    val high = audioData[i * 2 + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    sum += sample * sample
                }
                val rms = kotlin.math.sqrt(sum / shortsCount).toFloat()
                val normalizedRms = (rms / 32767f).coerceIn(0f, 1f)
                onRmsChanged(normalizedRms)
                com.example.viewmodel.ChatViewModel.updateRms(normalizedRms)
            }

            // Immediately enqueue PCM chunk for non-blocking asynchronous playback
            audioQueue.offer(audioData)
            ensureWorkerRunning()
        } catch (e: Exception) {
            Log.e(TAG, "Error queuing audio chunk", e)
        }
    }

    fun stopAndFlush() {
        try {
            audioQueue.clear()
            _isPlayingFlow.value = false
            onPlaybackStateChanged?.invoke(false)

            synchronized(lock) {
                globalAudioTrack?.apply {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        try {
                            pause()
                            flush()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error pausing/flushing AudioTrack: ${e.message}")
                        }
                    }
                }
            }
            Log.d(TAG, "AudioPlaybackManager queue cleared and AudioTrack flushed")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio playback", e)
        }
    }

    fun isPlaying(): Boolean = _isPlayingFlow.value
}

fun playAudioResponse(audioData: ByteArray) {
    AudioPlaybackManager.play(audioData)
}

fun stopAudioResponse() {
    AudioPlaybackManager.stopAndFlush()
}

