package com.example.automation.computeruse

import android.content.Context
import android.util.Log
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.service.JarvisAccessibilityService
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lifecycle states for the Computer-Use state machine loop.
 */
enum class ComputerUseState {
    IDLE,
    OBSERVING,
    ASSESSING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Result of Computer-Use task execution.
 */
data class ComputerUseResult(
    val success: Boolean,
    val goal: String,
    val stepsExecuted: Int,
    val message: String,
    val taskStatus: TaskStatus = if (success) TaskStatus.VERIFIED_SUCCESS else TaskStatus.FAILED,
    val verifiedOutcome: String? = null,
    val executedPhases: List<GoalState> = emptyList(),
    val error: String? = null
)

/**
 * Production-Grade Android Computer-Use Engine for MAX.
 *
 * Implements a strict, monotonic, evidence-grounded state machine:
 * OBSERVE -> ASSESS CURRENT STATE -> PLAN ONE ACTION -> EXECUTE -> SETTLE -> RE-OBSERVE -> VERIFY -> UPDATE STATE -> RECOVER / COMPLETE.
 */
object ComputerUseEngine {
    private const val TAG = "ComputerUseEngine"
    private const val BASE_TASK_TIMEOUT_MS = 60_000L
    private const val PER_STEP_TIMEOUT_MS = 8_000L
    private const val STEP_SETTLE_DELAY_MS = 350L

    suspend fun execute(
        context: Context,
        goal: String,
        targetApp: String? = null,
        maxSteps: Int = 10,
        cancellationToken: com.example.automation.engine.CancellationToken? = null,
        taskId: String? = null
    ): ComputerUseResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val classification = GoalClassifier.classifyGoal(goal, targetApp)
        val effectiveTaskId = taskId ?: "task_${System.currentTimeMillis()}"
        val taskContext = TaskContext(
            taskId = effectiveTaskId,
            goal = goal,
            targetApp = classification.targetApp,
            searchQuery = classification.searchQuery,
            isSearchGoal = classification.intentType == TaskIntentType.SEARCH,
            intentType = classification.intentType,
            navigationSteps = classification.navigationSteps,
            currentStepIndex = 0,
            startTime = startTime,
            maxSteps = maxSteps
        )

        Log.i(TAG, "[TASK] EXECUTE_START taskId=${taskContext.taskId} goal='$goal' targetApp='${taskContext.targetApp}' query='${taskContext.searchQuery}' intent=${taskContext.intentType} steps=${taskContext.navigationSteps.map { it.target }} maxSteps=$maxSteps")

        // 1. Cancellation before start
        if (cancellationToken?.isCancelled == true) {
            Log.w(TAG, "[TASK] ABORTED reason=CANCELLED_BEFORE_START")
            return@withContext ComputerUseResult(
                success = false,
                goal = goal,
                stepsExecuted = 0,
                message = "Task was cancelled before execution.",
                error = ComputerUseErrorCode.CANCELLED.name
            )
        }

        // 2. Master switch safety check
        if (!MaxRuntimeManager.isMaxEnabled(context)) {
            Log.w(TAG, "[TASK] ABORTED reason=MAX_DISABLED")
            return@withContext ComputerUseResult(
                success = false,
                goal = goal,
                stepsExecuted = 0,
                message = "MAX master switch is currently disabled.",
                error = ComputerUseErrorCode.MAX_DISABLED.name
            )
        }

        // 3. Concurrency lock acquisition
        if (!ComputerUseConcurrency.tryAcquireLock(taskContext)) {
            Log.w(TAG, "[TASK] ABORTED reason=CONCURRENCY_BLOCKED")
            return@withContext ComputerUseResult(
                success = false,
                goal = goal,
                stepsExecuted = 0,
                message = "Another automated computer-use task is actively controlling the device.",
                error = ComputerUseErrorCode.CONCURRENCY_BLOCKED.name
            )
        }

