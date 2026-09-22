package com.example.automation.skills.runtime

import android.content.Context
import android.util.Log
import com.example.automation.event.AutomationEventBus
import com.example.automation.skills.compiler.BoundSkillPlan
import com.example.automation.skills.compiler.BoundSkillStep
import com.example.automation.skills.compiler.SkillCompiler
import com.example.automation.skills.compiler.SkillInputBinder
import com.example.automation.skills.model.FailurePolicy
import com.example.automation.skills.model.SkillDefinition
import kotlinx.coroutines.delay

/**
 * Result returned after end-to-end skill execution.
 */
data class SkillExecutionResult(
    val success: Boolean,
    val skillId: String,
    val totalSteps: Int,
    val completedSteps: List<String> = emptyList(),
    val failedStepId: String? = null,
    val message: String = "",
    val executionEvidence: Map<String, Any?> = emptyMap()
)

/**
 * Main Runtime Orchestrator for MAX Automation Skills.
 * Coordinates input binding, step compilation, live UI observation,
 * adaptive element grounding, native execution, bounded state verification,
 * and drift recovery.
 */
class SkillRuntime(private val context: Context) {

    private val TAG = "SkillRuntime"

    /**
     * Executes a SkillDefinition with provided runtime inputs against live device UI.
     */
    suspend fun executeSkill(
        skill: SkillDefinition,
        providedInputs: Map<String, Any?> = emptyMap()
    ): SkillExecutionResult {
        Log.i(TAG, "=== Starting Skill Execution: '${skill.name}' (${skill.skillId}) ===")

        // 1. Bind Inputs
        val bindingResult = SkillInputBinder.bindInputs(skill, providedInputs)
        if (!bindingResult.success) {
            val err = "Input binding failed: ${bindingResult.errorMessage}"
            Log.e(TAG, err)
            return SkillExecutionResult(
                success = false,
                skillId = skill.skillId,
                totalSteps = skill.steps.size,
                message = err
            )
        }

        // 2. Compile Bound Execution Plan
        val plan: BoundSkillPlan = SkillCompiler.compile(
            skill = skill,
            boundInputs = bindingResult.boundValues,
            targetPlatform = "android"
        )

        Log.i(TAG, "Compiled ${plan.steps.size} steps in topological order: ${plan.executionOrder}")

        val completedStepIds = mutableListOf<String>()
        val stepMap = plan.steps.associateBy { it.stepId }

        // 3. Execute Steps in Topological Order
        for (stepId in plan.executionOrder) {
            val step = stepMap[stepId] ?: continue

            // Ensure all prerequisite dependencies succeeded
            val unsatisfied = step.dependsOn.filter { !completedStepIds.contains(it) }
            if (unsatisfied.isNotEmpty()) {
                val err = "Step '$stepId' cannot run: unsatisfied prerequisites $unsatisfied"
                Log.e(TAG, err)
                return SkillExecutionResult(
                    success = false,
                    skillId = skill.skillId,
                    totalSteps = plan.steps.size,
                    completedSteps = completedStepIds,
                    failedStepId = stepId,
                    message = err
                )
            }

            val maxRetries = step.retryPolicy.maxRetries.coerceAtLeast(0)
            var attempt = 0
            var stepPassed = false
            var lastErrorMessage = ""

            while (attempt <= maxRetries && !stepPassed) {
                attempt++
                Log.d(TAG, "Executing step '$stepId' (attempt $attempt/${maxRetries + 1})")

                // A. Execute Physical Action
                val execResult = SkillStepExecutor.executeStep(context, step)
                if (!execResult.success) {
                    lastErrorMessage = "Execution failed: ${execResult.message}"
                    Log.w(TAG, "Step '$stepId' $lastErrorMessage")

                    // Attempt Recovery
                    if (attempt <= maxRetries) {
                        val recovery = SkillRecovery.attemptRecovery(context, step, attempt)
                        Log.d(TAG, "Recovery attempt for '$stepId': ${recovery.message}")
                        delay(step.retryPolicy.delayMs.coerceAtLeast(300L))
                    }
                    continue
                }

                // B. Verify Post-Condition State
                val verificationResult = SkillStateVerifier.verifyStep(context, step)
                if (verificationResult.verified) {
                    stepPassed = true
                    Log.i(TAG, "✓ Step '$stepId' completed and verified.")
                } else {
                    lastErrorMessage = "Verification failed: ${verificationResult.message}"
                    Log.w(TAG, "Step '$stepId' $lastErrorMessage")

                    // Attempt Recovery on verification drift
                    if (attempt <= maxRetries) {
                        val recovery = SkillRecovery.attemptRecovery(context, step, attempt)
                        Log.d(TAG, "Recovery attempt on verification failure: ${recovery.message}")
                        delay(step.retryPolicy.delayMs.coerceAtLeast(300L))
                    }
                }
            }

            if (stepPassed) {
                completedStepIds.add(stepId)
            } else {
                Log.e(TAG, "✗ Step '$stepId' failed permanently after $attempt attempts. $lastErrorMessage")
                if (skill.failurePolicy == FailurePolicy.ABORT) {
                    return SkillExecutionResult(
                        success = false,
                        skillId = skill.skillId,
                        totalSteps = plan.steps.size,
                        completedSteps = completedStepIds,
                        failedStepId = stepId,
                        message = "Skill aborted at step '$stepId': $lastErrorMessage"
                    )
                }
            }
        }

        val allCompleted = completedStepIds.size == plan.steps.size
        val summaryMsg = if (allCompleted) {
            "✓ Skill '${skill.name}' completed successfully (${completedStepIds.size}/${plan.steps.size} steps verified)."
        } else {
            "Skill '${skill.name}' finished with partial completion (${completedStepIds.size}/${plan.steps.size} steps)."
        }

        Log.i(TAG, summaryMsg)
        return SkillExecutionResult(
            success = allCompleted,
            skillId = skill.skillId,
            totalSteps = plan.steps.size,
            completedSteps = completedStepIds,
            message = summaryMsg
        )
    }
}
