package com.example.automation.skills.compiler

import com.example.automation.skills.catalog.CanonicalCapabilityCatalog
import com.example.automation.skills.model.SkillDefinition
import com.example.automation.skills.model.StepRetryPolicy
import com.example.automation.skills.model.StepVerification
import java.util.*

/**
 * Compiled executable step bound with runtime inputs and resolved platform overrides.
 */
data class BoundSkillStep(
    val stepId: String,
    val canonicalAction: String,
    val nativeActionName: String,
    val semanticTarget: String,
    val arguments: Map<String, Any?>,
    val dependsOn: List<String>,
    val timeoutMs: Long,
    val retryPolicy: StepRetryPolicy,
    val verification: StepVerification?,
    val isMutating: Boolean
)

/**
 * Declarative compiled plan ready for runtime execution.
 */
data class BoundSkillPlan(
    val skillId: String,
    val name: String,
    val targetPlatform: String,
    val boundInputs: Map<String, Any?>,
    val steps: List<BoundSkillStep>,
    val executionOrder: List<String>
)

/**
 * Compiles canonical SkillDefinitions + bound inputs into platform-adapted execution plans.
 * Resolves platform overrides and maps verbs to trusted native capabilities.
 */
object SkillCompiler {

    /**
     * Compiles a SkillDefinition for the specified target platform ("android" or "windows").
     */
    fun compile(
        skill: SkillDefinition,
        boundInputs: Map<String, Any?>,
        targetPlatform: String = "android"
    ): BoundSkillPlan {
        val normalizedPlatform = targetPlatform.trim().lowercase()
        val compiledSteps = mutableListOf<BoundSkillStep>()
        val stepMap = mutableMapOf<String, BoundSkillStep>()

        val steps = skill.steps ?: emptyList()

        for (step in steps) {
            val stepId = step.stepId ?: ""
            val action = step.action ?: ""
            var target = step.target ?: ""
            val arguments = HashMap(step.arguments ?: emptyMap())

            // Apply platform override if present
            val overrides = step.platformOverrides
            if (normalizedPlatform == "android" && overrides?.android != null) {
                val override = overrides.android
                if (!override.packageName.isNullOrBlank()) {
                    arguments["package_name"] = override.packageName
                }
                if (!override.resourceId.isNullOrBlank()) {
                    arguments["resource_id"] = override.resourceId
                }
                if (!override.contentDescription.isNullOrBlank()) {
                    arguments["content_description"] = override.contentDescription
                }
            } else if (normalizedPlatform == "windows" && overrides?.windows != null) {
                val override = overrides.windows
                if (!override.processName.isNullOrBlank()) {
                    arguments["process_name"] = override.processName
                }
                if (!override.windowTitle.isNullOrBlank()) {
                    arguments["window_title"] = override.windowTitle
                }
                if (!override.automationId.isNullOrBlank()) {
                    arguments["automation_id"] = override.automationId
                }
            }

            // Interpolate placeholders in target and arguments
            val interpolatedTarget = SkillInputBinder.interpolateString(target, boundInputs)
            val interpolatedArguments = SkillInputBinder.interpolateArguments(arguments, boundInputs)

            // Interpolate verification
            val verification = step.verification
            val interpolatedVerification = if (verification != null && !verification.type.isNullOrBlank() && verification.type != "NONE") {
                StepVerification(
                    type = verification.type,
                    expectedValue = verification.expectedValue?.let { SkillInputBinder.interpolateString(it, boundInputs) },
                    timeoutMs = if (verification.timeoutMs > 0) verification.timeoutMs else 3000L
                )
            } else {
                null
            }

            val nativeAction = if (normalizedPlatform == "android") {
                CanonicalCapabilityCatalog.toAndroidAction(action) ?: action
            } else {
                CanonicalCapabilityCatalog.toWindowsAction(action) ?: action
            }

            val isMutating = action != "ui.wait"
            val retryPolicy = step.retryPolicy ?: StepRetryPolicy()

            val boundStep = BoundSkillStep(
                stepId = stepId,
                canonicalAction = action,
                nativeActionName = nativeAction,
                semanticTarget = interpolatedTarget,
                arguments = interpolatedArguments,
                dependsOn = step.dependsOn ?: emptyList(),
                timeoutMs = if (step.timeoutMs > 0) step.timeoutMs else 10000L,
                retryPolicy = retryPolicy,
                verification = interpolatedVerification,
                isMutating = isMutating
            )

            compiledSteps.add(boundStep)
            stepMap[stepId] = boundStep
        }

        // Compute execution order via topological sort
        val executionOrder = computeExecutionOrder(compiledSteps)

        return BoundSkillPlan(
            skillId = skill.skillId ?: "",
            name = skill.name ?: "",
            targetPlatform = normalizedPlatform,
            boundInputs = boundInputs,
            steps = compiledSteps,
            executionOrder = executionOrder
        )
    }

    private fun computeExecutionOrder(steps: List<BoundSkillStep>): List<String> {
        val inDegree = mutableMapOf<String, Int>()
        val adjacency = mutableMapOf<String, MutableList<String>>()

        for (step in steps) {
            inDegree[step.stepId] = 0
            adjacency[step.stepId] = mutableListOf()
        }

        for (step in steps) {
            val deps = step.dependsOn ?: emptyList()
            for (dep in deps) {
                if (adjacency.containsKey(dep)) {
                    adjacency[dep]?.add(step.stepId)
                    inDegree[step.stepId] = (inDegree[step.stepId] ?: 0) + 1
                }
            }
        }

        val queue: Queue<String> = LinkedList()
        for ((sId, deg) in inDegree) {
            if (deg == 0) queue.add(sId)
        }

        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.poll() ?: break
            order.add(current)

            adjacency[current]?.forEach { neighbor ->
                val newDeg = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDeg
                if (newDeg == 0) queue.add(neighbor)
            }
        }

        return if (order.size == steps.size) order else steps.map { it.stepId }
    }
}
