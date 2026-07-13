package com.example.automation.engine

import android.content.Context
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.tools.ToolRegistry
import com.example.automation.tools.ToolResult
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.RetryEngine
import com.example.automation.verification.VerificationRegistry

object ExecutionEngine {
    suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val startedAt = System.currentTimeMillis()
        val executionId = request.executionId
        
        try {
            AutomationEventBus.publish(AutomationEvent.ExecutionStarted(executionId, request))
            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.QUEUED))

            val tool = ToolRegistry.getToolForAction(request.action)
            if (tool == null) {
                val failResult = ToolResult(
                    success = false,
                    toolName = "UNKNOWN",
                    errorCode = "TOOL_NOT_FOUND",
                    message = "No tool matches action: ${request.action}"
                )
                AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, "UNKNOWN", "TOOL_NOT_FOUND", "No tool matches action: ${request.action}"))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
                AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, false))
                return failResult
            }

            if (tool is RequestValidator && !tool.validate(request)) {
                val failResult = ToolResult(
                    success = false,
                    toolName = tool.name,
                    errorCode = "VALIDATION_FAILED",
                    message = "Request validation failed for tool '${tool.name}'"
                )
                AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, tool.name, "VALIDATION_FAILED", "Request validation failed"))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
                AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, false))
                return failResult
            }

            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.RUNNING))
            AutomationEventBus.publish(AutomationEvent.ToolStarted(executionId, tool.name))

            var retriesCount = 0
            var totalVerificationTime = 0L

            val finalResult = RetryEngine.executeWithRetry(
                context = context,
                tool = tool,
                request = request,
                executeBlock = { attempt ->
                    if (attempt > 1) {
                        retriesCount++
                    }
                    tool.execute(context, request)
                },
                verifyBlock = { toolResult ->
                    if (toolResult.verificationRequired) {
                        AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.VERIFYING))
                        val verifyStart = System.currentTimeMillis()
                        
                        val verifier = VerificationRegistry.getVerifierForTool(tool.name)
                        val snapshot = DeviceContext.capture(context)
                        
                        val verificationResult = verifier?.verify(context, request, toolResult, snapshot)
                            ?: com.example.automation.verification.VerificationResult(
                                success = toolResult.success,
                                reason = if (toolResult.success) null else toolResult.message,
                                retryRecommended = !toolResult.success && toolResult.retryable,
                                snapshot = snapshot
                            )
                            
                        totalVerificationTime += (System.currentTimeMillis() - verifyStart)
                        
                        if (verificationResult.success) {
                            AutomationEventBus.publish(AutomationEvent.VerificationPassed(executionId, tool.name))
                        } else {
                            AutomationEventBus.publish(AutomationEvent.VerificationFailed(executionId, tool.name, verificationResult.reason ?: "Verification failed"))
                            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.RETRYING))
                        }
                        verificationResult
                    } else {
                        com.example.automation.verification.VerificationResult(
                            success = toolResult.success,
                            reason = if (toolResult.success) null else toolResult.message,
                            retryRecommended = false,
                            snapshot = DeviceContext.capture(context)
                        )
                    }
                }
            )

            val finishedAt = System.currentTimeMillis()
            val totalDuration = finishedAt - startedAt
            
            val metrics = ExecutionMetrics(
                startedAt = startedAt,
                finishedAt = finishedAt,
                retries = retriesCount,
                verificationTimeMs = totalVerificationTime,
                totalDurationMs = totalDuration
            )

            val completedResult = finalResult.copy(metrics = metrics)

            if (completedResult.success) {
                AutomationEventBus.publish(AutomationEvent.ToolFinished(executionId, tool.name, completedResult))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.SUCCEEDED))
                AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, true))
            } else {
                AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, tool.name, completedResult.errorCode ?: "EXECUTION_FAILED", completedResult.message ?: "Failed"))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
                AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, false))
            }

            return completedResult
        } catch (e: Exception) {
            android.util.Log.e("ExecutionEngine", "Uncaught exception in execute for action: ${request.action}", e)
            val failResult = ToolResult(
                success = false,
                toolName = "UNKNOWN",
                errorCode = "EXECUTION_EXCEPTION",
                message = e.message ?: "Uncaught exception",
                retryable = false
            )
            AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, "UNKNOWN", "EXECUTION_EXCEPTION", e.message ?: "Uncaught exception"))
            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
            AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, false))
            return failResult
        }
    }
}
