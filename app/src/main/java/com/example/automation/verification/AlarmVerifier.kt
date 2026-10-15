package com.example.automation.verification

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.tools.ToolResult

class AlarmVerifier : Verifier {
    override val supportedTools: Set<String> = setOf(
        "alarm", "timer", "reminder", "set_alarm", "set_timer", "set_reminder",
        "schedule_task", "cancel_task", "list_scheduled_tasks"
    )

    override fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult {
        val isSuccess = result.success
        return VerificationResult(
            success = isSuccess,
            reason = if (isSuccess) null else (result.message ?: "Failed to execute timer or alarm request"),
            retryRecommended = false,
            snapshot = snapshot
        )
    }
}
