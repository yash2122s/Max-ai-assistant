package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class CreateContactVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("create_contact")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val pkg = snapshot.ui.packageName.lowercase()
        val isEditor = pkg.contains("contact") || pkg.contains("dialer") || pkg.contains("phone") || pkg.contains("android")
        return VerificationResult(
            success = isEditor,
            reason = if (isEditor) null else "Not inside Contacts Editor app",
            retryRecommended = !isEditor,
            snapshot = snapshot
        )
    }
}
