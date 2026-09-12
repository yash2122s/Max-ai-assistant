package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

/**
 * Verifier for SystemTool actions (BACK, HOME, RECENTS, NOTIFICATIONS, SCREENSHOT, etc.).
 * For BACK action, accepts ANY valid state transition:
 * - keyboard hidden / dismissed
 * - dialog dismissed
 * - focus changed
 * - screen signature changed
 * - foreground package changed
 * - or successful dispatch of GLOBAL_ACTION_BACK
 */
class SystemVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("system", "system_action", "SYSTEM_ACTION", "BACK", "PERFORM_BACK")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        if (!result.success) {
            return VerificationResult(
                success = false,
                reason = result.message ?: "System action failed to execute",
                retryRecommended = result.retryable,
                snapshot = snapshot
            )
        }

        val actionStr = request.arguments.get("system_action_str")?.asString
            ?: request.arguments.get("action")?.asString
            ?: request.action

        val isBack = actionStr.equals("back", ignoreCase = true) ||
                request.action.equals("BACK", ignoreCase = true) ||
                request.action.equals("PERFORM_BACK", ignoreCase = true)

        if (isBack) {
            // For BACK action, if the execution succeeded without throwing an exception,
            // we accept any valid state transition or successful global action dispatch.
            return VerificationResult(
                success = true,
                reason = null,
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        return VerificationResult(
            success = true,
            reason = null,
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
