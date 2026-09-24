package com.example.automation.computeruse

import android.util.Log

object ActionVerifier {
    private const val TAG = "ActionVerifier"

    fun verify(
        action: ComputerAction,
        executionResult: ActionResult,
        before: ScreenObservation,
        after: ScreenObservation
    ): Pair<Boolean, String> {
        if (!executionResult.success) {
            val note = executionResult.message ?: "Action execution failed"
            Log.w(TAG, "[VERIFY] FAIL reason='$note'")
            return Pair(false, note)
        }

        // 1. Evaluate explicit ActionExpectation if attached
        val expectation = action.expectation
        if (expectation != null) {
            val (expVerified, expNote) = evaluateExpectation(expectation, before, after)
            if (!expVerified) {
                Log.w(TAG, "[VERIFY] FAIL reason='$expNote'")
                return Pair(false, expNote)
            }
        }

        // 2. Strict Type-specific Verification Rules
        val (verified, note) = when (action) {
            is ComputerAction.LaunchApp -> {
                val targetPkg = executionResult.targetApp ?: action.packageName ?: ""
                val currentPkg = after.foregroundPackage
                val isForeground = if (targetPkg.isNotBlank()) {
                    currentPkg.contains(targetPkg, ignoreCase = true) || targetPkg.contains(currentPkg, ignoreCase = true)
                } else {
                    after.isAppForeground && currentPkg != before.foregroundPackage
                }
                if (isForeground) {
                    Pair(true, "App '$currentPkg' is foreground and active")
                } else {
                    Pair(false, "App launch not verified: expected '$targetPkg', actual foreground is '$currentPkg'")
                }
            }

            is ComputerAction.TypeText -> {
                val text = action.text.trim()
                val textPresent = after.visibleTexts.any { it.contains(text, ignoreCase = true) } ||
                    after.editableElements.any { it.text?.contains(text, ignoreCase = true) == true } ||
                    (action.pressEnterAfter && before.hasScreenChanged(after))

                if (textPresent) {
                    Pair(true, "Text '$text' verified entered into field")
                } else {
                    Pair(false, "Text '$text' not found in target field after typing")
                }
            }

            is ComputerAction.TapElement -> {
                val screenChanged = before.hasScreenChanged(after)
                val targetSelected = after.interactiveElements.any { elem ->
                    elem.isSelected && (
                        elem.text?.contains(action.targetText, ignoreCase = true) == true ||
                        elem.contentDescription?.contains(action.targetText, ignoreCase = true) == true ||
                        elem.id == action.resourceId
                    )
                }
                val targetFocused = after.focusedElement?.let { elem ->
                    elem.text?.contains(action.targetText, ignoreCase = true) == true ||
                    elem.id == action.resourceId
                } ?: false

                if (screenChanged || targetSelected || targetFocused) {
                    Pair(true, if (screenChanged) "Screen transitioned after tap on '${action.targetText}'" else "Control '${action.targetText}' focused/selected")
                } else {
                    Pair(false, "Tap on '${action.targetText}' produced no state transition or selection change")
                }
            }

            is ComputerAction.TapCoordinate -> {
                val screenChanged = before.hasScreenChanged(after)
                if (screenChanged) {
                    Pair(true, "Screen transitioned after coordinate tap at (${action.x}, ${action.y})")
                } else {
                    Pair(false, "Coordinate tap at (${action.x}, ${action.y}) produced no screen change")
                }
            }

            is ComputerAction.PressEnter -> {
                val screenChanged = before.hasScreenChanged(after)
                Pair(true, "Dispatched Enter key (screenChanged=$screenChanged)")
            }

            is ComputerAction.Scroll -> {
                val screenChanged = before.hasScreenChanged(after)
                if (screenChanged) {
                    Pair(true, "Scrolled ${action.direction} successfully (viewport updated)")
                } else {
                    Pair(false, "Scroll ${action.direction} reached end or produced no viewport change")
                }
            }

            is ComputerAction.PressBack -> {
                val screenChanged = before.hasScreenChanged(after)
                if (screenChanged) {
                    Pair(true, "Screen navigated back successfully")
                } else {
                    Pair(false, "Back action produced no UI change")
                }
            }

            is ComputerAction.LongPress -> {
                val screenChanged = before.hasScreenChanged(after)
                if (screenChanged) {
                    Pair(true, "Long press triggered context menu/state change at (${action.x}, ${action.y})")
                } else {
                    Pair(false, "Long press at (${action.x}, ${action.y}) produced no context change")
                }
            }

            is ComputerAction.Swipe, is ComputerAction.Drag -> {
                val screenChanged = before.hasScreenChanged(after)
                if (screenChanged) {
                    Pair(true, "Gesture swipe/drag produced expected screen motion")
                } else {
                    Pair(false, "Gesture produced no detectable UI change")
                }
            }

            is ComputerAction.PressKey -> {
                val screenChanged = before.hasScreenChanged(after)
                Pair(true, "Dispatched key '${action.keyNameOrCode}' (screenChanged=$screenChanged)")
            }

            is ComputerAction.Wait -> {
                Pair(true, "Wait period concluded (${action.durationMs}ms)")
            }

            is ComputerAction.ObserveMore -> {
                Pair(true, "Observation cycle concluded: ${action.reason}")
            }

            is ComputerAction.FinishTask -> {
                Pair(action.success, action.message)
            }
        }

        Log.i(TAG, "[VERIFY] ${if (verified) "PASS" else "FAIL"} reason='$note'")
        return Pair(verified, note)
    }

    private fun evaluateExpectation(
        expectation: ActionExpectation,
        before: ScreenObservation,
        after: ScreenObservation
    ): Pair<Boolean, String> {
        return when (expectation) {
            is ActionExpectation.ScreenChange -> {
                if (before.hasScreenChanged(after)) {
                    Pair(true, "Screen state change verified")
                } else {
                    Pair(false, "Screen state unchanged after action")
                }
            }

            is ActionExpectation.TextPresent -> {
                val found = after.visibleTexts.any { it.contains(expectation.value, ignoreCase = true) } ||
                    after.editableElements.any { it.text?.contains(expectation.value, ignoreCase = true) == true }
                if (found) {
                    Pair(true, "Expected text '${expectation.value}' found on screen")
                } else {
                    Pair(false, "Expected text '${expectation.value}' not present on screen")
                }
            }

            is ActionExpectation.AppForeground -> {
                if (after.foregroundPackage.contains(expectation.packageName, ignoreCase = true)) {
                    Pair(true, "Target app '${expectation.packageName}' is in foreground")
                } else {
                    Pair(false, "Target app '${expectation.packageName}' not in foreground (current: '${after.foregroundPackage}')")
                }
            }

            is ActionExpectation.FocusField -> {
                val focused = after.focusedElement?.let {
                    it.id?.contains(expectation.target, ignoreCase = true) == true ||
                    it.text?.contains(expectation.target, ignoreCase = true) == true
                } ?: false
                if (focused) {
                    Pair(true, "Field '${expectation.target}' acquired focus")
                } else {
                    Pair(false, "Field '${expectation.target}' is not focused")
                }
            }

            is ActionExpectation.ElementSelected -> {
                val selected = after.interactiveElements.any {
                    it.isSelected && (
                        it.id?.contains(expectation.target, ignoreCase = true) == true ||
                        it.text?.contains(expectation.target, ignoreCase = true) == true
                    )
                }
                if (selected) {
                    Pair(true, "Element '${expectation.target}' is selected")
                } else {
                    Pair(false, "Element '${expectation.target}' is not selected")
                }
            }
        }
    }
}

