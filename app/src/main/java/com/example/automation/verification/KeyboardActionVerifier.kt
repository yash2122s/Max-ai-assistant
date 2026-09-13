package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

/**
 * Verifier for physical and virtual keyboard key events.
 */
class KeyboardActionVerifier : Verifier {
    override val supportedTools: Set<String> = setOf(
        "press_key", "PRESS_KEY", "KEYBOARD_ACTION", "KEY_PRESS"
    )

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        return VerificationResult(
            success = result.success,
            reason = if (result.success) null else (result.message ?: "Keyboard action failed verification"),
            retryRecommended = !result.success && result.errorCode != "UNSUPPORTED_KEY",
            snapshot = snapshot
        )
    }
}
