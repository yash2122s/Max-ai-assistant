package com.example.automation.engine.execution

import com.example.automation.engine.state.AgentTask
import com.example.automation.engine.state.TaskStatus
import com.example.automation.engine.planning.TaskPlanner

class LongTaskOrchestrator(
    private val capabilityRouter: CapabilityRouter,
    private val verificationEngine: VerificationEngine,
    private val recoveryEngine: TaskRecoveryEngine,
    private val taskPlanner: TaskPlanner
) {
    suspend fun executeTask(task: AgentTask) {
        task.status = TaskStatus.RUNNING

        while (task.status == TaskStatus.RUNNING && task.currentStepIndex < task.steps.size) {
            val currentStep = task.getCurrentStep() ?: break

            // 1. Act (CapabilityRouter handles internal Observation & Resolution if UI is needed)
            val executionResult = capabilityRouter.executeStep(currentStep, task.context)

            // 2. Verify (Action-specific ExpectedOutcome evaluation)
            val isVerified = verificationEngine.verify(currentStep.expectedOutcome)

            if (isVerified) {
                // Advance state cleanly
                task.context.updateState(
                    stepId = currentStep.id,
                    app = executionResult.activePackage,
                    screen = executionResult.activeScreen
                )
                task.advance()
                continue
            }

            // 3. Recover
            task.status = TaskStatus.RECOVERING
            val recovered = recoveryEngine.attemptRecovery(currentStep, task.context)

            if (recovered) {
                task.status = TaskStatus.RUNNING
                continue // Re-attempt the same step index
            }

            // 4. Re-plan (Recovery exhausted)
            val replanSuccess = taskPlanner.replanTask(task)
            
            if (!replanSuccess) {
                task.status = TaskStatus.FAILED
                break
            }
            
            task.status = TaskStatus.RUNNING
        }

        if (task.status == TaskStatus.RUNNING) {
            task.status = TaskStatus.COMPLETED
        }
    }
}
