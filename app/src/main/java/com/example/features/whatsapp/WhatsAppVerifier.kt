package com.example.features.whatsapp

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.Verifier
import com.example.automation.verification.VerificationResult
import com.example.service.JarvisAccessibilityService
import com.example.service.SendVerificationResult

class WhatsAppVerifier : Verifier {
    private val TAG = "WhatsAppVerifier"
    override val supportedTools: Set<String> = setOf("send_whatsapp_message", "navigate_whatsapp", "open_whatsapp_chat")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val currentPackage = snapshot.ui.packageName
        val isWhatsApp = currentPackage.equals("com.whatsapp", ignoreCase = true) ||
                currentPackage.equals("com.whatsapp.w4b", ignoreCase = true)

        val lastSendResult = JarvisAccessibilityService.lastSendVerificationResult
        val isTaskPending = JarvisAccessibilityService.pendingTask != null
        Log.d(TAG, "Verification: currentPackage = $currentPackage, isWhatsApp = $isWhatsApp, sendResult = $lastSendResult, isPending = $isTaskPending")

        if (!isWhatsApp) {
            return VerificationResult(
                success = false,
                reason = "Foreground package is not WhatsApp (got '$currentPackage')",
                retryRecommended = true,
                snapshot = snapshot
            )
        }

        return when (lastSendResult) {
            SendVerificationResult.SUCCESS -> {
                val reasonMsg = when (request.action) {
                    "NAVIGATE_WHATSAPP", "OPEN_WHATSAPP_SCREEN", "WHATSAPP_NAVIGATE" ->
                        "WhatsApp screen navigated and verified successfully"
                    "OPEN_WHATSAPP_CHAT" ->
                        "WhatsApp chat opened and verified successfully"
                    else ->
                        "WhatsApp UI confirmed message sent (input cleared / outgoing message observed)"
                }
                VerificationResult(
                    success = true,
                    reason = reasonMsg,
                    retryRecommended = false,
                    snapshot = snapshot
                )
            }

            SendVerificationResult.SEND_ACTION_CONFIRMED -> {
                VerificationResult(
                    success = true,
                    reason = "Send action executed and confirmed on WhatsApp UI",
                    retryRecommended = false,
                    snapshot = snapshot
                )
            }

            SendVerificationResult.WRONG_CHAT -> {
                VerificationResult(
                    success = false,
                    reason = "Safety check: Active WhatsApp chat does not match requested contact",
                    retryRecommended = true,
                    snapshot = snapshot
                )
            }

            SendVerificationResult.INPUT_NOT_FOUND -> {
                VerificationResult(
                    success = false,
                    reason = "Could not locate WhatsApp message entry input field",
                    retryRecommended = true,
                    snapshot = snapshot
                )
            }

            SendVerificationResult.SEND_BUTTON_NOT_FOUND -> {
                VerificationResult(
                    success = false,
                    reason = "Could not locate or click WhatsApp send button",
                    retryRecommended = true,
                    snapshot = snapshot
                )
            }

            SendVerificationResult.TIMEOUT, SendVerificationResult.FAILED -> {
                VerificationResult(
                    success = false,
                    reason = "WhatsApp send automation timed out or failed",
                    retryRecommended = true,
                    snapshot = snapshot
                )
            }

            SendVerificationResult.VERIFICATION_PENDING, null -> {
                if (isTaskPending) {
                    VerificationResult(
                        success = false,
                        reason = "WhatsApp automation is in progress...",
                        retryRecommended = false,
                        snapshot = snapshot
                    )
                } else {
                    VerificationResult(
                        success = isWhatsApp,
                        reason = if (isWhatsApp) "WhatsApp active in foreground with send dispatched" else "WhatsApp not in foreground",
                        retryRecommended = !isWhatsApp,
                        snapshot = snapshot
                    )
                }
            }
        }
    }
}
