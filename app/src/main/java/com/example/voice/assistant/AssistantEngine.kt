package com.example.voice.assistant

import android.content.Context
import com.example.network.ConnectionState
import com.example.voice.audio.*
import com.example.voice.tools.*
import org.json.JSONObject

import com.example.voice.vision.ScreenCaptureProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren

class AssistantEngine(private val context: Context) {
    val stateMachine = SessionStateMachine()
    val conversationManager = ConversationManager()
    
    // Capabilities and Tools
    val capabilityProvider = CapabilityProvider(context)
    val executionPolicy = ExecutionPolicy(capabilityProvider)
    val authorizationManager = AuthorizationManager(context, executionPolicy)
    val toolRegistry = AssistantToolRegistry(context, authorizationManager)

    // Audio Stack
    val audioFocusManager = AudioFocusManager(context)
    val audioPlayer = AudioPlayer(audioFocusManager)
    
    private val audioProcessor = AudioProcessor()
    val audioRecorder = AudioRecorder()
    val audioStateGuard = AudioStateGuard()

    // Emotion & Personality Engine
    val emotionEngine = com.example.emotion.engine.EmotionEngine(context)

    // Connection manager and Streaming Response Manager as lateinit to resolve circular reference
    lateinit var connectionManager: ConnectionManager
    lateinit var streamingResponseManager: StreamingResponseManager
    private var responseTimeoutJob: kotlinx.coroutines.Job? = null
    private val localExecutionScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    @Volatile var isAutonomousTaskRunning = false
        private set
    @Volatile var currentAutonomousGoal = ""
        private set
    @Volatile private var autonomousStepCount = 0
    @Volatile private var autonomousHardStop = false
    @Volatile private var consecutiveToolFailures = 0
    private var autonomousContinuationJob: kotlinx.coroutines.Job? = null

    fun cancelAutonomousTask(reason: String = "User cancelled") {
        android.util.Log.i("AssistantEngine", "[AUTONOMOUS_TASK] Cancelled: $reason")
        isAutonomousTaskRunning = false
        currentAutonomousGoal = ""
        autonomousStepCount = 0
        autonomousHardStop = false
        consecutiveToolFailures = 0
        autonomousContinuationJob?.cancel()
        autonomousContinuationJob = null
    }

