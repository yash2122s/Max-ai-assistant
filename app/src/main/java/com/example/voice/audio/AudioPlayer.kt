package com.example.voice.audio

import com.example.utils.AudioPlaybackManager

class AudioPlayer(private val audioFocusManager: AudioFocusManager) {

    @Synchronized
    fun play(audioData: ByteArray, onRmsChanged: (Float) -> Unit = {}) {
        audioFocusManager.requestFocus()
        AudioPlaybackManager.play(audioData, onRmsChanged)
    }

    @Synchronized
    fun stopAndFlush() {
        AudioPlaybackManager.stopAndFlush()
        audioFocusManager.abandonFocus()
    }

    fun isPlaying(): Boolean = AudioPlaybackManager.isPlaying()
}

