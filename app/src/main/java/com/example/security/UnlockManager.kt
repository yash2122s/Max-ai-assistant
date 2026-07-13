package com.example.security

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

class UnlockManager(private val context: Context) {
    
    /**
     * Wakes the screen if it is off (deep sleep).
     * Uses FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP.
     */
    @Suppress("DEPRECATION")
    fun wakeScreen() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or 
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "Jarvis:WakeLock"
            )
            wakeLock.acquire(10000)
        } catch (e: Exception) {
            Log.e("UnlockManager", "Failed to wake screen", e)
        }
    }
    
    /**
     * Ensures the screen is ON regardless of current state.
     * - If screen is OFF → wakes it up
     * - If screen is ON but dim → turns it fully on
     */
    @Suppress("DEPRECATION")
    fun ensureScreenOn() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                // Screen is OFF — wake it
                Log.d("UnlockManager", "Screen is OFF, waking up...")
                wakeScreen()
            } else {
                // Screen is already ON — still acquire to keep it awake during unlock
                Log.d("UnlockManager", "Screen is already ON, keeping alive...")
                val wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "Jarvis:KeepAlive"
                )
                wakeLock.acquire(10000)
            }
        } catch (e: Exception) {
            Log.e("UnlockManager", "Failed to ensure screen on", e)
        }
    }
    
    /**
     * Returns true if the screen is currently ON (interactive).
     */
    fun isScreenOn(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }
    
    /**
     * Returns true if the keyguard (lock screen) is currently showing.
     */
    fun isKeyguardLocked(): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return km.isKeyguardLocked
    }
}
