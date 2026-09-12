package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService

/**
 * Universal Scroll Tool for MAX.
 * Supports directional scrolling, bounded steps, and `untilTarget` scrolling with strict loop detection
 * using SCREEN_SIGNATURE_HISTORY and MAX_SCROLL_STEPS.
 */
class ScrollTool : Tool {
    private val TAG = "ScrollTool"
    override val name: String = "scroll_screen"
    override val supportedActions: Set<String> = setOf("SCROLL_DOWN", "SCROLL_UP", "SCROLL_SCREEN", "SCROLL")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    companion object {
        const val DEFAULT_MAX_SCROLL_STEPS = 5
        const val ABSOLUTE_MAX_SCROLL_STEPS = 10
    }

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val action = request.action.uppercase()
        val directionParam = args.get("direction")?.asString?.lowercase()
            ?: args.get("target")?.asString?.lowercase()
            ?: ""
        
        val direction = when {
            action == "SCROLL_UP" || directionParam.contains("up") -> "up"
            action == "SCROLL_DOWN" || directionParam.contains("down") -> "down"
            directionParam.contains("left") -> "left"
            directionParam.contains("right") -> "right"
            else -> "down"
        }

        val untilTarget = args.get("untilTarget")?.asString
            ?: args.get("targetText")?.asString?.takeIf { it != "down" && it != "up" && it != "left" && it != "right" }

        val maxSteps = (args.get("maxSteps")?.asInt ?: DEFAULT_MAX_SCROLL_STEPS).coerceIn(1, ABSOLUTE_MAX_SCROLL_STEPS)

        val service = JarvisAccessibilityService.instance
            ?: ServiceRegistry.get<JarvisAccessibilityService>(ServiceType.ACCESSIBILITY)

        if (service == null) {
            Log.e(TAG, "JarvisAccessibilityService is not running or accessible")
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "SERVICE_UNAVAILABLE",
                message = "Accessibility service is not active"
            )
        }

        Log.d(TAG, "Executing scroll $direction (untilTarget='$untilTarget', maxSteps=$maxSteps)")

        // If no untilTarget, perform a single scroll step
        if (untilTarget.isNullOrBlank()) {
            val (success, message) = service.scrollScreen(direction)
            return ToolResult(
                success = success,
                toolName = name,
                verificationRequired = true,
                message = message,
                errorCode = if (!success) "SCROLL_FAILED" else null
            )
        }

        // untilTarget loop with loop detection via SCREEN_SIGNATURE_HISTORY
        val signatureHistory = mutableSetOf<String>()
        var stepCount = 0

        while (stepCount < maxSteps) {
            val root = service.rootInActiveWindow
            if (root != null) {
                // 1. Check if target is already grounded on screen
                val grounding = ScreenGroundingEngine.groundTarget(target = untilTarget, rootNode = root)
                if (grounding.status == com.example.automation.ui.GroundingStatus.FOUND) {
                    Log.d(TAG, "untilTarget '$untilTarget' found after $stepCount scroll steps")
                    return ToolResult(
                        success = true,
                        toolName = name,
                        verificationRequired = true,
                        message = "Found target '$untilTarget' after $stepCount scroll steps"
                    )
                }

                // 2. Loop detection via screen signature history
                val currentSig = service.getScreenSignature(root)
                if (currentSig in signatureHistory) {
                    Log.w(TAG, "Screen signature repeated at step $stepCount. Reached end of scrollable content.")
                    break
                }
                signatureHistory.add(currentSig)
            }

            // 3. Perform scroll step
            stepCount++
            val (scrolled, msg) = service.scrollScreen(direction)
            if (!scrolled) {
                Log.w(TAG, "Scroll action failed at step $stepCount: $msg")
                break
            }

            kotlinx.coroutines.delay(400L)
        }

        // Final check for target after last scroll
        val finalRoot = service.rootInActiveWindow
        if (finalRoot != null) {
            val finalGrounding = ScreenGroundingEngine.groundTarget(target = untilTarget, rootNode = finalRoot)
            if (finalGrounding.status == com.example.automation.ui.GroundingStatus.FOUND) {
                return ToolResult(
                    success = true,
                    toolName = name,
                    verificationRequired = true,
                    message = "Found target '$untilTarget' at step $stepCount"
                )
            }
        }

        return ToolResult(
            success = false,
            toolName = name,
            errorCode = "TARGET_NOT_FOUND_AFTER_SCROLL",
            message = "Target '$untilTarget' not found after $stepCount scroll steps in direction $direction"
        )
    }
}
