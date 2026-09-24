package com.example.automation.computeruse

import android.util.Log

/**
 * State-aware, bounded recovery engine for the Computer-Use state machine.
 * Provides adaptive recovery actions when an action execution or verification fails,
 * strictly without falsely advancing the goal state.
 */
object ComputerUseRecoveryEngine {
    private const val TAG = "ComputerUseRecoveryEngine"
    const val MAX_RECOVERY_ATTEMPTS = 3

    fun planRecovery(
        attempt: Int,
        failedAction: ComputerAction,
        obs: ScreenObservation,
        context: TaskContext,
        currentPhase: GoalState
    ): ComputerAction {
        Log.w(TAG, "[RECOVERY] attempt=$attempt/3 phase=$currentPhase failedAction=${failedAction.actionName}")

        // 1. Dismiss potential blocking popups
        val dismissBtn = obs.interactiveElements.firstOrNull { elem ->
            val t = elem.text?.lowercase() ?: ""
            val d = elem.contentDescription?.lowercase() ?: ""
            t in setOf("not now", "cancel", "skip", "dismiss", "close", "no thanks", "later") ||
            d in setOf("close", "dismiss", "cancel")
        }
        if (dismissBtn != null && dismissBtn.bounds.width() > 0) {
            Log.i(TAG, "[RECOVERY] Dismissing blocking popup '${dismissBtn.displayLabel}'")
            return ComputerAction.TapCoordinate(
                x = dismissBtn.bounds.centerX(),
                y = dismissBtn.bounds.centerY(),
                description = "Dismiss Popup"
            )
        }

        // 2. Action-specific recovery strategies
        return when (failedAction) {
            is ComputerAction.TapCoordinate -> {
                if (attempt == 1) {
                    Log.i(TAG, "[RECOVERY] Coordinate tap produced no change; scrolling down to reveal viewport")
                    ComputerAction.Scroll(direction = "down")
                } else if (attempt == 2) {
                    Log.i(TAG, "[RECOVERY] Attempting physical swipe gesture fallback")
                    ComputerAction.Swipe(540, 1600, 540, 800, 350L)
                } else {
                    Log.i(TAG, "[RECOVERY] Pressing Back to restore preceding UI state")
                    ComputerAction.PressBack
                }
            }

            is ComputerAction.TapElement -> {
                val matched = obs.findElementByText(failedAction.targetText)
                if (matched != null && matched.bounds.width() > 0 && matched.bounds.height() > 0) {
                    Log.i(TAG, "[RECOVERY] Re-grounded element bounds (${matched.bounds.centerX()}, ${matched.bounds.centerY()})")
                    ComputerAction.TapCoordinate(
                        x = matched.bounds.centerX(),
                        y = matched.bounds.centerY(),
                        description = "Recovery Tap: ${matched.displayLabel}"
                    )
                } else {
                    Log.i(TAG, "[RECOVERY] Element not found on screen; scrolling down")
                    ComputerAction.Scroll(direction = "down")
                }
            }

            is ComputerAction.TypeText -> {
                val editable = obs.findEditableElement()
                if (editable != null && editable.bounds.width() > 0) {
                    Log.i(TAG, "[RECOVERY] Focusing editable field via coordinate tap before re-typing")
                    ComputerAction.TapCoordinate(editable.bounds.centerX(), editable.bounds.centerY(), "Focus Edit Field")
                } else {
                    Log.i(TAG, "[RECOVERY] Retrying typing with clearExisting=true")
                    ComputerAction.TypeText(
                        text = failedAction.text,
                        targetField = failedAction.targetField,
                        pressEnterAfter = true,
                        clearExisting = true
                    )
                }
            }

            is ComputerAction.PressEnter -> {
                val searchBtn = obs.interactiveElements.firstOrNull { elem ->
                    elem.id?.contains("search", ignoreCase = true) == true ||
                    elem.contentDescription?.contains("search", ignoreCase = true) == true
                }
                if (searchBtn != null && searchBtn.bounds.width() > 0) {
                    Log.i(TAG, "[RECOVERY] Tapping on-screen search icon instead of Enter key")
                    ComputerAction.TapCoordinate(searchBtn.bounds.centerX(), searchBtn.bounds.centerY(), "Search Submit")
                } else {
                    Log.i(TAG, "[RECOVERY] Dispatching KEYCODE_ENTER fallback")
                    ComputerAction.PressKey("ENTER")
                }
            }

            is ComputerAction.Scroll -> {
                Log.i(TAG, "[RECOVERY] Using swipe gesture for scroll fallback")
                ComputerAction.Swipe(540, 1600, 540, 600, 400L)
            }

            else -> {
                if (attempt == 2) {
                    Log.i(TAG, "[RECOVERY] Pressing Back to navigate away from unexpected screen")
                    ComputerAction.PressBack
                } else {
                    Log.i(TAG, "[RECOVERY] Waiting for UI stabilization")
                    ComputerAction.Wait(durationMs = 1000L, reason = "Recovery wait")
                }
            }
        }
    }
}
