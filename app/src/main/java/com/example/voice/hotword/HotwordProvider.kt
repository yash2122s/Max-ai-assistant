package com.example.voice.hotword

/**
 * Hardware and platform support status for always-on hotword detection ("Hey MAX").
 */
enum class HotwordSupportStatus {
    SUPPORTED,
    UNAVAILABLE_NO_DSP,
    UNAVAILABLE_NO_ENROLLMENT,
    UNAVAILABLE_DEVICE_RESTRICTED
}

/**
 * Clean abstraction for wake-word providers.
 * Isolates hotword triggers from GeminiWebSocketClient, AudioRecorder, and ActionDispatcher.
 */
interface HotwordProvider {
    fun getSupportStatus(): HotwordSupportStatus
    fun isSupported(): Boolean = getSupportStatus() == HotwordSupportStatus.SUPPORTED
    fun startListening(onWakeWord: () -> Unit)
    fun stopListening()
}
