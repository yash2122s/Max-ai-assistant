package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class WhatsAppVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("send_whatsapp_message")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val currentPackage = snapshot.ui.packageName
        val isWhatsApp = currentPackage.equals("com.whatsapp", ignoreCase = true) ||
                currentPackage.equals("com.whatsapp.w4b", ignoreCase = true)

        Log.d("WhatsAppVerifier", "Verification: currentPackage = $currentPackage, isWhatsApp = $isWhatsApp")

        return VerificationResult(
            success = isWhatsApp,
            reason = if (isWhatsApp) null else "Foreground package is not WhatsApp (got '$currentPackage')",
            retryRecommended = !isWhatsApp,
            snapshot = snapshot
        )
    }
}
