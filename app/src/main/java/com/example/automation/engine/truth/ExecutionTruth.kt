package com.example.automation.engine.truth

import android.util.Log
import com.example.data.local.AgentTaskStepRecord
import java.util.concurrent.atomic.AtomicReference

/**
 * Authoritative record of execution truth for a tool call or action step.
 *
 * Invariant:
 * LLM response ≠ execution result ≠ verification result.
 * An action is only successful if executor succeeded AND verifier verified it.
 */
data class ExecutionTruth(
    val taskId: String? = null,
    val stepId: String? = null,
    val executionId: String,
    val actionName: String,
    val executorSuccess: Boolean,
    val verifiedSuccess: Boolean,
    val finalSuccess: Boolean = executorSuccess && verifiedSuccess,
    val status: String, // COMPLETED, FAILED, BLOCKED, WAITING_FOR_APPROVAL, TIMEOUT, NO_TOOL_DISPATCHED
    val failureReason: String? = null,
    val message: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central coordinator enforcing that the LLM never independently decides SUCCESS.
 * Ensures the required pipeline:
 * User Request -> Task -> Step -> Executor -> ExecutionResult -> Verifier -> Task State -> Assistant Response.
 */
object ExecutionTruthCoordinator {
    private const val TAG = "ExecutionTruth"

    private val lastTurnTruth = AtomicReference<ExecutionTruth?>(null)
    private val recentExecutions = java.util.concurrent.ConcurrentHashMap<String, ExecutionTruth>()
    private val turnStartedAt = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Records the verified execution truth of the current turn's tool/action execution.
     */
    fun recordExecution(truth: ExecutionTruth) {
        Log.i(TAG, "Recording execution truth: action=${truth.actionName}, executorSuccess=${truth.executorSuccess}, verified=${truth.verifiedSuccess}, finalSuccess=${truth.finalSuccess}, status=${truth.status}, reason=${truth.failureReason}")
        lastTurnTruth.set(truth)
        recentExecutions[truth.actionName] = truth
        if (truth.taskId != null) {
            recentExecutions["${truth.taskId}_${truth.actionName}"] = truth
            if (truth.stepId != null) {
                recentExecutions["${truth.taskId}_${truth.stepId}_${truth.actionName}"] = truth
            }
        }
        if (truth.executionId.isNotBlank()) {
            recentExecutions["${truth.executionId}_${truth.actionName}"] = truth
        }
    }

    fun recordExecution(
        taskId: String? = null,
        stepId: String? = null,
        executionId: String,
        actionName: String,
        executorSuccess: Boolean,
        verifiedSuccess: Boolean,
        failureReason: String? = null,
        message: String? = null,
        status: String? = null
    ): ExecutionTruth {
        val finalSuccess = executorSuccess && verifiedSuccess
        val finalStatus = status ?: (if (finalSuccess) "COMPLETED" else "FAILED")
        val truth = ExecutionTruth(
            taskId = taskId,
            stepId = stepId,
            executionId = executionId,
            actionName = actionName,
            executorSuccess = executorSuccess,
            verifiedSuccess = verifiedSuccess,
            finalSuccess = finalSuccess,
            status = finalStatus,
            failureReason = failureReason,
            message = message
        )
        recordExecution(truth)
        return truth
    }

    fun getLastExecution(): ExecutionTruth? = lastTurnTruth.get()

    fun getCallExecution(taskId: String? = null, stepId: String? = null, executionId: String? = null): ExecutionTruth? {
        // Supplying identity is a strict lookup, never permission to use an unrelated recent call.
        if (taskId != null || stepId != null || executionId != null) {
            return recentExecutions.values.filter { truth ->
                truth.actionName in setOf("CALL_PHONE", "call_contact") &&
                    (taskId == null || truth.taskId == taskId) &&
                    (stepId == null || truth.stepId == stepId) &&
                    (executionId == null || truth.executionId == executionId)
            }.maxByOrNull { it.timestamp }
        }
        return (recentExecutions["CALL_PHONE"] ?: recentExecutions["call_contact"]
            ?: lastTurnTruth.get()?.takeIf { it.actionName in setOf("CALL_PHONE", "call_contact") })
            ?.takeIf { it.timestamp >= turnStartedAt.get() }
    }

    fun clearTurnExecution() {
        lastTurnTruth.set(null)
        turnStartedAt.set(System.currentTimeMillis())
    }

    fun clearAllExecutions() {
        lastTurnTruth.set(null)
        recentExecutions.clear()
        turnStartedAt.set(0L)
    }

    /**
     * Records a canonical lifecycle event and propagates state transitions to Room through TaskTransitionAuthority.
     */
    suspend fun recordLifecycleEvent(
        event: ExecutionLifecycleEvent,
        store: com.example.automation.engine.state.AgentTaskStore? = null
    ): Boolean {
        if (store != null && event.taskId != null && event.stepId != null) {
            val targetStatus = when (event) {
                is ExecutionLifecycleEvent.ExecutionRequested -> TaskTransitionAuthority.STATUS_DISPATCHING
                is ExecutionLifecycleEvent.ExecutionStarted -> TaskTransitionAuthority.STATUS_RUNNING
                is ExecutionLifecycleEvent.ExecutionReturned -> {
                    if (event.executorSuccess) TaskTransitionAuthority.STATUS_VERIFYING else TaskTransitionAuthority.STATUS_FAILED
                }
                is ExecutionLifecycleEvent.VerificationStarted -> TaskTransitionAuthority.STATUS_VERIFYING
                is ExecutionLifecycleEvent.VerificationReturned -> {
                    if (event.verified) TaskTransitionAuthority.STATUS_SUCCEEDED else TaskTransitionAuthority.STATUS_FAILED
                }
                is ExecutionLifecycleEvent.StepCompleted -> event.status
                is ExecutionLifecycleEvent.TaskCompleted -> null
            }

            if (targetStatus != null) {
                val actor = when (event) {
                    is ExecutionLifecycleEvent.ExecutionRequested -> "CanonicalRequestAdapter"
                    is ExecutionLifecycleEvent.ExecutionStarted -> "ExecutionEngine"
                    is ExecutionLifecycleEvent.ExecutionReturned -> "ExecutionEngine"
                    is ExecutionLifecycleEvent.VerificationStarted -> "VerificationCoordinator"
                    is ExecutionLifecycleEvent.VerificationReturned -> "VERIFIER"
                    is ExecutionLifecycleEvent.StepCompleted -> if (event.status == TaskTransitionAuthority.STATUS_SUCCEEDED) "VERIFIER" else "ExecutionEngine"
                    is ExecutionLifecycleEvent.TaskCompleted -> "TaskTransitionAuthority"
                }

                val transition = store.transitionStep(
                    taskId = event.taskId!!,
                    stepId = event.stepId!!,
                    executionId = event.executionId,
                    targetStatus = targetStatus,
                    actor = actor,
                    failureReason = when (event) {
                        is ExecutionLifecycleEvent.ExecutionReturned -> event.failureReason
                        is ExecutionLifecycleEvent.VerificationReturned -> event.failureReason
                        is ExecutionLifecycleEvent.StepCompleted -> event.failureReason
                        else -> null
                    },
                    verificationEvidence = when (event) {
                        is ExecutionLifecycleEvent.VerificationReturned -> event.evidence
                        is ExecutionLifecycleEvent.StepCompleted -> event.evidence
                        else -> null
                    },
                    verificationId = when (event) {
                        is ExecutionLifecycleEvent.VerificationStarted -> event.verificationId
                        is ExecutionLifecycleEvent.VerificationReturned -> event.verificationId
                        else -> null
                    },
                    executorResultJson = when (event) {
                        is ExecutionLifecycleEvent.ExecutionReturned -> event.rawResultJson
                        is ExecutionLifecycleEvent.StepCompleted -> event.rawResultJson
                        else -> null
                    },
                    payloadHash = (event as? ExecutionLifecycleEvent.ExecutionRequested)?.payloadHash,
                    payloadJson = (event as? ExecutionLifecycleEvent.ExecutionRequested)?.payloadJson
                )
                if (transition is TaskTransitionAuthority.TransitionResult.Rejected) return false
            }
        }

        when (event) {
            is ExecutionLifecycleEvent.VerificationReturned -> {
                recordExecution(
                    taskId = event.taskId,
                    stepId = event.stepId,
                    executionId = event.executionId,
                    actionName = "VERIFICATION",
                    executorSuccess = event.verified,
                    verifiedSuccess = event.verified,
                    failureReason = event.failureReason,
                    message = event.evidence ?: if (event.verified) "Verified" else event.failureReason
                )
            }
            is ExecutionLifecycleEvent.StepCompleted -> {
                val isSuccess = event.status in setOf(TaskTransitionAuthority.STATUS_SUCCEEDED, TaskTransitionAuthority.STATUS_COMPLETED)
                recordExecution(
                    taskId = event.taskId,
                    stepId = event.stepId,
                    executionId = event.executionId,
                    actionName = "STEP_COMPLETED",
                    executorSuccess = isSuccess,
                    verifiedSuccess = event.verified && isSuccess,
                    failureReason = event.failureReason,
                    message = event.evidence ?: event.failureReason
                )
            }
            else -> Unit
        }
        return true
    }

    // Common phrases where the LLM asserts completion of an action
    private val COMPLETION_ASSERTION_PATTERNS = listOf(
        Regex("(?i)\\b(done|complete|completed|finished|all set)\\b"),
        Regex("(?i)\\b(sent the message|message sent|message has been sent|sent your message)\\b"),
        Regex("(?i)\\b(called|dialed|placed the call)\\b"),
        Regex("(?i)\\b(opened|launched|started the app)\\b"),
        Regex("(?i)\\b(turned on|turned off|switched on|switched off)\\b"),
        Regex("(?i)\\b(successfully|success)\\b"),
        Regex("(?i)\\b(screenshot captured|captured the screenshot|captured a screenshot|took the screenshot|took a screenshot|taken the screenshot|screenshot taken|captured)\\b"),
        Regex("(?i)(చేసేశాను|పంపేశాను|ఓపెన్ చేశాను|కంప్లీట్ అయ్యింది|చేశాను|చేసాను)"), // Telugu completion affirmations
        Regex("(?i)\\b(chesanu|chesa|chesesanu|cheysa|cheysanu)\\b")
    )

    /**
     * Checks if text contains explicit claims of successful action completion.
     */
    fun assertsCompletion(text: String): Boolean {
        if (text.isBlank()) return false
        return COMPLETION_ASSERTION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Checks semantically if text asserts that a phone call was initiated, connected, or completed.
     * Covers English, Telugu script, and Tinglish (Telugu transliterated in Latin script).
     * Excludes questions, prompts, cancellations, and failure statements.
     */
    fun assertsCallCompletion(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // 1. Exclude questions and confirmation prompts
        val isQuestionOrPrompt = lower.endsWith("?") ||
            lower.contains("want me to") ||
            lower.contains("should i") ||
            lower.contains("shall i") ||
            lower.contains("would you like") ||
            lower.contains("please confirm") ||
            lower.contains("are you sure") ||
            lower.contains("which number") ||
            lower.contains("which contact") ||
            lower.contains("who would you like") ||
            lower.contains("చేయమంటారా") ||
            lower.contains("చేయాలా") ||
            lower.contains("కాల్ చేయనా") ||
            lower.contains("కన్ఫర్మ్") ||
            lower.contains("cheyamantara") ||
            lower.contains("cheyala") ||
            lower.contains("call cheyana") ||
            lower.contains("confirm chey")

        if (isQuestionOrPrompt) return false

        // 2. Exclude failure or cancellation statements
        val isFailureOrCancelled = lower.contains("unable to") ||
            lower.contains("failed to") ||
            lower.contains("could not") ||
            lower.contains("couldn't") ||
            lower.contains("cancelled") ||
            lower.contains("canceled") ||
            lower.contains("చేయలేకపోయాను") ||
            lower.contains("కుదరలేదు") ||
            lower.contains("విఫలమైంది") ||
            lower.contains("రద్దు చేయబడింది") ||
            lower.contains("cheyaledu") ||
            lower.contains("kudaraledu") ||
            lower.contains("cancel chesa") ||
            lower.contains("failed")

        if (isFailureOrCancelled) return false

        // 3. Exclude pure contact searching announcements without call claim
        val isJustSearching = (lower.contains("searching") || lower.contains("looking for") || lower.contains("వెతుకుతున్నాను") || lower.contains("vethukuthunna")) &&
            !lower.contains("called") && !lower.contains("connected") && !lower.contains("చేశాను") && !lower.contains("chesa")
        if (isJustSearching) return false

        // 4. English call-completion assertions
        val englishCallAssertions = listOf(
            Regex("""(?i)\b(i('ve| have)? (called|dialed|placed (the|a) call to))\b"""),
            Regex("""(?i)\b(call(ed)? (is )?(connected|placed|in progress))\b"""),
            Regex("""(?i)\b(calling \S+ now)\b"""),
            Regex("""(?i)\b(connected (the|your) call)\b"""),
            Regex("""(?i)\b(call initiated and connected)\b"""),
            Regex("""(?i)\b(call has been placed)\b""")
        )
        if (englishCallAssertions.any { it.containsMatchIn(trimmed) }) return true

        // 5. Telugu script call-completion assertions
        val teluguScriptAssertions = listOf(
            Regex("""(?i)(కాల్|ఫోన్)\s*(చేశాను|చేశా|చేసాను|చేసా|చేసేశాను|చేసేశా)"""),
            Regex("""(?i)(కాల్|ఫోన్)\s*(కనెక్ట్\s*(చేశాను|చేసాను|అయింది|అయ్యింది)|కలిసింది)"""),
            Regex("""(?i)(కాల్|ఫోన్)\s*(స్టార్ట్\s*(చేశాను|చేసాను|అయింది)|మొదలైంది|వెళ్తోంది)"""),
            Regex("""(?i)(కాల్\s*చేసేశా|కాల్\s*కలిసింది)""")
        )
        if (teluguScriptAssertions.any { it.containsMatchIn(trimmed) }) return true

        // 6. Tinglish call-completion assertions
        val tinglishAssertions = listOf(
            Regex("""(?i)\bcall\s*(chesa|chesanu|chesesanu|cheysa|cheysanu)\b"""),
            Regex("""(?i)\bcall\s*connect\s*(chesa|chesanu|aindi|ayindi|ayyindi)\b"""),
            Regex("""(?i)\bcall\s*(kalisindi|velthondi|start\s*chesa|start\s*chesanu)\b"""),
            Regex("""(?i)\bphone\s*(chesa|chesanu|kalipanu)\b"""),
            Regex("""(?i)\bcall\s*cheseddam\b""")
        )
        if (tinglishAssertions.any { it.containsMatchIn(trimmed) }) return true

        // 7. Combined semantic proximity: call entity + completion predicate
        val hasCallReference = lower.contains("call") || lower.contains("dial") || lower.contains("కాల్") || lower.contains("ఫోన్")
        val hasCompletionPredicate = lower.contains("connected") || lower.contains("placed") || lower.contains("done") ||
            lower.contains("all set") || lower.contains("చేశాను") || lower.contains("చేసేశాను") || lower.contains("chesanu") || lower.contains("chesa")
        if (hasCallReference && hasCompletionPredicate) {
            return true
        }

        return false
    }

    /**
     * Enforces the architectural invariant:
     * LLM response ≠ execution result ≠ verification result.
     *
     * A pending/failed call MUST NOT produce "call started/connected" claims.
     * If the LLM makes an unverified or premature completion claim, this method
     * intercepts and overrides the response with the verified reality.
     */
    fun validateOrCorrectAssistantResponse(
        llmResponse: String,
        executionTruth: ExecutionTruth? = lastTurnTruth.get(),
        taskId: String? = null,
        stepId: String? = null,
        executionId: String? = null
    ): String {
        val trimmed = llmResponse.trim()
        if (trimmed.isBlank()) return llmResponse

        // P0-1: Gated semantic call-completion verification
        if (assertsCallCompletion(trimmed)) {
            val callTruth = getCallExecution(taskId, stepId, executionId)
                ?: executionTruth?.takeIf { it.actionName in setOf("CALL_PHONE", "call_contact") &&
                    (taskId == null || it.taskId == taskId) && (stepId == null || it.stepId == stepId) &&
                    (executionId == null || it.executionId == executionId) }

            if (callTruth == null) {
                // False claim without ANY CALL_PHONE execution in context
                Log.w(TAG, "INTERCEPTED FAKE CALL COMPLETION: LLM claimed '$trimmed' but no CALL_PHONE action was executed.")
                val pendingConfirmation = com.example.automation.engine.PendingCallManager.getPendingConfirmation()
                return when {
                    pendingConfirmation != null -> {
                        "Are you sure you want to call ${pendingConfirmation.displayName} (${pendingConfirmation.phoneNumber})? Please confirm."
                    }
                    com.example.automation.engine.PendingCallManager.state is com.example.automation.engine.CallState.AwaitingContactSelection -> {
                        "I found multiple contacts. Please choose which one to call."
                    }
                    else -> {
                        "I haven't placed any call yet. Who would you like me to call?"
                    }
                }
            }

            // A CALL_PHONE execution exists. Check if it genuinely verified CALL_PLACED.
            if (!callTruth.finalSuccess || callTruth.status != "CALL_PLACED") {
                Log.w(TAG, "INTERCEPTED PREMATURE/FAILED CALL COMPLETION: LLM claimed '$trimmed' but CALL_PHONE status is '${callTruth.status}' (finalSuccess=${callTruth.finalSuccess}).")
                return when (callTruth.status) {
                    "CALL_REQUESTED" -> {
                        "Call intent has been requested from Android, awaiting carrier connection."
                    }
                    "WAITING_FOR_APPROVAL", "CONFIRMATION_REQUIRED" -> {
                        "Are you sure you want to place this call? Please confirm."
                    }
                    "WAITING_FOR_INPUT", "CONTACT_SELECTION_REQUIRED" -> {
                        "Please specify which contact or number to call."
                    }
                    "FAILED" -> {
                        "I was unable to place the call: ${callTruth.failureReason ?: callTruth.message ?: "Call failed"}."
                    }
                    else -> {
                        "Call has not been connected yet (status: ${callTruth.status})."
                    }
                }
            }
        }

        // Generic non-call action failure check
        val scoped = taskId != null || stepId != null || executionId != null
        val activeTruth = if (scoped) recentExecutions.values.filter {
            (taskId == null || it.taskId == taskId) && (stepId == null || it.stepId == stepId) &&
                (executionId == null || it.executionId == executionId)
        }.maxByOrNull { it.timestamp } else executionTruth ?: lastTurnTruth.get()
        if (activeTruth == null && assertsCompletion(trimmed)) {
            return "I cannot confirm completion: no verified execution result is available."
        }
        if (activeTruth != null && !activeTruth.finalSuccess) {
            val assertsSuccess = assertsCompletion(trimmed)
            if (assertsSuccess) {
                val reason = activeTruth.failureReason
                    ?: activeTruth.message
                    ?: "Verification failed"
                val corrected = "I was unable to complete the request: $reason."
                Log.w(TAG, "INTERCEPTED FALSE SUCCESS: LLM said '$trimmed' while executor/verifier status is '${activeTruth.status}' (reason='$reason'). Corrected to: '$corrected'")
                return corrected
            }
        }

        return llmResponse
    }

    /**
     * Derives parent task status strictly from child-step state.
     * Invariants:
     * - A task with:
     *   Step 1 = SUCCESS
     *   Step 2 = FAILED
     *   Step 3 = NOT_EXECUTED
     *   must NEVER be reported as COMPLETED.
     * - Parent task can only be COMPLETED if steps is non-empty and ALL steps are SUCCESS / COMPLETED.
     */
    fun deriveParentStatusFromSteps(steps: List<AgentTaskStepRecord>): Pair<String, String?> {
        if (steps.isEmpty()) {
            return "FAILED" to "Task has no execution steps"
        }

        val failedStep = steps.firstOrNull { it.status == "FAILED" }
        if (failedStep != null) {
            return "FAILED" to (failedStep.failureReason ?: "Step '${failedStep.stepId}' (${failedStep.actionType}) failed")
        }

        val cancelledStep = steps.firstOrNull { it.status == "CANCELLED" }
        if (cancelledStep != null) {
            return "CANCELLED" to (cancelledStep.failureReason ?: "Step '${cancelledStep.stepId}' was cancelled")
        }

        val interruptedStep = steps.firstOrNull { it.status == "INTERRUPTED" }
        if (interruptedStep != null) {
            return "INTERRUPTED" to (interruptedStep.failureReason ?: "Step '${interruptedStep.stepId}' was interrupted")
        }

        val waitingApprovalStep = steps.firstOrNull { it.status == "WAITING_FOR_APPROVAL" }
        if (waitingApprovalStep != null) {
            return "WAITING_FOR_APPROVAL" to null
        }

        steps.firstOrNull { it.status in setOf("BLOCKED", "UNVERIFIED", "WAITING_FOR_INPUT") }?.let {
            return it.status to it.failureReason
        }

        val inProgressStep = steps.firstOrNull { it.status in setOf("READY", "RUNNING", "VERIFYING", "RECOVERING", "DISPATCHING") }
        if (inProgressStep != null) {
            return "RUNNING" to null
        }

        val unexecutedStep = steps.firstOrNull { it.status in setOf("NOT_EXECUTED", "PENDING", "SKIPPED") }
        if (unexecutedStep != null) {
            return "FAILED" to "Task incomplete: step '${unexecutedStep.stepId}' was not executed"
        }

        val allSucceeded = steps.all { it.status in setOf("SUCCESS", "COMPLETED", "SUCCEEDED") && it.verificationResult == "VERIFIED" }
        return if (allSucceeded) {
            "COMPLETED" to null
        } else {
            "FAILED" to "Not all steps completed successfully"
        }
    }
}
