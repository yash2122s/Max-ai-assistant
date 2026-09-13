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
        const val DEFAULT_PLAN_TIMEOUT_MS = 60_000L
        const val MAX_TOTAL_PLAN_STEPS = 12
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

        Log.d(TAG, "Starting executeTask() for task '${task.id}' (${task.steps.size} steps) - goal: '${task.goal}'")

        val router = capabilityRouter ?: (appContext?.let { CapabilityRouter(it) })
        val verifier = verificationEngine ?: (appContext?.let { VerificationEngine(it) })

        while (task.status == TaskStatus.RUNNING && task.currentStepIndex < task.steps.size) {
            // 1. Check Cancellation & Runtime Status
            if (appContext != null && !MaxRuntimeManager.isMaxEnabled(appContext)) {
                Log.w(TAG, "Task cancelled: MAX runtime is disabled")
                task.status = TaskStatus.CANCELLED
                return@withContext TaskExecutionResult(
                    success = false,
                    task = task,
                    executedSteps = executedStepsCount,
                    totalSteps = totalInitialSteps,
                    message = "MAX is OFF",
                    isCancelled = true
                )
            }

            if (cancellationToken?.isCancelled == true) {
                Log.w(TAG, "Task cancelled via CancellationToken")
                task.status = TaskStatus.CANCELLED
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
                task.status = TaskStatus.FAILED
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

            // 4. Execute Step
            Log.d(TAG, "Executing step [${task.currentStepIndex + 1}/${task.steps.size}]: '${currentStep.id}' ($actionName)")
            val executionResult = router?.executeStep(currentStep, task.context) ?: ExecutionResult(success = false)

            // 5. Adaptive Post-Transition Settling Delay
            delay(200L)

            // 6. Re-Observe Post-Action
            val postObs = if (appContext != null) LightweightUiObserver.observe(appContext) else null

            // 7. Verify Expected Outcome
            val isVerified = if (currentStep.expectedOutcome != null && verifier != null) {
                verifier.verify(currentStep.expectedOutcome)
            } else {
                executionResult.success
            }

            if (isVerified) {
                // Advance state cleanly (Single Owner Invariant)
                task.context.updateState(
                    stepId = currentStep.id,
                    app = executionResult.activePackage ?: postObs?.foregroundPackage,
                    screen = executionResult.activeScreen
                )
                executedStepsCount++
                task.advance()
                continue
            }

            // 8. Bounded Local Recovery
            task.status = TaskStatus.RECOVERING
            Log.w(TAG, "Step '${currentStep.id}' verification failed. Attempting local recovery...")
            val recovered = if (appContext != null) {
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
                val replanSuccess = taskPlanner.replanTask(task)
                if (replanSuccess) {
                    task.status = TaskStatus.RUNNING
                    continue
                }
            }

            Log.e(TAG, "Step '${currentStep.id}' permanently failed and replanning failed. Aborting task.")
            task.status = TaskStatus.FAILED
            break
        }

        if (task.status == TaskStatus.RUNNING) {
            task.status = TaskStatus.COMPLETED
        }

        Log.d(TAG, "Task execution finished with status: ${task.status} ($executedStepsCount/${task.steps.size} steps)")
        return@withContext TaskExecutionResult(
            success = task.status == TaskStatus.COMPLETED,
            task = task,
            executedSteps = executedStepsCount,
            totalSteps = task.steps.size,
            message = if (task.status == TaskStatus.COMPLETED) "Task completed successfully" else "Task failed"
        )
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
        val initialTotalSteps = steps.size
        var executedStepsCount = 0
        var activeReEvalCount = 0
        val stepResults = mutableListOf<ToolResult>()
        val completedSteps = mutableListOf<ActionStep>()
        val completedStepIds = mutableSetOf<String>()
        val executionOutputs = mutableMapOf<String, MutableMap<String, Any?>>()
        val actionQueue = ActionQueue().apply { replaceQueue(steps) }

        if (steps.isEmpty()) {
            return@withContext AgentExecutionResult(
                success = false,
                goalPrompt = goalPrompt,
                executedStepsCount = 0,
                totalStepsCount = 0,
                errorMessage = "No action steps in plan"
            )
        }

        Log.d(TAG, "LongTaskOrchestrator.executePlan() started for goal: '$goalPrompt' (${steps.size} steps)")

        val result = withTimeoutOrNull(DEFAULT_PLAN_TIMEOUT_MS) {
            while (!actionQueue.isEmpty()) {
                // 1. Cancellation & MAX Status Check
                if (!MaxRuntimeManager.isMaxEnabled(context)) {
                    Log.w(TAG, "Plan aborted: MAX is disabled")
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
                            activeReEvalCount++
                            actionQueue.replaceQueue(decision.updatedSteps)
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
                val args = JsonObject().apply {
                    step.targetText?.let {
                        addProperty("text", it)
                        addProperty("target", it)
                        addProperty("app_name", it)
                        addProperty("appName", it)
                        addProperty("query", it)
                        addProperty("direction", it)
                        addProperty("key", it)
                        addProperty("system_action_str", it)
                    }
                    step.resourceId?.let { addProperty("resourceId", it) }
                    step.contentDescription?.let { addProperty("desc", it) }
                    step.fallbackX?.let { addProperty("x", it) }
                    step.fallbackY?.let { addProperty("y", it) }
                    for ((k, v) in step.params) {
                        addProperty(k, v)
                    }
                }

                val actionName = when (step.actionType.uppercase().trim()) {
                    "OPEN_SETTINGS", "SETTINGS" -> {
                        args.addProperty("app_name", "Settings")
                        "OPEN_APP"
                    }
                    "LAUNCH_APP" -> "OPEN_APP"
                    else -> step.actionType
                }

                val request = ExecutionRequest(
                    action = actionName,
                    arguments = args,
                    source = ExecutionSource.PLANNER,
                    cancellationToken = cancellationToken
                )

                val execId = request.executionId
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

                var toolResult = ExecutionEngine.execute(context, request, executionOutputs)
                stepResults.add(toolResult)

                // 7. Handle NeedsInput
                if (toolResult.status == ToolResultStatus.NEEDS_INPUT) {
                    val question = toolResult.needsInputQuestion ?: toolResult.message ?: "Input required"
                    Log.i(TAG, "Step ${step.stepId} requires user input: '$question'")
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
                delay(200L)
                val postObs = LightweightUiObserver.observe(context)

                // 9. Handle Failure with Bounded Recovery & Replanning
                if (!toolResult.success) {
                    Log.w(TAG, "Step ${step.stepId} (${step.actionType}) failed: ${toolResult.message}. Attempting recovery...")
                    var recoveredLocally = false
                    var retryAttempt = 1

                    while (retryAttempt <= MAX_STEP_RETRIES && !recoveredLocally) {
                        val attempted = recoveryEngine.attemptRecoveryForAction(context, step, toolResult.message ?: "", retryAttempt)
                        if (attempted) {
                            Log.i(TAG, "Retrying step ${step.stepId} after recovery attempt $retryAttempt")
                            toolResult = ExecutionEngine.execute(context, request, executionOutputs)
                            stepResults.add(toolResult)
                            if (toolResult.success) {
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
                                Log.i(TAG, "ReEvaluation CONTINUE")
                                continue
                            }
                            is ReEvalDecision.MODIFY -> {
                                Log.i(TAG, "ReEvaluation MODIFY (${decision.updatedSteps.size} new steps)")
                                activeReEvalCount++
                                actionQueue.replaceQueue(decision.updatedSteps)
                                continue
                            }
                            is ReEvalDecision.ABANDON -> {
                                Log.e(TAG, "ReEvaluation ABANDON: ${decision.reason}")
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

            // 11. Plan Completed Successfully
            Log.d(TAG, "Plan for '$goalPrompt' successfully completed ($executedStepsCount steps executed)")
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

        result ?: run {
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
    }
}
