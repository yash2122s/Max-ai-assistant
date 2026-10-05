package com.example.automation.verification

import android.content.Context
import android.util.Log
import com.example.automation.engine.ExecutionRequest
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.tools.Tool
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

object RetryEngine {
    private const val TAG = "RetryEngine"

    suspend fun executeWithRetry(
        context: Context,
        tool: Tool,
        request: ExecutionRequest,
        executeBlock: suspend (attempt: Int) -> ToolResult,
        verifyBlock: suspend (result: ToolResult) -> VerificationResult
    ): ToolResult {
        val policy = tool.retryPolicy
        var attempt = 1
        var lastResult: ToolResult

        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (request.cancellationToken.isCancelled) {
                return ToolResult.failure(tool.name, "EXECUTION_CANCELLED", "Task cancelled before dispatch")
            }
            if (attempt > 1) {
                AutomationEventBus.publish(AutomationEvent.RetryStarted(request.executionId, tool.name, attempt))
            }

            lastResult = executeBlock(attempt)
            
            if (request.cancellationToken.isCancelled) {
                return lastResult.copy(success = false, message = "Cancelled during execution")
            }

            val verificationResult = verifyBlock(lastResult)
            val finalSuccess = lastResult.success && verificationResult.success
            val isCallRequested = verificationResult.reason?.startsWith("CALL_REQUESTED") == true ||
                (lastResult.metadata.optString("status") == "CALL_REQUESTED" && !verificationResult.success)

            val updatedStatus = when {
                finalSuccess -> ToolResultStatus.SUCCESS
                isCallRequested -> ToolResultStatus.ACCEPTED
                lastResult.status == ToolResultStatus.NEEDS_INPUT -> ToolResultStatus.NEEDS_INPUT
                lastResult.status == ToolResultStatus.USER_ACTION_REQUIRED -> ToolResultStatus.USER_ACTION_REQUIRED
                lastResult.status == ToolResultStatus.PENDING_USER_ACTION -> ToolResultStatus.PENDING_USER_ACTION
                else -> ToolResultStatus.FAILURE
            }

            val updatedMetadata = org.json.JSONObject(lastResult.metadata.toString()).apply {
                put("executorSuccess", lastResult.success)
                if (verificationResult.reason?.startsWith("CALL_PLACED") == true) {
                    put("status", "CALL_PLACED")
                } else if (isCallRequested) {
                    put("status", "CALL_REQUESTED")
                }
            }

            val updatedResult = lastResult.copy(
                success = finalSuccess,
                status = updatedStatus,
                verification = verificationResult,
                attemptCount = attempt,
                metadata = updatedMetadata,
                message = if (!verificationResult.success && lastResult.success) {
                    verificationResult.reason ?: "Verification failed"
                } else {
                    lastResult.message
                },
                errorCode = if (!verificationResult.success && lastResult.success) {
                    if (isCallRequested) "CALL_REQUESTED" else "VERIFICATION_FAILED"
                } else {
                    lastResult.errorCode
                }
            )

            if (verificationResult.success) {
                if (attempt > 1) {
                    AutomationEventBus.publish(AutomationEvent.RetryFinished(request.executionId, tool.name, true))
                }
                return updatedResult
            }

            if (!updatedResult.retryable || !verificationResult.retryRecommended ||
                com.example.automation.engine.execution.DurableRecoveryCoordinator.isIrreversibleExternalAction(request.action)) {
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
