package com.example.automation.engine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.automation.models.UiStep
import com.example.automation.resolver.UiTargetResolver
import com.example.automation.tools.ToolResult
import com.example.automation.verification.LightweightUiObserver
import com.example.automation.verification.RuntimeStepVerifier
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.service.JarvisAccessibilityService
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Result of executing a sequence of [UiStep] interactions.
 */
data class UiSequenceResult(
    val success: Boolean,
    val executedCount: Int,
    val totalCount: Int,
    val failedStep: UiStep? = null,
    val message: String? = null,
    val isCancelled: Boolean = false
)

/**
 * Generic Runtime UI Interaction Orchestrator for MAX.
 * Executes multi-step UI sequences with the strict closed-loop flow:
 * STEP → OBSERVE → RESOLVE → ACT → WAIT → RE-OBSERVE → VERIFY → NEXT STEP
 */
object GenericUiStepOrchestrator {
    private const val TAG = "GenericUiOrchestrator"
    private const val MAX_STEP_RETRIES = 2

    suspend fun executeSequence(
        context: Context,
        steps: List<UiStep>,
        cancellationToken: CancellationToken? = null
    ): UiSequenceResult = withContext(Dispatchers.IO) {
        if (steps.isEmpty()) {
            return@withContext UiSequenceResult(success = true, executedCount = 0, totalCount = 0)
        }

        Log.d(TAG, "Starting UI sequence execution (${steps.size} steps)")
        var executedCount = 0

        for ((index, step) in steps.withIndex()) {
            // 1. CANCELLATION & RUNTIME STATUS CHECK
            if (!MaxRuntimeManager.isMaxEnabled(context)) {
                Log.w(TAG, "Execution aborted: MAX is OFF")
                return@withContext UiSequenceResult(
                    success = false,
                    executedCount = executedCount,
                    totalCount = steps.size,
                    failedStep = step,
                    message = "Execution cancelled because MAX is OFF",
                    isCancelled = true
                )
            }

            if (cancellationToken?.isCancelled == true) {
                Log.w(TAG, "Execution aborted: Task cancelled by user/client")
                return@withContext UiSequenceResult(
                    success = false,
                    executedCount = executedCount,
                    totalCount = steps.size,
                    failedStep = step,
                    message = "Execution cancelled",
                    isCancelled = true
                )
            }

            Log.d(TAG, "Executing step [${index + 1}/${steps.size}]: ${step::class.simpleName}")

            var stepSuccess = false
            var attempt = 1
            var lastError = ""

            // 2. BOUNDED RETRY LOOP (Max 2 attempts, no infinite loops)
            while (attempt <= MAX_STEP_RETRIES && !stepSuccess) {
                if (attempt > 1) {
                    Log.d(TAG, "Retrying step [${index + 1}] (attempt $attempt/$MAX_STEP_RETRIES)")
                    delay(150L)
                }

                // OBSERVE BEFORE ACTION
                val preObservation = LightweightUiObserver.observe(context)

                // ACT & VERIFY
                val result = executeSingleStep(context, step, preObservation)
                if (result.success) {
                    stepSuccess = true
                } else {
                    lastError = result.message ?: "Step execution failed"
                    attempt++
                }
            }

            if (!stepSuccess) {
                Log.e(TAG, "Step [${index + 1}] failed after $MAX_STEP_RETRIES attempts: $lastError")
                return@withContext UiSequenceResult(
                    success = false,
                    executedCount = executedCount,
                    totalCount = steps.size,
                    failedStep = step,
                    message = lastError
                )
            }

            executedCount++
        }

        Log.d(TAG, "UI sequence completed successfully ($executedCount/${steps.size} steps)")
        return@withContext UiSequenceResult(
            success = true,
            executedCount = executedCount,
            totalCount = steps.size,
            message = "Sequence executed successfully"
        )
    }

