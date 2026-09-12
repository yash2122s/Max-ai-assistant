package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

/**
 * Verifier for click_element / click_ui actions.
 * Verifies local signals (e.g. execution success, package changes, or UI state transitions).
 */
class ClickUiVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("click_element", "click_ui", "type_text")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val action = request.action
        val target = request.arguments.get("text")?.asString
            ?: request.arguments.get("target")?.asString
            ?: request.arguments.get("query")?.asString
            ?: ""

        Log.d("ClickUiVerifier", "Verifying $action target='$target', initialSuccess=${result.success}")

        if (result.success) {
            return VerificationResult(
                success = true,
                reason = null,
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        return VerificationResult(
            success = false,
            reason = result.message ?: "Failed to execute $action for target '$target'",
            retryRecommended = result.retryable,
            snapshot = snapshot
        )
    }
}
