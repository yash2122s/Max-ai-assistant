package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class DiagnosticsVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("diagnostics")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        return VerificationResult(
            success = true,
            reason = null,
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
