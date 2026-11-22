package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class PressEnterVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("press_enter", "PRESS_ENTER", "IME_ENTER")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val submissionVerified = result.metadata.optBoolean("submissionVerified", false)
        return VerificationResult(
            success = result.success && submissionVerified,
            reason = if (result.success && submissionVerified) null
                else result.message ?: "Enter/Search dispatch was not independently verified",
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
