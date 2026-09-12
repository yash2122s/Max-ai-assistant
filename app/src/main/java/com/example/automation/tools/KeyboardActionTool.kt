package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.keyboard.KeyboardController
import com.example.automation.verification.RetryPolicy

/**
 * Tool for executing physical and virtual keyboard key events.
 */
class KeyboardActionTool : Tool {
    override val name: String = "press_key"
    override val supportedActions: Set<String> = setOf(
        "KEYBOARD_ACTION", "PRESS_KEY", "KEY_PRESS",
        "KEY_ENTER", "KEY_BACK", "KEY_TAB", "KEY_SPACE",
        "KEY_DELETE", "KEY_BACKSPACE", "KEY_ESC", "KEY_ESCAPE",
        "KEY_HOME", "KEY_END", "KEY_ARROW_UP", "KEY_ARROW_DOWN",
        "KEY_ARROW_LEFT", "KEY_ARROW_RIGHT", "KEY_SHIFT", "KEY_CTRL", "KEY_ALT"
    )
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
        val key = args.get("key")?.asString
            ?: args.get("keyCode")?.asString
            ?: args.get("key_name")?.asString
            ?: request.action.removePrefix("KEY_")
        val target = args.get("target")?.asString
            ?: args.get("field")?.asString

        return KeyboardController.pressKey(
            context = context,
            keyNameOrCode = key,
            targetField = target
        )
    }
}
