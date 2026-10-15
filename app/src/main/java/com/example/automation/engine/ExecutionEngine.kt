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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.example.automation.engine.state.AgentTaskStore
import com.example.automation.engine.truth.ExecutionTruthCoordinator
import java.util.UUID

object ExecutionEngine {
    suspend fun execute(
        context: Context,
        request: ExecutionRequest,
        contextOutputs: Map<String, Map<String, Any?>> = emptyMap()
    ): ToolResult {
        val ownsTask = request.source != ExecutionSource.PLANNER
        val effective = if (ownsTask) request.copy(taskId = request.taskId ?: UUID.randomUUID().toString(),
            stepId = request.stepId ?: "step_1") else request
        var store: AgentTaskStore? = null
        var created = false
        var cancelled = false
        var outcome: ToolResult? = null
        if (ownsTask) TaskCancellationCoordinator.registerActiveTask(effective.taskId!!, effective.cancellationToken)
        try {
            outcome = withTimeoutOrNull(executionBudgetMs(effective)) {
                currentCoroutineContext().ensureActive()
                if (effective.cancellationToken.isCancelled) {
                    return@withTimeoutOrNull ToolResult.failure("UNKNOWN", "EXECUTION_CANCELLED", "Task cancelled before dispatch")
                }
                if (ownsTask) {
                    store = AgentTaskStore(context)
                    // Approval callers already own the correlated task/step. Never replace it.
                    if (store!!.getTask(effective.taskId!!) == null) {
                        store!!.create(effective.taskId, effective.arguments.get("last_input_transcription")?.asString ?: effective.action,
                            listOf(com.example.automation.state.ActionStep(effective.stepId!!, effective.action)))
                    }
                    created = true
                    val existing = store!!.getStep(effective.taskId, effective.stepId!!)
                    val preStarted = existing?.status == "RUNNING" && existing.executionId == effective.executionId.toString()
                    // Approval/legacy adapters may already have marked this exact
                    // attempt RUNNING; do not rewind it to DISPATCHING.
                    for (state in if (preStarted) emptyList() else listOf("DISPATCHING", "RUNNING")) {
                        val transition = store!!.transitionStep(effective.taskId, effective.stepId!!,
                            effective.executionId.toString(), state, "ExecutionEngine")
                        check(transition is com.example.automation.engine.truth.TaskTransitionAuthority.TransitionResult.Success) {
                            "Dispatch state rejected: $transition"
                        }
                    }
                }
                executeInternal(context, effective, contextOutputs, store)
            } ?: ToolResult.failure("UNKNOWN", "EXECUTION_TIMEOUT", "Action deadline exceeded")
            return finishOutcome(effective, outcome!!, store.takeIf { created })
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            outcome = ToolResult.failure("UNKNOWN", "EXECUTION_CANCELLED", "Task cancelled during execution; no future steps will run")
            if (kotlinx.coroutines.currentCoroutineContext().isActive && effective.cancellationToken.isCancelled) {
                cancelled = false
                return finishOutcome(effective, outcome!!, store.takeIf { created })
            }
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ExecutionEngine", "Execution lifecycle failed: ${effective.action}", e)
            outcome = ToolResult.failure("UNKNOWN", "EXECUTION_EXCEPTION", e.message ?: "Execution lifecycle failed")
            return finishOutcome(effective, outcome!!, store.takeIf { created })
        } finally {
            if (cancelled) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5000L) { finishOutcome(effective, outcome!!, store.takeIf { created }) }
                }
            }
            if (ownsTask) TaskCancellationCoordinator.unregisterActiveTask(effective.taskId)
        }
    }

    private suspend fun finishOutcome(request: ExecutionRequest, result: ToolResult, store: AgentTaskStore?): ToolResult {
        val cancelled = request.cancellationToken.isCancelled || result.errorCode == "EXECUTION_CANCELLED" || result.errorCode == "WAIT_CANCELLED"
        val prior = ExecutionTruthCoordinator.getLastExecution()?.takeIf { it.executionId == request.executionId.toString() }
        val status = when {
            cancelled -> "CANCELLED"
            result.metadata.optString("status") == "CONFIRMATION_REQUIRED" -> "WAITING_FOR_APPROVAL"
            result.status == com.example.automation.tools.ToolResultStatus.NEEDS_INPUT -> "WAITING_FOR_INPUT"
            result.status == com.example.automation.tools.ToolResultStatus.USER_ACTION_REQUIRED -> "BLOCKED"
            result.status == com.example.automation.tools.ToolResultStatus.ACCEPTED -> "CALL_REQUESTED"
            result.status == com.example.automation.tools.ToolResultStatus.ATTEMPTED_UNVERIFIED -> "UNVERIFIED"
            result.status == com.example.automation.tools.ToolResultStatus.OUTCOME_UNKNOWN -> "UNVERIFIED"
            result.isVerifiedSuccess -> prior?.status ?: "COMPLETED"
            result.errorCode == "VERIFICATION_FAILED" || result.success || result.errorCode == "EXECUTION_TIMEOUT" -> "UNVERIFIED"
            else -> "FAILED"
        }
        var completed = if (cancelled) result.copy(success = false, status = com.example.automation.tools.ToolResultStatus.FAILURE,
            errorCode = "EXECUTION_CANCELLED", message = "Task cancelled; no future steps will run") else result
        if (store != null) {
            try {
                check(withTimeoutOrNull(5000L) {
                val taskId = request.taskId!!
                val stepId = request.stepId!!
                if (status == "WAITING_FOR_INPUT") {
                    store.recordStepWaitingInput(taskId, stepId, request.executionId.toString(), result.message ?: "Input required")
                } else if (status == "WAITING_FOR_APPROVAL") {
                    store.recordStepWaitingApproval(taskId, stepId, request.executionId.toString(), request.action)
                } else {
                    val targetStatus = if (completed.isVerifiedSuccess) "SUCCEEDED" else if (status == "CALL_REQUESTED") "UNVERIFIED" else status
                    val transition = store.transitionStep(taskId, stepId, request.executionId.toString(), targetStatus, "ExecutionEngine",
                        failureReason = if (!completed.isVerifiedSuccess) completed.message else null,
                        verificationEvidence = completed.verification?.reason ?: completed.message ?: "${request.action} verified by ${completed.toolName}",
                        executorResultJson = com.google.gson.Gson().toJson(completed))
                    check(transition is com.example.automation.engine.truth.TaskTransitionAuthority.TransitionResult.Success) { "Terminal state rejected: $transition" }
                }
                true
                } == true) { "Terminal persistence deadline exceeded" }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                android.util.Log.e("ExecutionEngine", "Could not persist terminal execution", e)
                completed = ToolResult.failure(result.toolName, "TERMINAL_PERSISTENCE_FAILED", "Execution result could not be persisted: ${e.message}")
            }
        }
        completed = completed.copy(metadata = org.json.JSONObject(completed.metadata.toString()).apply {
            put("taskId", request.taskId ?: org.json.JSONObject.NULL)
            put("stepId", request.stepId ?: org.json.JSONObject.NULL)
            put("executionId", request.executionId.toString())
            put("terminalStatus", if (completed.errorCode == "TERMINAL_PERSISTENCE_FAILED") "UNVERIFIED" else status)
        })
        ExecutionTruthCoordinator.recordExecution(taskId = request.taskId, stepId = request.stepId,
            executionId = request.executionId.toString(), actionName = request.action,
            executorSuccess = prior?.executorSuccess ?: result.metadata.optBoolean("executorSuccess", result.success),
            verifiedSuccess = completed.isVerifiedSuccess,
            status = if (completed.errorCode == "TERMINAL_PERSISTENCE_FAILED") "UNVERIFIED" else status,
            failureReason = if (completed.isVerifiedSuccess) null else completed.message, message = completed.message)
        if (request.source != ExecutionSource.PLANNER) {
            if (cancelled) AutomationEventBus.publish(AutomationEvent.ExecutionCancelled(request.executionId, completed.message))
            else AutomationEventBus.publish(AutomationEvent.ExecutionFinished(request.executionId, completed.isVerifiedSuccess))
        }
        android.util.Log.i("ExecutionEngine", "[EXECUTION_RESULT] taskId=${request.taskId} stepId=${request.stepId} executionId=${request.executionId} action=${request.action} status=$status verified=${completed.isVerifiedSuccess}")
        return completed
    }

    private fun executionBudgetMs(request: ExecutionRequest): Long {
        if (request.action.uppercase() !in setOf("WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP")) return 25_000L
        return com.example.automation.tools.WaitTool.durationMs(request).coerceIn(0L, 3_600_000L) + 5000L
    }

    private suspend fun executeInternal(
        context: Context, request: ExecutionRequest,
        contextOutputs: Map<String, Map<String, Any?>>, store: AgentTaskStore?
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
                return failResult
            }

            if (!tool.validate(effectiveRequest)) {
                val failResult = ToolResult(
                    success = false,
                    toolName = tool.name,
                    errorCode = "VALIDATION_FAILED",
                    message = "Request validation failed for tool '${tool.name}'"
                )
                AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, tool.name, "VALIDATION_FAILED", "Request validation failed"))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
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

            val finalResult = withTimeoutOrNull(executionBudgetMs(effectiveRequest)) {
                RetryEngine.executeWithRetry(
                    context = context,
                    tool = tool,
                    request = effectiveRequest,
                    executeBlock = { attempt ->
                        currentCoroutineContext().ensureActive()
                        if (effectiveRequest.cancellationToken.isCancelled) throw kotlinx.coroutines.CancellationException("Task cancelled before tool dispatch")
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
                            // Verification uses the same identity and legal transition as dispatch.
                            store?.transitionStep(request.taskId!!, request.stepId!!, executionId.toString(), "VERIFYING", "ExecutionEngine")
                            val verifyStart = System.currentTimeMillis()
                            
                            val verifier = VerificationRegistry.getVerifierForTool(tool.name)

                            val snapshot = DeviceContext.capture(context)
                            
                            val verificationResult = verifier?.verify(context, effectiveRequest, toolResult, snapshot)
                                ?: com.example.automation.verification.VerificationResult(
                                    success = false,
                                    reason = "Verification required but no verifier is registered for ${tool.name}",
                                    retryRecommended = false,
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
                            toolResult.verification ?: com.example.automation.verification.VerificationResult(
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

            if (store != null && completedResult.isVerifiedSuccess) {
                store.transitionStep(request.taskId!!, request.stepId!!, executionId.toString(), "VERIFYING", "ExecutionEngine")
            }

            // Authoritative ExecutionTruth recording
            val isVerified = completedResult.isVerifiedSuccess
            val isCallRequested = completedResult.verification?.reason?.startsWith("CALL_REQUESTED") == true ||
                                  completedResult.errorCode == "CALL_REQUESTED" ||
                                  completedResult.status == com.example.automation.tools.ToolResultStatus.ACCEPTED
            val isCallPlaced = completedResult.verification?.reason?.startsWith("CALL_PLACED") == true ||
                               completedResult.metadata.optString("status") == "CALL_PLACED"

            val truthStatus = when {
                isCallPlaced -> "CALL_PLACED"
                isCallRequested -> "CALL_REQUESTED"
                completedResult.metadata.optString("status") == "CONFIRMATION_REQUIRED" -> "WAITING_FOR_APPROVAL"
                completedResult.status == com.example.automation.tools.ToolResultStatus.NEEDS_INPUT -> "WAITING_FOR_INPUT"
                isVerified -> "COMPLETED"
                completedResult.success -> "UNVERIFIED"
                else -> "FAILED"
            }

            com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(
                taskId = effectiveRequest.taskId,
                stepId = effectiveRequest.stepId,
                executionId = executionId.toString(),
                actionName = effectiveRequest.action,
                executorSuccess = completedResult.metadata.optBoolean("executorSuccess", completedResult.success) || isCallRequested,
                verifiedSuccess = isVerified && completedResult.success,
                status = truthStatus,
                failureReason = if (!completedResult.success && !isCallRequested) (completedResult.message ?: completedResult.errorCode) else null,
                message = completedResult.message
            )

            if (isVerified) {
                AutomationEventBus.publish(AutomationEvent.ToolFinished(executionId, tool.name, completedResult))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.SUCCEEDED))
            } else {
                AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, tool.name, completedResult.errorCode ?: "EXECUTION_FAILED", completedResult.message ?: "Failed"))
                AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
            }

            return completedResult
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ExecutionEngine", "Uncaught exception in execute for action: ${request.action}", e)
            val failResult = ToolResult(
                success = false,
                toolName = "UNKNOWN",
                errorCode = "EXECUTION_EXCEPTION",
                message = e.message ?: "Uncaught exception",
                retryable = false
            )
            com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(
                taskId = request.taskId,
                stepId = request.stepId,
                executionId = executionId.toString(),
                actionName = request.action,
                executorSuccess = false,
                verifiedSuccess = false,
                failureReason = e.message ?: "Uncaught execution exception",
                message = e.message
            )
            AutomationEventBus.publish(AutomationEvent.ToolFailed(executionId, "UNKNOWN", "EXECUTION_EXCEPTION", e.message ?: "Uncaught exception"))
            AutomationEventBus.publish(AutomationEvent.StateChanged(executionId, ExecutionState.FAILED))
            return failResult
        }
    }
}
