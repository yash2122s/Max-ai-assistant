package com.example.automation.engine

import android.content.Context
import android.util.Log
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
import com.example.automation.tools.ToolResult
import com.example.voice.vision.ScreenContextProvider
import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock


data class WaitingForInputState(
    val planId: String,
    val step: ActionStep,
    val question: String,
    val options: List<String> = emptyList(),
    val targetParam: String? = null,
    val savedOutputs: Map<String, Map<String, Any?>> = emptyMap(),
    val completedSteps: List<ActionStep> = emptyList(),
    val reEvalCount: Int = 0
)

data class AgentExecutionResult(
    val success: Boolean,
    val goalPrompt: String,
    val executedStepsCount: Int,
    val totalStepsCount: Int,
    val failedStepId: String? = null,
    val failedActionType: String? = null,
    val errorMessage: String? = null,
    val stepResults: List<ToolResult> = emptyList(),
    val isCancelled: Boolean = false,
    val isWaitingForInput: Boolean = false
)

object GoalPlanner {
    private const val TAG = "GoalPlanner"
    private const val MAX_PLAN_STEPS = 8
    private const val MAX_TOTAL_PLAN_STEPS = 12
    private const val DEFAULT_PLAN_TIMEOUT_MS = 60_000L

    val actionQueue = ActionQueue()

    private var planningProvider: com.example.network.provider.LLMPlanningProvider? = null

    // Execution Context State
    val executionOutputs = mutableMapOf<String, MutableMap<String, Any?>>()
    val completedStepIds = mutableSetOf<String>()
    val failedStepIds = mutableSetOf<String>()
    val completedSteps = mutableListOf<ActionStep>()
    @Volatile
    private var activeWaitingState: WaitingForInputState? = null
    @Volatile
    private var activeCancellationToken: CancellationToken? = null
    private var activeReEvalCount: Int = 0
    private val plannerMutex = kotlinx.coroutines.sync.Mutex()

    fun cancelActivePlan() {
        Log.i(TAG, "cancelActivePlan() invoked.")
        activeCancellationToken?.cancel()
    }

    fun setPlanningProvider(provider: com.example.network.provider.LLMPlanningProvider) {
        this.planningProvider = provider
    }

    fun getPlanningProvider(): com.example.network.provider.LLMPlanningProvider? = planningProvider

    private fun getRequiredProvider(): com.example.network.provider.LLMPlanningProvider {
        return planningProvider ?: throw IllegalStateException("LLMPlanningProvider not injected into GoalPlanner")
    }

    fun initializeGoalPlan(goalPrompt: String, steps: List<ActionStep>) {
        Log.d(TAG, "Initializing plan for goal: '$goalPrompt' with ${steps.size} steps")
        actionQueue.replaceQueue(steps)
        executionOutputs.clear()
        completedStepIds.clear()
        failedStepIds.clear()
        completedSteps.clear()
        activeWaitingState = null
        activeReEvalCount = 0
        WorldStateManager.setCurrentGoal(goalPrompt, steps)
    }

    fun getNextStep(): ActionStep? {
        val nextStep = actionQueue.dequeueReadyStep(
            completedStepIds = completedStepIds,
            failedStepIds = failedStepIds
        )
        if (nextStep != null) {
            Log.d(TAG, "Dequeued next ready action step: ${nextStep.actionType} (ID: ${nextStep.stepId}, target: ${nextStep.targetText ?: nextStep.resourceId})")
        } else if (actionQueue.isEmpty()) {
            Log.d(TAG, "Action queue is empty. Goal completed.")
        } else {
            Log.w(TAG, "No steps currently ready in DAG queue. Remaining steps may be blocked by failed dependencies.")
        }
        return nextStep
    }

    fun replanRemaining(remainingSteps: List<ActionStep>) {
        Log.d(TAG, "Replanning remaining queue with ${remainingSteps.size} steps")
        actionQueue.replaceQueue(remainingSteps)
        val currentGoal = WorldStateManager.getCurrentState().currentGoal
        WorldStateManager.setCurrentGoal(currentGoal, remainingSteps)
    }

    fun clearGoal() {
        actionQueue.clear()
        executionOutputs.clear()
        completedStepIds.clear()
        failedStepIds.clear()
        completedSteps.clear()
        activeWaitingState = null
        activeReEvalCount = 0
        WorldStateManager.setCurrentGoal(null, emptyList())
    }

    fun isGoalActive(): Boolean {
        return !actionQueue.isEmpty() || activeWaitingState != null
    }

    fun getWaitingInputState(): WaitingForInputState? = activeWaitingState

