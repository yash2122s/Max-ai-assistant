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
    private const val MAX_TASK_TIMEOUT_MS = 60_000L
    private const val STEP_SETTLE_DELAY_MS = 350L

    suspend fun execute(
        context: Context,
        goal: String,
        targetApp: String? = null,
        maxSteps: Int = 10
    ): ComputerUseResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val query = extractSearchTarget(goal)
        val isSearch = query.isNotBlank()
        val taskContext = TaskContext(
            goal = goal,
            targetApp = targetApp ?: extractTargetApp(goal),
            searchQuery = query,
            isSearchGoal = isSearch,
            startTime = startTime,
            maxSteps = maxSteps
        )

        Log.i(TAG, "[TASK] EXECUTE_START taskId=${taskContext.taskId} goal='$goal' targetApp='${taskContext.targetApp}' query='$query' isSearch=$isSearch maxSteps=$maxSteps")

        // 1. Master switch safety check
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

        // 2. Concurrency lock acquisition
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

        try {
            val timedResult = withTimeoutOrNull(MAX_TASK_TIMEOUT_MS) {
                executeLoop(context, taskContext)
            }

            val finalResult = timedResult ?: ComputerUseResult(
                success = false,
                goal = goal,
                stepsExecuted = maxSteps,
                message = "Task timed out after ${MAX_TASK_TIMEOUT_MS / 1000}s",
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
        taskContext: TaskContext
    ): ComputerUseResult {
        var stepsExecuted = 0
        var currentState = GoalState.INIT
        val executedPhases = mutableListOf<GoalState>(GoalState.INIT)
        var consecutiveFailures = 0
        var lastFailedAction: ComputerAction? = null
        var selectedCandidate: TargetGrounding? = null

        fun advanceState(nextState: GoalState, evidence: String) {
            if (nextState.ordinal > currentState.ordinal) {
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
            if (ComputerUseConcurrency.isTaskCancelled(taskContext)) {
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
            val obsBefore = ScreenObservationProvider.captureObservation(context)
            Log.i(TAG, "[OBSERVE] step=$step obsId=${obsBefore.observationId} app=${obsBefore.foregroundPackage} elements=${obsBefore.interactiveElements.size} texts=${obsBefore.visibleTexts.size}")

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
                    else "${taskContext.targetApp ?: "App"} open ayyindi. Verify chesanu."
                } else {
                    if (taskContext.isSearchGoal) "Target content for '${taskContext.searchQuery}' successfully verified on screen."
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
            val action = if (consecutiveFailures > 0 && lastFailedAction != null) {
                if (consecutiveFailures >= ComputerUseRecoveryEngine.MAX_RECOVERY_ATTEMPTS) {
                    Log.e(TAG, "[RECOVERY] Exhausted max 3 recovery attempts for '${lastFailedAction.actionName}'. Aborting.")
                    val isTelugu = isTeluguGoal(taskContext.goal)
                    val recMsg = if (isTelugu) "${taskContext.targetApp ?: "App"} open cheyyadam fail ayyindi." else "Action '${lastFailedAction.actionName}' failed after 3 recovery attempts."
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
                    failedAction = lastFailedAction,
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
                    (!taskContext.isSearchGoal && obsBefore.isAppForeground && (obsBefore.interactiveElements.isNotEmpty() || obsBefore.visibleTexts.isNotEmpty() || obsBefore.screenshotPath != null))

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
            val execResult = ComputerActionExecutor.execute(context, action)
            Log.i(TAG, "[CU] ACTION_EXECUTED action='${action.actionName}' success=${execResult.success} message='${execResult.message}'")

            // --- 6. ADAPTIVE SETTLE ---
            val settleTime = when (action) {
                is ComputerAction.LaunchApp -> 1500L
                is ComputerAction.TypeText -> 500L
                is ComputerAction.PressEnter -> 800L
                is ComputerAction.Swipe, is ComputerAction.Scroll -> 600L
                is ComputerAction.ObserveMore -> 300L
                else -> STEP_SETTLE_DELAY_MS
            }
            Log.i(TAG, "[WAIT] duration=${settleTime}ms")
            delay(settleTime)

            // --- 7. STATE: RE-OBSERVE & VERIFY ---
            val obsAfter = ScreenObservationProvider.captureObservation(context)
            val rootAvailable = JarvisAccessibilityService.getActiveRoot() != null
            val currentPkg = obsAfter.foregroundPackage
            val nodeCount = obsAfter.interactiveElements.size + obsAfter.visibleTexts.size
            val screenshotAvailable = obsAfter.screenshotPath != null
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
            Log.i(TAG, "[CU] VERIFICATION_STATUS status=$verStatus reason='$note'")
            Log.i(TAG, "[VERIFY] action=${action.actionName} isVerified=$isActionVerified note='$note'")

            if (isActionVerified) {
                consecutiveFailures = 0
                lastFailedAction = null
                AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "✓ $note"))

                // Phase advancement rules upon verified action execution
                when (action) {
                    is ComputerAction.LaunchApp -> {
                        advanceState(GoalState.APP_READY, action.appName)

                        // If goal was simply to launch and verify this app, finish immediately with VERIFIED_SUCCESS
                        if (!taskContext.isSearchGoal) {
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
                        }
                    }
                    is ComputerAction.TapCoordinate, is ComputerAction.TapElement -> {
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
                        if (action.pressEnterAfter) {
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
                Log.w(TAG, "[RECOVERY] Action verification failed: $note (attempt $consecutiveFailures/3)")
                AssistantEventBus.emit(AssistantEvent.ToolProgress("computer_use", "⚠️ $note (recovery attempt $consecutiveFailures/3)"))

                // If non-search app launch execution succeeded but verification failed
                if (action is ComputerAction.LaunchApp && execResult.success && !taskContext.isSearchGoal) {
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
        val teluguMatch = Regex("""(?i)([a-zA-Z0-9_\s]{2,40}?)(?:\s+ani|\s+ni|\s+lo)?\s+search""").find(goal)
        if (teluguMatch != null) {
            val candidate = teluguMatch.groupValues[1].trim()
            val filtered = candidate.replace(Regex("""(?i)^(?:open\s+\w+\.?\s*|youtube\s*|instagram\s*|whatsapp\s*)"""), "").trim()
            if (filtered.isNotBlank() && !filtered.equals("search", ignoreCase = true)) {
                return filtered
            }
        }

        // 3. English pattern: "search (for|about|lo)? <query>"
        val englishMatch = Regex("""(?i)(?:search\s+for|search\s+about|search\s+lo|search|find|look\s+for)\s+([a-zA-Z0-9_\s]{2,40})""").find(goal)
        if (englishMatch != null) {
            var candidate = englishMatch.groupValues[1].trim()
            candidate = candidate.split(Regex("""(?i)\.|\,|\band\b|\bthen\b|\binspect\b|\bopen\b|\bidentify\b|\bchesi\b|\bcheyyi\b""")).firstOrNull()?.trim() ?: candidate
            if (candidate.isNotBlank() && !candidate.equals("for", ignoreCase = true) && !candidate.equals("search", ignoreCase = true)) {
                return candidate
            }
        }

        // 4. Known defaults / fallback
        if (lower.contains("max_ai_assistant")) return "max_ai_assistant"
        if (lower.contains("python automation")) return "Python automation tutorial"

        return ""
    }
}
