package com.example.automation.verification

import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus

class CallVerifier : Verifier {
    override val supportedTools: Set<String> = setOf("call_contact")

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        // 1. Confirmation required
        if (result.errorCode == "CONFIRMATION_REQUIRED" || 
            result.metadata.optString("status") == "CONFIRMATION_REQUIRED" ||
            result.status == ToolResultStatus.NEEDS_INPUT) {
            return VerificationResult(
                success = false,
                reason = "CONFIRMATION_REQUIRED: User confirmation is required before placing the call.",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        // 2. Permission denied
        if (result.errorCode == "CALL_PERMISSION_REQUIRED" ||
            result.metadata.optString("status") == "PERMISSION_DENIED" ||
            result.status == ToolResultStatus.USER_ACTION_REQUIRED) {
            return VerificationResult(
                success = false,
                reason = "PERMISSION_DENIED: CALL_PHONE permission is required.",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        // 3. Tool execution failed
        if (!result.success) {
            return VerificationResult(
                success = false,
                reason = "CALL_FAILED: ${result.message ?: "Call operation failed"}",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        // 4. Verify telecom and device evidence with bounded polling
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        var hasCallEvidence = false
        var isInCallUi = false
        var isDialerOnly = false
        var isExplicitDialpad = false

        // Bounded observation window (up to 2000ms in 250ms steps)
        val maxChecks = 8
        for (i in 0 until maxChecks) {
            val isInTelecomCall = try {
                telecomManager?.isInCall == true
            } catch (e: SecurityException) {
                false
            }

            val isAudioInCall = audioManager?.let {
                it.mode == AudioManager.MODE_IN_CALL
            } ?: false

            val isTelephonyInCall = try {
                @Suppress("DEPRECATION")
                val state = telephonyManager?.callState
                state == TelephonyManager.CALL_STATE_OFFHOOK
            } catch (e: SecurityException) {
                false
            }

            val currentSnapshot = if (i == 0) snapshot else {
                val captured = DeviceContext.capture(context)
                if (captured.ui.packageName.isNotEmpty()) captured else snapshot
            }
            val fgPkg = currentSnapshot.ui.packageName.lowercase()
            val fgActivity = currentSnapshot.ui.activityName.lowercase()
            isInCallUi = fgPkg.contains("incallui") || 
                fgActivity.contains("incall") ||
                (fgPkg.contains("telecom") && !fgPkg.contains("dialer")) ||
                (fgPkg == "com.android.phone")

            // Dialer-only means the dialer app is on dialpad/keypad rather than in-call screen
            val isCurrentDialpad = fgActivity.contains("dialpad") || fgActivity.contains("dialtacts") ||
                currentSnapshot.ui.nodes.any { it.id?.contains("dialpad") == true || it.id?.contains("digits") == true }
            if (isCurrentDialpad) {
                isExplicitDialpad = true
            }
            isDialerOnly = (isExplicitDialpad || (fgPkg.contains("dialer") && !isInCallUi)) &&
                !isInTelecomCall && !isAudioInCall

            hasCallEvidence = isInTelecomCall || isAudioInCall || isTelephonyInCall || isInCallUi

            if (hasCallEvidence) {
                break
            }
            if (i < maxChecks - 1) {
                try { Thread.sleep(250) } catch (e: InterruptedException) { break }
            }
        }

        if (isDialerOnly && !hasCallEvidence && isExplicitDialpad) {
            return VerificationResult(
                success = false,
                reason = "DIALER_OPENED: Dialer was opened, but call was not placed.",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        if (hasCallEvidence) {
            return VerificationResult(
                success = true,
                reason = "CALL_PLACED",
                retryRecommended = false,
                snapshot = snapshot
            )
        }

        // When Intent.ACTION_CALL was successfully dispatched to Android system
        // but no active call-start evidence exists yet (telecom/audio/telephony not connected),
        // return non-completed CALL_REQUESTED status without triggering retries.
        return VerificationResult(
            success = false,
            reason = "CALL_REQUESTED: Call intent accepted by system, awaiting carrier connection.",
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