        val maxTaskTimeoutMs = BASE_TASK_TIMEOUT_MS + (maxSteps * PER_STEP_TIMEOUT_MS)
        try {
            val timedResult = try {
                withTimeoutOrNull(maxTaskTimeoutMs) {
                    executeLoop(context, taskContext, cancellationToken)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[TASK] Uncaught execution error in executeLoop", e)
                ComputerUseResult(
                    success = false,
                    goal = goal,
                    stepsExecuted = 0,
                    message = "Execution error: ${e.message ?: "Internal error"}",
                    error = "INTERNAL_ERROR"
                )
            }

            val finalResult = timedResult ?: ComputerUseResult(
                success = false,
                goal = goal,
                stepsExecuted = maxOf(taskContext.completedSteps.size, taskContext.currentStepIndex),
                message = "Task timed out after ${maxTaskTimeoutMs / 1000}s",
                error = ComputerUseErrorCode.TASK_TIMEOUT.name
            )

            Log.i(TAG, "[TASK] FINISHED taskId=${taskContext.taskId} success=${finalResult.success} error=${finalResult.error} stepsExecuted=${finalResult.stepsExecuted} elapsed=${System.currentTimeMillis() - startTime}ms")
            finalResult
        } finally {
            ComputerUseConcurrency.releaseLock(taskContext)
        }
    }

    private suspend fun executeLoop(
        context: Context,
        taskContext: TaskContext,
        cancellationToken: com.example.automation.engine.CancellationToken? = null
    ): ComputerUseResult {
        var stepsExecuted = 0
        var currentState = GoalState.INIT
        val executedPhases = mutableListOf<GoalState>(GoalState.INIT)
        var consecutiveFailures = 0
        var lastFailedAction: ComputerAction? = null
        var originalFailedAction: ComputerAction? = null
        var selectedCandidate: TargetGrounding? = null

        fun advanceState(nextState: GoalState, evidence: String) {
            val canAdvance = nextState.ordinal > currentState.ordinal || (nextState.isTerminal && !currentState.isTerminal)
            if (canAdvance) {
                Log.i(TAG, "[TRANSITION] $currentState -> $nextState evidence='$evidence'")
                currentState = nextState
                if (!executedPhases.contains(nextState)) {
                    executedPhases.add(nextState)
                    Log.i(TAG, "[CU_PHASE] $nextState detail='$evidence'")
                }
            }
        }

        AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "🧠 Initiating Computer-Use state machine for '${taskContext.goal}'..."))

