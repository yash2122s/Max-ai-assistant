package com.example.automation.skills.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.agent.UiAction
import com.example.automation.agent.UiActionExecutor
import com.example.automation.resolver.InputTargetResolver
import com.example.automation.skills.compiler.BoundSkillStep
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.delay

/**
 * Result of executing a single bound step.
 */
data class StepExecutionResult(
    val success: Boolean,
    val stepId: String,
    val message: String = "",
    val groundedTarget: GroundedUiTarget? = null
)

/**
 * Dispatches compiled skill steps to trusted native capabilities.
 */
object SkillStepExecutor {

    private const val TAG = "SkillStepExecutor"

    suspend fun executeStep(
        context: Context,
        step: BoundSkillStep
    ): StepExecutionResult {
        Log.i(TAG, "Executing step '${step.stepId}': action='${step.canonicalAction}', target='${step.semanticTarget}'")

        return when (step.canonicalAction.lowercase()) {
            "app.open" -> executeAppOpen(context, step)
            "ui.click" -> executeClick(context, step)
            "ui.type_text" -> executeTypeText(context, step)
            "ui.press_key" -> executePressKey(context, step)
            "ui.scroll" -> executeScroll(context, step)
            "ui.swipe" -> executeSwipe(context, step)
            "ui.wait" -> executeWait(step)
            "system.action" -> executeSystemAction(context, step)
            else -> {
                Log.w(TAG, "Unknown capability '${step.canonicalAction}', attempting semantic execution.")
                executeClick(context, step)
            }
        }
    }

    private suspend fun executeAppOpen(context: Context, step: BoundSkillStep): StepExecutionResult {
        val packageName = (step.arguments["package_name"] as? String)
            ?: (step.arguments["package"] as? String)
            ?: resolvePackageNameForApp(context, step.semanticTarget)

        if (packageName.isNullOrBlank()) {
            return StepExecutionResult(false, step.stepId, "Cannot resolve package name for '${step.semanticTarget}'")
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            delay(1000L)
            return StepExecutionResult(true, step.stepId, "Launched package '$packageName'")
        }

        val action = UiAction(type = UiAction.Type.LAUNCH_APP, packageName = packageName)
        val success = UiActionExecutor.executeAction(context, action)
        return StepExecutionResult(success, step.stepId, "Launched app via UiActionExecutor")
    }