    /**
     * Resumes a paused plan awaiting user input.
     * Binds user input to the target parameter and continues executing the SAME plan.
     */
    suspend fun resumePlanWithInput(
        context: Context,
        userInput: String,
        cancellationToken: CancellationToken = CancellationToken()
    ): AgentExecutionResult {
        return plannerMutex.withLock {
            val paused = activeWaitingState
            if (paused == null) {
                Log.w(TAG, "resumePlanWithInput() called but no active plan is in WAITING_FOR_USER_INPUT state.")
                return AgentExecutionResult(
                    success = false,
                    goalPrompt = "None",
                    executedStepsCount = 0,
                    totalStepsCount = 0,
                    errorMessage = "No active paused plan awaiting input."
                )
            }

            activeWaitingState = null
            val targetParam = paused.targetParam ?: "text"
            Log.i(TAG, "Resuming plan for goal '${paused.planId}' with input for param '$targetParam': '$userInput'")

            // 1. Bind input to step parameters
            val updatedParams = paused.step.params.toMutableMap()
            updatedParams[targetParam] = userInput
            val updatedStep = paused.step.copy(
                params = updatedParams,
                targetText = if (targetParam in listOf("text", "query", "target", "contact", "app_name")) userInput else paused.step.targetText,
                status = "READY"
            )

            // 2. Prepend updated step to remaining queue
            val currentQueue = actionQueue.getActiveSteps()
            actionQueue.replaceQueue(listOf(updatedStep) + currentQueue)

            // 3. Store input in context outputs
            executionOutputs.getOrPut(paused.step.stepId) { mutableMapOf() }[targetParam] = userInput

            // P1.3 — learn a contact preference when the user's reply resolved
            // an ambiguous contact during NeedsInput disambiguation (additive;
            // never changes plan semantics).
            val originalContactQuery = paused.step.targetText
                ?: paused.step.params["contact"]
                ?: paused.step.params["query"]
            com.example.automation.contact.ContactIntelligence.rememberIfContactSelection(
                context = context,
                actionType = paused.step.actionType,
                originalQuery = originalContactQuery,
                chosenInput = userInput
            )

            // 4. Resume execution loop on the SAME plan
            executePlanInternal(context, cancellationToken)
        }
    }

    /**
     * Executes the currently active plan sequentially through ExecutionEngine.
     * After each successful step, fresh ScreenContext observation is captured and
     * WorldStateManager is updated before proceeding to the next step.
     */
    suspend fun executePlan(
        context: Context,
        cancellationToken: CancellationToken = CancellationToken()
    ): AgentExecutionResult {
        return plannerMutex.withLock {
            executePlanInternal(context, cancellationToken)
        }
    }

