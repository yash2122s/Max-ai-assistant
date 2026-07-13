package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolResult
import kotlinx.coroutines.delay

object RetryEngine {
    private const val TAG = "RetryEngine"

    suspend fun executeWithRetry(
        context: Context,
        tool: Tool,
        request: ExecutionRequest,
        executeBlock: suspend (attempt: Int) -> ToolResult,
        verifyBlock: (result: ToolResult) -> VerificationResult
    ): ToolResult {
        val policy = tool.retryPolicy
        var attempt = 1
        var lastResult: ToolResult

        while (true) {
            if (attempt > 1) {
                AutomationEventBus.publish(AutomationEvent.RetryStarted(request.executionId, tool.name, attempt))
            }

            lastResult = executeBlock(attempt)
            
            if (request.cancellationToken.isCancelled) {
                return lastResult.copy(success = false, message = "Cancelled during execution")
            }

            val verificationResult = verifyBlock(lastResult)
            val updatedResult = lastResult.copy(
                verification = verificationResult,
                attemptCount = attempt
            )

            if (verificationResult.success) {
                if (attempt > 1) {
                    AutomationEventBus.publish(AutomationEvent.RetryFinished(request.executionId, tool.name, true))
                }
                return updatedResult
            }

            if (!updatedResult.retryable || !verificationResult.retryRecommended) {
                if (attempt > 1) {
                    AutomationEventBus.publish(AutomationEvent.RetryFinished(request.executionId, tool.name, false))
                }
                return updatedResult
            }

            val delayMs = calculateDelay(policy, attempt)
            if (delayMs < 0) {
                if (attempt > 1) {
                    AutomationEventBus.publish(AutomationEvent.RetryFinished(request.executionId, tool.name, false))
                }
                return updatedResult
            }

            Log.d(TAG, "Attempt $attempt failed. Delaying $delayMs ms before retry.")
            delay(delayMs)
            attempt++
        }
    }

    private fun calculateDelay(policy: RetryPolicy, attempt: Int): Long {
        return when (policy) {
            is RetryPolicy.NoRetry -> -1L
            is RetryPolicy.ImmediateRetry -> {
                if (attempt < policy.maxAttempts) 0L else -1L
            }
            is RetryPolicy.ExponentialBackoff -> {
                if (attempt < policy.maxAttempts) {
                    val multiplier = Math.pow(2.0, (attempt - 1).toDouble()).toLong()
                    policy.initialDelayMs * multiplier
                } else -1L
            }
            is RetryPolicy.WaitForUi -> {
                if (attempt < 2) policy.timeoutMs else -1L
            }
            is RetryPolicy.CompositeRetry -> {
                policy.policies.forEach { singlePolicy ->
                    val delay = calculateDelay(singlePolicy, attempt)
                    if (delay >= 0) return delay
                }
                -1L
            }
            else -> -1L
        }
    }
}
