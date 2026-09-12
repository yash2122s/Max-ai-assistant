package com.example.automation.activity

import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object AgentActivityTracker {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _state = MutableStateFlow(AgentActivityState())
    val state: StateFlow<AgentActivityState> = _state.asStateFlow()

    private const val MAX_TRACE_LOGS = 100

    init {
        scope.launch {
            AutomationEventBus.events.collect { event ->
                handleAutomationEvent(event)
            }
        }
        scope.launch {
            AssistantEventBus.events.collect { event ->
                handleAssistantEvent(event)
            }
        }
    }

    fun reset() {
        _state.value = AgentActivityState()
    }

    fun setAmbiguousGoal(goal: String, clarification: String) {
        _state.update { current ->
            current.copy(
                active = false,
                goal = goal,
                stage = AgentStage.UNDERSTANDING,
                isAmbiguous = true,
                clarificationPrompt = clarification,
                lastUpdated = System.currentTimeMillis()
            )
        }
        addTrace("GoalPlanner", "Ambiguous goal detected: \"$goal\". Requesting clarification: $clarification")
    }

    fun addTrace(tag: String, message: String, isSuccess: Boolean? = null, durationMs: Long? = null) {
        _state.update { current ->
            val newLogs = (current.traceLogs + AgentTraceEntry(
                tag = tag,
                message = message,
                isSuccess = isSuccess,
                durationMs = durationMs
            )).takeLast(MAX_TRACE_LOGS)
            current.copy(traceLogs = newLogs, lastUpdated = System.currentTimeMillis())
        }
    }

    fun handleAutomationEvent(event: AutomationEvent) {
        when (event) {
            is AutomationEvent.PlanningStarted -> {
                _state.value = AgentActivityState(
                    active = true,
                    goal = event.goal,
                    stage = AgentStage.PLANNING,
                    isAmbiguous = false,
                    clarificationPrompt = null,
                    lastUpdated = System.currentTimeMillis()
                )
                addTrace("GoalPlanner", "Planning started for goal: \"${event.goal}\"")
            }

            is AutomationEvent.PlanCreated -> {
                val stepStates = event.steps.mapIndexed { index, step ->
                    val target = step.targetText ?: step.resourceId ?: step.contentDescription
                    AgentStepState(
                        stepId = step.stepId,
                        stepIndex = index + 1,
                        totalSteps = event.steps.size,
                        actionType = step.actionType,
                        targetText = target,
                        status = "PENDING",
                        reason = ActionReasonFormatter.formatReason(step.actionType, target, goal = _state.value.goal)
                    )
                }
                _state.update { current ->
                    current.copy(
                        active = true,
                        stage = AgentStage.PLANNING,
                        totalSteps = event.steps.size,
                        steps = stepStates,
                        isAmbiguous = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("GeminiPlanningClient", "Plan generated: ${event.steps.size} steps", isSuccess = true)
            }

            is AutomationEvent.StepProgress -> {
                _state.update { current ->
                    val updatedSteps = current.steps.map { s ->
                        if (s.stepIndex == event.stepIndex) {
                            val resultMsg = when (event.status) {
                                "COMPLETED" -> "✓ Completed"
                                "FAILED" -> "✗ Failed"
                                "RUNNING" -> "⟳ Executing"
                                "VERIFYING" -> "🔍 Verifying"
                                else -> s.result
                            }
                            s.copy(status = event.status, result = resultMsg)
                        } else s
                    }
                    val newStage = when (event.status) {
                        "RUNNING" -> AgentStage.EXECUTING
                        "VERIFYING" -> AgentStage.VERIFYING
                        "COMPLETED" -> AgentStage.EXECUTING
                        "FAILED" -> AgentStage.FAILED
                        else -> current.stage
                    }
                    val reason = ActionReasonFormatter.formatReason(event.actionType, event.targetText, goal = current.goal)
                    current.copy(
                        active = true,
                        stage = newStage,
                        currentStepIndex = event.stepIndex,
                        totalSteps = event.totalSteps,
                        currentAction = event.actionType,
                        currentTarget = event.targetText,
                        currentReason = reason,
                        steps = updatedSteps,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("GoalPlanner", "Step ${event.stepIndex}/${event.totalSteps} (${event.actionType}): ${event.status}")
            }

            is AutomationEvent.ObservationCompleted -> {
                val obsReason = ActionReasonFormatter.formatReason("OBSERVING", null, goal = _state.value.goal)
                _state.update { current ->
                    current.copy(
                        currentApp = event.packageName,
                        observedNodeCount = event.nodeCount,
                        currentReason = obsReason,
                        stage = if (current.stage == AgentStage.EXECUTING || current.stage == AgentStage.VERIFYING) AgentStage.OBSERVING else current.stage,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("ScreenContextProvider", "Observed ${event.packageName ?: "Screen"} (${event.nodeCount} UI nodes)", isSuccess = true)
            }

            is AutomationEvent.ExecutionStarted -> {
                val targetText = if (event.request.arguments.has("text") && !event.request.arguments.get("text").isJsonNull) {
                    event.request.arguments.get("text").asString
                } else null
                val reason = ActionReasonFormatter.formatReason(event.request.action, targetText, goal = _state.value.goal)
                _state.update { current ->
                    current.copy(
                        active = true,
                        stage = AgentStage.EXECUTING,
                        currentAction = event.request.action,
                        currentReason = reason,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("ExecutionEngine", "Started action: ${event.request.action}")
            }

            is AutomationEvent.ToolStarted -> {
                addTrace(event.toolName, "Executing tool...")
            }

            is AutomationEvent.ToolFinished -> {
                addTrace(event.toolName, "Tool completed: ${event.result.message ?: "Success"}", isSuccess = event.result.success)
            }

            is AutomationEvent.ToolFailed -> {
                _state.update { current ->
                    current.copy(
                        error = event.message,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace(event.toolName, "Tool failed [${event.errorCode}]: ${event.message}", isSuccess = false)
            }

            is AutomationEvent.VerificationPassed -> {
                addTrace("VerificationRegistry", "${event.toolName} verified ✓", isSuccess = true)
            }

            is AutomationEvent.VerificationFailed -> {
                addTrace("VerificationRegistry", "${event.toolName} verification failed: ${event.reason}", isSuccess = false)
            }

            is AutomationEvent.RetryStarted -> {
                _state.update { current ->
                    current.copy(stage = AgentStage.VERIFYING, lastUpdated = System.currentTimeMillis())
                }
                addTrace("RetryEngine", "Retrying ${event.toolName} (Attempt ${event.attempt})...")
            }

            is AutomationEvent.RetryFinished -> {
                addTrace("RetryEngine", "Retry ${event.toolName} finished: ${if (event.success) "Success" else "Failed"}", isSuccess = event.success)
            }

            is AutomationEvent.ExecutionFinished -> {
                _state.update { current ->
                    val finalStage = if (event.success) AgentStage.COMPLETED else AgentStage.FAILED
                    current.copy(
                        stage = finalStage,
                        active = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("ExecutionEngine", "Task finished: ${if (event.success) "COMPLETED ✓" else "FAILED ✗"}", isSuccess = event.success)
            }

            is AutomationEvent.NeedsUserInput -> {
                _state.update { current ->
                    current.copy(
                        stage = AgentStage.WAITING,
                        clarificationPrompt = event.question,
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("GoalPlanner", "Paused waiting for user input: \"${event.question}\"")
            }

            is AutomationEvent.ObservationStarted -> {
                addTrace("ScreenContext", "Observing screen...")
            }

            is AutomationEvent.TargetGrounded -> {
                addTrace("ScreenGrounding", "Grounded target '${event.label}' at ${event.bounds}")
            }

            is AutomationEvent.ActionStarted -> {
                addTrace("ExecutionEngine", "Action started: ${event.actionType} (${event.target ?: ""})")
            }

            is AutomationEvent.TypingStarted -> {
                addTrace("TypeTextTool", "Typing: \"${event.text}\"")
            }

            is AutomationEvent.VerificationStarted -> {
                addTrace("VerificationRegistry", "Verifying ${event.toolName}...")
            }

            is AutomationEvent.ExecutionCancelled -> {
                _state.update { current ->
                    current.copy(
                        stage = AgentStage.FAILED,
                        active = false,
                        error = event.reason ?: "Execution cancelled",
                        lastUpdated = System.currentTimeMillis()
                    )
                }
                addTrace("ExecutionEngine", "Task CANCELLED: ${event.reason ?: "User cancelled"}", isSuccess = false)
            }

            is AutomationEvent.StateChanged -> {
                // Handled via ExecutionState
            }
        }
    }


    private fun handleAssistantEvent(event: AssistantEvent) {
        when (event) {
            is AssistantEvent.TextTranscribed -> {
                val isUser = event.role.equals("You", ignoreCase = true) || event.role.equals("user", ignoreCase = true)
                val isAssistant = event.role.equals("Gemini", ignoreCase = true) ||
                                  event.role.equals("MAX", ignoreCase = true) ||
                                  event.role.equals("model", ignoreCase = true) ||
                                  event.role.equals("assistant", ignoreCase = true)

                if (isUser && !_state.value.active) {
                    _state.update { current ->
                        current.copy(
                            goal = event.text,
                            stage = AgentStage.UNDERSTANDING,
                            isAmbiguous = false,
                            clarificationPrompt = null,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    addTrace("AssistantEngine", "Transcribed: \"${event.text}\"")
                } else if (isAssistant && !_state.value.active) {
                    _state.update { current ->
                        current.copy(
                            stage = AgentStage.RESPONDING,
                            lastUpdated = System.currentTimeMillis()
                        )
                    }
                    addTrace("GeminiLive", "Responding...")
                }
            }
            is AssistantEvent.AudioRmsChanged -> {
                if (!_state.value.active && _state.value.stage == AgentStage.IDLE && event.rms > 0.05f) {
                    _state.update { current ->
                        current.copy(stage = AgentStage.LISTENING, lastUpdated = System.currentTimeMillis())
                    }
                }
            }
            is AssistantEvent.ErrorOccurred -> {
                _state.update { current ->
                    current.copy(error = event.message, stage = AgentStage.FAILED, lastUpdated = System.currentTimeMillis())
                }
                addTrace("AssistantEngine", "Error: ${event.message}", isSuccess = false)
            }
            else -> {}
        }
    }
}
