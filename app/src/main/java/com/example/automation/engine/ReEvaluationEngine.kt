package com.example.automation.engine

import android.util.Log
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldState
import com.example.automation.tools.ToolResult
import com.google.gson.JsonParser

sealed class ReEvalDecision {
    data object CONTINUE : ReEvalDecision()

    data class MODIFY(
        val updatedSteps: List<ActionStep>,
        val reason: String
    ) : ReEvalDecision()

    data class ABANDON(
        val reason: String
    ) : ReEvalDecision()
}

object ReEvaluationEngine {
    private const val TAG = "ReEvaluationEngine"
    const val MAX_REEVALUATIONS = 3
    const val MAX_TOTAL_PLAN_STEPS = 12

    suspend fun evaluate(
        goal: String,
        failedStep: ActionStep,
        toolResult: ToolResult,
        worldState: WorldState,
        completedSteps: List<ActionStep>,
        remainingSteps: List<ActionStep>,
        executionOutputs: Map<String, Map<String, Any?>> = emptyMap(),
        reEvalCount: Int = 0,
        planningProvider: com.example.network.provider.LLMPlanningProvider? = null
    ): ReEvalDecision {
        Log.i(TAG, "Evaluating failure for step '${failedStep.stepId}' (${failedStep.actionType}) on goal: '$goal' (reEvalCount: $reEvalCount)")

        // 1. Safety Bound Check
        if (reEvalCount >= MAX_REEVALUATIONS) {
            Log.w(TAG, "Exceeded maximum re-evaluations limit ($MAX_REEVALUATIONS). Safely abandoning plan.")
            return ReEvalDecision.ABANDON("Exceeded maximum re-evaluation limit ($MAX_REEVALUATIONS).")
        }

        val totalExecutedAndPending = completedSteps.size + remainingSteps.size + 1
        if (totalExecutedAndPending >= MAX_TOTAL_PLAN_STEPS) {
            Log.w(TAG, "Exceeded maximum total plan steps limit ($MAX_TOTAL_PLAN_STEPS). Abandoning plan.")
            return ReEvalDecision.ABANDON("Total steps exceeded safety limit ($MAX_TOTAL_PLAN_STEPS).")
        }

        // 2. Fast Deterministic Heuristic Recovery
        val heuristicDecision = evaluateHeuristicRecovery(
            failedStep = failedStep,
            toolResult = toolResult,
            worldState = worldState,
            completedSteps = completedSteps,
            remainingSteps = remainingSteps
        )
        if (heuristicDecision != null) {
            Log.i(TAG, "Heuristic recovery resolved: $heuristicDecision")
            return heuristicDecision
        }


        // 3. LLM-Assisted Re-Evaluation if Provider is Available
        if (planningProvider != null) {
            val llmDecision = evaluateWithLLM(
                goal = goal,
                failedStep = failedStep,
                toolResult = toolResult,
                worldState = worldState,
                completedSteps = completedSteps,
                remainingSteps = remainingSteps,
                executionOutputs = executionOutputs,
                reEvalCount = reEvalCount,
                provider = planningProvider
            )
            if (llmDecision != null) {
                return llmDecision
            }
        }

        // 4. Default Safe Fallback
        val reason = toolResult.message ?: "Unrecoverable step failure in '${failedStep.actionType}'"
        Log.w(TAG, "No recovery strategy found for failure. Defaulting to ABANDON ($reason)")
        return ReEvalDecision.ABANDON(reason)
    }

