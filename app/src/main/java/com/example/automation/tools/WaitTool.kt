package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.actions.WaitAction
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.engine.ExecutionRequest
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.ScreenSummaryFormatter
import com.example.automation.ui.SignatureCalculator
import com.example.automation.ui.UiStateWaiter
import com.example.automation.verification.RetryPolicy
import com.example.service.JarvisAccessibilityService

/**
 * Dedicated tool for time-based wait steps in multi-step workflows.
 * Reports continuous countdown progress to AgentActivityTracker and AutomationEventBus,
 * responds immediately (<100ms) to cancellation tokens, and optionally gathers fresh UI state.
 */
class WaitTool(private val waitAction: WaitAction = WaitAction()) : Tool {
    companion object {
        fun durationMs(request: ExecutionRequest): Long {
            val args = request.arguments
            for (key in listOf("duration_ms", "durationMs")) {
                args.get(key)?.asString?.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
            }
            args.get("seconds")?.asString?.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0L) }
            val raw = listOf("duration", "time", "target", "text", "timeout").firstNotNullOfOrNull { args.get(it)?.asString }
            return WaitAction.parseDurationMs(raw).coerceIn(0L, 3_600_000L)
        }
    }
    private val TAG = "WaitTool"
    override val name: String = "wait"
    override val supportedActions: Set<String> = setOf("WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = true
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val args = request.arguments
        val rawTime = args.get("duration")?.asString
            ?: args.get("duration_ms")?.asString
            ?: args.get("durationMs")?.asString
            ?: args.get("seconds")?.asString
            ?: args.get("time")?.asString
            ?: args.get("target")?.asString
            ?: args.get("text")?.asString
            ?: args.get("timeout")?.asString
            ?: "5s"

        val durationMs = durationMs(request)
        Log.i(TAG, "Executing WaitTool: raw='$rawTime' -> resolved=${durationMs}ms (cancellable=true)")

        val service = JarvisAccessibilityService.instance
        val preSignature = SignatureCalculator.compute(service?.rootInActiveWindow)

        var lastReportedSec = -1L
        val completed = waitAction.executeWait(
            durationMs = durationMs,
            cancellationToken = request.cancellationToken
        ) { elapsedMs, remainingMs ->
            val remainingSec = (remainingMs + 999L) / 1000L
            if (remainingSec != lastReportedSec) {
                lastReportedSec = remainingSec
                val msg = "Waiting: ${remainingSec}s remaining..."
                AgentActivityTracker.addTrace("WaitTool", msg)
            }
        }

        if (!completed || request.cancellationToken?.isCancelled == true) {
            Log.w(TAG, "WaitTool was cancelled by user/cancellationToken")
            return ToolResult(
                success = false,
                toolName = name,
                errorCode = "WAIT_CANCELLED",
                message = "Wait was cancelled by user",
                verificationRequired = false
            )
        }

        // Closed-loop UI check if accessibility service is active
        val uiWait = if (service != null) {
            UiStateWaiter.waitForUiState(
                previousSignature = preSignature,
                expectedPackage = null,
                timeoutMs = 3000L
            )
        } else null

        val currentSummary = if (uiWait != null) {
            ScreenSummaryFormatter.format(
                ScreenGroundingEngine.scanTargets(uiWait.root),
                uiWait.root
            )
        } else ""

        return ToolResult(
            success = true,
            toolName = name,
            message = if (uiWait != null) {
                "Waited successfully for ${durationMs / 1000}s. UI settled: ${uiWait.reason}"
            } else {
                "Waited successfully for ${durationMs / 1000}s"
            },
            verificationRequired = false,
            currentStateSummary = currentSummary
        )
    }
}
