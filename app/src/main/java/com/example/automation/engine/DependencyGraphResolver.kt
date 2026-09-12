package com.example.automation.engine

import com.example.automation.state.ActionStep

sealed class PlanValidationResult {
    data object Valid : PlanValidationResult()
    data class Invalid(val errorCode: String, val message: String) : PlanValidationResult()
}

object DependencyGraphResolver {

    const val ERROR_EMPTY_PLAN = "INVALID_PLAN_EMPTY"
    const val ERROR_DUPLICATE_STEP_ID = "INVALID_PLAN_DUPLICATE_STEP_ID"
    const val ERROR_MISSING_DEPENDENCY = "INVALID_PLAN_MISSING_DEPENDENCY"
    const val ERROR_CIRCULAR_DEPENDENCY = "INVALID_PLAN_CIRCULAR_DEPENDENCY"

    fun validatePlan(steps: List<ActionStep>): PlanValidationResult {
        if (steps.isEmpty()) {
            return PlanValidationResult.Invalid(ERROR_EMPTY_PLAN, "Plan contains 0 steps.")
        }

        val stepIdSet = mutableSetOf<String>()
        for (step in steps) {
            if (step.stepId.isBlank()) {
                return PlanValidationResult.Invalid(ERROR_DUPLICATE_STEP_ID, "Step has blank stepId.")
            }
            if (!stepIdSet.add(step.stepId)) {
                return PlanValidationResult.Invalid(ERROR_DUPLICATE_STEP_ID, "Duplicate stepId '${step.stepId}' found.")
            }
        }

        // Check missing dependencies
        for (step in steps) {
            for (depId in step.dependsOn) {
                if (depId !in stepIdSet) {
                    return PlanValidationResult.Invalid(
                        ERROR_MISSING_DEPENDENCY,
                        "Step '${step.stepId}' depends on unknown stepId '$depId'."
                    )
                }
            }
        }

        // Cycle detection using Kahn's topological sort
        val inDegree = mutableMapOf<String, Int>()
        val adjList = mutableMapOf<String, MutableList<String>>() // parent -> children

        for (step in steps) {
            inDegree[step.stepId] = step.dependsOn.size
            for (depId in step.dependsOn) {
                adjList.getOrPut(depId) { mutableListOf() }.add(step.stepId)
            }
        }

        val queue = ArrayDeque<String>()
        for ((stepId, deg) in inDegree) {
            if (deg == 0) {
                queue.add(stepId)
            }
        }

        var visitedCount = 0
        while (queue.isNotEmpty()) {
            val curr = queue.removeFirst()
            visitedCount++
            for (child in adjList[curr] ?: emptyList()) {
                val newDeg = (inDegree[child] ?: 1) - 1
                inDegree[child] = newDeg
                if (newDeg == 0) {
                    queue.add(child)
                }
            }
        }

        if (visitedCount < steps.size) {
            return PlanValidationResult.Invalid(
                ERROR_CIRCULAR_DEPENDENCY,
                "Circular dependency detected in plan graph."
            )
        }

        return PlanValidationResult.Valid
    }

    fun isLinearPlan(steps: List<ActionStep>): Boolean {
        return steps.all { it.dependsOn.isEmpty() }
    }

    fun getReadySteps(
        steps: List<ActionStep>,
        completedStepIds: Set<String>,
        failedStepIds: Set<String> = emptySet(),
        cancelledStepIds: Set<String> = emptySet()
    ): List<ActionStep> {
        val blockedIds = getAllBlockedStepIds(steps, failedStepIds + cancelledStepIds)

        return steps.filter { step ->
            step.stepId !in completedStepIds &&
            step.stepId !in blockedIds &&
            step.dependsOn.all { depId -> depId in completedStepIds }
        }
    }

    fun getAllBlockedStepIds(
        steps: List<ActionStep>,
        terminalFailedOrCancelledIds: Set<String>
    ): Set<String> {
        val blocked = mutableSetOf<String>()
        blocked.addAll(terminalFailedOrCancelledIds)

        var changed = true
        while (changed) {
            changed = false
            for (step in steps) {
                if (step.stepId !in blocked) {
                    if (step.dependsOn.any { it in blocked }) {
                        blocked.add(step.stepId)
                        changed = true
                    }
                }
            }
        }
        return blocked
    }
}
