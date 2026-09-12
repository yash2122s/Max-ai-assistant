package com.example.automation.engine

import android.content.Context
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.tools.ToolRegistry
import com.example.automation.tools.ToolResult
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.RetryEngine
import com.example.automation.verification.VerificationRegistry

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object ExecutionEngine {
    suspend fun execute(
        context: Context,
        request: ExecutionRequest,
        contextOutputs: Map<String, Map<String, Any?>> = emptyMap()
    ): ToolResult {
        val startedAt = System.currentTimeMillis()
        val executionId = request.executionId
        
        try {
            AutomationEventBus.publish(AutomationEvent.ExecutionStarted(executionId, request))
            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.QUEUED))

            // 1. Resolve & interpolate parameters against completed step outputs
            val effectiveRequest = if (contextOutputs.isNotEmpty()) {
                val paramMap = mutableMapOf<String, String>()
                for ((k, v) in request.arguments.entrySet()) {
                    if (v.isJsonPrimitive && v.asJsonPrimitive.isString) {
                        paramMap[k] = v.asString
                    }
                }
                when (val interpolation = ParameterInterpolationEngine.interpolate(paramMap, contextOutputs)) {
                    is InterpolationResult.Success -> {
                        val interpolatedArgs = request.arguments.deepCopy()
                        for ((k, v) in interpolation.interpolatedParams) {
                            interpolatedArgs.addProperty(k, v)
                        }
                        request.copy(arguments = interpolatedArgs)
                    }
                    is InterpolationResult.Failure -> {
                        val failResult = ToolResult(
                            success = false,
                            toolName = "InterpolationEngine",
                            errorCode = "UNRESOLVED_VARIABLE",
                            message = interpolation.message
                        )
                        AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, "InterpolationEngine", "UNRESOLVED_VARIABLE", interpolation.message))
                        AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
                        AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, false))
                        return failResult
                    }
                }
            } else {
                request
            }

            val tool = ToolRegistry.getToolForAction(effectiveRequest.action)
            if (tool == null) {
                val failResult = ToolResult(
                    success = false,
                    toolName = "UNKNOWN",
                    errorCode = "TOOL_NOT_FOUND",
                    message = "No tool matches action: ${effectiveRequest.action}"
                )
                AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, "UNKNOWN", "TOOL_NOT_FOUND", "No tool matches action: ${effectiveRequest.action}"))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
                AutomationEventBus.publish(AutomationEvent.ExecutionFinished(executionId, false))
                return failResult
            }

            if (tool is RequestValidator && !tool.validate(effectiveRequest)) {
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
            val targetParam = effectiveRequest.arguments.get("target")?.asString
                ?: effectiveRequest.arguments.get("text")?.asString
                ?: effectiveRequest.arguments.get("app_name")?.asString
            AutomationEventBus.publish(AutomationEvent.ActionStarted(executionId, effectiveRequest.action, targetParam))

            var retriesCount = 0
            var totalVerificationTime = 0L

            val finalResult = withTimeoutOrNull(25_000L) {
                RetryEngine.executeWithRetry(
                    context = context,
                    tool = tool,
                    request = effectiveRequest,
                    executeBlock = { attempt ->
                        currentCoroutineContext().ensureActive()
                        if (attempt > 1) {
                            retriesCount++
                        }
                        tool.execute(context, effectiveRequest)
                    },
                    verifyBlock = { toolResult ->
                        if (toolResult.status == com.example.automation.tools.ToolResultStatus.NEEDS_INPUT) {
                            // Needs input does not need post-execution verification
                            com.example.automation.verification.VerificationResult(
                                success = false,
                                reason = toolResult.needsInputQuestion ?: "User input required",
                                retryRecommended = false,
                                snapshot = DeviceContext.capture(context)
                            )
                        } else if (toolResult.verificationRequired) {
                            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.VERIFYING))
                            AutomationEventBus.publish(AutomationEvent.VerificationStarted(executionId, tool.name))
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
            } ?: ToolResult(
                success = false,
                toolName = tool.name,
                errorCode = "EXECUTION_TIMEOUT",
                message = "Tool '${tool.name}' timed out after 25 seconds."
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
