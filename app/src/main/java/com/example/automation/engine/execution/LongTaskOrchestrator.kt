package com.example.automation.engine.execution

import android.content.Context
import android.util.Log
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.ActionQueue
import com.example.automation.engine.AgentExecutionResult
import com.example.automation.engine.CancellationToken
import com.example.automation.engine.DependencyGraphResolver
import com.example.automation.engine.ExecutionEngine
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.ExecutionSource
import com.example.automation.engine.GoalPlanner
import com.example.automation.engine.PlanValidationResult
import com.example.automation.engine.ReEvalDecision
import com.example.automation.engine.ReEvaluationEngine
import com.example.automation.engine.WaitingForInputState
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.engine.state.AgentTask
import com.example.automation.engine.state.TaskStatus
import com.example.automation.engine.state.TaskStep
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.automation.verification.LightweightUiObserver
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.memory.skill.SkillMemoryRepository
import com.example.service.JarvisAccessibilityService
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Result of executing an [AgentTask].
 */
data class TaskExecutionResult(
    val success: Boolean,
    val task: AgentTask,
    val executedSteps: Int,
    val totalSteps: Int,
    val message: String? = null,
    val isCancelled: Boolean = false
)

/**
 * Authoritative Single Owner of Long-Horizon Task and Step Progression for MAX AI.
 *
 * Invariant: Only LongTaskOrchestrator advances step indices and controls task state.
 * Implements the strict closed-loop execution pattern:
 * OBSERVE -> GROUND -> AUTHORIZE -> EXECUTE -> WAIT -> RE-OBSERVE -> VERIFY -> RECOVER/REPLAN -> NEXT STEP
 */
