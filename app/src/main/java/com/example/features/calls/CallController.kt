package com.example.features.calls

import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

class CallController(private val context: Context) {
    companion object {
        private const val TAG = "CallController"
    }
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    fun answerCall(): Boolean {
        // 1. Try TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.acceptRingingCall()
                Log.d(TAG, "Call answered via TelecomManager")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager answer failed: ${e.message}, falling back to accessibility gesture")
        }

        // 2. Fallback to Accessibility gesture
        val a11y = com.example.service.JarvisAccessibilityService.instance
        if (a11y != null) {
            val answered = a11y.performAnswerCallGesture()
            if (answered) {
                Log.d(TAG, "Call answered via AccessibilityService gesture")
                return true
            }
        }

        Log.e(TAG, "Failed to answer call via all available mechanisms")
        return false
    }

    fun rejectCall(): Boolean {
        // 1. Try TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val success = telecomManager.endCall()
                if (success) {
                    Log.d(TAG, "Call ended via TelecomManager: true")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager reject failed: ${e.message}, falling back to accessibility gesture")
        }

        // 2. Fallback to Accessibility gesture
        val a11y = com.example.service.JarvisAccessibilityService.instance
        if (a11y != null) {
            val rejected = a11y.performRejectCallGesture()
            if (rejected) {
                Log.d(TAG, "Call rejected via AccessibilityService gesture")
                return true
            }
        }

        Log.e(TAG, "Failed to reject call via all available mechanisms")
        return false
    }
}
