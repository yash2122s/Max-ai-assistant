package com.example.security

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * P0 Security Gate: Unlock Verifier.
 *
 * Invariants:
 * 1. Unlock attempt != Unlock success.
 * 2. Keyguard status must be explicitly verified before declaring unlock success.
 * 3. Sensitive downstream operations (calls, messaging, UI taps, typing)
 *    must NEVER execute while keyguard is locked.
 */
object UnlockVerifier {
    private const val TAG = "UnlockVerifier"

    val SENSITIVE_ACTIONS = setOf(
        "CALL_PHONE",
        "SEND_WHATSAPP",
        "SEND_WHATSAPP_MESSAGE",
        "SEND_TELEGRAM",
        "SEND_SMS",
        "CLICK_NODE",
        "CLICK_ELEMENT",
        "CLICK_TEXT",
        "CLICK_INDEX",
        "CLICK_UI",
        "TYPE_TEXT",
        "PRESS_ENTER",
        "PRESS_KEY",
        "OPEN_APP",
        "LAUNCH_APP",
        "TAKE_SCREENSHOT",
        "GET_SCREEN_CONTEXT",
        "SCROLL_SCREEN",
        "COMPUTER_USE",
        "UI_AGENT",
        "TASK_INTENT"
    )

    fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked ?: false
    }

    /**
     * Polls KeyguardManager up to [timeoutMs] to verify keyguard dismissal.
     */
    suspend fun verifyUnlocked(
        context: Context,
        timeoutMs: Long = 7000,
        pollIntervalMs: Long = 300
    ): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            ?: return false

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!km.isKeyguardLocked) {
                Log.i(TAG, "Keyguard dismissal verified.")
                return true
            }
            delay(pollIntervalMs)
        }

        val isLocked = km.isKeyguardLocked
        Log.w(TAG, "Keyguard dismissal verification timed out. isLocked=$isLocked")
        return !isLocked
    }

    /**
     * Enforces that sensitive actions cannot execute while keyguard is locked.
     */
    fun assertUnlockedForAction(context: Context, actionType: String): Result<Unit> {
        val normalizedAction = actionType.uppercase().trim()
        if (normalizedAction in SENSITIVE_ACTIONS) {
            if (isKeyguardLocked(context)) {
                val reason = "Security gate blocked '$actionType': device is currently locked with keyguard."
                Log.w(TAG, reason)
                return Result.failure(SecurityException(reason))
            }
        }
        return Result.success(Unit)
    }
}
