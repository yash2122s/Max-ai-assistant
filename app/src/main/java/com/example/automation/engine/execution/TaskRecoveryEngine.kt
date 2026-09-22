package com.example.automation.engine.execution

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.state.TaskContext
import com.example.automation.engine.state.TaskStep
import com.example.automation.models.UiStep
import com.example.automation.resolver.UiTargetResolver
import com.example.automation.state.ActionStep
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Bounded local recovery engine for MAX AI.
 * Handles localized UI failures (obscuring keyboard, transient dialogs, target off-screen)
 * without triggering an expensive LLM replan cycle.
 *
 * Bounds:
 * MAX_STEP_RETRIES = 2
 */
class TaskRecoveryEngine {
    companion object {
        private const val TAG = "TaskRecoveryEngine"
        const val MAX_STEP_RETRIES = 2
    }

    /**
     * Attempts bounded recovery on a [TaskStep].
     */
    suspend fun attemptRecovery(
        context: Context,
        step: TaskStep,
        taskContext: TaskContext
    ): Boolean {
        if (step.retryCount >= MAX_STEP_RETRIES) {
            Log.w(TAG, "Step '${step.id}' exhausted max local retries ($MAX_STEP_RETRIES). Escalating to replanner.")
            return false
        }

        step.retryCount++
        Log.i(TAG, "Attempting local recovery for step '${step.id}' (attempt ${step.retryCount}/$MAX_STEP_RETRIES)")
        return executeRecoveryStrategy(context, step.action)
    }

    /**
     * Attempts bounded recovery on an [ActionStep].
     */
    suspend fun attemptRecoveryForAction(
        context: Context,
        step: ActionStep,
        lastError: String,
        attemptNumber: Int
    ): Boolean {
        if (attemptNumber > MAX_STEP_RETRIES) {
            Log.w(TAG, "ActionStep '${step.stepId}' exceeded max retries ($MAX_STEP_RETRIES). Escalating.")
            return false
        }

        Log.i(TAG, "Attempting recovery for ActionStep '${step.stepId}' (${step.actionType}) - error: '$lastError'")
        return executeRecoveryStrategy(context, step.toUiStep())
    }

    private suspend fun executeRecoveryStrategy(context: Context, uiStep: UiStep): Boolean {
        val service = JarvisAccessibilityService.instance

        // Strategy 1: Dismiss soft-keyboard if obscuring target
        if (isKeyboardLikelyObstructing(uiStep)) {
            Log.d(TAG, "Recovery Strategy 1: Attempting to dismiss keyboard via GLOBAL_ACTION_BACK")
            val backDismissed = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
            if (backDismissed) {
                delay(300L) // Wait for IME collapse animation
                return true
            }
        }

        // Strategy 2: Dismiss unexpected transient dialog or modal
        if (dismissUnexpectedDialog(service)) {
            Log.d(TAG, "Recovery Strategy 2: Dismissed unexpected transient dialog")
            delay(300L)
            return true
        }

        // Strategy 3: Bounded scroll to reveal target if off-screen
        if (isTargetScrollable(uiStep)) {
            Log.d(TAG, "Recovery Strategy 3: Target might be off-screen. Performing bounded scroll-down.")
            val (scrolled, _) = service?.scrollScreen("down") ?: Pair(false, "No service")
            if (scrolled) {
                delay(400L) // Wait for scroll settling
                return true
            }
        }

        // Strategy 4: Fallback settling delay (transient layout animation)
        Log.d(TAG, "Recovery Strategy 4: Applying settling delay for transient UI state")
        delay(500L)
        return true
    }

    private fun isKeyboardLikelyObstructing(step: UiStep): Boolean {
        return when (step) {
            is UiStep.Tap, is UiStep.Scroll, is UiStep.Swipe, is UiStep.PressKey -> true
            else -> false
        }
    }

    private fun isTargetScrollable(step: UiStep): Boolean {
        return when (step) {
            is UiStep.Tap, is UiStep.LongPress -> true
            else -> false
        }
    }

    private fun dismissUnexpectedDialog(service: JarvisAccessibilityService?): Boolean {
        if (service == null) return false
        val root = service.rootInActiveWindow ?: return false

        if (!isModalOrDialogPresent(root)) {
            return false
        }

        val dismissKeywords = listOf("Not now", "Dismiss", "Cancel", "Skip", "Close", "No thanks", "Later")
        for (keyword in dismissKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val clickable = if (node.isClickable) node else findClickableAncestor(node)
                if (clickable != null) {
                    val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.i(TAG, "Dismissed unexpected modal/dialog via '$keyword' button")
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun isModalOrDialogPresent(root: AccessibilityNodeInfo): Boolean {
        fun scan(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 25) return false
            val cls = node.className?.toString()?.lowercase(java.util.Locale.ROOT).orEmpty()
            val id = node.viewIdResourceName?.lowercase(java.util.Locale.ROOT).orEmpty()
            if (cls.contains("dialog") || cls.contains("popup") || cls.contains("alert") ||
                id.contains("dialog") || id.contains("modal") || id.contains("bottom_sheet") ||
                id.contains("popup") || id.contains("interstitial") || id.contains("sheet_container") ||
                id.contains("promo") || id.contains("action_sheet")) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
                if (scan(child, depth + 1)) return true
            }
            return false
        }

        if (scan(root, 0)) return true

        val affirmativeKeywords = listOf("Try it", "Continue", "OK", "Turn on", "Learn more", "Get started", "Accept", "Allow")
        val hasAffirmative = affirmativeKeywords.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
        val dismissKeywords = listOf("Not now", "Dismiss", "Cancel", "Skip", "Close", "No thanks", "Later")
        val hasDismiss = dismissKeywords.any { root.findAccessibilityNodeInfosByText(it).isNotEmpty() }
        return hasAffirmative && hasDismiss
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }
}