    init {
        com.example.automation.engine.TaskCancellationCoordinator.registerCancellationListener(this) {
            localExecutionScope.coroutineContext.cancelChildren()
        }
        connectionManager = ConnectionManager(
            context = context,
            emotionEngine = emotionEngine,
            onMessageReceived = { text ->
                responseTimeoutJob?.cancel()
                responseTimeoutJob = null
                val sanitized = com.example.utils.InternalReasoningFilter.sanitize(text)
                if (sanitized.isNotBlank()) {
                    val verified = com.example.automation.engine.truth.ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(sanitized)
                    conversationManager.addMessage("Gemini", verified)
                    streamingResponseManager.handleIncomingText(verified)
                }
            },
            onAudioReceived = { audioBytes ->
                responseTimeoutJob?.cancel()
                responseTimeoutJob = null
                streamingResponseManager.handleIncomingAudio(audioBytes) { rms ->
                    AssistantEventBus.emit(AssistantEvent.AudioRmsChanged(rms))
                }
            },
            onConnectionStateChanged = { connState ->
                val engineState = when (connState) {
                    ConnectionState.CONNECTED -> SessionState.CONNECTED
                    ConnectionState.CONNECTING -> SessionState.CONNECTING
                    ConnectionState.DISCONNECTED -> SessionState.IDLE
                    ConnectionState.FAILED -> SessionState.ERROR
                }
                stateMachine.transitionTo(engineState)
                AssistantEventBus.emit(AssistantEvent.ConnectionStateChanged(connState))
            },
            onExecuteAutomation = { jsonStr ->
                try {
                    val jsonObj = org.json.JSONObject(jsonStr)
                    val functionName = jsonObj.optString("function_name")
                    val action = jsonObj.optString("action")
                
                    val resolvedAction = if (action.isEmpty() && functionName.isNotEmpty()) {
                        when (functionName) {
                            "open_app" -> "OPEN_APP"
                            "send_whatsapp_message" -> "SEND_WHATSAPP"
                            "click_element" -> "CLICK_ELEMENT"
                            "type_text" -> "TYPE_TEXT"
                            "scroll_screen" -> "SCROLL_SCREEN"
                            "press_key" -> "PRESS_KEY"
                            "task_completed" -> "TASK_COMPLETED"
                            "inspect_screen" -> "INSPECT_SCREEN"
                            "computer_use", "ui_agent" -> "COMPUTER_USE"
                            "flashlight" -> {
                                val state = jsonObj.optBoolean("enabled", true)
                                if (state) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF"
                            }
                            "schedule_task" -> "SCHEDULE_TASK"
                            "cancel_task" -> "CANCEL_TASK"
                            "list_scheduled_tasks" -> "LIST_TASKS"
                            else -> functionName.uppercase()
                        }
                    } else action

                    if (resolvedAction.isNotEmpty()) {
                        jsonObj.put("action", resolvedAction)
                    }

                    val target = jsonObj.optString("app")
                        .ifEmpty { jsonObj.optString("app_name") }
                        .ifEmpty { jsonObj.optString("query") }
                        .ifEmpty { jsonObj.optString("text") }
                        .ifEmpty { jsonObj.optString("system_action_str") }

                    val lastInputTranscript = jsonObj.optString("last_input_transcription")
                    val lastUserText = lastInputTranscript.ifEmpty {
                        conversationManager.getHistory().lastOrNull { it.role == "You" }?.content ?: ""
                    }

                    // Validate action necessity against explicit user intent (existing hard gate)
                    // Diagnostic Voice Ingress logging
                    android.util.Log.d("AssistantEngine", "[VOICE_INGRESS] functionName='$functionName', action='$action', json=$jsonStr")
                    if (lastUserText.isNotBlank()) {
                        android.util.Log.d("AssistantEngine", "[VOICE_TRANSCRIPT] lastUserText='$lastUserText'")
                    }

                    val normalizedJson = com.example.automation.engine.ActionDispatcher.normalizeJson(jsonObj)
                    val normalizedAction = normalizedJson.optString("action", "NONE")
                    android.util.Log.d("AssistantEngine", "[VOICE_NORMALIZE] normalizedAction='$normalizedAction', target='$target'")

                    val goalParam = jsonObj.optString("goal")
                        .ifEmpty { jsonObj.optString("query") }
                        .ifEmpty { jsonObj.optString("text") }

                    val effectiveGoal = if (lastUserText.isNotBlank()) lastUserText else goalParam

                    // Check if the goal resolves via local deterministic fast-path
                    val fastPathPlan = if (effectiveGoal.isNotBlank()) {
                        com.example.automation.engine.GoalPlanner.resolveFastPathPlan(effectiveGoal, context)
                    } else null

                    val executionResult = if (normalizedAction == "COMPUTER_USE" || functionName == "computer_use" || functionName == "ui_agent") {
                        android.util.Log.d("AssistantEngine", "[VOICE_COMPUTER_USE] Dispatching computer_use directly to ActionDispatcher.")
                        com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                    } else if (normalizedAction == "TASK_INTENT" || functionName == "task_intent") {
                        android.util.Log.d("AssistantEngine", "[VOICE_TASK_INTENT] Dispatching task_intent directly to ActionDispatcher for background orchestration.")
                        com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                    } else if (fastPathPlan != null && fastPathPlan.isNotEmpty()) {
                        android.util.Log.d("AssistantEngine", "[VOICE_PLAN] Routing voice goal '$effectiveGoal' through fast-path DAG (${fastPathPlan.size} steps, ZERO LLM calls).")
                        val validationError = com.example.automation.engine.GoalPlanner.validatePlan(fastPathPlan, effectiveGoal)
                        if (validationError != null) {
                            android.util.Log.e("AssistantEngine", "[VOICE_PLAN] Fast-path validation failed: $validationError")
                            org.json.JSONObject().apply {
                                put("status", "error")
                                put("success", false)
                                put("executionState", "FAILED")
                                put("reason", validationError)
                            }.toString()
                        } else {
                            val planResult = com.example.automation.engine.GoalPlanner.planAndExecute(context, effectiveGoal)
                            android.util.Log.d("AssistantEngine", "[VOICE_RESULT] Finished execution for fast-path goal '$effectiveGoal' (success=${planResult.success})")
                            org.json.JSONObject().apply {
                                put("status", if (planResult.success) "success" else "error")
                                put("success", planResult.success)
                                put("executionState", if (planResult.success) "COMPLETED" else "FAILED")
                                put("reason", planResult.errorMessage ?: (if (planResult.success) "Goal completed successfully." else "Goal execution failed."))
                            }.toString()
                        }
                    } else if (functionName == "execute_automation" ||
                        functionName in setOf("task_completed", "inspect_screen", "click_element", "type_text", "scroll_screen", "press_key", "open_app") ||
                        normalizedAction in setOf("EXECUTE_AUTOMATION", "CLICK_TEXT", "CLICK_ELEMENT", "TYPE_TEXT", "PRESS_ENTER", "PRESS_KEY", "SCROLL_SCREEN", "OPEN_APP", "TASK_COMPLETED", "INSPECT_SCREEN")) {
                        // Gemini Live WebSocket ALREADY structured the tool call with action/app/query/target.
                        // Dispatch the structured action directly through ActionDispatcher (ZERO additional LLM calls)!
                        android.util.Log.d("AssistantEngine", "[VOICE_DIRECT_DISPATCH] Dispatching structured action '$normalizedAction' directly to ActionDispatcher.")
                        
                        // If query/text is present but target is missing, populate target from query/text
                        if (normalizedAction == "TYPE_TEXT" && !normalizedJson.has("text") && goalParam.isNotBlank()) {
                            normalizedJson.put("text", goalParam)
                            normalizedJson.put("target", goalParam)
                        } else if (normalizedAction == "OPEN_APP" && !normalizedJson.has("app") && target.isNotBlank()) {
                            normalizedJson.put("app", target)
                        }
                        
                        if (normalizedAction in setOf("TASK_COMPLETED", "INSPECT_SCREEN") || functionName in setOf("task_completed", "inspect_screen")) {
                            com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                        } else {
                            // Authorize and dispatch
                            val auth = com.example.automation.engine.ActionAuthorizer.authorizeAction(lastUserText.ifEmpty { effectiveGoal }, normalizedAction, target.ifEmpty { goalParam })
                            if (!auth.authorized) {
                                android.util.Log.w("AssistantEngine", "[VOICE_AUTH] Action '$normalizedAction' BLOCKED: ${auth.reason}")
                                org.json.JSONObject().apply {
                                    put("status", "blocked")
                                    put("success", false)
                                    put("executionState", "BLOCKED")
                                    put("reason", auth.reason)
                                }.toString()
                            } else {
                                com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                            }
                        }
                    } else {
                        val originalIntent = normalizedJson.optString("original_user_intent")
                        val isApprovalReply = com.example.automation.approval.ApprovalCoordinator.parseReply(lastUserText) == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE
                        val intentForAuth = if (normalizedAction == "CALL_PHONE" && originalIntent.isNotBlank()) {
                            originalIntent
                        } else if (normalizedAction == "CALL_PHONE" && isApprovalReply && com.example.automation.engine.PendingCallManager.state !is com.example.automation.engine.CallState.Idle) {
                            com.example.automation.engine.PendingCallManager.getOriginalIntent() ?: (if (target.isNotBlank()) "Call $target" else "Call")
                        } else {
                            lastUserText.ifEmpty { effectiveGoal }
                        }
                        val auth = com.example.automation.engine.ActionAuthorizer.authorizeAction(intentForAuth, normalizedAction, target)
                        if (!auth.authorized) {
                            android.util.Log.w("AssistantEngine", "[VOICE_AUTH] Unsolicited/Rejected action '$normalizedAction' BLOCKED: ${auth.reason}")
                            com.example.automation.activity.AgentActivityTracker.addTrace("ActionAuthorizer", "Action '$normalizedAction' blocked: ${auth.reason}", isSuccess = false)
                            
                            // If CALL_PHONE was blocked because user actually wanted WhatsApp messaging, route to TaskPlanner!
                            val lowerGoal = (lastUserText.ifEmpty { effectiveGoal }).lowercase()
                            if (normalizedAction == "CALL_PHONE" && (lowerGoal.contains("whatsapp") || lowerGoal.contains("message") || lowerGoal.contains("msg"))) {
                                android.util.Log.i("AssistantEngine", "[VOICE_AUTOREPAIR] Rerouting rejected CALL_PHONE to WhatsApp DAG for goal: '$effectiveGoal'")
                                val taskJson = org.json.JSONObject().apply {
                                    put("action", "TASK_INTENT")
                                    put("goal", "send_whatsapp_message")
                                    put("target_entity", "WhatsApp")
                                    put("required_outcome", "MessageSent")
                                    put("last_input_transcription", lastUserText.ifEmpty { effectiveGoal })
                                }
                                com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, taskJson)
                            } else {
                                org.json.JSONObject().apply {
                                    put("status", "blocked")
                                    put("success", false)
                                    put("executionState", "BLOCKED")
                                    put("reason", auth.reason)
                                }.toString()
                            }
                        } else {
                            // P1.2 — risk-aware approval: decide auto-run vs confirmation vs hard block.
                            val settings = com.example.data.preferences.SettingsManager(context)
                            val mode = runCatching {
                                com.example.automation.engine.ActionAuthorizer.ApprovalMode.valueOf(settings.approvalMode)
                            }.getOrDefault(com.example.automation.engine.ActionAuthorizer.ApprovalMode.SAFE_AUTO)
                            com.example.automation.engine.ActionAuthorizer.setCautionAllowlist(
                                settings.approvalAllowlist.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            )
                            val evaluation = com.example.automation.engine.ActionAuthorizer.evaluateApproval(
                                mode = mode,
                                actionType = normalizedAction,
                                userIntent = lastUserText,
                                target = target,
                                allowCautionAutoInFullAuto = settings.isCautionAutoAllowedInFullAuto
                            )
                            when (evaluation.decision) {
                                com.example.automation.engine.ActionAuthorizer.ApprovalDecision.APPROVED -> {
                                    com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                                }
                                com.example.automation.engine.ActionAuthorizer.ApprovalDecision.BLOCKED -> {
                                    android.util.Log.w("AssistantEngine", "Action '$normalizedAction' BLOCKED by approval policy: ${evaluation.reason}")
                                    com.example.automation.activity.AgentActivityTracker.addTrace(
                                        "ApprovalPolicy", "Action '$normalizedAction' blocked: ${evaluation.reason}", isSuccess = false
                                    )
                                    org.json.JSONObject().apply {
                                        put("status", "blocked")
                                        put("success", false)
                                        put("executionState", "BLOCKED")
                                        put("reason", evaluation.reason)
                                    }.toString()
                                }
                                com.example.automation.engine.ActionAuthorizer.ApprovalDecision.NEEDS_CONFIRMATION -> {
                                    val approvalTaskId = "task_appr_" + java.util.UUID.randomUUID().toString().take(8)
                                    val approvalStepId = "step_1"
                                    val approvalExecId = java.util.UUID.randomUUID().toString()
                                    val taskStore = com.example.automation.engine.state.AgentTaskStore(context)
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        taskStore.createTask(approvalTaskId, "Approve $normalizedAction", totalSteps = 1)
                                        taskStore.updateStep(
                                            taskId = approvalTaskId,
                                            step = com.example.automation.state.ActionStep(stepId = approvalStepId, actionType = normalizedAction),
                                            stepIndex = 0,
                                            status = "READY",
                                            executionId = approvalExecId
                                        )
                                        com.example.automation.approval.ApprovalCoordinator.requestApproval(
                                            context = context,
                                            action = normalizedAction,
                                            target = target,
                                            payload = normalizedJson,
                                            parentTaskId = approvalTaskId,
                                            stepId = approvalStepId,
                                            executionId = approvalExecId,
                                            taskStore = taskStore
                                        )
                                    }
                                    com.example.automation.engine.truth.ExecutionTruthCoordinator.recordExecution(
                                        taskId = approvalTaskId,
                                        stepId = approvalStepId,
                                        executionId = approvalExecId,
                                        actionName = normalizedAction,
                                        executorSuccess = false,
                                        verifiedSuccess = false,
                                        status = "WAITING_FOR_APPROVAL",
                                        failureReason = "Confirmation requested from the user: ${evaluation.reason}"
                                    )
                                    org.json.JSONObject().apply {
                                        put("status", "awaiting_approval")
                                        put("success", false)
                                        put("executionState", "WAITING_FOR_APPROVAL")
                                        put("taskId", approvalTaskId)
                                        put("stepId", approvalStepId)
                                        put("executionId", approvalExecId)
                                        put("reason", "Confirmation requested from the user.")
                                    }.toString()
                                }
                            }
                        }
                    }

                    // Feed automation execution outcome to Emotion Engine
                    val parsed = runCatching { org.json.JSONObject(executionResult) }.getOrNull()
                    if (parsed?.optString("status") == "success" || parsed?.optString("executionState") == "COMPLETED") {
                        emotionEngine.processEvent(com.example.emotion.model.EmotionEvent.TaskSuccess(resolvedAction.ifEmpty { functionName }))
                    } else if (parsed?.optString("status") == "error" || parsed?.optString("executionState") == "FAILED") {
                        val reason = parsed?.optString("reason", "Task execution failed") ?: "Task execution failed"
                        emotionEngine.processEvent(com.example.emotion.model.EmotionEvent.TaskFailure(resolvedAction.ifEmpty { functionName }, reason))
                    }

                    // Track autonomous execution state
                    if (resolvedAction == "TASK_COMPLETED" || functionName == "task_completed" || normalizedAction == "TASK_COMPLETED") {
                        android.util.Log.i("AssistantEngine", "[AUTONOMOUS_TASK] Task completed. Resetting autonomous task state.")
                        cancelAutonomousTask("Task completed signal received")
                    } else {
                        val isAutomationTool = normalizedAction in setOf("CLICK_TEXT", "CLICK_ELEMENT", "TYPE_TEXT", "PRESS_ENTER", "PRESS_KEY", "SCROLL_SCREEN", "OPEN_APP", "COMPUTER_USE") ||
                            functionName in setOf("click_element", "type_text", "scroll_screen", "open_app", "press_key")
                        if (isAutomationTool) {
                            isAutonomousTaskRunning = true
                            autonomousStepCount++
                            if (currentAutonomousGoal.isBlank()) {
                                currentAutonomousGoal = lastUserText.ifBlank { "Autonomous UI task" }
                            }
                            val isSuccess = parsed?.optBoolean("success", true) ?: true
                            if (isSuccess) {
                                consecutiveToolFailures = 0
                            } else {
                                consecutiveToolFailures++
                            }
                            if (consecutiveToolFailures >= 3 || autonomousStepCount >= 25) {
                                autonomousHardStop = true
                                android.util.Log.w("AssistantEngine", "[AUTONOMOUS_TASK] Safety limit reached: consecutiveFailures=$consecutiveToolFailures, stepCount=$autonomousStepCount")
                            }
                        }
                    }

                    executionResult

                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("AssistantEngine", "Error executing automation in voice assistant", e)
                    emotionEngine.processEvent(com.example.emotion.model.EmotionEvent.TaskFailure(jsonStr, e.message ?: "Execution error"))
                    org.json.JSONObject().apply {
                        put("status", "error")
                        put("success", false)
                        put("executionState", "FAILED")
                        put("reason", e.message ?: "Unknown error in voice tool execution")
                    }.toString()
                }
            },
            onUserTranscription = { userText ->
                handleUserTranscription(userText)
            },
            onTurnComplete = {
                responseTimeoutJob?.cancel()
                responseTimeoutJob = null
                audioProcessor.reset()
                streamingResponseManager.handleTurnComplete()

                // Autonomous task continuation loop: nudge Gemini 3.8 Live with fresh screen observation
                if (isAutonomousTaskRunning && !autonomousHardStop && autonomousStepCount < 25) {
                    autonomousContinuationJob?.cancel()
                    autonomousContinuationJob = localExecutionScope.launch {
                        delay(650)
                        if (!isAutonomousTaskRunning || autonomousHardStop) return@launch
                        val screenSummary = com.example.service.JarvisAccessibilityService.getFormattedScreenSummary()
                        android.util.Log.i("AssistantEngine", "[AUTONOMOUS_CONTINUATION] Step $autonomousStepCount finished. Auto-nudging Gemini 3.8 Live with screen summary.")
                        val continuationPrompt = "Continue the existing task from the fresh phone state; this is an automatic continuation, not a new user command. " +
                            "Do not quote, paraphrase, repeat, or summarize previous assistant messages; use task state and this fresh screen only. " +
                            "Latest screen observation:\n$screenSummary\n" +
                            "Use the current observation to select the next action or recover if the screen changed. " +
                            "Keep speech silent while working. If the goal is not yet finished, call the next tool. When the goal is 100% finished on screen, call task_completed with a concise summary."
                        connectionManager.sendClientContinuation(continuationPrompt)
                    }
                }
            },
            onThoughtSummaryDelta = { delta, fullSummary ->
                AssistantEventBus.emit(AssistantEvent.ThoughtSummaryDelta(delta, fullSummary))
            },
            onInteractionStatusChanged = { status ->
                AssistantEventBus.emit(AssistantEvent.InteractionStatusChanged(status))
            }
        )


        streamingResponseManager = StreamingResponseManager(
            audioPlayer = audioPlayer,
            toolRegistry = toolRegistry,
            onExecuteToolResponse = { callId, toolName, resultJson ->
                connectionManager.sendToolResponse(callId, toolName, resultJson)
            },
            onStateTransition = { newState ->
                stateMachine.transitionTo(newState)
            }
        )
    }

    /**
     * Routes spoken/typed user text. P1.2: an affirmative/negative reply to a
     * pending approval is consumed here so confirmations work hands-free.
     */
    private fun handleUserTranscription(userText: String) {
        // 1. Pending approval reply routing (P1.2)
        if (com.example.automation.approval.ApprovalCoordinator.hasPending()) {
            val ctx = com.example.automation.approval.ApprovalCoordinator.context() ?: context
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.example.automation.approval.ApprovalCoordinator.handleReply(userText, ctx)
            }
            return
        }

        // 2. Pending habit routine suggestion reply routing (P1.4)
        if (com.example.automation.habits.HabitSuggestionCoordinator.hasPending()) {
            val intent = com.example.automation.approval.ApprovalCoordinator.parseReply(userText)
            if (intent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.APPROVE) {
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", "Creating the routine..."))
                val ctx = com.example.automation.habits.HabitSuggestionCoordinator.context() ?: context
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val result = try {
                        com.example.automation.habits.HabitSuggestionCoordinator.approveAndRun(ctx)
                    } catch (e: Exception) {
                        "Could not create routine: ${e.message}"
                    }
                    AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", result))
                }
                return
            }
            if (intent == com.example.automation.approval.ApprovalCoordinator.ReplyIntent.DENY) {
                com.example.automation.habits.HabitSuggestionCoordinator.cancel()
                return
            }
        }

        val lowerText = userText.trim().lowercase()
        val isStop = lowerText in setOf("stop", "cancel", "never mind", "quit", "halt", "ఆపు", "వద్దు", "చాలు") ||
            lowerText.startsWith("stop ") || lowerText.startsWith("cancel ")
        if (isStop && isAutonomousTaskRunning) {
            cancelAutonomousTask("User requested stop: '$userText'")
        } else if (!isStop && userText.isNotBlank()) {
            currentAutonomousGoal = userText.trim()
            autonomousStepCount = 0
            autonomousHardStop = false
            consecutiveToolFailures = 0
        }

        conversationManager.addMessage("You", userText)
        AssistantEventBus.emit(AssistantEvent.TextTranscribed("You", userText))

        val app = context.applicationContext as? com.example.MyApplication
        val gate = app?.entitlementGate

        // Voice quota gate check and real-time Memory Fact saving or retrieval
        CoroutineScope(Dispatchers.IO).launch {
            val quotaDecision = gate?.checkAndConsumeQuota() ?: com.example.firebase.manager.GateDecision.AllowLifetime
            if (!quotaDecision.isAllowed) {
                val blockMsg = when (quotaDecision) {
                    is com.example.firebase.manager.GateDecision.BlockDailyLimitReached ->
                        "Daily free limit reached (${quotaDecision.messageCountToday}/${quotaDecision.limit} messages). Upgrade to Premium to continue."
                    is com.example.firebase.manager.GateDecision.BlockTrialExpired ->
                        "Your 3-day free trial has ended. Activate MAX Premium to continue."
                    is com.example.firebase.manager.GateDecision.BlockLicenseRevoked ->
                        "Your MAX license has been revoked by the issuer."
                    is com.example.firebase.manager.GateDecision.BlockLicenseInactive ->
                        quotaDecision.reason
                    is com.example.firebase.manager.GateDecision.BlockNotAuthenticated ->
                        "Google Sign-In required to use MAX."
                    is com.example.firebase.manager.GateDecision.BlockError ->
                        quotaDecision.message
                    else -> "Usage limit reached. Activate MAX Premium."
                }
                streamingResponseManager.stopAllPlayback()
                connectionManager.sendInterruptionSignal()
                conversationManager.addMessage("MAX", blockMsg)
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", blockMsg))
                AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.PERMISSION, blockMsg))
                return@launch
            }

            val saveResult = com.example.memory.retrieval.MemoryFactResolver.extractAndSaveMemoryFact(context, userText)
            if (saveResult != null) {
                android.util.Log.i("AssistantEngine", "[VOICE_MEMORY_DIRECT_SAVE] Saved fact for '$userText': $saveResult")
                streamingResponseManager.stopAllPlayback()
                responseTimeoutJob?.cancel()
                responseTimeoutJob = null
                conversationManager.addMessage("MAX", saveResult)
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", saveResult))
                return@launch
            }

            if (com.example.memory.retrieval.MemoryFactResolver.isMemoryQuery(userText)) {
                val factResult = com.example.memory.retrieval.MemoryFactResolver.resolve(context, userText)
                if (factResult != null && factResult.found) {
                    android.util.Log.i("AssistantEngine", "[MEMORY_DIRECT_RESOLVE] Resolved memory fact for '$userText': ${factResult.responseText}")
                    streamingResponseManager.stopAllPlayback()
                    responseTimeoutJob?.cancel()
                    responseTimeoutJob = null
                    conversationManager.addMessage("MAX", factResult.responseText)
                    AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", factResult.responseText))
                }
            }
        }

        // Update Emotion Engine state based on incoming speech
        val (detectedEmotionEvent, currentEmotion) = emotionEngine.processUserText(userText)
        if (detectedEmotionEvent != null) {
            android.util.Log.d("AssistantEngine", "[EMOTION_EVENT] Detected: $detectedEmotionEvent | New state: ${currentEmotion.getDominantTraits()}")
        }
        if (com.example.automation.engine.SemanticUiCommandParser.isDeterministicPrimitive(userText) ||
            com.example.automation.engine.GoalPlanner.resolveFastPathPlan(userText, context) != null) {
            android.util.Log.i("AssistantEngine", "[DETERMINISTIC_VOICE_ROUTE] User speech is deterministic UI command: '$userText'. Intercepting locally with 0 Gemini calls.")
            
            // Immediately stop any Gemini speculative audio/text playback
            streamingResponseManager.stopAllPlayback()
            responseTimeoutJob?.cancel()
            responseTimeoutJob = null
            
            // Execute locally through GoalPlanner
            executeLocalGoal(userText)
            return
        }

        // If in incoming call decision window, check if userText contains call decision
        if (com.example.automation.IncomingCallManager.isListeningForCallDecision()) {
            val decision = com.example.automation.IncomingCallManager.parseCallDecision(userText)
            if (decision != null) {
                android.util.Log.d("AssistantEngine", "[CALL_DECISION_DETECTED] Voice decision: $decision from text '$userText'")
                com.example.automation.IncomingCallManager.handleCallDecision(context, decision)
            }
        }

        if (isVisionQuery(userText)) {
            CoroutineScope(Dispatchers.IO).launch {
                android.util.Log.d("AssistantEngine", "Vision query detected in voice input: '$userText'. Capturing fresh screen...")
                val jpeg = ScreenCaptureProvider.captureCompressedJpeg()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    connectionManager.sendVideoFrame(jpeg)
                    android.util.Log.d("AssistantEngine", "Fresh screen frame sent to Gemini Live for voice query (${jpeg.size} bytes)")
                } else {
                    android.util.Log.w("AssistantEngine", "Fresh screen capture returned null for voice query")
                }
            }
        }
    }

    private fun isVisionQuery(text: String): Boolean {
        val cleaned = text.trim().lowercase()
        return cleaned.contains("screen") ||
            cleaned.contains("display") ||
            cleaned.contains("what's on") ||
            cleaned.contains("what is on") ||
            cleaned.contains("read this") ||
            cleaned.contains("screenshot") ||
            cleaned.contains("looking at") ||
            cleaned.contains("which app") ||
            cleaned.contains("what app") ||
            cleaned.contains("app is open") ||
            cleaned.contains("what is open") ||
            cleaned.contains("visible") ||
            cleaned.contains("page show") ||
            cleaned.contains("what does this show") ||
            cleaned.contains("స్క్రీన్") ||
            cleaned.contains("డిస్ప్లే") ||
            cleaned.contains("చూడు")
    }

    fun announceIncomingCall(caller: String, rawNumber: String? = null) {
        val callerDisplay = if (caller.equals("Unknown caller", ignoreCase = true) ||
            caller.equals("unknown number", ignoreCase = true) ||
            caller.startsWith("an unknown", ignoreCase = true)) {
            "unknown number"
        } else {
            caller
        }
        val prompt = "Incoming call from $callerDisplay. Accept or reject?"
        
        conversationManager.addMessage("MAX", prompt)
        AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", prompt))
        
        val wasConnected = connectionManager.isConnected()
        if (!wasConnected) {
            connectionManager.connect()
        }
        
        stateMachine.transitionTo(SessionState.STREAMING_AUDIO)
        connectionManager.sendText(prompt)
        
        // Automatically start listening for user's voice decision (Accept/Reject)
        CoroutineScope(Dispatchers.Main).launch {
            delay(400)
            startListening()
            com.example.automation.IncomingCallManager.onListeningStarted(context, wasConnectedBeforeCall = wasConnected)
            android.util.Log.d("AssistantEngine", "[VOICE_LISTENING_STARTED] Incoming call voice listening started")
        }
    }

    fun start() {
        val settings = com.example.data.preferences.SettingsManager(context)
        if (!settings.hasValidGeminiApiKey()) {
            android.util.Log.d("AssistantEngine", "[SECURITY_GATE] Gemini API Key is missing. AssistantEngine idle until key configured.")
            return
        }
        AssistantLogger.startSession()
        if (!connectionManager.isConnected()) {
            connectionManager.connect()
        }
    }

    fun startListening() {
        val settings = com.example.data.preferences.SettingsManager(context)
        if (!settings.hasValidGeminiApiKey()) {
            android.util.Log.w("AssistantEngine", "[SECURITY_GATE] Gemini API Key is missing. Cannot start listening.")
            return
        }

        // License & Entitlement Gate Pre-Check
        val app = context.applicationContext as? com.example.MyApplication
        val gate = app?.entitlementGate
        val voiceDecision = gate?.canStartVoiceSession() ?: com.example.firebase.manager.GateDecision.AllowLifetime
        if (!voiceDecision.isAllowed) {
            val blockMsg = when (voiceDecision) {
                is com.example.firebase.manager.GateDecision.BlockDailyLimitReached ->
                    "Daily free limit reached (${voiceDecision.messageCountToday}/${voiceDecision.limit} messages). Upgrade to Premium to continue."
                is com.example.firebase.manager.GateDecision.BlockTrialExpired ->
                    "Your 3-day free trial has ended. Activate MAX Premium to continue."
                is com.example.firebase.manager.GateDecision.BlockLicenseRevoked ->
                    "Your MAX license has been revoked by the issuer."
                is com.example.firebase.manager.GateDecision.BlockLicenseInactive ->
                    voiceDecision.reason
                is com.example.firebase.manager.GateDecision.BlockNotAuthenticated ->
                    "Please sign in with Google to use MAX."
                is com.example.firebase.manager.GateDecision.BlockError ->
                    voiceDecision.message
                else -> "Usage limit reached. Activate MAX Premium."
            }
            android.util.Log.w("AssistantEngine", "[LICENSE_GATE] Voice session BLOCKED: $blockMsg")
            conversationManager.addMessage("MAX", blockMsg)
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", blockMsg))
            AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.PERMISSION, blockMsg))
            return
        }

        if (!audioStateGuard.tryBeginStarting()) {
            android.util.Log.d("AssistantEngine", "startListening rejected: AudioStateGuard is in state ${audioStateGuard.getState()}")
            return
        }
        if (audioRecorder.isRecording()) {
            audioStateGuard.markListening()
            return
        }
        
        // Interrupt Gemini audio playback if user starts speaking (barge-in)
        streamingResponseManager.handleUserSpeaking()
        connectionManager.sendInterruptionSignal()

        android.util.Log.d("VoiceLatency", "[VoiceLatency] speech_start ts=${System.currentTimeMillis()}")
        stateMachine.transitionTo(SessionState.LISTENING)
        try {
            audioRecorder.startRecording(
                audioProcessor = audioProcessor,
                onAudioChunk = { chunk ->
                    connectionManager.sendAudio(chunk)
                },
                onRmsChanged = { rms ->
                    AssistantEventBus.emit(AssistantEvent.AudioRmsChanged(rms))
                }
            )
            audioStateGuard.markListening()
        } catch (e: Exception) {
            android.util.Log.e("AssistantEngine", "Failed to start audio recording", e)
            audioStateGuard.abortStarting()
            stateMachine.transitionTo(SessionState.CONNECTED)
        }
    }

    fun isRecording(): Boolean = audioRecorder.isRecording() || audioStateGuard.getState() == com.example.voice.audio.AudioGuardState.LISTENING

    fun stopListening() {
        if (!audioStateGuard.tryBeginStopping()) {
            android.util.Log.d("AssistantEngine", "stopListening rejected: AudioStateGuard is in state ${audioStateGuard.getState()}")
            return
        }
        android.util.Log.d("VoiceLatency", "[VoiceLatency] speech_end ts=${System.currentTimeMillis()}")
        try {
            audioRecorder.stopRecording()
        } catch (e: Exception) {
            android.util.Log.e("AssistantEngine", "Error stopping audio recorder", e)
        } finally {
            audioProcessor.reset()
            streamingResponseManager.stopAllPlayback()
            audioStateGuard.markIdle()
            if (stateMachine.getState() == SessionState.LISTENING || stateMachine.getState() == SessionState.STREAMING_AUDIO) {
                stateMachine.transitionTo(SessionState.CONNECTED)
            }
        }
    }

    fun sendTextMessage(userText: String, promptText: String = userText) {
        val app = context.applicationContext as? com.example.MyApplication
        val gate = app?.entitlementGate

        // Intercept deterministic UI commands locally without Gemini
        if (com.example.automation.engine.SemanticUiCommandParser.isDeterministicPrimitive(userText) ||
            com.example.automation.engine.GoalPlanner.resolveFastPathPlan(userText, context) != null) {
            android.util.Log.i("AssistantEngine", "[DETERMINISTIC_TEXT_ROUTE] Text is deterministic UI command: '$userText'. Intercepting locally with 0 Gemini calls.")
            conversationManager.addMessage("You", userText)
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("You", userText))
            executeLocalGoal(userText)
            return
        }

        // Intercept memory fact saving directly
        CoroutineScope(Dispatchers.IO).launch {
            val saveResult = com.example.memory.retrieval.MemoryFactResolver.extractAndSaveMemoryFact(context, userText)
            if (saveResult != null) {
                gate?.checkAndConsumeQuota()
                android.util.Log.i("AssistantEngine", "[MEMORY_DIRECT_TEXT_SAVE] Saved fact for '$userText': $saveResult")
                conversationManager.addMessage("You", userText)
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("You", userText))
                conversationManager.addMessage("MAX", saveResult)
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", saveResult))
                return@launch
            }

            // Intercept personal memory queries directly
            if (com.example.memory.retrieval.MemoryFactResolver.isMemoryQuery(userText)) {
                val factResult = com.example.memory.retrieval.MemoryFactResolver.resolve(context, userText)
                if (factResult != null && factResult.found) {
                    gate?.checkAndConsumeQuota()
                    android.util.Log.i("AssistantEngine", "[MEMORY_DIRECT_TEXT_RESOLVE] Resolved memory fact for '$userText': ${factResult.responseText}")
                    conversationManager.addMessage("You", userText)
                    AssistantEventBus.emit(AssistantEvent.TextTranscribed("You", userText))
                    conversationManager.addMessage("MAX", factResult.responseText)
                    AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", factResult.responseText))
                    return@launch
                }
            }

            // Update Emotion Engine state based on incoming text message
            val (detectedEmotionEvent, currentEmotion) = emotionEngine.processUserText(userText)
            if (detectedEmotionEvent != null) {
                android.util.Log.d("AssistantEngine", "[EMOTION_EVENT_TEXT] Detected: $detectedEmotionEvent | New state: ${currentEmotion.getDominantTraits()}")
            }

            sendTextMessageToModel(userText, promptText)
        }
    }

    private fun executeLocalGoal(userText: String) {
        localExecutionScope.launch {
            val reply = try {
                val result = com.example.automation.engine.GoalPlanner.planAndExecute(context, userText)
                when {
                    result.isCancelled -> result.errorMessage ?: "Task cancelled; remaining steps not executed."
                    result.success -> result.stepResults.lastOrNull()?.message ?: "Task completed and verified."
                    else -> result.errorMessage ?: "Action failed."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", "Task cancelled; remaining steps not executed."))
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AssistantEngine", "Deterministic execution failed", e)
                com.example.automation.event.AutomationEventBus.publish(
                    com.example.automation.event.AutomationEvent.ExecutionFinished(java.util.UUID.randomUUID(), false))
                "Action failed: ${e.message ?: "execution exception"}"
            }
            conversationManager.addMessage("MAX", reply)
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", reply))
            kotlinx.coroutines.withTimeoutOrNull(5000L) {
                (context.applicationContext as? com.example.MyApplication)?.entitlementGate?.checkAndConsumeQuota()
            }
        }
    }

    private fun sendTextMessageToModel(userText: String, promptText: String) {
        val settings = com.example.data.preferences.SettingsManager(context)
        val keyPresent = settings.hasValidGeminiApiKey()
        android.util.Log.d("GeminiFlow", "[GeminiFlow] key_present=$keyPresent")
        if (!keyPresent) {
            android.util.Log.w("GeminiFlow", "[GeminiFlow] error=missing_api_key")
            android.util.Log.w("AssistantEngine", "[SECURITY_GATE] Gemini API Key is missing. Text message dropped.")
            AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.PERMISSION, "Gemini API key required. Please configure in Settings."))
            return
        }
        conversationManager.addMessage("You", userText)
        stateMachine.transitionTo(SessionState.WAITING_FOR_MODEL)
        
        // Bounded response timeout (15s) so state is never permanently stuck in THINKING
        responseTimeoutJob?.cancel()
        responseTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(15000)
            if (stateMachine.getState() == SessionState.WAITING_FOR_MODEL || stateMachine.getState() == SessionState.TOOL_EXECUTION) {
                android.util.Log.w("GeminiFlow", "[GeminiFlow] error=response_timeout")
                android.util.Log.d("GeminiFlow", "[GeminiFlow] thinking_cleared")
                val fallbackState = if (connectionManager.isConnected()) SessionState.CONNECTED else SessionState.IDLE
                stateMachine.transitionTo(fallbackState)
                AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.NETWORK, "Response timed out. Please try again."))
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Central Entitlement & Daily Quota Gate Check
            val app = context.applicationContext as? com.example.MyApplication
            val gate = app?.entitlementGate
            val decision = gate?.checkAndConsumeQuota() ?: com.example.firebase.manager.GateDecision.AllowLifetime
            if (!decision.isAllowed) {
                val blockMessage = when (decision) {
                    is com.example.firebase.manager.GateDecision.BlockDailyLimitReached -> {
                        "Daily free limit reached (${decision.messageCountToday}/${decision.limit} messages). Upgrade to Premium to continue."
                    }
                    is com.example.firebase.manager.GateDecision.BlockTrialExpired -> {
                        "Your 3-day free trial has ended. Activate MAX Premium to continue."
                    }
                    is com.example.firebase.manager.GateDecision.BlockLicenseRevoked -> {
                        "Your MAX license has been revoked by the issuer."
                    }
                    is com.example.firebase.manager.GateDecision.BlockLicenseInactive -> {
                        decision.reason
                    }
                    is com.example.firebase.manager.GateDecision.BlockNotAuthenticated -> {
                        "Please sign in with your Google account to use MAX."
                    }
                    is com.example.firebase.manager.GateDecision.BlockError -> {
                        decision.message
                    }
                    else -> "Message quota exceeded. Please activate MAX Premium."
                }
                android.util.Log.w("AssistantEngine", "[LICENSE_GATE] AI request BLOCKED: $blockMessage")
                conversationManager.addMessage("MAX", blockMessage)
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", blockMessage))
                AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.PERMISSION, blockMessage))
                val fallbackState = if (connectionManager.isConnected()) SessionState.CONNECTED else SessionState.IDLE
                stateMachine.transitionTo(fallbackState)
                responseTimeoutJob?.cancel()
                return@launch
            }

            val relevantMemories = try {
                com.example.memory.data.MemoryRepository.getInstance(context).getRelevantMemories(userText)
            } catch (e: Exception) {
                emptyList()
            }
            val effectivePrompt = if (relevantMemories.isNotEmpty() && promptText == userText) {
                val snippet = com.example.memory.retrieval.MemoryFormatter.formatForPrompt(relevantMemories)
                "<MAX_RETRIEVED_MEMORY>\n$snippet\n</MAX_RETRIEVED_MEMORY>\n\n$promptText"
            } else {
                promptText
            }

            if (isVisionQuery(userText)) {
                android.util.Log.d("AssistantEngine", "Vision query detected in text prompt: '$userText'. Capturing fresh screen...")
                val jpeg = ScreenCaptureProvider.captureCompressedJpeg()
                if (jpeg != null && jpeg.isNotEmpty()) {
                    connectionManager.sendVideoFrame(jpeg)
                    android.util.Log.d("AssistantEngine", "Fresh screen frame sent to Gemini Live for text prompt (${jpeg.size} bytes)")
                } else {
                    android.util.Log.w("AssistantEngine", "Fresh screen capture returned null for text prompt")
                }
            }
            connectionManager.sendText(effectivePrompt)
        }
    }

    fun reconnect() {
        connectionManager.forceReconnect()
    }

    fun pause() {
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        cancelAutonomousTask("Pause requested")
        stopListening()
        streamingResponseManager.stopAllPlayback()
        connectionManager.sendInterruptionSignal()
    }

    fun stop() {
        pause()
    }

    fun getConnectionState(): ConnectionState {
        return connectionManager.connectionState
    }

    fun destroy() {
        localExecutionScope.coroutineContext.cancelChildren()
        com.example.automation.engine.TaskCancellationCoordinator.unregisterCancellationListener(this)
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        stop()
        connectionManager.disconnect()
    }
}
