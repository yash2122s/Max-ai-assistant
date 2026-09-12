package com.example.automation.tools

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.verification.RetryPolicy
import com.example.core.registry.ServiceRegistry
import com.example.core.registry.ServiceType
import com.example.service.JarvisAccessibilityService

/**
 * Universal Press Enter / IME Action Tool for MAX.
 * Follows the strict hierarchy:
 * 1. Focused field IME action (ACTION_IME_ENTER)
 * 2. Explicit semantic submit action (SEARCH / GO / DONE)
 * 3. Grounded Search/Go/Done/Submit button (only when relationship to focused field is clear; never clicks "Send")
 * 4. Key-event fallback
 */
class PressEnterTool : Tool {
    private val TAG = "PressEnterTool"

    override val name: String = "press_enter"
    override val supportedActions: Set<String> = setOf("PRESS_ENTER", "IME_ENTER", "ENTER_KEY", "PERFORM_ENTER")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val target = request.arguments.get("target")?.asString
            ?: request.arguments.get("field")?.asString
        return com.example.automation.keyboard.KeyboardController.pressEnter(context, target)
    }
}
