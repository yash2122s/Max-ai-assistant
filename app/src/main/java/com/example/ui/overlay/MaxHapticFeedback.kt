package com.example.ui.overlay

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Universal Haptic & Vibration Feedback Manager for MAX.
 * Provides tactile haptic feedback during UI automation phases.
 */
object MaxHapticFeedback {
    private const val TAG = "MaxHapticFeedback"

    private fun getVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get vibrator service: ${e.message}")
            null
        }
    }

    /**
     * Subtle light tick vibration (when UI element is grounded / locked on).
     */
    fun tick(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(15L, 80))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(15L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Medium action pulse (when a tap, type, or key event is dispatched).
     */
    fun actionPulse(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(28L, 160))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(28L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Subtle initiation pulse (when planning or observation begins).
     */
    fun startPulse(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(20L, 100))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20L)
            }
        } catch (_: Exception) {}
    }

    /**
     * Pleasant double-pulse celebration (when automation succeeds).
     */
    fun successWave(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 25, 60, 40)
                val amplitudes = intArrayOf(0, 140, 0, 200)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 25, 60, 40), -1)
            }
        } catch (_: Exception) {}
    }

    /**
     * Warning double-buzz (when automation fails / is blocked).
     */
    fun failureAlert(context: Context) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 45, 70, 60)
                val amplitudes = intArrayOf(0, 220, 0, 220)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 45, 70, 60), -1)
            }
        } catch (_: Exception) {}
    }
}
