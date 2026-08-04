package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class PeriodTrackerVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("period_tracker")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        return VerificationResult(
            success = result.success,
            reason = if (result.success) null else result.message,
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
