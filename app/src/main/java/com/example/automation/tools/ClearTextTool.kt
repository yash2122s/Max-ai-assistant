package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.keyboard.KeyboardController
import com.example.automation.verification.RetryPolicy

/**
 * Tool for clearing text in target or focused input fields.
 */
class ClearTextTool : Tool {
    override val name: String = "clear_text"
    override val supportedActions: Set<String> = setOf("CLEAR_TEXT", "CLEAR_INPUT", "RESET_TEXT")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = true,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val target = args.get("target")?.asString
            ?: args.get("field")?.asString

        return KeyboardController.clearText(
            context = context,
            targetField = target
        )
    }
}
