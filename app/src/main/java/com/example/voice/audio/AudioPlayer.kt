package com.example.voice.audio

import com.example.utils.AudioPlaybackManager

class AudioPlayer(private val audioFocusManager: AudioFocusManager) {
    @Volatile
    private var hasFocus = false

    init {
        AudioPlaybackManager.onPlaybackStateChanged = { isPlaying ->
            if (!isPlaying && hasFocus) {
                audioFocusManager.abandonFocus()
                hasFocus = false
            }
        }
    }

    @Synchronized
    fun play(audioData: ByteArray, onRmsChanged: (Float) -> Unit = {}) {
        if (!hasFocus) {
            hasFocus = audioFocusManager.requestFocus()
        }
        AudioPlaybackManager.play(audioData, onRmsChanged)
    }

    @Synchronized
    fun stopAndFlush() {
        AudioPlaybackManager.stopAndFlush()
        if (hasFocus) {
            audioFocusManager.abandonFocus()
            hasFocus = false
        }
    }

    fun isPlaying(): Boolean = AudioPlaybackManager.isPlaying()
}

