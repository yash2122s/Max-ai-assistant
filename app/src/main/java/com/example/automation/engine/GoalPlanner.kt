package com.example.automation.engine

import android.util.Log
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager

object GoalPlanner {
    private const val TAG = "GoalPlanner"
    val actionQueue = ActionQueue()

    fun initializeGoalPlan(goalPrompt: String, steps: List<ActionStep>) {
        Log.d(TAG, "Initializing plan for goal: '$goalPrompt' with ${steps.size} steps")
        actionQueue.replaceQueue(steps)
        WorldStateManager.setCurrentGoal(goalPrompt, steps)
    }

    fun getNextStep(): ActionStep? {
        val nextStep = actionQueue.dequeue()
        if (nextStep != null) {
            Log.d(TAG, "Dequeued next action step: ${nextStep.actionType} (target: ${nextStep.targetText ?: nextStep.resourceId})")
        } else {
            Log.d(TAG, "Action queue is empty. Goal completed.")
        }
        return nextStep
    }

    fun replanRemaining(remainingSteps: List<ActionStep>) {
        Log.d(TAG, "Replanning remaining queue with ${remainingSteps.size} steps")
        actionQueue.replaceQueue(remainingSteps)
        val currentGoal = WorldStateManager.getCurrentState().currentGoal
        WorldStateManager.setCurrentGoal(currentGoal, remainingSteps)
    }

    fun clearGoal() {
        actionQueue.clear()
        WorldStateManager.setCurrentGoal(null, emptyList())
    }

    fun isGoalActive(): Boolean {
        return !actionQueue.isEmpty()
    }
}
