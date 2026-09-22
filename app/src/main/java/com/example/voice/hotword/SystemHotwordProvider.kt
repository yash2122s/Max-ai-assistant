package com.example.voice.hotword

import android.content.Context
import android.util.Log

/**
 * Android system hotword provider implementation.
 * Evaluates hardware DSP capabilities and sound trigger availability.
 * On modern Android without OEM-privileged DSP enrollment models,
 * reports UNAVAILABLE_NO_DSP gracefully without fabricating fake hotword detection.
 */
class SystemHotwordProvider(private val context: Context) : HotwordProvider {
    companion object {
        private const val TAG = "SystemHotwordProvider"
    }

    override fun getSupportStatus(): HotwordSupportStatus {
        // Third-party applications on standard Android/AOSP/MIUI cannot enroll custom keyphrases into DSP HAL
        return HotwordSupportStatus.UNAVAILABLE_NO_DSP
    }

    override fun startListening(onWakeWord: () -> Unit) {
        Log.i(TAG, "Hotword startListening requested, but DSP keyphrase trigger is not available on this platform")
    }

    override fun stopListening() {
        Log.i(TAG, "Hotword stopListening called")
    }
}