class LongTaskOrchestrator(
    private val context: Context? = null,
    private val capabilityRouter: CapabilityRouter? = null,
    private val verificationEngine: VerificationEngine? = null,
    private val recoveryEngine: TaskRecoveryEngine = TaskRecoveryEngine(),
    private val taskPlanner: TaskPlanner = TaskPlanner()
) {
    companion object {
        private const val TAG = "LongTaskOrchestrator"
        const val MAX_STEP_RETRIES = 2
        const val MAX_REPLANS = 3
        const val DEFAULT_PLAN_TIMEOUT_MS = 180_000L
        const val MAX_TOTAL_PLAN_STEPS = 20
    }

    /**
     * Secondary constructor for legacy callers omitting context.
     */
    constructor(
        capabilityRouter: CapabilityRouter,
        verificationEngine: VerificationEngine,
        recoveryEngine: TaskRecoveryEngine,
        taskPlanner: TaskPlanner
    ) : this(
        context = null,
        capabilityRouter = capabilityRouter,
        verificationEngine = verificationEngine,
        recoveryEngine = recoveryEngine,
        taskPlanner = taskPlanner
    )

    private fun resolveContext(): Context? {
        return context ?: JarvisAccessibilityService.instance?.applicationContext
    }

    /**
     * Executes an [AgentTask] containing declarative [TaskStep]s.
     */
    suspend fun executeTask(
        task: AgentTask,
        cancellationToken: CancellationToken? = null
    ): TaskExecutionResult = withContext(Dispatchers.IO) {
        val appContext = resolveContext()
        task.status = TaskStatus.RUNNING
        var executedStepsCount = 0
        var replanCount = 0
        val totalInitialSteps = task.steps.size
        val deadline = System.nanoTime() + DEFAULT_PLAN_TIMEOUT_MS * 1_000_000L

        Log.d(TAG, "Starting executeTask() for task '${task.id}' (${task.steps.size} steps) - goal: '${task.goal}'")

        val taskStore = appContext?.let { com.example.automation.engine.state.AgentTaskStore(it) }
        val effectiveToken = cancellationToken ?: CancellationToken()
        try {
        withTimeoutOrNull(5000L) { taskStore?.createFromStepRecords(
            taskId = task.id,
            goal = task.goal,
            steps = task.steps.mapIndexed { index, s ->
                com.example.data.local.AgentTaskStepRecord(
                    taskId = task.id,
                    stepId = s.id,
                    actionType = s.action::class.simpleName ?: "ACTION",
                    stepIndex = index,
                    status = "READY",
                    payloadJson = com.google.gson.Gson().toJson(s),
                    updatedAt = System.currentTimeMillis()
                )
            }
        ); true } ?: error("Task persistence initialization deadline exceeded")

        val router = capabilityRouter ?: (appContext?.let { CapabilityRouter(it) })
        val verifier = verificationEngine ?: (appContext?.let { VerificationEngine(it) })

        com.example.automation.engine.TaskCancellationCoordinator.registerActiveTask(task.id, effectiveToken)
        if (appContext != null) {
            com.example.automation.service.TaskExecutionForegroundService.start(
                appContext,
                task.id,
                task.goal,
                task.steps.size
            )
        }

        while (task.status == TaskStatus.RUNNING && task.currentStepIndex < task.steps.size) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() >= deadline) {
                taskStore?.terminatePending(task.id, "UNVERIFIED", "Task deadline exceeded; verify in-flight effects before recovery")
                task.status = TaskStatus.FAILED
                return@withContext TaskExecutionResult(false, task, executedStepsCount, task.steps.size, "Task deadline exceeded")
            }
            // 1. Check Cancellation & Runtime Status
            if (appContext != null && !MaxRuntimeManager.isMaxEnabled(appContext)) {
                Log.w(TAG, "Task cancelled: MAX runtime is disabled")
                task.status = TaskStatus.CANCELLED
                taskStore?.terminatePending(task.id, "CANCELLED", "MAX is OFF")
                return@withContext TaskExecutionResult(
                    success = false,
                    task = task,
                    executedSteps = executedStepsCount,
                    totalSteps = totalInitialSteps,
                    message = "MAX is OFF",
                    isCancelled = true
                )
            }

            if (effectiveToken.isCancelled) {
                Log.w(TAG, "Task cancelled via CancellationToken")
                task.status = TaskStatus.CANCELLED
                taskStore?.terminatePending(task.id, "CANCELLED", "Execution cancelled")
                return@withContext TaskExecutionResult(
                    success = false,
                    task = task,
                    executedSteps = executedStepsCount,
                    totalSteps = totalInitialSteps,
                    message = "Execution cancelled",
                    isCancelled = true
                )
            }

            val currentStep = task.getCurrentStep() ?: break

            // 2. Pre-Authorize Step
            val actionName = currentStep.action::class.simpleName ?: "ACTION"
            val auth = ActionAuthorizer.authorizeAction(task.goal, actionName, currentStep.id)
            if (!auth.authorized) {
                Log.e(TAG, "Step '${currentStep.id}' rejected by ActionAuthorizer: ${auth.reason}")
                currentStep.status = "FAILED"
                currentStep.failureReason = "Unauthorized action: ${auth.reason}"
                task.status = TaskStatus.FAILED
                taskStore?.recordStepResult(task.id, currentStep.id, UUID.randomUUID().toString(), executorSuccess = false, verifiedSuccess = false, failureReason = currentStep.failureReason)
                taskStore?.deriveAndPersistTaskCompletion(task.id)
                return@withContext TaskExecutionResult(
                    success = false,
                    task = task,
                    executedSteps = executedStepsCount,
                    totalSteps = totalInitialSteps,
                    message = "Unauthorized action: ${auth.reason}"
                )
            }

            // 3. Pre-Observation (Fresh Screen State)
            if (appContext != null) {
                LightweightUiObserver.observe(appContext)
            }

            val stepExecId = UUID.randomUUID().toString()
            taskStore?.recordStepStarted(task.id, currentStep.id, stepExecId)

            if (appContext != null) {
                com.example.automation.service.TaskExecutionForegroundService.updateProgress(
                    context = appContext,
                    stepIndex = task.currentStepIndex + 1,
                    totalSteps = task.steps.size,
                    actionType = actionName,
                    description = currentStep.id
                )
            }

            // 4. Execute Step
            Log.d(TAG, "Executing step [${task.currentStepIndex + 1}/${task.steps.size}]: '${currentStep.id}' ($actionName)")
            val executionResult = withTimeoutOrNull(25_000L) { router?.executeStep(currentStep, task.context) } ?: ExecutionResult(success = false)
            if (effectiveToken.isCancelled) throw kotlinx.coroutines.CancellationException("Stopped during step '${currentStep.id}'")

            // 5. Adaptive Post-Transition Settling Delay

            // 6. Re-Observe Post-Action
            val postObs = if (appContext != null) LightweightUiObserver.observe(appContext) else null

            // 7. Verify Expected Outcome
            val isVerified = if (executionResult.success && currentStep.expectedOutcome != null && verifier != null) {
                verifier.verify(currentStep.expectedOutcome)
            } else {
                executionResult.success && !executionResult.verificationEvidence.isNullOrBlank()
            }

            if (isVerified) {
                // Advance state cleanly (Single Owner Invariant)
                currentStep.status = "SUCCESS"
                taskStore?.recordStepResult(task.id, currentStep.id, stepExecId, executorSuccess = executionResult.success, verifiedSuccess = true,
                    verificationEvidence = executionResult.verificationEvidence ?: "Expected outcome verified: ${currentStep.expectedOutcome}; package=${postObs?.foregroundPackage}")
                task.context.updateState(
                    stepId = currentStep.id,
                    app = executionResult.activePackage ?: postObs?.foregroundPackage,
                    screen = executionResult.activeScreen
                )
                executedStepsCount++
                task.advance(lastStepSuccess = true)
                continue
            }

            currentStep.status = "FAILED"
            currentStep.failureReason = if (!executionResult.success) "Action execution failed" else "Verification failed"

            if (executionResult.success || DurableRecoveryCoordinator.isIrreversibleExternalAction(actionName)) {
                taskStore?.recordStepResult(task.id, currentStep.id, stepExecId, executorSuccess = executionResult.success,
                    verifiedSuccess = false, unverified = true, failureReason = "Outcome unverified; automatic replay blocked")
                break
            }

            // 8. Bounded Local Recovery
            task.status = TaskStatus.RECOVERING
            Log.w(TAG, "Step '${currentStep.id}' verification failed. Attempting local recovery...")
            val recovered = if (appContext != null && currentStep.retryCount < currentStep.maxRetries) {
                currentStep.retryCount++
                recoveryEngine.attemptRecovery(appContext, currentStep, task.context)
            } else {
                false
            }

            if (recovered) {
                Log.i(TAG, "Local recovery succeeded for step '${currentStep.id}'. Retrying step.")
                task.status = TaskStatus.RUNNING
                continue // Re-attempt same step index with fresh state
            }

            // 9. Replanning (Local recovery exhausted)
            if (replanCount < MAX_REPLANS) {
                replanCount++
                Log.w(TAG, "Attempting dynamic replan (attempt $replanCount/$MAX_REPLANS) for goal: '${task.goal}'")
                val replanSuccess = taskPlanner.replanTask(task, appContext)
                if (replanSuccess) {
                    task.status = TaskStatus.RUNNING
                    continue
                }
            }

            Log.e(TAG, "Step '${currentStep.id}' permanently failed and replanning failed. Aborting task.")
            currentStep.status = "FAILED"
            task.status = TaskStatus.FAILED
            taskStore?.recordStepResult(task.id, currentStep.id, stepExecId, executorSuccess = executionResult.success, verifiedSuccess = false, failureReason = currentStep.failureReason)

            // Mark any remaining steps as NOT_EXECUTED
            val nextIndex = task.currentStepIndex + 1
            if (nextIndex < task.steps.size) {
                for (i in nextIndex until task.steps.size) {
                    task.steps[i].status = "NOT_EXECUTED"
                    task.steps[i].failureReason = "Aborted due to failure in step '${currentStep.id}'"
                    taskStore?.updateStep(
                        taskId = task.id,
                        step = ActionStep(stepId = task.steps[i].id, actionType = task.steps[i].action::class.simpleName ?: "ACTION"),
                        stepIndex = i,
                        status = "NOT_EXECUTED",
                        failureReason = "Aborted due to failure in step '${currentStep.id}'"
                    )
                }
            }
            break
        }

        // Authoritative parent status derived strictly from child steps
        val allStepsSucceeded = task.steps.isNotEmpty() && task.steps.all { it.status == "SUCCESS" } && executedStepsCount == task.steps.size
        task.status = if (allStepsSucceeded) TaskStatus.COMPLETED else TaskStatus.FAILED
        val persistedTask = taskStore?.deriveAndPersistTaskCompletion(task.id)
        if (persistedTask != null && persistedTask.status != "COMPLETED") task.status = TaskStatus.FAILED

        val isSuccess = task.status == TaskStatus.COMPLETED
        Log.d(TAG, "Task execution finished with status: ${task.status} ($executedStepsCount/${task.steps.size} steps)")
        AutomationEventBus.publish(AutomationEvent.ExecutionFinished(UUID.randomUUID(), success = isSuccess))

        return@withContext TaskExecutionResult(
            success = isSuccess,
            task = task,
            executedSteps = executedStepsCount,
            totalSteps = task.steps.size,
            message = if (isSuccess) "Task completed successfully" else "Task failed"
        )
        } catch (e: kotlinx.coroutines.CancellationException) {
            task.status = TaskStatus.CANCELLED
            withContext(kotlinx.coroutines.NonCancellable) {
                withTimeoutOrNull(5000L) { taskStore?.terminatePending(task.id, "CANCELLED", "Execution cancelled") }
            }
            AutomationEventBus.publish(AutomationEvent.ExecutionCancelled(UUID.randomUUID(), "Task cancelled"))
            if (!currentCoroutineContext().isActive) throw e
            TaskExecutionResult(false, task, executedStepsCount, task.steps.size, "Task cancelled", isCancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "Task lifecycle exception: taskId=${task.id}", e)
            task.status = TaskStatus.FAILED
            withTimeoutOrNull(5000L) { taskStore?.terminatePending(task.id, "FAILED", e.message ?: "Execution exception") }
            AutomationEventBus.publish(AutomationEvent.ExecutionFinished(UUID.randomUUID(), false))
            TaskExecutionResult(false, task, executedStepsCount, task.steps.size, e.message ?: "Execution exception")
        } finally {
            com.example.automation.engine.TaskCancellationCoordinator.unregisterActiveTask(task.id)
            if (appContext != null) {
                com.example.automation.service.TaskExecutionForegroundService.stop(appContext)
            }
        }
    }

    /**
     * Authoritative plan execution loop for ActionStep sequences generated by planners.
     * Replaces GoalPlanner's isolated step loop and enforces the single owner invariant.
     */
    suspend fun executePlan(
        context: Context,
        goalPrompt: String,
        steps: List<ActionStep>,
        cancellationToken: CancellationToken = CancellationToken(),
        planningProvider: com.example.network.provider.LLMPlanningProvider? = null
    ): AgentExecutionResult = withContext(Dispatchers.IO) {
        var initialTotalSteps = steps.size
        var executedStepsCount = 0
        var activeReEvalCount = 0
        val stepResults = mutableListOf<ToolResult>()
        val completedSteps = mutableListOf<ActionStep>()
        val completedStepIds = mutableSetOf<String>()
        val executionOutputs = mutableMapOf<String, MutableMap<String, Any?>>()
        val actionQueue = ActionQueue().apply { replaceQueue(steps) }

        val validation = DependencyGraphResolver.validatePlan(steps)
        if (validation is PlanValidationResult.Invalid) {
            return@withContext AgentExecutionResult(
                success = false,
                goalPrompt = goalPrompt,
                executedStepsCount = 0,
                totalStepsCount = 0,
                errorMessage = validation.message
            )
        }

        Log.d(TAG, "LongTaskOrchestrator.executePlan() started for goal: '$goalPrompt' (${steps.size} steps)")

        val planTaskId = UUID.randomUUID().toString()
        val taskStore = com.example.automation.engine.state.AgentTaskStore(context)

        val dynamicWaitMs = steps.filter { it.actionType in setOf("WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP") }
            .sumOf { com.example.automation.actions.WaitAction.parseDurationMs(it.targetText ?: it.params["duration"]) }
        val dynamicPlanTimeoutMs = DEFAULT_PLAN_TIMEOUT_MS + dynamicWaitMs


        suspend fun installReplan(updatedSteps: List<ActionStep>): Boolean {
            if (activeReEvalCount >= MAX_REPLANS || updatedSteps.isEmpty()) return false
            val combined = completedSteps + updatedSteps
            if (combined.size > MAX_TOTAL_PLAN_STEPS || DependencyGraphResolver.validatePlan(combined) !is PlanValidationResult.Valid) return false
            taskStore?.replacePendingPlan(planTaskId, updatedSteps, completedStepIds)
            activeReEvalCount++
            initialTotalSteps = combined.size
            actionQueue.replaceQueue(updatedSteps)
            return true
        }

        try {
        val result = withTimeoutOrNull(dynamicPlanTimeoutMs) {
            taskStore.create(planTaskId, goalPrompt, steps)
            com.example.automation.engine.TaskCancellationCoordinator.registerActiveTask(planTaskId, cancellationToken)
            com.example.automation.service.TaskExecutionForegroundService.start(context, planTaskId, goalPrompt, steps.size)
            while (!actionQueue.isEmpty()) {
                // 1. Cancellation & MAX Status Check
                if (!MaxRuntimeManager.isMaxEnabled(context)) {
                    Log.w(TAG, "Plan aborted: MAX is disabled")
                    taskStore.terminatePending(planTaskId, "CANCELLED", "MAX is OFF")
                    return@withTimeoutOrNull AgentExecutionResult(
                        success = false,
                        goalPrompt = goalPrompt,
                        executedStepsCount = executedStepsCount,
                        totalStepsCount = initialTotalSteps,
                        errorMessage = "MAX is OFF",
                        isCancelled = true
                    )
                }

                if (cancellationToken.isCancelled || !currentCoroutineContext().isActive) {
                    Log.w(TAG, "Plan execution cancelled")
                    taskStore.terminatePending(planTaskId, "CANCELLED", "Plan execution was cancelled.")
                    val cancelId = UUID.randomUUID()
                    AutomationEventBus.publish(
                        AutomationEvent.ExecutionCancelled(cancelId, "Plan execution was cancelled.")
                    )
                    return@withTimeoutOrNull AgentExecutionResult(
                        success = false,
                        goalPrompt = goalPrompt,
                        executedStepsCount = executedStepsCount,
                        totalStepsCount = initialTotalSteps,
                        errorMessage = "Plan execution was cancelled.",
                        stepResults = stepResults,
                        isCancelled = true
                    )
                }

                // 2. Safety Limit
                if (executedStepsCount >= MAX_TOTAL_PLAN_STEPS) {
                    Log.e(TAG, "Safety limit reached: Exceeded maximum allowed steps ($MAX_TOTAL_PLAN_STEPS)")
                    return@withTimeoutOrNull AgentExecutionResult(
                        success = false,
                        goalPrompt = goalPrompt,
                        executedStepsCount = executedStepsCount,
                        totalStepsCount = initialTotalSteps,
                        errorMessage = "Exceeded safety limit of $MAX_TOTAL_PLAN_STEPS steps.",
                        stepResults = stepResults
                    )
                }

                // 3. Dequeue Next Step
                val step = actionQueue.dequeueReadyStep(completedStepIds)
                if (step == null) {
                    if (actionQueue.isEmpty()) break
                    val blockedRemaining = actionQueue.getActiveSteps()
                    Log.w(TAG, "${blockedRemaining.size} steps blocked by dependencies. Evaluating re-plan.")
                    val dummyFailed = blockedRemaining.first()
                    val dummyResult = ToolResult.failure(
                        toolName = dummyFailed.actionType,
                        errorCode = "DEPENDENCY_BLOCKED",
                        message = "Step '${dummyFailed.stepId}' blocked"
                    )
                    val decision = ReEvaluationEngine.evaluate(
                        goal = goalPrompt,
                        failedStep = dummyFailed,
                        toolResult = dummyResult,
                        worldState = WorldStateManager.getCurrentState(),
                        completedSteps = completedSteps.toList(),
                        remainingSteps = blockedRemaining,
                        executionOutputs = executionOutputs,
                        reEvalCount = activeReEvalCount,
                        planningProvider = planningProvider,
                        context = context
                    )
                    when (decision) {
                        is ReEvalDecision.CONTINUE -> break
                        is ReEvalDecision.MODIFY -> {
                            if (!installReplan(decision.updatedSteps)) break
                            continue
                        }
                        is ReEvalDecision.ABANDON -> {
                            return@withTimeoutOrNull AgentExecutionResult(
                                success = false,
                                goalPrompt = goalPrompt,
                                executedStepsCount = executedStepsCount,
                                totalStepsCount = initialTotalSteps,
                                errorMessage = "Plan blocked: ${decision.reason}",
                                stepResults = stepResults
                            )
                        }
                    }
                }

                // 4. Pre-Authorize Step
                val targetText = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.params["target"] ?: step.params["text"]
                val auth = ActionAuthorizer.authorizeAction(goalPrompt, step.actionType, targetText)
                if (!auth.authorized) {
                    val unauthResult = ToolResult.failure(
                        toolName = step.actionType,
                        errorCode = "UNAUTHORIZED_STEP",
                        message = "Step '${step.stepId}' unauthorized: ${auth.reason}"
                    )
                    stepResults.add(unauthResult)
                    return@withTimeoutOrNull AgentExecutionResult(
                        success = false,
                        goalPrompt = goalPrompt,
                        executedStepsCount = executedStepsCount,
                        totalStepsCount = initialTotalSteps,
                        failedStepId = step.stepId,
                        failedActionType = step.actionType,
                        errorMessage = "Unauthorized step: ${auth.reason}",
                        stepResults = stepResults
                    )
                }

                // 5. Pre-Observation
                LightweightUiObserver.observe(context)

                // 6. Build & Execute Step via ExecutionEngine
                val request = ExecutionRequest.fromStep(step, ExecutionSource.PLANNER, cancellationToken, planTaskId)
                val actionName = request.action

                val execId = request.executionId
                taskStore?.recordStepStarted(planTaskId, step.stepId, execId.toString())
                AutomationEventBus.publish(
                    AutomationEvent.StepProgress(
                        executionId = execId,
                        stepIndex = executedStepsCount + 1,
                        totalSteps = initialTotalSteps,
                        actionType = step.actionType,
                        targetText = targetText,
                        status = "RUNNING"
                    )
                )

                com.example.automation.service.TaskExecutionForegroundService.updateProgress(
                    context = context,
                    stepIndex = executedStepsCount + 1,
                    totalSteps = initialTotalSteps,
                    actionType = step.actionType,
                    description = targetText ?: ""
                )

                var toolResult = ExecutionEngine.execute(context, request, executionOutputs)
                stepResults.add(toolResult)
                if (cancellationToken.isCancelled || toolResult.errorCode == "EXECUTION_CANCELLED") {
                    taskStore.terminatePending(planTaskId, "CANCELLED", "Cancelled during step '${step.stepId}' (${step.actionType}); remaining steps not executed")
                    AutomationEventBus.publish(AutomationEvent.ExecutionCancelled(execId, "Cancelled during ${step.actionType}"))
                    return@withTimeoutOrNull AgentExecutionResult(false, goalPrompt, executedStepsCount, initialTotalSteps,
                        errorMessage = "Cancelled during ${step.actionType}; remaining steps not executed", stepResults = stepResults, isCancelled = true)
                }

                // 7. Handle NeedsInput
                if (toolResult.status == ToolResultStatus.NEEDS_INPUT) {
                    val question = toolResult.needsInputQuestion ?: toolResult.message ?: "Input required"
                    Log.i(TAG, "Step ${step.stepId} requires user input: '$question'")
                    taskStore?.recordStepWaitingInput(planTaskId, step.stepId, execId.toString(), question)
                    AutomationEventBus.publish(
                        AutomationEvent.NeedsUserInput(
                            executionId = execId,
                            stepId = step.stepId,
                            question = question,
                            options = toolResult.needsInputOptions,
                            targetParam = toolResult.targetParam
                        )
                    )
                    return@withTimeoutOrNull AgentExecutionResult(
                        success = false,
                        goalPrompt = goalPrompt,
                        executedStepsCount = executedStepsCount,
                        totalStepsCount = initialTotalSteps,
                        isWaitingForInput = true,
                        stepResults = stepResults
                    )
                }

                // 8. Adaptive Settling Delay & Post-Observation
                val postObs = LightweightUiObserver.observe(context)

                // 9. Handle Failure with Bounded Recovery & Replanning
                if (!toolResult.isVerifiedSuccess) {
                    val ambiguous = toolResult.success || toolResult.metadata.optBoolean("executorSuccess", false) || toolResult.status == ToolResultStatus.ACCEPTED ||
                        toolResult.status == ToolResultStatus.PENDING_USER_ACTION ||
                        DurableRecoveryCoordinator.isIrreversibleExternalAction(actionName)
                    if (ambiguous || toolResult.status == ToolResultStatus.USER_ACTION_REQUIRED) {
                        taskStore?.recordStepResult(planTaskId, step.stepId, execId.toString(),
                            executorSuccess = toolResult.success || toolResult.status == ToolResultStatus.ACCEPTED,
                            verifiedSuccess = false, unverified = ambiguous, failureReason = toolResult.message ?: "Outcome unverified; replay blocked")
                        taskStore?.deriveAndPersistTaskCompletion(planTaskId)
                        return@withTimeoutOrNull AgentExecutionResult(success = false, goalPrompt = goalPrompt,
                            executedStepsCount = executedStepsCount, totalStepsCount = initialTotalSteps,
                            errorMessage = "Action requires verification or user intervention; not replayed.", stepResults = stepResults)
                    }
                    Log.w(TAG, "Step ${step.stepId} (${step.actionType}) failed: ${toolResult.message}. Attempting recovery...")
                    var recoveredLocally = false
                    var retryAttempt = 1

                    while (toolResult.retryable && retryAttempt <= MAX_STEP_RETRIES && !recoveredLocally) {
                        currentCoroutineContext().ensureActive()
                        if (cancellationToken.isCancelled) throw kotlinx.coroutines.CancellationException("Plan stopped before recovery")
                        val attempted = recoveryEngine.attemptRecoveryForAction(context, step, toolResult.message ?: "", retryAttempt)
                        if (attempted) {
                            Log.i(TAG, "Retrying step ${step.stepId} after recovery attempt $retryAttempt")
                            toolResult = ExecutionEngine.execute(context, request, executionOutputs)
                            stepResults.add(toolResult)
                            if (toolResult.isVerifiedSuccess) {
                                recoveredLocally = true
                                break
                            }
                        }
                        retryAttempt++
                    }

                    if (!recoveredLocally) {
                        Log.w(TAG, "Local recovery failed for step ${step.stepId}. Invoking ReEvaluationEngine...")
                        val decision = ReEvaluationEngine.evaluate(
                            goal = goalPrompt,
                            failedStep = step,
                            toolResult = toolResult,
                            worldState = WorldStateManager.getCurrentState(),
                            completedSteps = completedSteps.toList(),
                            remainingSteps = actionQueue.getActiveSteps(),
                            executionOutputs = executionOutputs,
                            reEvalCount = activeReEvalCount,
                            planningProvider = planningProvider,
                            context = context
                        )

                        when (decision) {
                            is ReEvalDecision.CONTINUE -> {
                                taskStore?.recordStepResult(planTaskId, step.stepId, execId.toString(), false, false,
                                    failureReason = "Required step failed; continuing would omit part of the goal")
                                break
                            }
                            is ReEvalDecision.MODIFY -> {
                                Log.i(TAG, "ReEvaluation MODIFY (${decision.updatedSteps.size} new steps)")
                                if (!installReplan(decision.updatedSteps)) break
                                continue
                            }
                            is ReEvalDecision.ABANDON -> {
                                Log.e(TAG, "ReEvaluation ABANDON: ${decision.reason}")
                                taskStore?.recordStepResult(planTaskId, step.stepId, execId.toString(), executorSuccess = false, verifiedSuccess = false, failureReason = decision.reason)
                                val remaining = actionQueue.getActiveSteps()
                                remaining.forEachIndexed { rIdx, remStep ->
                                    taskStore?.updateStep(
                                        taskId = planTaskId,
                                        step = remStep,
                                        stepIndex = executedStepsCount + 1 + rIdx,
                                        status = "NOT_EXECUTED",
                                        failureReason = "Aborted due to failure in step '${step.stepId}'"
                                    )
                                }
                                taskStore?.deriveAndPersistTaskCompletion(planTaskId)
                                AutomationEventBus.publish(
                                    AutomationEvent.ExecutionFinished(UUID.randomUUID(), success = false)
                                )
                                return@withTimeoutOrNull AgentExecutionResult(
                                    success = false,
                                    goalPrompt = goalPrompt,
                                    executedStepsCount = executedStepsCount,
                                    totalStepsCount = initialTotalSteps,
                                    failedStepId = step.stepId,
                                    failedActionType = step.actionType,
                                    errorMessage = decision.reason,
                                    stepResults = stepResults
                                )
                            }
                        }
                    }
                }

                // 10. Step Succeeded: Record outputs and advance
                executedStepsCount++
                completedStepIds.add(step.stepId)
                completedSteps.add(step)
                taskStore?.recordStepResult(planTaskId, step.stepId, execId.toString(), executorSuccess = true,
                    verifiedSuccess = toolResult.isVerifiedSuccess,
                    verificationEvidence = toolResult.verification?.reason ?: toolResult.message,
                    executorResultJson = com.google.gson.Gson().toJson(toolResult))

                if (toolResult.outputData.isNotEmpty()) {
                    val map = executionOutputs.getOrPut(step.stepId) { mutableMapOf() }
                    map.putAll(toolResult.outputData)
                    if (step.outputKey != null) {
                        executionOutputs[step.outputKey] = map
                    }
                }

                AutomationEventBus.publish(
                    AutomationEvent.StepProgress(
                        executionId = execId,
                        stepIndex = executedStepsCount,
                        totalSteps = initialTotalSteps,
                        actionType = step.actionType,
                        targetText = targetText,
                        status = "COMPLETED"
                    )
                )

                WorldStateManager.recordActionExecuted(
                    actionName = step.actionType,
                    targetDescription = targetText ?: step.actionType,
                    success = true
                )
            }

            // 11. Authoritative Plan Completion Verification
            val allStepsSucceeded = executedStepsCount == initialTotalSteps &&
                completedSteps.size == initialTotalSteps &&
                completedStepIds.size == initialTotalSteps

            if (!allStepsSucceeded) {
                Log.e(TAG, "Plan completion check failed: executed $executedStepsCount/$initialTotalSteps steps.")
                val remaining = actionQueue.getActiveSteps()
                remaining.forEachIndexed { rIdx, remStep ->
                    taskStore?.updateStep(
                        taskId = planTaskId,
                        step = remStep,
                        stepIndex = executedStepsCount + rIdx,
                        status = "NOT_EXECUTED",
                        failureReason = "Incomplete plan execution"
                    )
                }
                taskStore?.deriveAndPersistTaskCompletion(planTaskId)
                AutomationEventBus.publish(
                    AutomationEvent.ExecutionFinished(UUID.randomUUID(), success = false)
                )
                return@withTimeoutOrNull AgentExecutionResult(
                    success = false,
                    goalPrompt = goalPrompt,
                    executedStepsCount = executedStepsCount,
                    totalStepsCount = initialTotalSteps,
                    errorMessage = "Plan execution incomplete: only $executedStepsCount of $initialTotalSteps steps executed.",
                    stepResults = stepResults
                )
            }

            val persistedPlan = taskStore.deriveAndPersistTaskCompletion(planTaskId)
            if (persistedPlan?.status != "COMPLETED") {
                return@withTimeoutOrNull AgentExecutionResult(success = false, goalPrompt = goalPrompt,
                    executedStepsCount = executedStepsCount, totalStepsCount = initialTotalSteps,
                    errorMessage = "Persisted task state is not verified complete", stepResults = stepResults)
            }
            Log.d(TAG, "Plan for '$goalPrompt' successfully completed and verified ($executedStepsCount steps executed)")
            AutomationEventBus.publish(
                AutomationEvent.ExecutionFinished(UUID.randomUUID(), success = true)
            )

            // Skill Memory: If task completed with >= 2 steps, save verified skill trace
            if (completedSteps.size >= 2) {
                try {
                    val skillRepo = SkillMemoryRepository(context)
                    skillRepo.saveSkill(goalPrompt, completedSteps)
                    Log.i(TAG, "Saved verified multi-step execution trace to SkillMemoryRepository for '$goalPrompt'")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed saving skill trace to memory: ${e.message}")
                }
            }

            AgentExecutionResult(
                success = true,
                goalPrompt = goalPrompt,
                executedStepsCount = executedStepsCount,
                totalStepsCount = initialTotalSteps,
                stepResults = stepResults
            )
        }

        if (result != null && !result.success && !result.isWaitingForInput && !result.isCancelled) {
            taskStore.terminatePending(planTaskId, "FAILED", result.errorMessage ?: "Task failed")
            AutomationEventBus.publish(AutomationEvent.ExecutionFinished(UUID.randomUUID(), false))
        }
        result ?: run {
            taskStore.terminatePending(planTaskId, "UNVERIFIED", "Plan deadline exceeded; inspect in-flight action before recovery")
            AutomationEventBus.publish(
                AutomationEvent.ExecutionFinished(UUID.randomUUID(), success = false)
            )
            AgentExecutionResult(
                success = false,
                goalPrompt = goalPrompt,
                executedStepsCount = executedStepsCount,
                totalStepsCount = initialTotalSteps,
                errorMessage = "Plan execution timed out after ${DEFAULT_PLAN_TIMEOUT_MS / 1000}s",
                stepResults = stepResults
            )
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable) {
                withTimeoutOrNull(5000L) { taskStore.terminatePending(planTaskId, "CANCELLED", "Cancelled during plan; remaining steps not executed") }
            }
            AutomationEventBus.publish(AutomationEvent.ExecutionCancelled(UUID.randomUUID(), "Plan cancelled"))
            if (!currentCoroutineContext().isActive) throw e
            AgentExecutionResult(false, goalPrompt, executedStepsCount, initialTotalSteps,
                errorMessage = "Task cancelled; remaining steps not executed", stepResults = stepResults, isCancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "Plan lifecycle failed before completion: taskId=$planTaskId", e)
            withTimeoutOrNull(5000L) { taskStore.terminatePending(planTaskId, "FAILED", e.message ?: "Plan execution exception") }
            AutomationEventBus.publish(AutomationEvent.ExecutionFinished(UUID.randomUUID(), false))
            AgentExecutionResult(false, goalPrompt, executedStepsCount, initialTotalSteps,
                errorMessage = e.message ?: "Plan execution exception", stepResults = stepResults)
        } finally {
            com.example.automation.engine.TaskCancellationCoordinator.unregisterActiveTask(planTaskId)
            com.example.automation.service.TaskExecutionForegroundService.stop(context)
        }
    }
}
