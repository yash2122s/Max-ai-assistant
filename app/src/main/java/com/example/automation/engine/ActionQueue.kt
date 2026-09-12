package com.example.automation.engine

import com.example.automation.state.ActionStep
import java.util.concurrent.ConcurrentLinkedQueue

class ActionQueue {
    private val queue = ConcurrentLinkedQueue<ActionStep>()

    fun enqueue(step: ActionStep) {
        queue.add(step)
    }

    fun enqueueAll(steps: List<ActionStep>) {
        queue.addAll(steps)
    }

    fun dequeue(): ActionStep? {
        return queue.poll()
    }

    fun peek(): ActionStep? {
        return queue.peek()
    }

    fun isEmpty(): Boolean {
        return queue.isEmpty()
    }

    fun isNotEmpty(): Boolean {
        return queue.isNotEmpty()
    }

    fun size(): Int {
        return queue.size
    }

    fun clear() {
        queue.clear()
    }

    fun toList(): List<ActionStep> {
        return queue.toList()
    }

    fun replaceQueue(newSteps: List<ActionStep>) {
        queue.clear()
        queue.addAll(newSteps)
    }

    /**
     * Dependency-aware step polling.
     * If all remaining steps are linear (no dependsOn), preserves strictly standard FIFO dequeue().
     * If dependencies exist, selects the first READY step according to completed/failed dependency state.
     */
    @Synchronized
    fun dequeueReadyStep(
        completedStepIds: Set<String>,
        failedStepIds: Set<String> = emptySet(),
        cancelledStepIds: Set<String> = emptySet()
    ): ActionStep? {
        val currentSteps = queue.toList()
        if (currentSteps.isEmpty()) return null

        if (DependencyGraphResolver.isLinearPlan(currentSteps)) {
            return queue.poll()
        }

        val readySteps = DependencyGraphResolver.getReadySteps(
            steps = currentSteps,
            completedStepIds = completedStepIds,
            failedStepIds = failedStepIds,
            cancelledStepIds = cancelledStepIds
        )

        val nextReady = readySteps.firstOrNull() ?: return null

        // Remove only this specific step from queue
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            if (s.stepId == nextReady.stepId) {
                iterator.remove()
                return s
            }
        }

        return null
    }

    fun getActiveSteps(): List<ActionStep> {
        return queue.toList()
    }

    fun getPendingSteps(): List<ActionStep> {
        return queue.filter { it.status == "PENDING" || it.status == "READY" }
    }

    @Synchronized
    fun removeStep(stepId: String): Boolean {
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            if (s.stepId == stepId) {
                iterator.remove()
                return true
            }
        }
        return false
    }
}

