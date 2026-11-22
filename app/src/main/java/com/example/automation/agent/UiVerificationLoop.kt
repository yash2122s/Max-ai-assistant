package com.example.automation.agent

import android.content.Context
import android.util.Log
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.preferences.SettingsManager
import com.example.voice.vision.ScreenCaptureProvider
import com.example.voice.vision.ScreenContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * Autonomous UI verification loop implementing:
 * Observe -> Decide -> Revalidate -> Act -> Adaptive Wait -> Re-observe -> Verify
 *
 * Fully bounded to maxSteps to prevent infinite loops, with cancellation support and
 * provider-level fallback from OpenRouter to Gemini.
 */
class UiVerificationLoop(
    private val context: Context,
    private val router: UiAgentRouter = UiAgentRouter(context),
    private val decisionProvider: (suspend (UiTask, UiObservation, List<UiStepRecord>) -> Triple<UiAgentReasoningResult, String, String>)? = null,
    private val actionExecutor: suspend (Context, UiAction) -> Boolean = { actionContext, action ->
        UiActionExecutor.executeAction(actionContext, action)
    },
    private val observationProvider: (suspend () -> UiObservation)? = null,
    private val adaptiveWait: (suspend (UiAction) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UiVerificationLoop"
        private const val DEFAULT_SETTLE_DELAY_MS = 400L
        private const val APP_LAUNCH_MAX_WAIT_MS = 2500L
        private const val APP_LAUNCH_POLL_INTERVAL_MS = 150L
        private val SCREEN_BOUND_ACTIONS = setOf(
            UiAction.Type.TAP, UiAction.Type.LONG_PRESS, UiAction.Type.SWIPE,
            UiAction.Type.SCROLL, UiAction.Type.TYPE, UiAction.Type.KEY_ENTER
        )
    }

    suspend fun executeTask(
        task: UiTask,
        cancellationToken: com.example.automation.engine.CancellationToken? = null
    ): UiTaskResult = withContext(Dispatchers.Default) {
        val stepHistory = mutableListOf<UiStepRecord>()
        val actionsTaken = mutableListOf<UiAction>()

        if (!MaxRuntimeManager.isMaxEnabled(context)) {
            Log.w(TAG, "UI Task rejected: MAX master switch is OFF.")
            return@withContext UiTaskResult(
                taskId = task.id,
                success = false,
                message = "MAX is currently OFF.",
                stepsExecuted = 0,
                providerUsed = "none",
                modelUsed = "none",
                error = "MAX_DISABLED"
            )
        }

        Log.d(TAG, "Starting UI Automation Loop: task=${task.id} (maxSteps=${task.maxSteps})")

        val geminiProvider = GeminiUiAgentProvider()
        val openRouterProvider = OpenRouterUiAgentProvider()
        val settings = SettingsManager(context)

        for (stepIndex in 1..task.maxSteps) {
            // Check cancellation & runtime status
            if (cancellationToken?.isCancelled == true) {
                Log.w(TAG, "UI Task cancelled by token at step $stepIndex.")
                return@withContext UiTaskResult(
                    taskId = task.id,
                    success = false,
                    message = "Task cancelled by caller.",
                    stepsExecuted = stepIndex - 1,
                    providerUsed = "none",
                    modelUsed = "none",
                    actionsTaken = actionsTaken,
                    error = "CANCELLED"
                )
            }

            if (!MaxRuntimeManager.isMaxEnabled(context)) {
                Log.w(TAG, "UI Task aborted at step $stepIndex: MAX turned OFF.")
                return@withContext UiTaskResult(
                    taskId = task.id,
                    success = false,
                    message = "Task cancelled: MAX was turned OFF.",
                    stepsExecuted = stepIndex - 1,
                    providerUsed = "none",
                    modelUsed = "none",
                    actionsTaken = actionsTaken,
                    error = "MAX_DISABLED"
                )
            }

            Log.d(TAG, "── Step $stepIndex / ${task.maxSteps} ──")
            val stepResult = try {
                withTimeout(task.stepTimeoutMs.coerceAtLeast(1L)) {
                    executeStep(
                        task = task,
                        stepIndex = stepIndex,
                        stepHistory = stepHistory,
                        actionsTaken = actionsTaken,
                        cancellationToken = cancellationToken,
                        openRouterProvider = openRouterProvider,
                        geminiProvider = geminiProvider,
                        settings = settings
                    )
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "UI step $stepIndex exceeded ${task.stepTimeoutMs}ms")
                return@withContext UiTaskResult(
                    taskId = task.id,
                    success = false,
                    message = "UI step $stepIndex timed out after ${task.stepTimeoutMs}ms.",
                    stepsExecuted = stepIndex - 1,
                    providerUsed = "provider-pipeline",
                    modelUsed = "dynamic",
                    actionsTaken = actionsTaken,
                    error = "ACTION_TIMEOUT"
                )
            }

            if (stepResult != null) return@withContext stepResult
        }

        // Bounded loop completed without explicit finish
        Log.w(TAG, "Task reached bounded step limit (${task.maxSteps} steps)")
        UiTaskResult(
            taskId = task.id,
            success = false,
            message = "Task reached step limit (${task.maxSteps}) without completing.",
            stepsExecuted = task.maxSteps,
            providerUsed = "provider-pipeline",
            modelUsed = "dynamic",
            actionsTaken = actionsTaken,
            error = "STEP_LIMIT_EXCEEDED"
        )
    }

    private suspend fun executeStep(
        task: UiTask,
        stepIndex: Int,
        stepHistory: MutableList<UiStepRecord>,
        actionsTaken: MutableList<UiAction>,
        cancellationToken: com.example.automation.engine.CancellationToken?,
        openRouterProvider: OpenRouterUiAgentProvider,
        geminiProvider: GeminiUiAgentProvider,
        settings: SettingsManager
    ): UiTaskResult? {
        suspend fun cancellationResult(): UiTaskResult = UiTaskResult(
            taskId = task.id,
            success = false,
            message = "Task cancelled by caller.",
            stepsExecuted = stepIndex - 1,
            providerUsed = "none",
            modelUsed = "none",
            actionsTaken = actionsTaken,
            error = "CANCELLED"
        )

        fun isCancelled(): Boolean = cancellationToken?.isCancelled == true

        suspend fun interruptionResult(): UiTaskResult? {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) return cancellationResult()
            if (!MaxRuntimeManager.isMaxEnabled(context)) {
                return failedActionResult(task, stepIndex, "none", "none", actionsTaken,
                    "MAX_DISABLED", "Task cancelled: MAX was turned OFF.")
            }
            return null
        }

        interruptionResult()?.let { return it }

        // 1. OBSERVE
        val observationBefore = captureCurrentObservation()
        interruptionResult()?.let { return it }

        // 2. DECIDE
        val (reasoningResult, providerUsed, modelUsed) = decisionProvider?.invoke(
            task,
            observationBefore,
            stepHistory
        ) ?: decideNextAction(
            task = task,
            observation = observationBefore,
            stepHistory = stepHistory,
            openRouterProvider = openRouterProvider,
            geminiProvider = geminiProvider,
            settings = settings
        )
        interruptionResult()?.let { return it }

        when (reasoningResult) {
            is UiAgentReasoningResult.FinishTask -> {
                if (!reasoningResult.success) {
                    return UiTaskResult(
                        taskId = task.id,
                        success = false,
                        message = reasoningResult.message,
                        stepsExecuted = stepIndex,
                        providerUsed = providerUsed,
                        modelUsed = modelUsed,
                        actionsTaken = actionsTaken,
                        error = "MODEL_REPORTED_FAILURE"
                    )
                }

                // P0 completion gate: a model claim is never completion evidence.
                val finalObservation = captureCurrentObservation()
                interruptionResult()?.let { return it }
                val completion = UiTaskCompletionVerifier.verify(task, finalObservation, stepHistory)
                if (!completion.verified) {
                    Log.w(TAG, completion.message)
                    return UiTaskResult(
                        taskId = task.id,
                        success = false,
                        message = completion.message,
                        stepsExecuted = stepIndex,
                        providerUsed = providerUsed,
                        modelUsed = modelUsed,
                        actionsTaken = actionsTaken,
                        error = completion.errorCode
                    )
                }

                return UiTaskResult(
                    taskId = task.id,
                    success = true,
                    message = reasoningResult.message.ifBlank { completion.message },
                    stepsExecuted = stepIndex,
                    providerUsed = providerUsed,
                    modelUsed = modelUsed,
                    actionsTaken = actionsTaken,
                    completionVerified = true
                )
            }

            is UiAgentReasoningResult.Error -> {
                val cleanMessage = reasoningResult.message.ifBlank { "UI Agent is temporarily unavailable. Reconnecting..." }
                return UiTaskResult(
                    taskId = task.id,
                    success = false,
                    message = cleanMessage,
                    stepsExecuted = stepIndex,
                    providerUsed = providerUsed,
                    modelUsed = modelUsed,
                    actionsTaken = actionsTaken,
                    error = reasoningResult.errorCode ?: "REASONING_ERROR"
                )
            }

            is UiAgentReasoningResult.ActionDecision -> {
                val action = reasoningResult.action
                val targetPackage = task.targetPackage?.takeIf { it.isNotBlank() }
                val displayMetrics = context.resources.displayMetrics
                val actionIsValid = action.validate(
                    displayWidth = displayMetrics.widthPixels,
                    displayHeight = displayMetrics.heightPixels,
                    observation = observationBefore
                )
                if (!actionIsValid) {
                    return failedActionResult(task, stepIndex, providerUsed, modelUsed, actionsTaken, "ACTION_INVALID", "UI action ${action.type} has missing or unsafe parameters.")
                }

                if (targetPackage != null && action.type == UiAction.Type.LAUNCH_APP &&
                    !action.packageName.equals(targetPackage, ignoreCase = true)
                ) {
                    return failedActionResult(task, stepIndex, providerUsed, modelUsed, actionsTaken, "PACKAGE_NOT_AUTHORIZED", "Launch package ${action.packageName} is not authorized for target package $targetPackage.")
                }
                if (targetPackage != null && action.type != UiAction.Type.LAUNCH_APP &&
                    !observationBefore.foregroundPackage.equals(targetPackage, ignoreCase = true)
                ) {
                    return failedActionResult(task, stepIndex, providerUsed, modelUsed, actionsTaken, "WRONG_FOREGROUND_PACKAGE", "Foreground package is ${observationBefore.foregroundPackage}, expected $targetPackage.")
                }

                // Reasoning may take seconds. Never execute coordinates or input against
                // the screen observed before that network round trip without reacquiring it.
                if (action.type in SCREEN_BOUND_ACTIONS) {
                    val currentObservation = captureCurrentObservation()
                    interruptionResult()?.let { return it }
                    if (targetPackage != null && !currentObservation.foregroundPackage.equals(targetPackage, ignoreCase = true)) {
                        return failedActionResult(task, stepIndex, providerUsed, modelUsed, actionsTaken,
                            "WRONG_FOREGROUND_PACKAGE", "Foreground package changed before action dispatch.")
                    }
                    if (!sameInteractionState(observationBefore, currentObservation)) {
                        return failedActionResult(task, stepIndex, providerUsed, modelUsed, actionsTaken,
                            "STALE_OBSERVATION", "Screen changed while deciding; action was not dispatched. Observe and plan again.")
                    }
                }

                Log.d(TAG, "Action decision: ${action.type}")

                // 3. ACT. Check cancellation immediately before dispatch so a cancelled
                // decision cannot launch an OS action.
                interruptionResult()?.let { return it }
                actionsTaken.add(action)
                val executionSuccess = actionExecutor(context, action)
                interruptionResult()?.let { return it }

                // 4. WAIT
                adaptiveWait?.invoke(action) ?: performAdaptiveWait(action)
                interruptionResult()?.let { return it }

                // 5. RE-OBSERVE (including after launch)
                val observationAfter = captureCurrentObservation()
                interruptionResult()?.let { return it }

                // 6. VERIFY
                val (isVerified, note) = verifyActionOutcome(action, observationBefore, observationAfter, targetPackage)
                Log.d(TAG, "Step $stepIndex verification: action=${action.type} verified=$isVerified")

                stepHistory.add(
                    UiStepRecord(
                        stepIndex = stepIndex,
                        action = action.copy(isSuccess = executionSuccess),
                        observationBefore = observationBefore,
                        observationAfter = observationAfter,
                        verified = isVerified,
                        verificationNote = note
                    )
                )

                if (!executionSuccess || !isVerified) {
                    val failureCode = if (!executionSuccess) "ACTION_EXECUTION_FAILED" else "ACTION_UNVERIFIED"
                    val failureMessage = if (!executionSuccess) {
                        "UI action ${action.type} failed to execute; task stopped before completion."
                    } else {
                        "UI action ${action.type} executed but was not locally verified; task stopped before completion."
                    }
                    return failedActionResult(task, stepIndex, providerUsed, modelUsed, actionsTaken, failureCode, failureMessage)
                }
            }
        }

        return null
    }

    private fun failedActionResult(
        task: UiTask,
        stepIndex: Int,
        providerUsed: String,
        modelUsed: String,
        actionsTaken: List<UiAction>,
        error: String,
        message: String
    ): UiTaskResult = UiTaskResult(
        taskId = task.id,
        success = false,
        message = message,
        stepsExecuted = stepIndex,
        providerUsed = providerUsed,
        modelUsed = modelUsed,
        actionsTaken = actionsTaken,
        error = error
    )

    private suspend fun decideNextAction(
        task: UiTask,
        observation: UiObservation,
        stepHistory: List<UiStepRecord>,
        openRouterProvider: OpenRouterUiAgentProvider,
        geminiProvider: GeminiUiAgentProvider,
        settings: SettingsManager
    ): Triple<UiAgentReasoningResult, String, String> {
        val openRouterKey = settings.uiAgentApiKey
        val openRouterModel = settings.uiAgentModel.ifBlank { OpenRouterUiAgentProvider.DEFAULT_MODEL }
        val geminiKey = settings.geminiApiKey

        // Provider Strategy:
        // 1. If OpenRouter is configured, try OpenRouter first
        if (openRouterKey.isNotBlank()) {
            val res = openRouterProvider.reasonNextAction(
                task = task,
                observation = observation,
                stepHistory = stepHistory,
                apiKey = openRouterKey,
                model = openRouterModel
            )

            // If OpenRouter succeeded or reached finish, return decision
            if (res is UiAgentReasoningResult.ActionDecision || res is UiAgentReasoningResult.FinishTask) {
                return Triple(res, OpenRouterUiAgentProvider.PROVIDER_ID, openRouterModel)
            }

            // On failure/timeout, fall back to Gemini 2.5 Flash -> Gemini 1.5 Flash
            Log.w(TAG, "OpenRouter reasoning failed; triggering Gemini UI Agent fallback: ${(res as? UiAgentReasoningResult.Error)?.message}")
            if (geminiKey.isNotBlank()) {
                val geminiRes = geminiProvider.reasonNextAction(
                    task = task,
                    observation = observation,
                    stepHistory = stepHistory,
                    apiKey = geminiKey,
                    model = GeminiUiAgentProvider.DEFAULT_MODEL
                )
                if (geminiRes is UiAgentReasoningResult.ActionDecision || geminiRes is UiAgentReasoningResult.FinishTask) {
                    return Triple(geminiRes, GeminiUiAgentProvider.PROVIDER_ID, GeminiUiAgentProvider.DEFAULT_MODEL)
                }

                // Secondary fallback to Gemini 1.5 Flash
                Log.w(TAG, "Gemini ${GeminiUiAgentProvider.DEFAULT_MODEL} failed; trying secondary fallback ${GeminiUiAgentProvider.FALLBACK_MODEL}")
                val fallbackRes = geminiProvider.reasonNextAction(
                    task = task,
                    observation = observation,
                    stepHistory = stepHistory,
                    apiKey = geminiKey,
                    model = GeminiUiAgentProvider.FALLBACK_MODEL
                )
                return Triple(fallbackRes, GeminiUiAgentProvider.PROVIDER_ID, GeminiUiAgentProvider.FALLBACK_MODEL)
            }
            return Triple(res, OpenRouterUiAgentProvider.PROVIDER_ID, openRouterModel)
        }

        // 2. Direct Gemini UI Agent (2.5 Flash -> 1.5 Flash fallback) if OpenRouter is unconfigured
        if (geminiKey.isNotBlank()) {
            val res = geminiProvider.reasonNextAction(
                task = task,
                observation = observation,
                stepHistory = stepHistory,
                apiKey = geminiKey,
                model = GeminiUiAgentProvider.DEFAULT_MODEL
            )
            if (res is UiAgentReasoningResult.ActionDecision || res is UiAgentReasoningResult.FinishTask) {
                return Triple(res, GeminiUiAgentProvider.PROVIDER_ID, GeminiUiAgentProvider.DEFAULT_MODEL)
            }

            // Secondary fallback to Gemini 1.5 Flash
            Log.w(TAG, "Gemini ${GeminiUiAgentProvider.DEFAULT_MODEL} failed; trying secondary fallback ${GeminiUiAgentProvider.FALLBACK_MODEL}")
            val fallbackRes = geminiProvider.reasonNextAction(
                task = task,
                observation = observation,
                stepHistory = stepHistory,
                apiKey = geminiKey,
                model = GeminiUiAgentProvider.FALLBACK_MODEL
            )
            return Triple(fallbackRes, GeminiUiAgentProvider.PROVIDER_ID, GeminiUiAgentProvider.FALLBACK_MODEL)
        }

        // 3. Unconfigured fallback
        val emptyRes = UiAgentReasoningResult.Error("No API keys configured for UI Agent (neither OpenRouter nor Gemini)", errorCode = "MISSING_KEY", retryable = false)
        return Triple(emptyRes, "none", "none")
    }

    private suspend fun performAdaptiveWait(action: UiAction) {
        when (action.type) {
            UiAction.Type.LAUNCH_APP -> {
                val targetPkg = action.packageName
                if (targetPkg != null) {
                    var waited = 0L
                    while (waited < APP_LAUNCH_MAX_WAIT_MS) {
                        delay(APP_LAUNCH_POLL_INTERVAL_MS)
                        waited += APP_LAUNCH_POLL_INTERVAL_MS
                        val screenContext = ScreenContextProvider.captureScreenContext(context)
                        if (screenContext.packageName == targetPkg) {
                            Log.d(TAG, "Adaptive wait: Target package '$targetPkg' in foreground after ${waited}ms")
                            break
                        }
                    }
                } else {
                    delay(DEFAULT_SETTLE_DELAY_MS)
                }
            }
            UiAction.Type.TYPE -> delay(350L)
            UiAction.Type.SWIPE, UiAction.Type.SCROLL -> delay(400L)
            UiAction.Type.TAP, UiAction.Type.LONG_PRESS -> delay(300L)
            UiAction.Type.KEY_BACK, UiAction.Type.KEY_HOME, UiAction.Type.KEY_ENTER -> delay(300L)
            UiAction.Type.SCREENSHOT, UiAction.Type.FINISH -> delay(100L)
        }
    }

    private suspend fun captureCurrentObservation(): UiObservation {
        observationProvider?.let { return it.invoke() }

        val screenshot = ScreenCaptureProvider.captureCompressedJpeg()
        val screenContext = ScreenContextProvider.captureScreenContext(context)

        return UiObservation(
            screenshotJpeg = screenshot,
            prunedElements = screenContext.prunedNodes,
            foregroundPackage = screenContext.packageName,
            visibleText = screenContext.visibleText,
            clickableElements = screenContext.clickableElements
        )
    }

    private fun verifyActionOutcome(
        action: UiAction,
        before: UiObservation,
        after: UiObservation,
        targetPackage: String?
    ): Pair<Boolean, String> {
        if (targetPackage != null && !after.foregroundPackage.equals(targetPackage, ignoreCase = true)) {
            return Pair(false, "Foreground package is ${after.foregroundPackage}, expected $targetPackage")
        }

        return when (action.type) {
            UiAction.Type.LAUNCH_APP -> {
                val target = action.packageName
                if (target != null && after.foregroundPackage == target) {
                    Pair(true, "App $target confirmed in foreground")
                } else {
                    Pair(false, "Foreground package is ${after.foregroundPackage}, expected $target")
                }
            }
            UiAction.Type.TYPE -> {
                val typed = action.text ?: ""
                val editors = before.prunedElements.filter { it.isEditable }
                val previousField = editors.filter { it.isFocused }.singleOrNull()
                    ?: editors.singleOrNull()
                val sameFields = after.prunedElements.filter { field ->
                    field.isEditable && previousField != null &&
                        if (!previousField.resourceId.isNullOrBlank()) previousField.resourceId == field.resourceId
                        else previousField.nodeId == field.nodeId && previousField.className == field.className
                }
                val matchingFields = sameFields.filter { field ->
                    field.text == typed && (previousField?.isFocused != true || field.isFocused)
                }
                if (typed.isNotBlank() && sameFields.size == 1 && matchingFields.size == 1) {
                    Pair(true, "Expected text confirmed in an observed editable field")
                } else {
                    Pair(false, "Expected input could not be confirmed in a unique editable field")
                }
            }
            UiAction.Type.KEY_BACK, UiAction.Type.KEY_HOME, UiAction.Type.KEY_ENTER -> {
                val changed = !sameInteractionState(before, after)
                if (changed) {
                    Pair(true, "Navigation/action key triggered state transition")
                } else {
                    Pair(false, "Key event produced no detectable UI state transition")
                }
            }
            UiAction.Type.TAP, UiAction.Type.LONG_PRESS, UiAction.Type.SWIPE, UiAction.Type.SCROLL -> {
                val changed = !sameInteractionState(before, after)
                if (changed) {
                    Pair(true, "Screen state changed")
                } else {
                    Pair(false, "Action produced no detectable UI change")
                }
            }
            UiAction.Type.SCREENSHOT, UiAction.Type.FINISH -> Pair(true, "Verified")
        }
    }

    /** Compare interaction evidence, not capture time or JPEG noise on a semantic screen. */
    private fun sameInteractionState(before: UiObservation, after: UiObservation): Boolean {
        if (before.foregroundPackage != after.foregroundPackage || before.visibleText != after.visibleText ||
            before.clickableElements != after.clickableElements || before.prunedElements != after.prunedElements) {
            return false
        }
        // With no semantic tree, do not dispatch an old visual coordinate on a different frame.
        if (before.prunedElements.isEmpty() && after.prunedElements.isEmpty()) {
            val previousImage = before.screenshotJpeg
            val currentImage = after.screenshotJpeg
            return if (previousImage == null) currentImage == null
                else currentImage != null && previousImage.contentEquals(currentImage)
        }
        return true
    }
}