    private suspend fun executePlanInternal(
        context: Context,
        cancellationToken: CancellationToken = CancellationToken()
    ): AgentExecutionResult {
        val goalPrompt = WorldStateManager.getCurrentState().currentGoal ?: "Unspecified Goal"
        val initialTotalSteps = actionQueue.size() + completedSteps.size

        if (!isGoalActive()) {
            Log.w(TAG, "executePlan() called but no active goal/steps in queue.")
            return AgentExecutionResult(
                success = false,
                goalPrompt = goalPrompt,
                executedStepsCount = completedSteps.size,
                totalStepsCount = 0,
                errorMessage = "No active goal or action steps in queue."
            )
        }

        Log.d(TAG, "Starting executePlan() for goal: '$goalPrompt' with $initialTotalSteps total steps")
        var executedStepsCount = completedSteps.size
        val stepResults = mutableListOf<ToolResult>()
        activeCancellationToken = cancellationToken

        return try {
            val result = withTimeoutOrNull(DEFAULT_PLAN_TIMEOUT_MS) {
                while (isGoalActive()) {
                // 1. Check cancellation
                if (cancellationToken.isCancelled || !currentCoroutineContext().isActive) {
                    Log.w(TAG, "Plan execution cancelled by token or coroutine cancellation.")
                    val cancelId = java.util.UUID.randomUUID()
                    com.example.automation.event.AutomationEventBus.publish(
                        com.example.automation.event.AutomationEvent.ExecutionCancelled(cancelId, "Plan execution was cancelled.")
                    )
                    clearGoal()
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

                // 2. Enforce safety step limit
                if (executedStepsCount >= MAX_TOTAL_PLAN_STEPS) {
                    Log.e(TAG, "Safety limit reached: Exceeded maximum allowed steps ($MAX_TOTAL_PLAN_STEPS). Stopping plan.")
                    clearGoal()
                    return@withTimeoutOrNull AgentExecutionResult(
                        success = false,
                        goalPrompt = goalPrompt,
                        executedStepsCount = executedStepsCount,
                        totalStepsCount = initialTotalSteps,
                        errorMessage = "Plan exceeded safety limit of $MAX_TOTAL_PLAN_STEPS steps.",
                        stepResults = stepResults
                    )
                }

                // 3. Dequeue next ready step
                val step = getNextStep()
                if (step == null) {
                    if (actionQueue.isEmpty()) {
                        break // All steps completed
                    }
                    // Remaining steps are blocked in DAG
                    val blockedRemaining = actionQueue.getActiveSteps()
                    Log.w(TAG, "${blockedRemaining.size} steps remaining in queue but blocked by dependencies. Evaluating plan recovery.")
                    
                    val dummyFailed = blockedRemaining.first()
                    val dummyResult = ToolResult.failure(
                        toolName = dummyFailed.actionType,
                        errorCode = "DEPENDENCY_BLOCKED",
                        message = "Step '${dummyFailed.stepId}' is blocked because its dependencies failed."
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
                        planningProvider = planningProvider
                    )
                    when (decision) {
                        is ReEvalDecision.CONTINUE -> break
                        is ReEvalDecision.MODIFY -> {
                            activeReEvalCount++
                            actionQueue.replaceQueue(decision.updatedSteps)
                            WorldStateManager.setCurrentGoal(goalPrompt, decision.updatedSteps)
                            continue
                        }
                        is ReEvalDecision.ABANDON -> {
                            clearGoal()
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

                // 4. Execute step with pre-authorization, action-specific verification and recovery
                val outcome = executeStepWithRecovery(
                    context = context,
                    step = step,
                    goalPrompt = goalPrompt,
                    initialTotalSteps = initialTotalSteps,
                    executedStepsCount = executedStepsCount,
                    cancellationToken = cancellationToken,
                    stepResults = stepResults
                )

                when (outcome) {
                    is StepExecutionOutcome.NeedsInput -> {
                        val toolResult = outcome.toolResult
                        val question = toolResult.needsInputQuestion ?: toolResult.message ?: "Input required to proceed"
                        Log.i(TAG, "Step ${step.stepId} (${step.actionType}) needs user input: '$question'. Pausing plan.")

                        activeWaitingState = WaitingForInputState(
                            planId = goalPrompt,
                            step = step,
                            question = question,
                            options = toolResult.needsInputOptions,
                            targetParam = toolResult.targetParam,
                            savedOutputs = executionOutputs.toMap(),
                            completedSteps = completedSteps.toList(),
                            reEvalCount = activeReEvalCount
                        )

                        val execId = java.util.UUID.randomUUID()
                        com.example.automation.event.AutomationEventBus.publish(
                            com.example.automation.event.AutomationEvent.NeedsUserInput(
                                executionId = execId,
                                stepId = step.stepId,
                                question = question,
                                options = toolResult.needsInputOptions,
                                targetParam = toolResult.targetParam
                            )
                        )
                        com.example.voice.assistant.AssistantEventBus.emit(
                            com.example.voice.assistant.AssistantEvent.NeedsUserInput(
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
                            errorMessage = question,
                            stepResults = stepResults,
                            isWaitingForInput = true
                        )
                    }

                    is StepExecutionOutcome.Failed -> {
                        failedStepIds.add(step.stepId)
                        val toolResult = outcome.toolResult
                        Log.w(TAG, "Step ${step.stepId} (${step.actionType}) failed: ${outcome.failureReason}. Evaluating recovery via ReEvaluationEngine.")

                        val execId = java.util.UUID.randomUUID()
                        com.example.automation.event.AutomationEventBus.publish(
                            com.example.automation.event.AutomationEvent.StepProgress(
                                executionId = execId,
                                stepIndex = executedStepsCount + 1,
                                totalSteps = initialTotalSteps,
                                actionType = step.actionType,
                                targetText = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.params.values.firstOrNull(),
                                status = "FAILED"
                            )
                        )

                        val decision = ReEvaluationEngine.evaluate(
                            goal = goalPrompt,
                            failedStep = step,
                            toolResult = toolResult,
                            worldState = WorldStateManager.getCurrentState(),
                            completedSteps = completedSteps.toList(),
                            remainingSteps = actionQueue.getActiveSteps(),
                            executionOutputs = executionOutputs,
                            reEvalCount = activeReEvalCount,
                            planningProvider = planningProvider
                        )

                        when (decision) {
                            is ReEvalDecision.CONTINUE -> {
                                Log.i(TAG, "ReEvaluation decision: CONTINUE with remaining independent steps.")
                                continue
                            }
                            is ReEvalDecision.MODIFY -> {
                                Log.i(TAG, "ReEvaluation decision: MODIFY (${decision.reason}) - replacing remaining steps with ${decision.updatedSteps.size} recovery steps.")
                                activeReEvalCount++
                                actionQueue.replaceQueue(decision.updatedSteps)
                                WorldStateManager.setCurrentGoal(goalPrompt, decision.updatedSteps)
                                continue
                            }
                            is ReEvalDecision.ABANDON -> {
                                Log.e(TAG, "ReEvaluation decision: ABANDON (${decision.reason}). Halting plan.")
                                clearGoal()
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

                    is StepExecutionOutcome.Success -> {
                        // 8. Step Succeeded: Record outputs and progress
                        executedStepsCount++
                        completedStepIds.add(step.stepId)
                        completedSteps.add(step)

                        val toolResult = outcome.toolResult
                        if (toolResult.outputData.isNotEmpty()) {
                            val map = executionOutputs.getOrPut(step.stepId) { mutableMapOf() }
                            map.putAll(toolResult.outputData)
                            if (step.outputKey != null) {
                                executionOutputs[step.outputKey] = map
                            }
                        }

                        val execId = java.util.UUID.randomUUID()
                        com.example.automation.event.AutomationEventBus.publish(
                            com.example.automation.event.AutomationEvent.StepProgress(
                                executionId = execId,
                                stepIndex = executedStepsCount,
                                totalSteps = initialTotalSteps,
                                actionType = step.actionType,
                                targetText = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.params.values.firstOrNull(),
                                status = "COMPLETED"
                            )
                        )

                        WorldStateManager.recordActionExecuted(
                            actionName = step.actionType,
                            targetDescription = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.actionType,
                            success = true
                        )

                        // 9. Capture fresh ScreenContext observation for next step ONLY after verified step
                        if (isGoalActive() && !actionQueue.isEmpty()) {
                            try {
                                val freshContext = ScreenContextProvider.captureScreenContext(context, focus = "general")
                                if (!freshContext.packageName.isNullOrBlank()) {
                                    WorldStateManager.setForegroundApp(freshContext.packageName)
                                }
                                com.example.automation.event.AutomationEventBus.publish(
                                    com.example.automation.event.AutomationEvent.ObservationCompleted(
                                        executionId = execId,
                                        packageName = freshContext.packageName,
                                        nodeCount = freshContext.prunedNodes.size,
                                        visibleSummary = freshContext.visibleText.take(100)
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Error capturing fresh screen context post-step: ${e.message}")
                            }
                        }
                    }
                }
            }

            // 10. All steps completed successfully
            Log.d(TAG, "Goal '$goalPrompt' completed successfully ($executedStepsCount steps executed).")
            clearGoal()
            AgentExecutionResult(
                success = true,
                goalPrompt = goalPrompt,
                executedStepsCount = executedStepsCount,
                totalStepsCount = initialTotalSteps,
                stepResults = stepResults
            )
        }

            result ?: run {
                Log.e(TAG, "Goal '$goalPrompt' timed out after ${DEFAULT_PLAN_TIMEOUT_MS}ms.")
                clearGoal()
                AgentExecutionResult(
                    success = false,
                    goalPrompt = goalPrompt,
                    executedStepsCount = executedStepsCount,
                    totalStepsCount = initialTotalSteps,
                    errorMessage = "Plan execution timed out after ${DEFAULT_PLAN_TIMEOUT_MS / 1000}s.",
                    stepResults = stepResults
                )
            }
        } finally {
            activeCancellationToken = null
        }
    }

    private sealed interface StepExecutionOutcome {
        data class Success(val toolResult: ToolResult) : StepExecutionOutcome
        data class NeedsInput(val toolResult: ToolResult) : StepExecutionOutcome
        data class Failed(val toolResult: ToolResult, val failureReason: String) : StepExecutionOutcome
    }

    private suspend fun executeStepWithRecovery(
        context: Context,
        step: ActionStep,
        goalPrompt: String,
        initialTotalSteps: Int,
        executedStepsCount: Int,
        cancellationToken: CancellationToken,
        stepResults: MutableList<ToolResult>
    ): StepExecutionOutcome {
        // 1. Authorize step against original intent before executing
        val target = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.params["target"] ?: step.params["text"]
        val auth = ActionAuthorizer.authorizeAction(goalPrompt, step.actionType, target)
        if (!auth.authorized) {
            val unauthResult = ToolResult.failure(
                toolName = step.actionType,
                errorCode = "UNAUTHORIZED_STEP",
                message = "Step '${step.stepId}' is unauthorized: ${auth.reason}"
            )
            stepResults.add(unauthResult)
            return StepExecutionOutcome.Failed(unauthResult, "Unauthorized action drift on step ${step.stepId}: ${auth.reason}")
        }

        // 2. Build ExecutionRequest
        val args = JsonObject().apply {
            step.targetText?.let {
                addProperty("text", it)
                addProperty("target", it)
                addProperty("app_name", it)
                addProperty("app", it)
                addProperty("appName", it)
                addProperty("query", it)
                addProperty("system_action_str", it)
                addProperty("direction", it)
                addProperty("mode", it)
                if (it.equals("true", ignoreCase = true) || it.equals("on", ignoreCase = true)) {
                    addProperty("enabled", true)
                    addProperty("dndEnabled", true)
                    addProperty("state", true)
                } else if (it.equals("false", ignoreCase = true) || it.equals("off", ignoreCase = true)) {
                    addProperty("enabled", false)
                    addProperty("dndEnabled", false)
                    addProperty("state", false)
                }
                it.toIntOrNull()?.let { num ->
                    addProperty("level", num)
                    addProperty("percent", num)
                }
            }
            step.resourceId?.let {
                addProperty("resourceId", it)
                addProperty("id", it)
            }
            step.contentDescription?.let {
                addProperty("description", it)
                addProperty("desc", it)
                addProperty("message", it)
            }
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

        Log.d(TAG, "Executing plan step ${executedStepsCount + 1}/$initialTotalSteps: ${step.actionType} (ID: ${step.stepId})")
        com.example.automation.event.AutomationEventBus.publish(
            com.example.automation.event.AutomationEvent.StepProgress(
                executionId = request.executionId,
                stepIndex = executedStepsCount + 1,
                totalSteps = initialTotalSteps,
                actionType = step.actionType,
                targetText = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.params.values.firstOrNull(),
                status = "RUNNING"
            )
        )

        // 3. Execute via ExecutionEngine (which triggers action-specific verification contracts)
        val toolResult = ExecutionEngine.execute(context, request, executionOutputs)
        stepResults.add(toolResult)

        // 4. Handle NEEDS_INPUT
        if (toolResult.status == com.example.automation.tools.ToolResultStatus.NEEDS_INPUT) {
            return StepExecutionOutcome.NeedsInput(toolResult)
        }

        // 5. Handle verification failure
        if (!toolResult.success) {
            return StepExecutionOutcome.Failed(toolResult, toolResult.message ?: "Step verification failed")
        }

        return StepExecutionOutcome.Success(toolResult)
    }

    val SUPPORTED_ACTIONS = setOf(
        "OPEN_APP", "OPEN_SETTINGS", "LAUNCH_APP", "SETTINGS", "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "TYPE_TEXT", "SCROLL_SCREEN",
        "PRESS_ENTER", "IME_ENTER", "ENTER_KEY", "PERFORM_ENTER", "BACK",
        "SYSTEM_ACTION", "SET_VOLUME", "SET_BRIGHTNESS", "SET_DND", "SET_RINGER_MODE",
        "GET_BATTERY_STATUS", "GET_DEVICE_STATUS", "SEND_WHATSAPP_MESSAGE", "SEND_WHATSAPP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF",
        "CALL_PHONE", "CREATE_CONTACT", "YOUTUBE_SEARCH", "OPEN_WHATSAPP_CHAT", "UNLOCK_DEVICE", "TAKE_SCREENSHOT",
        "SCROLL_DOWN", "SCROLL_UP", "MAX_DIAGNOSTICS"
    )

    /**
     * Validates a generated list of ActionSteps against schema rules, supported actions,
     * dependency DAG constraints (cycles, missing IDs), required parameters, and explicit user-intent authorization.
     */
    fun validatePlan(steps: List<ActionStep>, goal: String? = null): String? {
        if (steps.isEmpty()) {
            return "Generated plan is empty (0 steps)."
        }
        if (steps.size > MAX_PLAN_STEPS) {
            return "Plan exceeds maximum allowed steps (${steps.size} > $MAX_PLAN_STEPS)."
        }

        // P0-2 — unlock-like actions can never be planned as autonomous steps;
        // they always require the explicit ApprovalCoordinator flow first.
        val confirmationStep = steps.firstOrNull { ActionAuthorizer.requiresEveryModeConfirmation(it.actionType) }
        if (confirmationStep != null) {
            return "Plan step '${confirmationStep.stepId}' (${confirmationStep.actionType}) requires explicit user confirmation and cannot run as a plan step."
        }


        // 1. Validate Dependency Graph (Kahn's algorithm cycle check, missing IDs, duplicate IDs)
        val dagValidation = DependencyGraphResolver.validatePlan(steps)
        if (dagValidation is PlanValidationResult.Invalid) {
            return "${dagValidation.errorCode}: ${dagValidation.message}"
        }

        for (step in steps) {
            val normalizedAction = step.actionType.uppercase().trim()
            if (normalizedAction !in SUPPORTED_ACTIONS) {
                return "Unsupported actionType '$normalizedAction' at step '${step.stepId}'."
            }

            when (normalizedAction) {
                "OPEN_APP", "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "TYPE_TEXT" -> {
                    if (step.targetText.isNullOrBlank() && step.resourceId.isNullOrBlank() && step.contentDescription.isNullOrBlank() && step.params.isEmpty()) {
                        return "Action '$normalizedAction' at step '${step.stepId}' requires targetText, resourceId, contentDescription, or params."
                    }
                }
                "SYSTEM_ACTION" -> {
                    if (step.targetText.isNullOrBlank() && step.params.isEmpty()) {
                        return "Action 'SYSTEM_ACTION' at step '${step.stepId}' requires targetText (e.g. 'home', 'back', 'recent') or params."
                    }
                }
            }

            // Strict User-Intent Authorization Check
            if (!goal.isNullOrBlank()) {
                val target = step.targetText ?: step.resourceId ?: step.contentDescription ?: step.params["target"] ?: step.params["text"]
                val auth = ActionAuthorizer.authorizeAction(goal, normalizedAction, target)
                if (!auth.authorized) {
                    return "Step '${step.stepId}' ($normalizedAction) is unauthorized: ${auth.reason}"
                }
            }
        }
        return null
    }


    /**
     * Determines whether a goal requires active on-screen UI hierarchy inspection
     * (e.g. clicking, typing, finding elements, scrolling, or screen text extraction).
     */
    fun isScreenContextRequired(goal: String): Boolean {
        val lower = goal.trim().lowercase()
        val uiIndicators = listOf(
            "click", "tap", "press", "type", "write", "scroll",
            "find", "select", "read", "screen", "button", "display",
            "inspect", "visible", "నొక్కు", "ట్యాప్", "టైప్", "రాయి", "స్క్రీన్"
        )
        return uiIndicators.any { lower.contains(it) }
    }

    /**
     * Resolves deterministic single-step or compound UI automation commands locally
     * without invoking the LLM Planning Provider.
     */
    fun resolveFastPathPlan(goal: String, context: Context? = null): List<ActionStep>? {
        val trimmed = goal.trim()
        if (trimmed.isBlank() || isGoalAmbiguous(trimmed)) return null

        // 1. Compound UI Automation Fast-Path (Deterministic DAG Plan)
        val parsedUi = SemanticUiCommandParser.parse(trimmed)
        if (parsedUi != null && parsedUi.actions.isNotEmpty()) {
            val steps = mutableListOf<ActionStep>()
            var prevStepId: String? = null
            var stepCounter = 1
            var hasTypedInPlan = false

            for (act in parsedUi.actions) {
                val stepId = "step_${stepCounter++}"
                val dependsOn = if (prevStepId != null) listOf(prevStepId) else emptyList()

                val actionStep = when (act.action) {
                    "OPEN_APP" -> {
                        val rawApp = act.target ?: parsedUi.app ?: ""
                        val resolvedPkg = if (context != null) {
                            com.example.knowledge.apps.InstalledAppsRepository.findApp(context, rawApp)
                        } else rawApp
                        if (resolvedPkg == null) {
                            return null
                        }
                        ActionStep(
                            stepId = stepId,
                            actionType = "OPEN_APP",
                            targetText = resolvedPkg,
                            dependsOn = dependsOn
                        )
                    }
                    "TYPE" -> {
                        val params = mutableMapOf<String, String>()
                        params.putAll(act.params)
                        if (act.text != null) {
                            params["text"] = act.text
                        }
                        if (!params.containsKey("replace")) {
                            if (hasTypedInPlan) {
                                params["replace"] = "false"
                            } else {
                                params["replace"] = "true"
                                hasTypedInPlan = true
                            }
                        }
                        ActionStep(
                            stepId = stepId,
                            actionType = "TYPE_TEXT",
                            targetText = act.target ?: act.text,
                            params = params,
                            dependsOn = dependsOn
                        )
                    }
                    "FIND" -> {
                        val params = mutableMapOf<String, String>("mode" to "find")
                        params.putAll(act.params)
                        ActionStep(
                            stepId = stepId,
                            actionType = "CLICK_TEXT",
                            targetText = act.target,
                            params = params,
                            dependsOn = dependsOn
                        )
                    }
                    "SYSTEM" -> {
                        ActionStep(
                            stepId = stepId,
                            actionType = "SYSTEM_ACTION",
                            targetText = act.target ?: "home",
                            params = act.params,
                            dependsOn = dependsOn
                        )
                    }
                    "PRESS_ENTER" -> {
                        ActionStep(
                            stepId = stepId,
                            actionType = "PRESS_ENTER",
                            targetText = act.target ?: "enter",
                            params = act.params,
                            dependsOn = dependsOn
                        )
                    }
                    "SCROLL" -> {
                        ActionStep(
                            stepId = stepId,
                            actionType = "SCROLL_SCREEN",
                            targetText = act.target ?: "DOWN",
                            params = act.params,
                            dependsOn = dependsOn
                        )
                    }
                    else -> { // "CLICK"
                        ActionStep(
                            stepId = stepId,
                            actionType = "CLICK_TEXT",
                            targetText = act.target,
                            params = act.params,
                            dependsOn = dependsOn
                        )
                    }
                }
                steps.add(actionStep)
                prevStepId = stepId
            }

            return steps
        }

        // 2. Single-Step Normalized Command Fast-Path
        val normalized = NaturalCommandNormalizer.normalize(trimmed)
        if (normalized.isCompound) return null

        return when (normalized.intent) {
            FastPathIntent.FLASHLIGHT_ON -> listOf(ActionStep(stepId = "step_1", actionType = "FLASHLIGHT_ON"))
            FastPathIntent.FLASHLIGHT_OFF -> listOf(ActionStep(stepId = "step_1", actionType = "FLASHLIGHT_OFF"))
            FastPathIntent.NAV_HOME -> listOf(ActionStep(stepId = "step_1", actionType = "SYSTEM_ACTION", targetText = "home"))
            FastPathIntent.NAV_BACK -> listOf(ActionStep(stepId = "step_1", actionType = "SYSTEM_ACTION", targetText = "back"))
            FastPathIntent.NAV_RECENTS -> listOf(ActionStep(stepId = "step_1", actionType = "SYSTEM_ACTION", targetText = "recent"))
            FastPathIntent.NAV_NOTIFICATIONS -> listOf(ActionStep(stepId = "step_1", actionType = "SYSTEM_ACTION", targetText = "notifications"))
            FastPathIntent.NAV_QUICK_SETTINGS -> listOf(ActionStep(stepId = "step_1", actionType = "SYSTEM_ACTION", targetText = "quick_settings"))
            FastPathIntent.SCREENSHOT -> listOf(ActionStep(stepId = "step_1", actionType = "SYSTEM_ACTION", targetText = "screenshot"))
            FastPathIntent.VOLUME_UP -> listOf(ActionStep(stepId = "step_1", actionType = "SET_VOLUME", targetText = "up"))
            FastPathIntent.VOLUME_DOWN -> listOf(ActionStep(stepId = "step_1", actionType = "SET_VOLUME", targetText = "down"))
            FastPathIntent.MUTE -> listOf(ActionStep(stepId = "step_1", actionType = "SET_RINGER_MODE", targetText = "silent"))
            FastPathIntent.UNMUTE -> listOf(ActionStep(stepId = "step_1", actionType = "SET_RINGER_MODE", targetText = "normal"))
            FastPathIntent.BRIGHTNESS_UP -> listOf(ActionStep(stepId = "step_1", actionType = "SET_BRIGHTNESS", targetText = "up"))
            FastPathIntent.BRIGHTNESS_DOWN -> listOf(ActionStep(stepId = "step_1", actionType = "SET_BRIGHTNESS", targetText = "down"))
            FastPathIntent.DND_ON -> listOf(ActionStep(stepId = "step_1", actionType = "SET_DND", targetText = "true"))
            FastPathIntent.DND_OFF -> listOf(ActionStep(stepId = "step_1", actionType = "SET_DND", targetText = "false"))
            FastPathIntent.GET_BATTERY -> listOf(ActionStep(stepId = "step_1", actionType = "GET_BATTERY_STATUS"))
            FastPathIntent.GET_DEVICE_STATUS -> listOf(ActionStep(stepId = "step_1", actionType = "GET_DEVICE_STATUS"))
            FastPathIntent.OPEN_APP -> {
                val rawTarget = normalized.target
                if (rawTarget.isNullOrBlank()) return null

                // Dynamically resolve package name from canonical InstalledAppsRepository
                val resolvedPkg = if (context != null) {
                    com.example.knowledge.apps.InstalledAppsRepository.findApp(context, rawTarget)
                } else null

                val finalTarget = resolvedPkg ?: if (context == null) rawTarget else null
                if (finalTarget != null) {
                    listOf(ActionStep(stepId = "step_1", actionType = "OPEN_APP", targetText = finalTarget))
                } else {
                    Log.w(TAG, "Fast-path OPEN_APP could not resolve installed app for: '$rawTarget'")
                    null
                }
            }
            FastPathIntent.UI_AUTOMATION -> null
            FastPathIntent.UNKNOWN -> null
        }
    }

    /**
     * Checks if a goal is a bare ambiguous keyword or vague noun without an actionable verb.
     */
    fun isGoalAmbiguous(goal: String): Boolean {
        val trimmed = goal.trim().lowercase()
        val words = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return true

        // If SemanticUiCommandParser can extract concrete actions, it is unambiguous
        val parsed = SemanticUiCommandParser.parse(trimmed)
        if (parsed != null && parsed.actions.isNotEmpty()) {
            return false
        }

        if (words.size == 1) {
            val single = words[0]
            val validSingleCommands = setOf(
                "back", "home", "recents", "notifications", "flashlight", "screenshot",
                "wifi", "bluetooth", "help", "enter", "done", "submit", "venakki",
                "వెనక్కి", "ఎంటర్", "హోమ్", "హోం", "రీసెంట్", "నోటిఫికేషన్"
            )
            val bareAmbiguousNouns = setOf("vegetable", "bhaskar", "music", "hello", "food", "video", "chat", "message", "app", "phone", "settings")
            if (single in bareAmbiguousNouns) {
                return true
            }
            if (single !in validSingleCommands) {
                return true
            }
        }
        return false
    }

    /**
     * Generates a dynamic plan via Fast-Path resolution or Gemini REST client based on goal and screen context,
     * validates the plan, initializes GoalPlanner, and executes the plan sequentially.
     */
    suspend fun planAndExecute(
        context: Context,
        goal: String,
        cancellationToken: CancellationToken = CancellationToken()
    ): AgentExecutionResult {
        val executionId = java.util.UUID.randomUUID()
        Log.d(TAG, "Starting planAndExecute() for goal: '$goal' (executionId: $executionId)")

        // 0. Ambiguity Check: prevent guessing or executing arbitrary tools on bare nouns
        if (isGoalAmbiguous(goal)) {
            Log.w(TAG, "Goal '$goal' is ambiguous. Requesting clarification instead of executing tool.")
            val clarification = "What would you like me to do with \"$goal\"?"
            com.example.automation.activity.AgentActivityTracker.setAmbiguousGoal(goal, clarification)
            clearGoal()
            com.example.automation.event.AutomationEventBus.publish(
                com.example.automation.event.AutomationEvent.ExecutionFinished(executionId, success = false)
            )
            return AgentExecutionResult(
                success = false,
                goalPrompt = goal,
                executedStepsCount = 0,
                totalStepsCount = 0,
                errorMessage = "Ambiguous request: $clarification"
            )
        }

        // 1. Fast-Path Resolution (Deterministic single-step commands bypass LLM planning)
        val fastPathPlan = resolveFastPathPlan(goal, context)
        if (fastPathPlan != null) {
            Log.i(TAG, "[DETERMINISTIC_ROUTE] Fast-path resolved plan for goal '$goal' (${fastPathPlan.size} steps, 0 LLM calls)")
            com.example.automation.activity.AgentActivityTracker.addTrace(
                tag = "GoalPlanner",
                message = "Fast-path resolved: \"$goal\" (0 LLM calls, 0 token usage)",
                isSuccess = true
            )

            // Fast path plan MUST pass validation and authorization!
            val validationError = validatePlan(fastPathPlan, goal)
            if (validationError != null) {
                Log.e(TAG, "Fast-path plan validation failed: $validationError")
                clearGoal()
                com.example.automation.event.AutomationEventBus.publish(
                    com.example.automation.event.AutomationEvent.ExecutionFinished(executionId, success = false)
                )
                return AgentExecutionResult(
                    success = false,
                    goalPrompt = goal,
                    executedStepsCount = 0,
                    totalStepsCount = fastPathPlan.size,
                    errorMessage = "Invalid fast-path plan: $validationError"
                )
            }

            com.example.automation.event.AutomationEventBus.publish(
                com.example.automation.event.AutomationEvent.PlanCreated(executionId, fastPathPlan)
            )

            initializeGoalPlan(goal, fastPathPlan)
            return executePlan(context, cancellationToken)
        }

        // Emit planning started for LLM path
        com.example.automation.event.AutomationEventBus.publish(
            com.example.automation.event.AutomationEvent.PlanningStarted(executionId, goal)
        )

        // 2. Capture task-aware screen context
        val screenContext = if (isScreenContextRequired(goal)) {
            try {
                val ctx = ScreenContextProvider.captureScreenContext(context, focus = "general")
                com.example.automation.event.AutomationEventBus.publish(
                    com.example.automation.event.AutomationEvent.ObservationCompleted(
                        executionId = executionId,
                        packageName = ctx.packageName,
                        nodeCount = ctx.prunedNodes.size,
                        visibleSummary = ctx.visibleText.take(100)
                    )
                )
                ctx
            } catch (e: Exception) {
                Log.w(TAG, "Failed to capture screen context for planning: ${e.message}")
                com.example.voice.vision.ScreenContextPayload(
                    packageName = null,
                    prunedNodes = emptyList(),
                    visibleText = "",
                    clickableElements = emptyList(),
                    screenshotJpeg = null,
                    isScreenshotSent = false,
                    hasAccessibility = false,
                    focusMode = "general"
                )
            }
        } else {
            val foregroundApp = WorldStateManager.getCurrentState().currentApp.ifBlank {
                com.example.service.JarvisAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
            }
            com.example.voice.vision.ScreenContextPayload(
                packageName = foregroundApp,
                prunedNodes = emptyList(),
                visibleText = "",
                clickableElements = emptyList(),
                screenshotJpeg = null,
                isScreenshotSent = false,
                hasAccessibility = com.example.service.JarvisAccessibilityService.instance != null,
                focusMode = "general"
            )
        }

        // 3. Call injected LLM Planning Provider
        val provider = getRequiredProvider()
        val planningRequest = com.example.network.provider.PlanningRequest(
            goal = goal,
            screenContext = screenContext
        )
        val planningResult = provider.generatePlan(planningRequest)

        if (!planningResult.success) {
            Log.e(TAG, "Plan generation failed: ${planningResult.errorMessage}")
            clearGoal()
            com.example.automation.event.AutomationEventBus.publish(
                com.example.automation.event.AutomationEvent.ExecutionFinished(executionId, success = false)
            )
            return AgentExecutionResult(
                success = false,
                goalPrompt = goal,
                executedStepsCount = 0,
                totalStepsCount = 0,
                errorMessage = planningResult.errorMessage ?: "Plan generation failed."
            )
        }

        if (planningResult.tokenUsage != null) {
            com.example.automation.activity.AgentActivityTracker.addTrace(
                tag = "GeminiPlanningProvider",
                message = "Plan generated in ${planningResult.durationMs}ms (Prompt: ${planningResult.tokenUsage.promptTokens}, Output: ${planningResult.tokenUsage.candidateTokens}, Total: ${planningResult.tokenUsage.totalTokens} tokens)",
                isSuccess = true,
                durationMs = planningResult.durationMs
            )
        } else {
            com.example.automation.activity.AgentActivityTracker.addTrace(
                tag = "GeminiPlanningProvider",
                message = "Plan generated in ${planningResult.durationMs}ms (No token telemetry)",
                isSuccess = true,
                durationMs = planningResult.durationMs
            )
        }

        val plan = planningResult.plan
        com.example.automation.event.AutomationEventBus.publish(
            com.example.automation.event.AutomationEvent.PlanCreated(executionId, plan)
        )

        // 4. Validate generated plan with Goal Authorization
        val validationError = validatePlan(plan, goal)
        if (validationError != null) {
            Log.e(TAG, "Generated plan validation failed: $validationError")
            clearGoal()
            com.example.automation.event.AutomationEventBus.publish(
                com.example.automation.event.AutomationEvent.ExecutionFinished(executionId, success = false)
            )
            return AgentExecutionResult(
                success = false,
                goalPrompt = goal,
                executedStepsCount = 0,
                totalStepsCount = plan.size,
                errorMessage = "Invalid generated plan: $validationError"
            )
        }

        // 5. Initialize goal plan in queue and execute via M6.1 runner
        initializeGoalPlan(goal, plan)
        return executePlan(context, cancellationToken)
    }

    suspend fun planAndExecute(
        context: Context,
        goal: String,
        apiKey: String,
        cancellationToken: CancellationToken = CancellationToken()
    ): AgentExecutionResult {
        return planAndExecute(context, goal, cancellationToken)
    }
}