        for (step in 1..taskContext.maxSteps) {
            stepsExecuted = step

            // 1. Cancellation check
            if (cancellationToken?.isCancelled == true || ComputerUseConcurrency.isTaskCancelled(taskContext)) {
                Log.w(TAG, "[TASK] CANCELLED at step $step")
                Log.i(TAG, "[CU] FINAL_TASK_STATUS status=FAILED message='Task was cancelled'")
                return ComputerUseResult(
                    success = false,
                    goal = taskContext.goal,
                    stepsExecuted = stepsExecuted,
                    message = "Task was cancelled by user or system policy.",
                    taskStatus = TaskStatus.FAILED,
                    executedPhases = executedPhases.toList(),
                    error = ComputerUseErrorCode.CANCELLED.name
                )
            }

            // --- 2. STATE: OBSERVING ---
            AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "🔍 Observing screen (Step $step/${taskContext.maxSteps})..."))
            val currentSnapshot = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot()
            // Computer-use planning must fuse the current tree with a screenshot on
            // every action boundary; a fresh tree alone cannot expose overlays/icons.
            val obsBefore = ScreenObservationProvider.captureObservation(
                context, captureScreenshot = true, taskId = taskContext.taskId,
                stepId = "step_$step", executionId = taskContext.executionId,
                causedByAction = null
            )
            Log.i(TAG, "[OBSERVE] step=$step obsId=${obsBefore.observationId} app=${obsBefore.foregroundPackage} elements=${obsBefore.interactiveElements.size} texts=${obsBefore.visibleTexts.size} screenshot=${obsBefore.hasScreenshot}")

            // Permission dialogs are user-gated UI, not a loading state. Stop
            // with an actionable terminal result instead of retrying the same
            // search against the permission controller forever.
            val permissionPrompt = findBlockingPermissionPrompt(obsBefore)
            if (permissionPrompt != null) {
                advanceState(GoalState.FAILED, "Permission prompt requires user action: $permissionPrompt")
                val message = "Google Maps is waiting for user permission ($permissionPrompt). Approve it, then retry the search."
                Log.w(TAG, "[TASK] TERMINAL status=FAILED error=${ComputerUseErrorCode.PERMISSION_REQUIRED} detail='$permissionPrompt'")
                return ComputerUseResult(
                    success = false,
                    goal = taskContext.goal,
                    stepsExecuted = stepsExecuted,
                    message = message,
                    taskStatus = TaskStatus.FAILED,
                    executedPhases = executedPhases.toList(),
                    error = ComputerUseErrorCode.PERMISSION_REQUIRED.name
                )
            }

            // Discard stale candidate before assessment
            if (selectedCandidate?.isStale(obsBefore) == true) {
                Log.w(TAG, "[CANDIDATE] Discarding stale candidate: ${selectedCandidate?.semanticLabel}")
                selectedCandidate = null
            }

            // --- 3. STATE: ASSESSING CURRENT STATE & SMART ATTACHMENT ---
            val assessment = GoalStateAssessor.assessCurrentState(
                obs = obsBefore,
                context = taskContext,
                currentPhase = currentState,
                selectedCandidate = selectedCandidate
            )
            Log.i(TAG, "[ASSESS] assessedState=${assessment.assessedState} status=${assessment.status} isAlreadySatisfied=${assessment.isAlreadySatisfied} evidence='${assessment.primaryEvidence}'")

            if (assessment.isAlreadySatisfied && assessment.status == EvidenceStatus.VERIFIED) {
                advanceState(assessment.assessedState, assessment.primaryEvidence)
            } else if (assessment.status == EvidenceStatus.VERIFIED && assessment.assessedState.ordinal > currentState.ordinal) {
                advanceState(assessment.assessedState, assessment.primaryEvidence)
            }

            // Terminal check from verified assessment
            if (currentState == GoalState.DESTINATION_VERIFIED) {
                val isTelugu = isTeluguGoal(taskContext.goal)
                val outcomeMsg = if (isTelugu) {
                    if (taskContext.isSearchGoal) "'${taskContext.searchQuery}' kosam search chesi, result open chesi verify chesanu."
                    else if (taskContext.completedSteps.isNotEmpty()) "${taskContext.completedSteps.last()} open chesi verify chesanu."
                    else "${taskContext.targetApp ?: "App"} open ayyindi. Verify chesanu."
                } else {
                    if (taskContext.isSearchGoal) "Target content for '${taskContext.searchQuery}' successfully verified on screen."
                    else if (taskContext.completedSteps.isNotEmpty()) "Navigated to '${taskContext.completedSteps.last()}' and verified."
                    else "${taskContext.targetApp ?: "App"} opened and verified."
                }
                Log.i(TAG, "[CU] FINAL_TASK_STATUS status=VERIFIED_SUCCESS message='$outcomeMsg'")
                AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "✓ Verified: $outcomeMsg"))
                return ComputerUseResult(
                    success = true,
                    goal = taskContext.goal,
                    stepsExecuted = stepsExecuted,
                    message = outcomeMsg,
                    taskStatus = TaskStatus.VERIFIED_SUCCESS,
                    verifiedOutcome = outcomeMsg,
                    executedPhases = executedPhases.toList()
                )
            }

            // --- 4. STATE: PLANNING ---
            val action = if (consecutiveFailures > 0 && (lastFailedAction != null || originalFailedAction != null)) {
                val actionToRecover = originalFailedAction ?: lastFailedAction!!
                if (consecutiveFailures > ComputerUseRecoveryEngine.MAX_RECOVERY_ATTEMPTS) {
                    Log.e(TAG, "[RECOVERY] Exhausted max 3 recovery attempts for '${actionToRecover.actionName}'. Aborting.")
                    val isTelugu = isTeluguGoal(taskContext.goal)
                    val recMsg = if (isTelugu) "${taskContext.targetApp ?: "App"} open cheyyadam fail ayyindi." else "Action '${actionToRecover.actionName}' failed after 3 recovery attempts."
                    Log.i(TAG, "[CU] FINAL_TASK_STATUS status=FAILED message='$recMsg'")
                    return ComputerUseResult(
                        success = false,
                        goal = taskContext.goal,
                        stepsExecuted = stepsExecuted,
                        message = recMsg,
                        taskStatus = TaskStatus.FAILED,
                        executedPhases = executedPhases.toList(),
                        error = ComputerUseErrorCode.RECOVERY_EXHAUSTED.name
                    )
                }
                ComputerUseRecoveryEngine.planRecovery(
                    attempt = consecutiveFailures,
                    failedAction = actionToRecover,
                    obs = obsBefore,
                    context = taskContext,
                    currentPhase = currentState
                )
            } else {
                ComputerUsePlanner.planNextAction(
                    currentPhase = currentState,
                    obs = obsBefore,
                    context = taskContext,
                    assessment = assessment,
                    selectedCandidate = selectedCandidate
                )
            }

            if (action is ComputerAction.FinishTask) {
                val isActuallyVerified = currentState == GoalState.DESTINATION_VERIFIED ||
                    (taskContext.isSearchGoal && DestinationVerifier.verify(obsBefore, taskContext.searchQuery, taskContext.targetApp, selectedCandidate).verified) ||
                    (taskContext.intentType == TaskIntentType.OPEN_APP_ONLY && assessment.isAlreadySatisfied && assessment.status == EvidenceStatus.VERIFIED) ||
                    (taskContext.intentType == TaskIntentType.COMPOUND_UI_NAVIGATION && taskContext.remainingSteps.isEmpty() && taskContext.completedSteps.isNotEmpty()) ||
                    (taskContext.intentType == TaskIntentType.UI_ACTION && taskContext.remainingSteps.isEmpty() && taskContext.completedSteps.isNotEmpty())

                if (!isActuallyVerified && action.success) {
                    Log.w(TAG, "[DECIDE] Refusing false FinishTask: Destination is not verified by observation evidence!")
                    advanceState(GoalState.FAILED, "Unverified FinishTask attempt rejected")
                    val isTelugu = isTeluguGoal(taskContext.goal)
                    val unverifiedMsg = if (isTelugu) "${taskContext.targetApp ?: "App"} launch ayyindi, kani screen ni verify cheyyalekapoyanu." else "Task completion rejected: destination evidence missing."
                    Log.i(TAG, "[CU] FINAL_TASK_STATUS status=EXECUTED_UNVERIFIED message='$unverifiedMsg'")
                    return ComputerUseResult(
                        success = false,
                        goal = taskContext.goal,
                        stepsExecuted = stepsExecuted,
                        message = unverifiedMsg,
                        taskStatus = TaskStatus.EXECUTED_UNVERIFIED,
                        executedPhases = executedPhases.toList(),
                        error = ComputerUseErrorCode.DESTINATION_NOT_VERIFIED.name
                    )
                }

                if (isActuallyVerified) {
                    advanceState(GoalState.DESTINATION_VERIFIED, "FinishTask verified")
                }

                val finalStatus = if (action.success && isActuallyVerified) TaskStatus.VERIFIED_SUCCESS else TaskStatus.FAILED
                Log.i(TAG, "[CU] FINAL_TASK_STATUS status=$finalStatus message='${action.message}'")
                AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", if (action.success) "✓ ${action.message}" else "✗ ${action.message}"))
                return ComputerUseResult(
                    success = action.success && isActuallyVerified,
                    goal = taskContext.goal,
                    stepsExecuted = stepsExecuted,
                    message = action.message,
                    taskStatus = finalStatus,
                    verifiedOutcome = if (action.success && isActuallyVerified) action.message else null,
                    executedPhases = executedPhases.toList(),
                    error = if (!action.success || !isActuallyVerified) ComputerUseErrorCode.DESTINATION_NOT_VERIFIED.name else null
                )
            }

            // Track candidate grounding if action is tapping search result
            if (action is ComputerAction.TapCoordinate && action.grounding != null) {
                selectedCandidate = action.grounding
            }

            // --- 5. STATE: EXECUTING ---
            AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "⚙️ Executing ${action.actionName}..."))
            val beforeVersion = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot().stateVersion
            val execResult = ComputerActionExecutor.execute(context, action)
            Log.i(
                TAG,
                "[CU_STEP] taskId=${taskContext.taskId} executionId=${taskContext.executionId} " +
                    "step=$step phase=$currentState query='${taskContext.searchQuery}' action=${action.actionName} " +
                    "dispatchSuccess=${execResult.success} submissionVerified=${execResult.submissionVerified} " +
                    "durationMs=${execResult.durationMs} method=${execResult.methodUsed} error=${execResult.errorCode}"
            )

            // --- 6. ADAPTIVE SETTLE & PREDICATE WAIT ---
            val settleTime = when (action) {
                is ComputerAction.LaunchApp -> 1500L
                is ComputerAction.TypeText -> 500L
                is ComputerAction.PressEnter -> 800L
                is ComputerAction.Swipe, is ComputerAction.Scroll -> 600L
                is ComputerAction.ObserveMore -> 300L
                else -> STEP_SETTLE_DELAY_MS
            }
            val predicate = when (val exp = action.expectation) {
                is ActionExpectation.AppForeground -> com.example.automation.state.ExpectedPredicate.PackageForeground(exp.packageName)
                is ActionExpectation.TextPresent -> com.example.automation.state.ExpectedPredicate.TextAppeared(exp.value)
                is ActionExpectation.FocusField -> com.example.automation.state.ExpectedPredicate.FocusEquals(exp.target)
                else -> null
            }
            if (predicate != null) {
                com.example.automation.state.CurrentUiStateStore.waitForPredicate(
                    predicate = predicate,
                    timeoutMs = settleTime,
                    minStateVersion = beforeVersion + 1L
                )
            } else {
                delay(settleTime)
            }

            // --- 7. STATE: RE-OBSERVE & VERIFY ---
            val postSnapshot = com.example.automation.state.CurrentUiStateStore.getCurrentSnapshot()
            val needPostScreenshot = postSnapshot.isSparseOrObscured || !postSnapshot.isFresh(maxAgeMs = 2000L)
            val obsAfter = ScreenObservationProvider.captureObservation(
                context, captureScreenshot = true, taskId = taskContext.taskId,
                stepId = "step_$step", executionId = taskContext.executionId,
                causedByAction = action.actionName,
                expectedPostcondition = action.expectation?.toString()
            )
            val rootAvailable = JarvisAccessibilityService.getActiveRoot() != null
            val currentPkg = obsAfter.foregroundPackage
            val nodeCount = obsAfter.interactiveElements.size + obsAfter.visibleTexts.size
            val screenshotAvailable = obsAfter.hasScreenshot
            val obsStatus = when {
                rootAvailable && screenshotAvailable -> "OBSERVED"
                rootAvailable || screenshotAvailable -> "DEGRADED"
                else -> "FAILED"
            }

            Log.i(TAG, "[CU] CURRENT_PACKAGE package='$currentPkg'")
            Log.i(TAG, "[CU] ROOT_AVAILABLE available=$rootAvailable")
            Log.i(TAG, "[CU] NODE_COUNT count=$nodeCount")
            Log.i(TAG, "[CU] SCREEN_CAPTURE_AVAILABLE available=$screenshotAvailable path='${obsAfter.screenshotPath ?: "none"}'")
            Log.i(TAG, "[CU] OBSERVATION_STATUS status=$obsStatus")

            val (isActionVerified, note) = ActionVerifier.verify(action, execResult, obsBefore, obsAfter)
            val verStatus = when {
                isActionVerified -> "VERIFIED"
                execResult.success -> "UNVERIFIED"
                else -> "FAILED"
            }
            Log.i(
                TAG,
                "[CU] VERIFICATION_STATUS taskId=${taskContext.taskId} step=$step status=$verStatus " +
                    "observedPackage='${obsAfter.foregroundPackage}' retry=$consecutiveFailures " +
                    "timeoutMs=$PER_STEP_TIMEOUT_MS reason='$note'"
            )
            Log.i(TAG, "[VERIFY] action=${action.actionName} isVerified=$isActionVerified note='$note'")

            if (isActionVerified) {
                consecutiveFailures = 0
                lastFailedAction = null
                originalFailedAction = null
                AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "✓ $note"))

                // Phase advancement rules upon verified action execution
                when (action) {
                    is ComputerAction.LaunchApp -> {
                        advanceState(GoalState.APP_READY, action.appName)

                        // LaunchApp may ONLY finish the task when intentType == OPEN_APP_ONLY
                        if (taskContext.intentType == TaskIntentType.OPEN_APP_ONLY) {
                            advanceState(GoalState.DESTINATION_VERIFIED, "${action.appName} verified on screen")
                            val isTelugu = isTeluguGoal(taskContext.goal)
                            val verifiedMsg = if (isTelugu) "${action.appName} open ayyindi. Verify chesanu." else "${action.appName} opened and verified."
                            Log.i(TAG, "[CU] FINAL_TASK_STATUS status=VERIFIED_SUCCESS message='$verifiedMsg'")
                            AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "✓ $verifiedMsg"))
                            return ComputerUseResult(
                                success = true,
                                goal = taskContext.goal,
                                stepsExecuted = stepsExecuted,
                                message = verifiedMsg,
                                taskStatus = TaskStatus.VERIFIED_SUCCESS,
                                verifiedOutcome = verifiedMsg,
                                executedPhases = executedPhases.toList()
                            )
                        } else {
                            advanceState(GoalState.NAVIGATION_IN_PROGRESS, "${action.appName} launched, proceeding with navigation")
                            val totalSteps = taskContext.navigationSteps.size + (if (taskContext.targetApp != null) 1 else 0)
                            Log.i(TAG, "[TASK_PROGRESS] step=1/$totalSteps action=LaunchApp verified=true remaining=${taskContext.remainingSteps}")
                        }
                    }
                    is ComputerAction.TapCoordinate, is ComputerAction.TapElement -> {
                        if (taskContext.intentType == TaskIntentType.COMPOUND_UI_NAVIGATION ||
                            taskContext.intentType == TaskIntentType.UI_ACTION) {
                            val currentTarget = taskContext.currentTarget
                            val actionTarget = when (action) {
                                is ComputerAction.TapElement -> action.targetText
                                is ComputerAction.TapCoordinate -> action.grounding?.semanticLabel
                                    ?: action.grounding?.contentDescription
                                    ?: action.description
                                    ?: ""
                                else -> ""
                            }
                            if (currentTarget != null && actionTarget.isNotBlank() && (
                                actionTarget.equals(currentTarget, ignoreCase = true) ||
                                actionTarget.contains(currentTarget, ignoreCase = true) ||
                                currentTarget.contains(actionTarget, ignoreCase = true)
                            )) {
                                taskContext.completedSteps.add(currentTarget)
                                taskContext.currentStepIndex++
                                Log.i(TAG, "[TASK_VERIFY] target=$currentTarget verified=true")
                                val stepNum = taskContext.currentStepIndex + (if (taskContext.targetApp != null) 1 else 0)
                                val totalSteps = taskContext.navigationSteps.size + (if (taskContext.targetApp != null) 1 else 0)
                                Log.i(TAG, "[TASK_PROGRESS] step=$stepNum/$totalSteps action=TapElement target=$currentTarget verified=true remaining=${taskContext.remainingSteps}")

                                if (taskContext.remainingSteps.isEmpty()) {
                                    advanceState(GoalState.DESTINATION_VERIFIED, "All navigation steps completed: ${taskContext.completedSteps.joinToString(" -> ")}")
                                    val isTelugu = isTeluguGoal(taskContext.goal)
                                    val destinationMsg = if (isTelugu) {
                                        "${taskContext.completedSteps.lastOrNull() ?: "Target"} open chesi verify chesanu."
                                    } else {
                                        "Navigated to '${taskContext.completedSteps.lastOrNull() ?: "destination"}' and verified."
                                    }
                                    Log.i(TAG, "[CU] FINAL_TASK_STATUS status=VERIFIED_SUCCESS message='$destinationMsg'")
                                    AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "✓ $destinationMsg"))
                                    return ComputerUseResult(
                                        success = true,
                                        goal = taskContext.goal,
                                        stepsExecuted = stepsExecuted,
                                        message = destinationMsg,
                                        taskStatus = TaskStatus.VERIFIED_SUCCESS,
                                        verifiedOutcome = destinationMsg,
                                        executedPhases = executedPhases.toList()
                                    )
                                }
                            }
                        }
                        if (currentState < GoalState.SEARCH_UI_OPEN) {
                            if (obsAfter.findEditableElement() != null || obsAfter.interactiveElements.any { it.isEditable }) {
                                advanceState(GoalState.SEARCH_UI_OPEN, "Search UI opened")
                            }
                        }
                        if (currentState == GoalState.SEARCH_RESULTS_OBSERVED) {
                            advanceState(GoalState.RESULT_SELECTED, "Search result tapped")
                        }
                    }
                    is ComputerAction.TypeText -> {
                        advanceState(GoalState.QUERY_ENTERED, action.text)
                        if (action.pressEnterAfter && execResult.submissionVerified) {
                            advanceState(GoalState.SEARCH_SUBMITTED, "Search submitted via enter")
                        }
                    }
                    is ComputerAction.PressEnter -> {
                        advanceState(GoalState.SEARCH_SUBMITTED, "Search submitted via enter key")
                    }
                    else -> {}
                }

                // Domain verification checks on post-action screen
                if (taskContext.isSearchGoal) {
                    if (currentState >= GoalState.SEARCH_SUBMITTED && currentState < GoalState.RESULT_SELECTED) {
                        val resultsRes = SearchResultsVerifier.verify(obsAfter, taskContext.searchQuery)
                        if (resultsRes.verified) {
                            advanceState(GoalState.SEARCH_RESULTS_OBSERVED, "Search results verified on screen")
                        }
                    }

                    if (currentState == GoalState.RESULT_SELECTED) {
                        val destRes = DestinationVerifier.verify(obsAfter, taskContext.searchQuery, taskContext.targetApp, selectedCandidate)
                        if (destRes.verified) {
                            advanceState(GoalState.DESTINATION_VERIFIED, destRes.message)
                        }
                    }
                }
            } else {
                consecutiveFailures++
                lastFailedAction = action
                if (originalFailedAction == null) {
                    originalFailedAction = action
                }
                selectedCandidate = null
                Log.w(TAG, "[RECOVERY] Action verification failed: $note (attempt $consecutiveFailures/3)")
                AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "⚠️ $note (recovery attempt $consecutiveFailures/3)"))

                // If OPEN_APP_ONLY app launch execution succeeded but verification failed
                if (action is ComputerAction.LaunchApp && execResult.success && taskContext.intentType == TaskIntentType.OPEN_APP_ONLY) {
                    val isTelugu = isTeluguGoal(taskContext.goal)
                    val unverifiedMsg = if (isTelugu) "${action.appName} launch ayyindi, kani screen ni verify cheyyalekapoyanu." else "${action.appName} was launched, but screen verification could not be confirmed."
                    Log.i(TAG, "[CU] FINAL_TASK_STATUS status=EXECUTED_UNVERIFIED message='$unverifiedMsg'")
                    return ComputerUseResult(
                        success = false,
                        goal = taskContext.goal,
                        stepsExecuted = stepsExecuted,
                        message = unverifiedMsg,
                        taskStatus = TaskStatus.EXECUTED_UNVERIFIED,
                        executedPhases = executedPhases.toList(),
                        error = ComputerUseErrorCode.APP_NOT_FOREGROUND.name
                    )
                }
            }
        }

        Log.e(TAG, "[TASK] FAILED: Max steps reached without completing goal.")
        val isTelugu = isTeluguGoal(taskContext.goal)
        val termMsg = if (isTelugu) "${taskContext.targetApp ?: "App"} open cheyyadam fail ayyindi." else "Task reached max steps limit without completing."
        Log.i(TAG, "[CU] FINAL_TASK_STATUS status=FAILED message='$termMsg'")
        return ComputerUseResult(
            success = false,
            goal = taskContext.goal,
            stepsExecuted = stepsExecuted,
            message = termMsg,
            taskStatus = TaskStatus.FAILED,
            executedPhases = executedPhases.toList(),
            error = ComputerUseErrorCode.TASK_TIMEOUT.name
        )
    }

    fun extractTargetApp(goal: String): String? {
        val lower = goal.lowercase()
        return when {
            lower.contains("youtube") -> "YouTube"
            lower.contains("instagram") -> "Instagram"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("telegram") -> "Telegram"
            lower.contains("maps") || lower.contains("google maps") -> "Google Maps"
            lower.contains("chrome") || lower.contains("browser") -> "Chrome"
            lower.contains("spotify") -> "Spotify"
            lower.contains("settings") -> "Settings"
            else -> null
        }
    }

    fun extractSearchTarget(goal: String): String {
        val lower = goal.lowercase()
        // 1. Quoted string
        val quoted = Regex("""["']([^"']+)["']""").find(goal)
        if (quoted != null && quoted.groupValues[1].isNotBlank()) {
            return quoted.groupValues[1].trim()
        }

        // 2. Telugu pattern: "<query> (ani|ni|lo)? search"
        val teluguMatch = Regex("""(?i)([a-zA-Z0-9_\s\u0C00-\u0C7F]{2,40}?)(?:\s+ani|\s+ni|\s+lo)?\s+search""").find(goal)
        if (teluguMatch != null) {
            val candidate = teluguMatch.groupValues[1].trim()
            val filtered = candidate.replace(Regex("""(?i)^(?:open\s+\w+\.?\s*|youtube\s*|instagram\s*|whatsapp\s*)"""), "").trim()
                .replace(Regex("""(?i)^(?:lo|ni|ki|ani|na|meeda|nundi|nunchi)\s+"""), "").trim()
            if (filtered.isNotBlank() && !filtered.equals("search", ignoreCase = true)) {
                return filtered
            }
        }

        // 3. English pattern: "search (for|about|lo)? <query>"
        val englishMatch = Regex("""(?i)(?:search\s+for|search\s+about|search\s+lo|search|find|look\s+for)\s+([a-zA-Z0-9_\s\u0C00-\u0C7F]{2,40})""").find(goal)
        if (englishMatch != null) {
            var candidate = englishMatch.groupValues[1].trim()
            candidate = candidate.split(Regex("""(?i)\.|\,|\band\b|\bthen\b|\binspect\b|\bopen\b|\bidentify\b|\bchesi\b|\bcheyyi\b""")).firstOrNull()?.trim() ?: candidate
            candidate = candidate.replace(Regex("""(?i)^(?:lo|ni|ki|ani|na|meeda|nundi|nunchi)\s+"""), "").trim()
            if (candidate.isNotBlank() && !candidate.equals("for", ignoreCase = true) && !candidate.equals("search", ignoreCase = true)) {
                return candidate
            }
        }

        // 4. Known defaults / fallback
        if (lower.contains("max_ai_assistant")) return "max_ai_assistant"
        if (lower.contains("python automation")) return "Python automation tutorial"

        return ""
    }

    /**
     * Returns the visible permission choice when Maps is blocked by Android's
     * runtime permission controller. The automation must not silently grant a
     * permission on the user's behalf.
     */
    internal fun findBlockingPermissionPrompt(obs: ScreenObservation): String? {
        val labels = (obs.visibleTexts + obs.interactiveElements.map { it.displayLabel })
            .map { it.trim().lowercase() }
        val joined = labels.joinToString(" ")
        val isPermissionUi = obs.foregroundPackage.contains("permission", ignoreCase = true) ||
            joined.contains("allow") || joined.contains("while using")
        val mentionsLocation = joined.contains("location") || joined.contains("precise") || joined.contains("approximate")
        if (!isPermissionUi || !mentionsLocation) return null

        return labels.firstOrNull {
            it.contains("while using") || it == "allow" || it.contains("only this time") || it == "deny"
        } ?: "location permission"
    }
}