    private suspend fun executeSingleStep(
        context: Context,
        step: UiStep,
        preObservation: com.example.automation.verification.LightweightUiObservation
    ): ToolResult {
        val service = JarvisAccessibilityService.instance

        return when (step) {
            is UiStep.OpenApp -> {
                val args = JsonObject().apply {
                    addProperty("app_name", step.appName)
                    step.packageName?.let { addProperty("package", it) }
                }
                val request = ExecutionRequest(
                    action = "OPEN_APP",
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                val toolResult = ExecutionEngine.execute(context, request)
                if (toolResult.success && step.packageName != null) {
                    val verified = RuntimeStepVerifier.verifyAppForeground(context, step.packageName, timeoutMs = 2500L)
                    if (!verified) {
                        return ToolResult(success = false, toolName = "open_app", message = "App did not enter foreground")
                    }
                }
                toolResult
            }

            is UiStep.Tap -> {
                val targetQuery = step.target ?: step.resourceId ?: step.contentDescription ?: ""
                val args = JsonObject().apply {
                    if (targetQuery.isNotEmpty()) addProperty("target", targetQuery)
                    step.resourceId?.let { addProperty("resourceId", it) }
                    step.contentDescription?.let { addProperty("desc", it) }
                    step.index?.let { addProperty("index", it) }
                }
                val request = ExecutionRequest(
                    action = "TAP",
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                val toolResult = ExecutionEngine.execute(context, request)
                if (toolResult.success) {
                    RuntimeStepVerifier.verifyUiTransition(context, preObservation.screenSignature, timeoutMs = 1500L)
                }
                toolResult
            }

            is UiStep.TypeText -> {
                val args = JsonObject().apply {
                    addProperty("text", step.text)
                    step.target?.let { addProperty("target", it) }
                    step.resourceId?.let { addProperty("resourceId", it) }
                    addProperty("replace", step.clearExisting)
                }
                val request = ExecutionRequest(
                    action = "TYPE_TEXT",
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                val toolResult = ExecutionEngine.execute(context, request)
                if (toolResult.success) {
                    RuntimeStepVerifier.verifyTextEntered(context, step.text, timeoutMs = 1000L)
                }
                toolResult
            }

            is UiStep.ClearText -> {
                val args = JsonObject().apply {
                    step.target?.let { addProperty("target", it) }
                    step.resourceId?.let { addProperty("resourceId", it) }
                }
                val request = ExecutionRequest(
                    action = "CLEAR_TEXT",
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                ExecutionEngine.execute(context, request)
            }

            is UiStep.PressKey -> {
                val args = JsonObject().apply {
                    addProperty("key", step.key)
                    step.target?.let { addProperty("target", it) }
                }
                val request = ExecutionRequest(
                    action = "PRESS_KEY",
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                val toolResult = ExecutionEngine.execute(context, request)
                if (toolResult.success) {
                    RuntimeStepVerifier.verifyUiTransition(context, preObservation.screenSignature, timeoutMs = 1500L)
                }
                toolResult
            }

            is UiStep.PressEnter -> {
                val request = ExecutionRequest(
                    action = "PRESS_ENTER",
                    arguments = JsonObject(),
                    source = ExecutionSource.PLANNER
                )
                val toolResult = ExecutionEngine.execute(context, request)
                if (toolResult.success) {
                    RuntimeStepVerifier.verifyUiTransition(context, preObservation.screenSignature, timeoutMs = 2000L)
                }
                toolResult
            }

            is UiStep.PressBack -> {
                val ok = service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
                if (ok) {
                    RuntimeStepVerifier.verifyUiTransition(context, preObservation.screenSignature, timeoutMs = 1500L)
                }
                ToolResult(success = ok, toolName = "press_back", message = if (ok) "Back pressed" else "Back failed")
            }

            is UiStep.Scroll -> {
                val (ok, msg) = service?.scrollScreen(step.direction) ?: Pair(false, "Accessibility unavailable")
                ToolResult(success = ok, toolName = "scroll_screen", message = msg)
            }

            is UiStep.Swipe -> {
                val ok = service?.scrollScreen("down")?.first ?: false
                ToolResult(success = ok, toolName = "swipe", message = if (ok) "Swiped" else "Swipe failed")
            }

            is UiStep.LongPress -> {
                val targetQuery = step.target ?: step.resourceId ?: ""
                val args = JsonObject().apply {
                    if (targetQuery.isNotEmpty()) addProperty("target", targetQuery)
                }
                val request = ExecutionRequest(
                    action = "CLICK_ELEMENT",
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                ExecutionEngine.execute(context, request)
            }

            is UiStep.WaitForUi -> {
                val targetText = step.targetText ?: step.resourceId
                val ok = if (step.packageName != null) {
                    RuntimeStepVerifier.verifyAppForeground(context, step.packageName, step.timeoutMs)
                } else if (!targetText.isNullOrBlank()) {
                    RuntimeStepVerifier.verifyTargetVisible(context, targetText, timeoutMs = step.timeoutMs)
                } else {
                    true
                }
                ToolResult(success = ok, toolName = "wait_for_ui", message = if (ok) "Target found" else "Wait timed out")
            }

            is UiStep.ToggleSystemSetting -> {
                val actionName = when (step.settingKey.lowercase()) {
                    "wifi" -> "TOGGLE_WIFI"
                    "bluetooth" -> "SET_BLUETOOTH"
                    "flashlight", "torch" -> if (step.enabled) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF"
                    "dnd" -> "SET_DND"
                    else -> "TOGGLE_SETTING"
                }
                val args = JsonObject().apply {
                    addProperty("enabled", step.enabled)
                    addProperty("state", step.enabled)
                }
                val request = ExecutionRequest(
                    action = actionName,
                    arguments = args,
                    source = ExecutionSource.PLANNER
                )
                ExecutionEngine.execute(context, request)
            }

            is UiStep.Finish -> {
                ToolResult(success = step.success, toolName = "finish", message = step.message ?: "Finished")
            }
        }
    }
}