    private fun evaluateHeuristicRecovery(
        failedStep: ActionStep,
        toolResult: ToolResult,
        worldState: WorldState,
        completedSteps: List<ActionStep>,
        remainingSteps: List<ActionStep>
    ): ReEvalDecision? {

        // A. Explicit Fallback Action on Step
        if (!failedStep.fallbackAction.isNullOrBlank()) {
            val fallbackStep = ActionStep(
                stepId = "${failedStep.stepId}_fallback",
                actionType = failedStep.fallbackAction,
                targetText = failedStep.targetText,
                resourceId = failedStep.resourceId,
                contentDescription = failedStep.contentDescription,
                fallbackX = failedStep.fallbackX,
                fallbackY = failedStep.fallbackY,
                params = failedStep.params,
                outputKey = failedStep.outputKey
            )
            return ReEvalDecision.MODIFY(
                updatedSteps = listOf(fallbackStep) + remainingSteps,
                reason = "Executing configured fallback action '${failedStep.fallbackAction}' for '${failedStep.stepId}'."
            )
        }

        // B. Modal Dialog or Permission Scrim Obstruction
        if (worldState.isDialogVisible) {
            val dismissStep = ActionStep(
                stepId = "step_dismiss_dialog_${System.currentTimeMillis() % 1000}",
                actionType = "SYSTEM_ACTION",
                targetText = "back"
            )
            // Retry the failed step after dismissing dialog
            return ReEvalDecision.MODIFY(
                updatedSteps = listOf(dismissStep, failedStep) + remainingSteps,
                reason = "Detected modal dialog obstruction. Injecting back navigation to dismiss dialog."
            )
        }

        // C. Independent Optional Step Failure: If no remaining steps depend on this step, continue
        val remainingDependentSteps = remainingSteps.filter { failedStep.stepId in it.dependsOn }
        if (remainingDependentSteps.isEmpty() && remainingSteps.isNotEmpty() && !DependencyGraphResolver.isLinearPlan(remainingSteps)) {
            Log.d(TAG, "Step '${failedStep.stepId}' has no dependents in remaining DAG. Proceeding with CONTINUE.")
            return ReEvalDecision.CONTINUE
        }

        // D. Standalone Fast-Path or Single Step with no fallback: Fail safely without invoking LLM
        if (completedSteps.isEmpty() && remainingSteps.isEmpty()) {
            return ReEvalDecision.ABANDON(toolResult.message ?: "Failed executing single-step action '${failedStep.actionType}'.")
        }

        return null
    }


    private suspend fun evaluateWithLLM(
        goal: String,
        failedStep: ActionStep,
        toolResult: ToolResult,
        worldState: WorldState,
        completedSteps: List<ActionStep>,
        remainingSteps: List<ActionStep>,
        executionOutputs: Map<String, Map<String, Any?>>,
        reEvalCount: Int,
        provider: com.example.network.provider.LLMPlanningProvider
    ): ReEvalDecision? {
        try {
            val prompt = """
                You are the MAX Agent Dynamic Re-Evaluation Engine.
                An execution step in a multi-step plan has failed.
                
                GOAL: $goal
                FAILED STEP: ${failedStep.stepId} (${failedStep.actionType}, target: ${failedStep.targetText ?: failedStep.resourceId})
                FAILURE CODE: ${toolResult.errorCode}
                FAILURE MESSAGE: ${toolResult.message}
                CURRENT APP: ${worldState.currentApp} (Activity: ${worldState.currentActivity})
                COMPLETED STEPS: ${completedSteps.map { it.stepId }}
                REMAINING STEPS: ${remainingSteps.map { "${it.stepId}:${it.actionType}" }}
                RE-EVALUATION ATTEMPT: ${reEvalCount + 1}/$MAX_REEVALUATIONS
                
                Choose EXACTLY ONE decision:
                1. "CONTINUE": Discard failed step and continue remaining independent steps.
                2. "MODIFY": Provide a revised list of JSON steps to recover and achieve the goal.
                3. "ABANDON": Abort the plan safely if the goal cannot be achieved.
                
                Respond in strictly valid JSON:
                {
                  "decision": "CONTINUE" | "MODIFY" | "ABANDON",
                  "reason": "Brief explanation",
                  "modifiedSteps": [ ... ] (only if decision is MODIFY)
                }
            """.trimIndent()

            val planningRequest = com.example.network.provider.PlanningRequest(
                goal = prompt,
                screenContext = com.example.voice.vision.ScreenContextPayload(
                    packageName = worldState.currentApp.ifBlank { null },
                    prunedNodes = emptyList(),
                    visibleText = "",
                    clickableElements = emptyList(),
                    screenshotJpeg = null,
                    isScreenshotSent = false,
                    hasAccessibility = false,
                    focusMode = "general"
                )
            )

            val planningResult = provider.generatePlan(planningRequest)
            if (!planningResult.success || planningResult.plan.isEmpty()) {
                return null
            }

            // Validate modified steps against schema and authorization
            val modifiedSteps = planningResult.plan
            val validationError = GoalPlanner.validatePlan(modifiedSteps, goal)
            if (validationError != null) {
                Log.e(TAG, "Modified plan validation failed: $validationError")
                return ReEvalDecision.ABANDON("Modified plan invalid: $validationError")
            }

            val dagValidation = DependencyGraphResolver.validatePlan(modifiedSteps)
            if (dagValidation is PlanValidationResult.Invalid) {
                Log.e(TAG, "Modified DAG validation failed: ${dagValidation.message}")
                return ReEvalDecision.ABANDON("Modified DAG invalid: ${dagValidation.message}")
            }

            return ReEvalDecision.MODIFY(
                updatedSteps = modifiedSteps,
                reason = "LLM proposed valid modified recovery sequence."
            )
        } catch (e: Exception) {
            Log.w(TAG, "Exception during LLM re-evaluation: ${e.message}")
            return null
        }
    }
}
