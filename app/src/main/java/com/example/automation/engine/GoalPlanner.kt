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

    private fun getRequiredProvider(context: Context? = null): com.example.network.provider.LLMPlanningProvider {
        return planningProvider ?: com.example.network.provider.GeminiPlanningProvider(context).also {
            planningProvider = it
        }
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
            if (targetParam == "confirmed") {
                val isApproved = com.example.automation.approval.ApprovalCoordinator.parseReply(userInput) == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE
                updatedParams["confirmed"] = isApproved.toString()
                updatedParams["user_confirmed"] = isApproved.toString()
            } else {
                updatedParams[targetParam] = userInput
            }
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
                executedStepsCount = 0,
                totalStepsCount = 0,
                errorMessage = "No active goal or action steps in queue."
            )
        }

        Log.d(TAG, "GoalPlanner delegating plan execution to LongTaskOrchestrator for goal: '$goalPrompt'")
        val orchestrator = com.example.automation.engine.execution.LongTaskOrchestrator(
            context = context,
            capabilityRouter = com.example.automation.engine.execution.CapabilityRouter(context),
            verificationEngine = com.example.automation.engine.execution.VerificationEngine(context),
            recoveryEngine = com.example.automation.engine.execution.TaskRecoveryEngine(),
            taskPlanner = com.example.automation.engine.planning.TaskPlanner()
        )

        activeCancellationToken = cancellationToken
        return try {
            val activeSteps = actionQueue.getActiveSteps()
            val result = orchestrator.executePlan(
                context = context,
                goalPrompt = goalPrompt,
                steps = activeSteps,
                cancellationToken = cancellationToken,
                planningProvider = planningProvider
            )

            if (result.isWaitingForInput) {
                val pausedStep = activeSteps.getOrNull(result.executedStepsCount) ?: activeSteps.lastOrNull()
                if (pausedStep != null) {
                    val lastRes = result.stepResults.lastOrNull()
                    activeWaitingState = WaitingForInputState(
                        planId = goalPrompt,
                        step = pausedStep,
                        question = lastRes?.needsInputQuestion ?: lastRes?.message ?: "Input required",
                        options = lastRes?.needsInputOptions ?: emptyList(),
                        targetParam = lastRes?.targetParam ?: "confirmed"
                    )
                }
            } else {
                clearGoal()
            }
            result
        } finally {
            activeCancellationToken = null
            if (activeWaitingState == null) clearGoal()
        }
    }

    val BASE_PLANNER_ACTIONS = setOf(
        "OPEN_APP", "OPEN_SETTINGS", "LAUNCH_APP", "SETTINGS", "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "TYPE_TEXT", "CLEAR_TEXT", "SCROLL_SCREEN",
        "PRESS_ENTER", "IME_ENTER", "ENTER_KEY", "PERFORM_ENTER", "BACK", "PRESS_BACK",
        "PRESS_KEY", "KEYBOARD_ACTION", "KEY_PRESS", "TAP", "SWIPE", "WAIT_FOR_UI", "WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP",
        "SYSTEM_ACTION", "SET_VOLUME", "SET_BRIGHTNESS", "SET_DND", "SET_RINGER_MODE",
        "GET_BATTERY_STATUS", "GET_DEVICE_STATUS", "SEND_WHATSAPP_MESSAGE", "SEND_WHATSAPP", "FLASHLIGHT_ON", "FLASHLIGHT_OFF",
        "CALL_PHONE", "CREATE_CONTACT", "YOUTUBE_SEARCH", "OPEN_WHATSAPP_CHAT", "UNLOCK_DEVICE", "TAKE_SCREENSHOT",
        "SCROLL_DOWN", "SCROLL_UP", "MAX_DIAGNOSTICS"
    )

    /**
     * Dynamic capability validation: checks against registered tools in ToolRegistry as well
     * as fundamental planner primitives. Eliminates hardcoded whitelist drift.
     */
    fun isActionSupported(action: String): Boolean {
        val norm = action.uppercase().trim()
        if (BASE_PLANNER_ACTIONS.contains(norm)) return true
        if (com.example.automation.tools.ToolRegistry.getToolForAction(norm) != null) return true
        return false
    }

    val SUPPORTED_ACTIONS: Set<String>
        get() = BASE_PLANNER_ACTIONS + com.example.automation.tools.ToolRegistry.getAllCapabilities().map { it.uppercase() }

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
            if (!isActionSupported(normalizedAction)) {
                return "Unsupported actionType '$normalizedAction' at step '${step.stepId}'."
            }

            when (normalizedAction) {
                "OPEN_APP", "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "TYPE_TEXT", "TAP" -> {
                    if (step.targetText.isNullOrBlank() && step.resourceId.isNullOrBlank() && step.contentDescription.isNullOrBlank() && step.params.isEmpty()) {
                        return "Action '$normalizedAction' at step '${step.stepId}' requires targetText, resourceId, contentDescription, or params."
                    }
                }
                "PRESS_KEY", "KEYBOARD_ACTION", "KEY_PRESS" -> {
                    val key = step.targetText ?: step.params["key"] ?: step.params["keyCode"] ?: step.params["key_name"] ?: step.resourceId
                    if (key.isNullOrBlank()) {
                        return "Action '$normalizedAction' at step '${step.stepId}' requires a key name or code in targetText, params, or resourceId."
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
                    "WAIT", "WAIT_FOR_UI" -> {
                        val isTimeWait = act.action == "WAIT" ||
                            (act.target != null && (
                                act.target.contains(Regex("""\d+\s*(?:s|sec|seconds|ms|m|min|సెకన్లు|సెకండ్లు)""")) ||
                                act.target.matches(Regex("""\d+"""))
                            ))
                        if (isTimeWait) {
                            val duration = act.target ?: act.params["duration"] ?: "5s"
                            val params = act.params.toMutableMap()
                            params["duration"] = duration
                            ActionStep(
                                stepId = stepId,
                                actionType = "WAIT",
                                targetText = duration,
                                params = params,
                                dependsOn = dependsOn
                            )
                        } else {
                            ActionStep(
                                stepId = stepId,
                                actionType = "WAIT_FOR_UI",
                                targetText = act.target ?: "composer",
                                params = act.params.ifEmpty { mapOf("timeout" to "5000") },
                                dependsOn = dependsOn
                            )
                        }
                    }
                    else -> { // "CLICK" or "TAP"
                        ActionStep(
                            stepId = stepId,
                            actionType = if (act.action.equals("TAP", ignoreCase = true)) "TAP" else "CLICK_TEXT",
                            targetText = act.target,
                            params = act.params,
                            dependsOn = dependsOn
                        )
                    }
                }
                steps.add(actionStep)
                prevStepId = stepId
            }

            return com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.normalizePlan(steps, goal)
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

    fun isSimplePlan(steps: List<ActionStep>): Boolean = steps.size == 1 &&
        steps.single().dependsOn.isEmpty() && steps.single().actionType.uppercase() !in
        setOf("WAIT", "WAIT_FOR_TIME", "DELAY", "SLEEP", "WAIT_FOR_UI", "TASK_INTENT", "COMPUTER_USE", "UI_AGENT")

    private suspend fun executeSimplePlan(
        context: Context, goal: String, step: ActionStep, token: CancellationToken
    ): AgentExecutionResult {
        // No shared queue/mutex, FGS or screen preflight for an atomic tool action.
        // The same ExecutionEngine still authoritatively executes, verifies and persists it.
        if (!com.example.core.lifecycle.MaxRuntimeManager.isMaxEnabled(context)) {
            return AgentExecutionResult(false, goal, 0, 1, errorMessage = "MAX is OFF", isCancelled = true)
        }
        val taskId = java.util.UUID.randomUUID().toString()
        val request = ExecutionRequest.fromStep(step, ExecutionSource.MANUAL, token, taskId)
        request.arguments.addProperty("last_input_transcription", goal)
        val settings = com.example.data.preferences.SettingsManager(context)
        val approval = ActionAuthorizer.evaluateApproval(
            mode = runCatching { ActionAuthorizer.ApprovalMode.valueOf(settings.approvalMode) }.getOrDefault(ActionAuthorizer.ApprovalMode.SAFE_AUTO),
            actionType = request.action, userIntent = goal, target = step.targetText,
            allowCautionAutoInFullAuto = settings.isCautionAutoAllowedInFullAuto)
        if (approval.decision != ActionAuthorizer.ApprovalDecision.APPROVED) {
            val message = if (approval.decision == ActionAuthorizer.ApprovalDecision.NEEDS_CONFIRMATION) {
                com.example.automation.approval.ApprovalCoordinator.requestApproval(context, request.action, step.targetText,
                    org.json.JSONObject(request.arguments.toString()).put("action", request.action))
            } else approval.reason
            com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(taskId = taskId, stepId = step.stepId,
                executionId = request.executionId.toString(), actionName = request.action, executorSuccess = false, verifiedSuccess = false,
                status = if (approval.decision == ActionAuthorizer.ApprovalDecision.NEEDS_CONFIRMATION) "WAITING_FOR_APPROVAL" else "BLOCKED", message = message)
            return AgentExecutionResult(false, goal, 0, 1, errorMessage = message)
        }
        val unlocked = com.example.security.UnlockVerifier.assertUnlockedForAction(context, request.action)
        if (unlocked.isFailure) {
            val reason = unlocked.exceptionOrNull()?.message ?: "Device is locked"
            com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(taskId = taskId, stepId = step.stepId,
                executionId = request.executionId.toString(), actionName = request.action, executorSuccess = false, verifiedSuccess = false,
                status = "BLOCKED", failureReason = reason)
            return AgentExecutionResult(false, goal, 0, 1, errorMessage = reason)
        }
        val result = ExecutionEngine.execute(context, request)
        return AgentExecutionResult(success = result.isVerifiedSuccess, goalPrompt = goal,
            executedStepsCount = if (result.isVerifiedSuccess) 1 else 0, totalStepsCount = 1,
            failedStepId = if (result.isVerifiedSuccess) null else step.stepId,
            errorMessage = if (result.isVerifiedSuccess) null else result.message ?: "Action failed",
            stepResults = listOf(result), isCancelled = token.isCancelled || result.errorCode == "EXECUTION_CANCELLED")
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
        com.example.automation.engine.truth.ExecutionTruthCoordinator.clearTurnExecution()
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

            if (isSimplePlan(fastPathPlan)) {
                return executeSimplePlan(context, goal, fastPathPlan.single(), cancellationToken)
            }

            com.example.automation.event.AutomationEventBus.publish(
                com.example.automation.event.AutomationEvent.PlanCreated(executionId, fastPathPlan)
            )

            return plannerMutex.withLock {
                initializeGoalPlan(goal, fastPathPlan)
                executePlanInternal(context, cancellationToken)
            }
        }

        // 1.5. Skill Memory Recall (Phase 9) - Check if a verified skill exists for this goal
        val savedSkill = try {
            com.example.memory.skill.SkillMemoryRepository(context).findSkill(goal)
        } catch (e: Exception) {
            Log.w(TAG, "Skill memory lookup error: ${e.message}")
            null
        }

        if (savedSkill != null && savedSkill.steps.isNotEmpty()) {
            Log.i(TAG, "[SKILL_MEMORY_RECALL] Reusing ${savedSkill.steps.size} verified steps for goal: '$goal' (skill: '${savedSkill.task}')")
            val normalizedSteps = com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.normalizePlan(savedSkill.steps, goal)
            val validationError = validatePlan(normalizedSteps, goal)
            if (validationError == null) {
                if (isSimplePlan(normalizedSteps)) return executeSimplePlan(context, goal, normalizedSteps.single(), cancellationToken)
                return plannerMutex.withLock {
                    initializeGoalPlan(goal, normalizedSteps)
                    executePlanInternal(context, cancellationToken)
                }
            } else {
                Log.w(TAG, "Recalled skill failed validation ($validationError). Falling back to LLM planning.")
            }
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
        val provider = getRequiredProvider(context)
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

        val rawPlan = planningResult.plan
        val plan = com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer.normalizePlan(rawPlan, goal)
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
        if (isSimplePlan(plan)) return executeSimplePlan(context, goal, plan.single(), cancellationToken)
        return plannerMutex.withLock {
            initializeGoalPlan(goal, plan)
            executePlanInternal(context, cancellationToken)
        }
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