    private suspend fun executeClick(context: Context, step: BoundSkillStep): StepExecutionResult {
        val grounded = AdaptiveUiGrounder.groundTarget(step.semanticTarget, step.arguments)
        if (!grounded.success || grounded.bounds == null) {
            return StepExecutionResult(false, step.stepId, "Grounding failed: ${grounded.reason}", grounded)
        }

        // Try direct node performAction(ACTION_CLICK) if node is available and clickable
        val node = grounded.targetNode
        if (node != null && node.isClickable) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Log.d(TAG, "Direct Accessibility ACTION_CLICK succeeded on '${step.semanticTarget}'")
                delay(300L)
                return StepExecutionResult(true, step.stepId, "Direct click succeeded", grounded)
            }
        }

        // Fallback: Tap at center coordinates
        val action = UiAction(
            type = UiAction.Type.TAP,
            x = grounded.centerX,
            y = grounded.centerY,
            reason = "Skill step ${step.stepId}"
        )
        val success = UiActionExecutor.executeAction(context, action)
        delay(300L)
        return StepExecutionResult(success, step.stepId, "Dispatched tap at (${grounded.centerX}, ${grounded.centerY})", grounded)
    }

    private suspend fun executeTypeText(context: Context, step: BoundSkillStep): StepExecutionResult {
        val textToType = (step.arguments["text"] as? String) ?: ""
        val pressEnter = (step.arguments["press_enter"] as? Boolean) == true

        // Try InputTargetResolver first
        val root = JarvisAccessibilityService.getActiveRoot()
        if (root != null) {
            val resolvedInput = InputTargetResolver.resolveInputTarget(root, step.semanticTarget)
            if (resolvedInput != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                }
                val setDirectly = resolvedInput.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (setDirectly) {
                    Log.d(TAG, "Set text directly via Accessibility ACTION_SET_TEXT: '$textToType'")
                    if (pressEnter) {
                        delay(200L)
                        JarvisAccessibilityService.instance?.pressImeAction()
                    }
                    return StepExecutionResult(true, step.stepId, "Text set directly")
                }
            }
        }

        // Ground target element
        val grounded = AdaptiveUiGrounder.groundTarget(step.semanticTarget, step.arguments)
        if (grounded.success && grounded.bounds != null) {
            // Click to focus
            val tapAction = UiAction(type = UiAction.Type.TAP, x = grounded.centerX, y = grounded.centerY)
            UiActionExecutor.executeAction(context, tapAction)
            delay(300L)
        }

        // Execute typing
        val typeAction = UiAction(type = UiAction.Type.TYPE, text = textToType)
        val typed = UiActionExecutor.executeAction(context, typeAction)
        if (pressEnter) {
            delay(200L)
            val enterAction = UiAction(type = UiAction.Type.KEY_ENTER)
            UiActionExecutor.executeAction(context, enterAction)
        }

        return StepExecutionResult(typed, step.stepId, "Typed text via UiActionExecutor")
    }

    private suspend fun executePressKey(context: Context, step: BoundSkillStep): StepExecutionResult {
        val key = (step.arguments["key"] as? String)?.uppercase() ?: ""
        val action = when (key) {
            "ENTER" -> UiAction(type = UiAction.Type.KEY_ENTER)
            "BACK" -> UiAction(type = UiAction.Type.KEY_BACK)
            "HOME" -> UiAction(type = UiAction.Type.KEY_HOME)
            else -> UiAction(type = UiAction.Type.KEY_ENTER)
        }
        val success = UiActionExecutor.executeAction(context, action)
        return StepExecutionResult(success, step.stepId, "Pressed key $key")
    }

    private suspend fun executeScroll(context: Context, step: BoundSkillStep): StepExecutionResult {
        val direction = (step.arguments["direction"] as? String) ?: "DOWN"
        val action = UiAction(type = UiAction.Type.SCROLL, direction = direction)
        val success = UiActionExecutor.executeAction(context, action)
        return StepExecutionResult(success, step.stepId, "Scrolled $direction")
    }

    private suspend fun executeSwipe(context: Context, step: BoundSkillStep): StepExecutionResult {
        val x = (step.arguments["startX"] as? Number)?.toInt() ?: 500
        val y = (step.arguments["startY"] as? Number)?.toInt() ?: 1000
        val endX = (step.arguments["endX"] as? Number)?.toInt() ?: 500
        val endY = (step.arguments["endY"] as? Number)?.toInt() ?: 300
        val duration = (step.arguments["duration_ms"] as? Number)?.toLong() ?: 300L
        val action = UiAction(type = UiAction.Type.SWIPE, x = x, y = y, endX = endX, endY = endY, durationMs = duration)
        val success = UiActionExecutor.executeAction(context, action)
        return StepExecutionResult(success, step.stepId, "Swiped")
    }

    private suspend fun executeWait(step: BoundSkillStep): StepExecutionResult {
        val duration = (step.arguments["duration_ms"] as? Number)?.toLong() ?: 1000L
        delay(duration)
        return StepExecutionResult(true, step.stepId, "Waited ${duration}ms")
    }

    private suspend fun executeSystemAction(context: Context, step: BoundSkillStep): StepExecutionResult {
        val command = (step.arguments["command"] as? String)?.uppercase() ?: step.semanticTarget.uppercase()
        val service = JarvisAccessibilityService.instance
        if (service != null) {
            when (command) {
                "BACK" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "HOME" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "RECENTS" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                else -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            delay(500L)
            return StepExecutionResult(true, step.stepId, "Performed system global action $command")
        }
        return StepExecutionResult(false, step.stepId, "Accessibility service unavailable for system action")
    }

    private fun resolvePackageNameForApp(context: Context, appName: String): String? {
        val lower = appName.lowercase()
        return when {
            lower.contains("instagram") -> "com.instagram.android"
            lower.contains("whatsapp") -> "com.whatsapp"
            lower.contains("youtube") -> "com.google.android.youtube"
            lower.contains("telegram") -> "org.telegram.messenger"
            lower.contains("settings") -> "com.android.settings"
            else -> null
        }
    }
}
