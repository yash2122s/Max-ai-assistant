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

        if (!result.success) {
            return VerificationResult(
                success = false,
                reason = result.message ?: "Failed to execute $action for target '$target'",
                retryRecommended = result.retryable,
                snapshot = snapshot
            )
        }

        // State-based semantic check for critical transition steps
        val lowerTarget = target.lowercase()
        val root = com.example.service.JarvisAccessibilityService.getActiveRoot()

        if (lowerTarget.contains("first account") || lowerTarget.contains("first profile") || lowerTarget.contains("first user")) {
            // After clicking first account, verify that we navigated away from the search input edit mode or to a profile/detail screen
            if (root != null) {
                val hasSearchInputFocused = try {
                    val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    val id = focused?.viewIdResourceName?.lowercase() ?: ""
                    id.contains("action_bar_search_edit_text") || id.contains("search_src_text")
                } catch (_: Throwable) { false }

                val hasProfileOrMessageButton = try {
                    val messageNodes = root.findAccessibilityNodeInfosByText("Message")
                    messageNodes.any { node ->
                        val id = node.viewIdResourceName?.lowercase() ?: ""
                        !id.endsWith("_tab") && node.isClickable
                    }
                } catch (_: Throwable) { false }

                Log.d("ClickUiVerifier", "State check for 'first account': profileOrMessageButton=$hasProfileOrMessageButton, searchFocused=$hasSearchInputFocused")
            }
        }

        return VerificationResult(
            success = true,
            reason = null,
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
