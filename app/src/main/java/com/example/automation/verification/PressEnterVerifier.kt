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
        // Press Enter verification passes if the tool completed execution without error
        return VerificationResult(
            success = result.success,
            reason = if (result.success) null else result.message,
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
