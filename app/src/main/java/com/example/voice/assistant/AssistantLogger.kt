package com.example.voice.assistant

import android.util.Log
import java.util.UUID

object AssistantLogger {
    private const val TAG = "AssistantEngine"
    private var sessionID: String = ""

    // Performance Metrics
    private var triggerTime: Long = 0
    private var firstAudioResponseTime: Long = 0
    private var firstTranscriptTime: Long = 0

    fun startSession() {
        sessionID = UUID.randomUUID().toString().take(8)
        triggerTime = System.currentTimeMillis()
        firstAudioResponseTime = 0
        firstTranscriptTime = 0
        logInfo("Session started (ID: $sessionID)")
    }

    fun recordFirstTranscript() {
        if (firstTranscriptTime == 0L) {
            firstTranscriptTime = System.currentTimeMillis()
            val latency = firstTranscriptTime - triggerTime
            logInfo("Time to first user transcript: ${latency}ms")
        }
    }

    fun recordFirstAudioResponse() {
        if (firstAudioResponseTime == 0L) {
            firstAudioResponseTime = System.currentTimeMillis()
            val latency = firstAudioResponseTime - triggerTime
            logInfo("Time to first audio response: ${latency}ms")
        }
    }

    fun logInfo(msg: String) {
        Log.i(TAG, "[$sessionID] $msg")
    }

    fun logWarn(msg: String) {
        Log.w(TAG, "[$sessionID] $msg")
    }

    fun logError(msg: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$sessionID] $msg", throwable)
    }
}
