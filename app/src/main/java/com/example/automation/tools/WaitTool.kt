package com.example.automation.tools

import android.content.Context
import android.util.Log
import com.example.automation.actions.WaitAction
import com.example.automation.activity.AgentActivityTracker
import com.example.automation.engine.ExecutionRequest
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.verification.RetryPolicy

/**
 * Dedicated tool for time-based wait steps in multi-step workflows.
 * Reports continuous countdown progress to AgentActivityTracker and AutomationEventBus,
 * and responds immediately (<100ms) to cancellation tokens.
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
                // Step indices belong to the orchestrator; countdown must not
                // rewrite a multi-step task as step 1/1.
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

        return ToolResult(
            success = true,
            toolName = name,
            message = "Waited successfully for ${durationMs / 1000}s",
            verificationRequired = false
        )
    }
}
