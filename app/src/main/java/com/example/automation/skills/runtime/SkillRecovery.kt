package com.example.automation.skills.runtime

import android.content.Context
import android.util.Log
import com.example.automation.agent.UiAction
import com.example.automation.agent.UiActionExecutor
import com.example.automation.engine.execution.TaskRecoveryEngine
import com.example.automation.skills.compiler.BoundSkillStep
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Result of a recovery attempt.
 */
data class RecoveryResult(
    val success: Boolean,
    val actionTaken: String = "",
    val message: String = ""
)

/**
 * Safe, non-destructive recovery coordinator for MAX Automation Skills.
 * Handles UI drift, transient dialogs, and re-grounding.
 */
object SkillRecovery {

    private const val TAG = "SkillRecovery"

    /**
     * Attempts safe recovery after a step failure or verification timeout.
     */
    suspend fun attemptRecovery(
        context: Context,
        step: BoundSkillStep,
        attemptNumber: Int
    ): RecoveryResult {
        Log.w(TAG, "Attempting recovery for step '${step.stepId}' (attempt #$attemptNumber)")

        // 1. Check for blocking dialogs (e.g. "Not Now", "Cancel", "Dismiss", "Allow")
        val dismissedModal = dismissBlockingDialog(context)
        if (dismissedModal) {
            delay(500L)
            return RecoveryResult(true, "DISMISSED_MODAL", "Dismissed blocking modal dialog")
        }

        // 2. Short stabilization delay and fresh re-observation
        delay(400L)
        val freshGrounding = AdaptiveUiGrounder.groundTarget(step.semanticTarget, step.arguments)
        if (freshGrounding.success) {
            return RecoveryResult(true, "RE_GROUNDED", "Successfully re-grounded target on refreshed hierarchy")
        }

        // 3. If multiple failures on search/input, dismiss soft keyboard if active
        if (attemptNumber >= 2 && (step.canonicalAction == "ui.type" || step.canonicalAction == "ui.search")) {
            val service = JarvisAccessibilityService.instance
            val windows = try { service?.windows } catch (_: Throwable) { null }
            val hasImeWindow = windows?.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
            if (service != null && hasImeWindow) {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(500L)
                return RecoveryResult(true, "BACK_NAVIGATED", "Dismissed soft keyboard via back navigation")
            }
        }

        return RecoveryResult(false, "NONE", "No safe recovery strategy resolved for step '${step.stepId}'")
    }

    private suspend fun dismissBlockingDialog(context: Context): Boolean {
        val root = JarvisAccessibilityService.getActiveRoot() ?: return false
        val commonDismissLabels = listOf("not now", "dismiss", "cancel", "close", "got it", "skip", "later")

        for (label in commonDismissLabels) {
            val grounded = AdaptiveUiGrounder.groundTarget(label, rootNode = root)
            if (grounded.success && grounded.bounds != null) {
                Log.i(TAG, "Found blocking dialog button '$label', dismissing...")
                val tapAction = UiAction(type = UiAction.Type.TAP, x = grounded.centerX, y = grounded.centerY)
                UiActionExecutor.executeAction(context, tapAction)
                delay(500L)
                return true
            }
        }
        return false
    }
}
