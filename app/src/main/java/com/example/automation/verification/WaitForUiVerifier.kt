package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class WaitForUiVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("wait_for_ui", "wait")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val target = request.arguments.get("text")?.asString
            ?: request.arguments.get("target")?.asString
            ?: request.arguments.get("query")?.asString
            ?: ""

        Log.d("WaitForUiVerifier", "Verifying wait_for_ui target='$target', success=${result.success}")

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
            reason = result.message ?: "Target '$target' not verified within timeout",
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
