package com.example.automation.agent

import android.content.Context
import android.util.Log
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.data.preferences.SettingsManager
import com.example.voice.vision.ScreenCaptureProvider
import com.example.voice.vision.ScreenContextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Autonomous UI verification loop implementing:
 * Observe -> Decide -> Act -> Adaptive Wait -> Re-observe -> Verify -> Recover
 *
 * Fully bounded to maxSteps to prevent infinite loops, with cancellation support and
 * provider-level fallback from OpenRouter to Gemini.
 */
class UiVerificationLoop(
    private val context: Context,
    private val router: UiAgentRouter = UiAgentRouter(context)
) {
    companion object {
        private const val TAG = "UiVerificationLoop"
        private const val DEFAULT_SETTLE_DELAY_MS = 400L
        private const val APP_LAUNCH_MAX_WAIT_MS = 2500L
        private const val APP_LAUNCH_POLL_INTERVAL_MS = 150L
    }

    suspend fun executeTask(task: UiTask): UiTaskResult = withContext(Dispatchers.Default) {
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

        Log.d(TAG, "Starting UI Automation Loop: '${task.goal}' (maxSteps=${task.maxSteps})")

        var consecutiveNoOpCount = 0
        var lastActionSignature: String? = null

        val geminiProvider = GeminiUiAgentProvider()
        val openRouterProvider = OpenRouterUiAgentProvider()
        val settings = SettingsManager(context)

        for (stepIndex in 1..task.maxSteps) {
            // Check cancellation & runtime status
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

            // 1. OBSERVE: Current screen state & pruned accessibility hierarchy
            val observationBefore = captureCurrentObservation()

            // 2. DECIDE: Query primary provider with fallback to secondary provider
            val (reasoningResult, providerUsed, modelUsed) = decideNextAction(
                task = task,
                observation = observationBefore,
                stepHistory = stepHistory,
                openRouterProvider = openRouterProvider,
                geminiProvider = geminiProvider,
                settings = settings
            )

            when (reasoningResult) {
                is UiAgentReasoningResult.FinishTask -> {
                    Log.d(TAG, "UI Agent completed goal: success=${reasoningResult.success}, message='${reasoningResult.message}'")
                    return@withContext UiTaskResult(
                        taskId = task.id,
                        success = reasoningResult.success,
                        message = reasoningResult.message,
                        stepsExecuted = stepIndex,
                        providerUsed = providerUsed,
                        modelUsed = modelUsed,
                        actionsTaken = actionsTaken
                    )
                }

                is UiAgentReasoningResult.Error -> {
                    Log.w(TAG, "Reasoning error at step $stepIndex: ${reasoningResult.message}")
                    return@withContext UiTaskResult(
                        taskId = task.id,
                        success = false,
                        message = "UI Agent reasoning failed: ${reasoningResult.message}",
                        stepsExecuted = stepIndex,
                        providerUsed = providerUsed,
                        modelUsed = modelUsed,
                        actionsTaken = actionsTaken,
                        error = reasoningResult.message
                    )
                }

                is UiAgentReasoningResult.ActionDecision -> {
                    val action = reasoningResult.action
                    Log.d(TAG, "Action decision: ${action.type} (${action.reason})")
                    actionsTaken.add(action)

                    // 3. ACT: Execute the requested action on Android OS
                    val executionSuccess = UiActionExecutor.executeAction(context, action)

                    // 4. ADAPTIVE WAIT: Dynamic settle delays based on action type
                    performAdaptiveWait(action)

                    // 5. RE-OBSERVE: Capture updated screen state
                    val observationAfter = captureCurrentObservation()

                    // 6. VERIFY: Compare screen before vs after
                    val (isVerified, note) = verifyActionOutcome(action, observationBefore, observationAfter)
                    Log.d(TAG, "Step $stepIndex verification: verified=$isVerified ($note)")

                    // 7. RECOVER: Detect stuck loops / repeated actions without state change
                    val currentSignature = "${action.type}:${action.x}:${action.y}:${action.text}:${action.packageName}"
                    val screenChanged = observationBefore.visibleText != observationAfter.visibleText ||
                        observationBefore.foregroundPackage != observationAfter.foregroundPackage

                    if (currentSignature == lastActionSignature && !screenChanged) {
                        consecutiveNoOpCount++
                        if (consecutiveNoOpCount >= 2) {
                            Log.w(TAG, "Self-healing recovery triggered: repeated action with zero UI change. Dismissing keyboard/navigating back.")
                            UiActionExecutor.executeAction(context, UiAction(type = UiAction.Type.KEY_BACK, reason = "Self-healing: dismiss blocking overlay/IME"))
                            delay(400L)
                            consecutiveNoOpCount = 0
                        }
                    } else {
                        consecutiveNoOpCount = 0
                    }
                    lastActionSignature = currentSignature

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
                }
            }
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

            // On failure/timeout, fall back to Gemini
            Log.w(TAG, "OpenRouter reasoning failed; triggering Gemini UI Agent fallback: ${(res as? UiAgentReasoningResult.Error)?.message}")
            if (geminiKey.isNotBlank()) {
                val geminiRes = geminiProvider.reasonNextAction(
                    task = task,
                    observation = observation,
                    stepHistory = stepHistory,
                    apiKey = geminiKey,
                    model = GeminiUiAgentProvider.DEFAULT_MODEL
                )
                return Triple(geminiRes, GeminiUiAgentProvider.PROVIDER_ID, GeminiUiAgentProvider.DEFAULT_MODEL)
            }
            return Triple(res, OpenRouterUiAgentProvider.PROVIDER_ID, openRouterModel)
        }

        // 2. Direct Gemini UI Agent fallback if OpenRouter is unconfigured
        if (geminiKey.isNotBlank()) {
            val res = geminiProvider.reasonNextAction(
                task = task,
                observation = observation,
                stepHistory = stepHistory,
                apiKey = geminiKey,
                model = GeminiUiAgentProvider.DEFAULT_MODEL
            )
            return Triple(res, GeminiUiAgentProvider.PROVIDER_ID, GeminiUiAgentProvider.DEFAULT_MODEL)
        }

        // 3. Unconfigured fallback
        val emptyRes = UiAgentReasoningResult.Error("No API keys configured for UI Agent (neither OpenRouter nor Gemini)", retryable = false)
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
        after: UiObservation
    ): Pair<Boolean, String> {
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
                if (after.visibleText.contains(typed, ignoreCase = true)) {
                    Pair(true, "Typed text appears on screen")
                } else {
                    Pair(true, "Text input dispatched")
                }
            }
            UiAction.Type.KEY_BACK, UiAction.Type.KEY_HOME, UiAction.Type.KEY_ENTER -> {
                Pair(true, "Navigation/action key dispatched")
            }
            UiAction.Type.TAP, UiAction.Type.LONG_PRESS, UiAction.Type.SWIPE, UiAction.Type.SCROLL -> {
                val changed = before.visibleText != after.visibleText || before.foregroundPackage != after.foregroundPackage
                Pair(true, if (changed) "Screen state changed" else "Action dispatched")
            }
            UiAction.Type.SCREENSHOT, UiAction.Type.FINISH -> Pair(true, "Verified")
        }
    }
}
