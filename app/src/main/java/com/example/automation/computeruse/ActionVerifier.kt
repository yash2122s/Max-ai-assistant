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

        if (action !is ComputerAction.Wait && action !is ComputerAction.ObserveMore && !after.isFresh()) {
            return false to "Fresh foreground observation is required for verification"
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
                    currentPkg.isNotBlank() && currentPkg.equals(targetPkg, ignoreCase = true)
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
                val textPresent = text.isNotEmpty() && after.editableElements.any { it.text?.contains(text) == true }

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
                        (action.resourceId != null && elem.id == action.resourceId)
                    )
                }
                val targetFocused = after.focusedElement?.let { elem ->
                    elem.text?.contains(action.targetText, ignoreCase = true) == true ||
                    (action.resourceId != null && elem.id == action.resourceId)
                } ?: false

                val isTargetVerifiedExp = action.expectation is ActionExpectation.TargetVerified
                val targetPresent = after.visibleTexts.any { it.contains(action.targetText, ignoreCase = true) } ||
                    after.interactiveElements.any { elem ->
                        elem.text?.contains(action.targetText, ignoreCase = true) == true ||
                        elem.contentDescription?.contains(action.targetText, ignoreCase = true) == true ||
                        (action.resourceId != null && elem.id == action.resourceId)
                    }

                if (screenChanged || targetSelected || targetFocused) {
                    val msg = when {
                        screenChanged -> "Screen transitioned after tap on '${action.targetText}'"
                        targetSelected -> "Control '${action.targetText}' focused/selected"
                        else -> "Control '${action.targetText}' focused"
                    }
                    Pair(true, msg)
                } else {
                    Pair(false, "Tap on '${action.targetText}' produced no state transition, focus, or selection change")
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
                Pair(screenChanged || action.expectation != null, "Enter dispatch requires an observed transition or verified expectation (screenChanged=$screenChanged)")
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
                Pair(screenChanged || action.expectation != null, "Key dispatch requires an observed transition or verified expectation (screenChanged=$screenChanged)")
            }

            is ComputerAction.Wait -> {
                Pair(true, "Wait period concluded (${action.durationMs}ms)")
            }

            is ComputerAction.ObserveMore -> {
                Pair(true, "Observation cycle concluded: ${action.reason}")
            }

            is ComputerAction.FinishTask -> {
                Pair(false, "Task completion must be derived from verified goal state")
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
                if (expectation.packageName.isNotBlank() && after.foregroundPackage.equals(expectation.packageName, ignoreCase = true)) {
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

            is ActionExpectation.TargetVerified -> {
                val target = expectation.target
                val selected = after.interactiveElements.any {
                    it.isSelected && (
                        it.text?.contains(target, ignoreCase = true) == true ||
                        it.contentDescription?.contains(target, ignoreCase = true) == true ||
                        it.id?.contains(target, ignoreCase = true) == true
                    )
                }
                val focused = after.interactiveElements.any {
                    it.isFocused && (
                        it.text?.contains(target, ignoreCase = true) == true ||
                        it.contentDescription?.contains(target, ignoreCase = true) == true ||
                        it.id?.contains(target, ignoreCase = true) == true
                    )
                }
                val newlyAppeared = after.visibleTexts.any { it.contains(target, ignoreCase = true) } &&
                    !before.visibleTexts.any { it.contains(target, ignoreCase = true) }
                val packageChanged = before.foregroundPackage != after.foregroundPackage &&
                    after.visibleTexts.any { it.contains(target, ignoreCase = true) }

                if (selected || focused) {
                    Pair(true, "Target '$target' verified selected/focused")
                } else if (newlyAppeared) {
                    Pair(true, "Target '$target' verified newly appeared on screen")
                } else if (packageChanged) {
                    Pair(true, "Target '$target' verified in destination package '${after.foregroundPackage}'")
                } else {
                    Pair(false, "Target '$target' was already present or produced no destination transition")
                }
            }
        }
    }
}
