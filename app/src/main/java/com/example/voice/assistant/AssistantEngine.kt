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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect

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
    @Volatile lateinit var streamingResponseManager: StreamingResponseManager
    private var responseTimeoutJob: kotlinx.coroutines.Job? = null
    private val localExecutionScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val languageScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val languageSettings = com.example.data.preferences.SettingsManager(context)
    val autonomousCoordinator = com.example.automation.engine.AutonomousTaskCoordinator(localExecutionScope)
    private var voiceSessionStartTime: Long = 0L

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
                val sanitized = com.example.utils.InternalReasoningFilter.sanitizeDelta(text)
                if (sanitized.isNotEmpty()) {
                    val verified = com.example.automation.engine.truth.ExecutionTruthCoordinator.validateOrCorrectAssistantResponse(sanitized)
                    val lastMsg = conversationManager.getHistory().lastOrNull()
                    if (lastMsg == null || lastMsg.content.trim() != verified.trim()) {
                        conversationManager.addMessage("Gemini", verified)
                    }
                    streamingResponseManager.handleIncomingText(verified)
                    com.example.analytics.MaxActivityTracker.trackAiRequest(context, com.example.analytics.ActivityStatus.SUCCESS)
                    com.example.analytics.MaxActivityTracker.trackChatRequest(context, com.example.analytics.ActivityStatus.SUCCESS)
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
                            "click_element" -> "CLICK_TEXT"
                            "scroll_screen" -> "SCROLL_SCREEN"
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
                    autonomousCoordinator.onAutomationToolInvoked(functionName.ifEmpty { resolvedAction }, effectiveGoal)

                    // Check if the goal resolves via local deterministic fast-path
                    val fastPathPlan = if (effectiveGoal.isNotBlank()) {
                        com.example.automation.engine.GoalPlanner.resolveFastPathPlan(effectiveGoal, context)
                    } else null

                    val isAtomicToolCall = functionName in setOf(
                        "open_app", "click_element", "type_text", "press_enter",
                        "press_key", "clear_text", "scroll_screen", "system_action",
                        "send_whatsapp_message", "open_whatsapp_chat", "navigate_whatsapp",
                        "youtube_search", "flashlight", "set_volume", "set_brightness",
                        "set_dnd", "set_ringer_mode", "get_battery_status", "get_device_status",
                        "search_contact", "call_contact", "send_sms", "take_screenshot",
                        "take_photo", "run_adb_command", "get_bluetooth_status", "set_bluetooth",
                        "get_notifications", "reply_notification", "set_alarm", "set_timer",
                        "control_media", "search_files", "toggle_wifi", "open_settings",
                        "get_app_usage", "get_clipboard", "set_clipboard", "get_location",
                        "run_routine", "create_routine", "delete_routine", "list_routines",
                        "log_period_start", "log_period_end", "log_period_note",
                        "get_period_history", "get_period_prediction", "clear_all_period_data",
                        "save_memory", "forget_memory", "set_reminder", "unlock_device",
                        "spotify_smart_search", "instagram_bulk_unlike", "wait",
                        "inspect_screen", "get_screen_context", "create_contact", "task_completed"
                    ) || normalizedAction in setOf(
                        "OPEN_APP", "CLICK_ELEMENT", "CLICK_TEXT", "TYPE_TEXT",
                        "PRESS_ENTER", "PRESS_KEY", "CLEAR_TEXT", "SCROLL_SCREEN", "SYSTEM_ACTION",
                        "YOUTUBE_SEARCH", "FLASHLIGHT_ON", "FLASHLIGHT_OFF", "SET_VOLUME",
                        "SET_BRIGHTNESS", "SET_DND", "SET_RINGER_MODE", "GET_BATTERY_STATUS",
                        "GET_DEVICE_STATUS", "CALL_PHONE", "CALL_CONTACT", "SEND_SMS",
                        "TAKE_SCREENSHOT", "TAKE_PHOTO", "RUN_ADB_COMMAND", "TOGGLE_WIFI",
                        "OPEN_SETTINGS", "SET_ALARM", "SET_TIMER", "CONTROL_MEDIA",
                        "SEARCH_FILES", "SAVE_MEMORY", "FORGET_MEMORY", "SET_REMINDER",
                        "SPOTIFY_SMART_SEARCH", "TASK_COMPLETED", "UNLOCK_DEVICE",
                        "GET_BLUETOOTH_STATUS", "SET_BLUETOOTH", "GET_NOTIFICATIONS",
                        "REPLY_NOTIFICATION", "GET_APP_USAGE", "GET_CLIPBOARD", "SET_CLIPBOARD",
                        "GET_LOCATION", "RUN_ROUTINE", "CREATE_ROUTINE", "DELETE_ROUTINE",
                        "LIST_ROUTINES", "LOG_PERIOD_START", "LOG_PERIOD_END", "LOG_PERIOD_NOTE",
                        "GET_PERIOD_HISTORY", "GET_PERIOD_PREDICTION", "CLEAR_ALL_PERIOD_DATA",
                        "INSPECT_SCREEN", "GET_SCREEN_CONTEXT", "CREATE_CONTACT", "WAIT"
                    )

                    val executionResult = if (normalizedAction == "COMPUTER_USE" || functionName == "computer_use" || functionName == "ui_agent") {
                        android.util.Log.d("AssistantEngine", "[VOICE_COMPUTER_USE] Dispatching computer_use directly to ActionDispatcher.")
                        com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                    } else if (normalizedAction == "TASK_INTENT" || functionName == "task_intent") {
                        android.util.Log.d("AssistantEngine", "[VOICE_TASK_INTENT] Dispatching task_intent directly to ActionDispatcher for background orchestration.")
                        com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                    } else if (isAtomicToolCall) {
                        // Gemini Live WebSocket ALREADY structured the tool call with atomic action/app/query/target.
                        // Dispatch the structured action directly through ActionDispatcher (ZERO hijacking by multi-step plans)!
                        android.util.Log.d("AssistantEngine", "[VOICE_DIRECT_DISPATCH] Dispatching atomic structured action '$normalizedAction' directly to ActionDispatcher.")

                        // If query/text is present but target is missing, populate target from query/text
                        if (normalizedAction == "TYPE_TEXT" && !normalizedJson.has("text") && goalParam.isNotBlank()) {
                            normalizedJson.put("text", goalParam)
                            normalizedJson.put("target", goalParam)
                        } else if (normalizedAction == "OPEN_APP" && !normalizedJson.has("app") && target.isNotBlank()) {
                            normalizedJson.put("app", target)
                        } else if (normalizedAction == "YOUTUBE_SEARCH" && !normalizedJson.has("query")) {
                            val q = target.ifEmpty { goalParam }
                            if (q.isNotBlank()) normalizedJson.put("query", q)
                        }

                        // Authorize and dispatch
                        val isCallAction = normalizedAction == "CALL_PHONE" || normalizedAction == "CALL_CONTACT"
                        val callState = com.example.automation.engine.PendingCallManager.state
                        val isSelectionReply = (callState is com.example.automation.engine.CallState.AwaitingContactSelection) &&
                            com.example.automation.engine.PendingCallManager.getSelectionCandidates().isNotEmpty() &&
                            com.example.automation.engine.PendingCallManager.resolveAndSelectCandidate(lastUserText) != null

                        val authIntent = resolveAuthorizationIntent(
                            action = normalizedAction,
                            userText = lastUserText,
                            callState = callState,
                            originalPendingIntent = com.example.automation.engine.PendingCallManager.getOriginalIntent(),
                            target = target,
                            effectiveGoal = effectiveGoal,
                            isSelectionMatch = isSelectionReply
                        )

                        val auth = com.example.automation.engine.ActionAuthorizer.authorizeAction(authIntent, normalizedAction, target.ifEmpty { goalParam })
                        if (!auth.authorized) {
                            android.util.Log.w("AssistantEngine", "[VOICE_AUTH] Action '$normalizedAction' BLOCKED: ${auth.reason}")
                            if (isCallAction && com.example.automation.engine.PendingCallManager.state is com.example.automation.engine.CallState.Confirmed) {
                                com.example.automation.engine.PendingCallManager.clear()
                            }
                            org.json.JSONObject().apply {
                                put("status", "blocked")
                                put("success", false)
                                put("executionState", "BLOCKED")
                                put("reason", auth.reason)
                            }.toString()
                        } else {
                            val result = try {
                                com.example.automation.engine.ActionDispatcher.dispatchWithResult(context, normalizedJson)
                            } finally {
                                // Clear PendingCallManager only if the state AFTER dispatch (or in the exception path) is still Confirmed.
                                // Never clear on NEEDS_INPUT / AwaitingContactSelection / AwaitingConfirmation / Calling results.
                                if (isCallAction && com.example.automation.engine.PendingCallManager.state is com.example.automation.engine.CallState.Confirmed) {
                                    com.example.automation.engine.PendingCallManager.clear()
                                }
                            }
                            val resultJson = runCatching { org.json.JSONObject(result) }.getOrNull()
                            val executionSucceeded = resultJson?.optBoolean("success", false) == true ||
                                resultJson?.optString("executionState") == "COMPLETED"
                            if (executionSucceeded && !autonomousCoordinator.isCompoundGoal(lastUserText.ifEmpty { effectiveGoal })) {
                                autonomousCoordinator.onTaskCompleted("Atomic tool '$normalizedAction' completed.")
                            }
                            result
                        }
                    } else if (functionName == "execute_automation" || normalizedAction == "EXECUTE_AUTOMATION") {
                        if (effectiveGoal.isBlank()) {
                            org.json.JSONObject().apply {
                                put("status", "blocked")
                                put("success", false)
                                put("executionState", "BLOCKED")
                                put("reason", "The automation goal is missing; clarification is required before execution.")
                            }.toString()
                        } else {
                            // GoalPlanner owns both deterministic fast paths and
                            // validated LLM planning. Do not require a local
                            // fast-path match here, or complex user goals are
                            // incorrectly sent through generic authorization.
                            val validationError = if (fastPathPlan != null && fastPathPlan.isNotEmpty()) {
                                android.util.Log.d("AssistantEngine", "[VOICE_PLAN] Routing voice goal '$effectiveGoal' through fast-path DAG (${fastPathPlan.size} steps, ZERO LLM calls).")
                                com.example.automation.engine.GoalPlanner.validatePlan(fastPathPlan, effectiveGoal)
                            } else {
                                android.util.Log.d("AssistantEngine", "[VOICE_PLAN] Routing voice goal '$effectiveGoal' through validated planner fallback.")
                                null
                            }

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
                                android.util.Log.d("AssistantEngine", "[VOICE_RESULT] Finished planned goal '$effectiveGoal' (success=${planResult.success})")
                                if (planResult.success && !planResult.isWaitingForInput) {
                                    autonomousCoordinator.onTaskCompleted("Planned goal completed and verified.")
                                }
                                org.json.JSONObject().apply {
                                    put("status", if (planResult.success) "success" else "error")
                                    put("success", planResult.success)
                                    put("executionState", if (planResult.success) "COMPLETED" else "FAILED")
                                    put("reason", planResult.errorMessage ?: (if (planResult.success) "Goal completed successfully." else "Goal execution failed."))
                                }.toString()
                            }
                        }
                    } else {
                        val auth = com.example.automation.engine.ActionAuthorizer.authorizeAction(lastUserText.ifEmpty { effectiveGoal }, normalizedAction, target)
                        if (!auth.authorized) {
                            android.util.Log.w("AssistantEngine", "[VOICE_AUTH] Unsolicited/Rejected action '$normalizedAction' BLOCKED: ${auth.reason}")
                            com.example.automation.activity.AgentActivityTracker.addTrace("ActionAuthorizer", "Action '$normalizedAction' blocked: ${auth.reason}", isSuccess = false)
                            org.json.JSONObject().apply {
                                put("status", "blocked")
                                put("success", false)
                                put("executionState", "BLOCKED")
                                put("reason", auth.reason)
                            }.toString()
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
                                    localExecutionScope.launch {
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

                    val enrichedResult = autonomousCoordinator.enrichToolResult(executionResult)

                    // Feed automation execution outcome to Emotion Engine
                    val parsed = runCatching { org.json.JSONObject(enrichedResult) }.getOrNull()
                    if (functionName == "task_completed" || normalizedAction == "TASK_COMPLETED") {
                        val completionAccepted = parsed?.optBoolean("completionAccepted", false) == true &&
                            parsed.optBoolean("success", false)
                        if (completionAccepted) {
                            val summaryText = normalizedJson.optString("summary", "Task completed and verified.")
                            autonomousCoordinator.onTaskCompleted(summaryText)
                        } else {
                            android.util.Log.w("AssistantEngine", "[VOICE_COMPLETION] Ignoring unverified task_completed claim.")
                        }
                    }
                    if (parsed?.optString("status") == "success" || parsed?.optString("executionState") == "COMPLETED") {
                        emotionEngine.processEvent(com.example.emotion.model.EmotionEvent.TaskSuccess(resolvedAction.ifEmpty { functionName }))
                    } else if (parsed?.optString("status") == "error" || parsed?.optString("executionState") == "FAILED") {
                        val reason = parsed.optString("reason", "Task execution failed")
                        emotionEngine.processEvent(com.example.emotion.model.EmotionEvent.TaskFailure(resolvedAction.ifEmpty { functionName }, reason))
                    }

                    enrichedResult

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
            onInterimTranscription = { interimText ->
                android.util.Log.d("AssistantEngine", "Interim voice caption: '$interimText'")
                onInterimTranscriptionListener?.invoke(interimText)
            },
            onUserTranscription = { userText ->
                handleUserTranscription(userText)
            },
            onTurnComplete = {
                lastAuthoritativeTranscription = null
                responseTimeoutJob?.cancel()
                responseTimeoutJob = null
                audioProcessor.reset()
                streamingResponseManager.handleTurnComplete()
                autonomousCoordinator.onTurnComplete(connectionManager, audioPlayer, context)
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
        var observedLanguage = languageSettings.languagePreference
        languageScope.launch(start = CoroutineStart.UNDISPATCHED) {
            languageSettings.languagePreferenceFlow.collect { language ->
                if (language != languageSettings.languagePreference) return@collect
                if (language != observedLanguage) {
                    observedLanguage = language
                    onLanguagePreferenceChanged(language)
                }
            }
        }
    }

    private fun onLanguagePreferenceChanged(language: String) {
        if (connectionManager.updateLanguage(language)) {
            lastAuthoritativeTranscription = null
            responseTimeoutJob?.cancel()
            responseTimeoutJob = null
            streamingResponseManager.stopAllPlayback()
            // The old turn's accumulated text belongs to the old language as well.
            streamingResponseManager = StreamingResponseManager(audioPlayer, toolRegistry,
                { id, name, result -> connectionManager.sendToolResponse(id, name, result) },
                { state -> stateMachine.transitionTo(state) })
        }
    }

    var onInterimTranscriptionListener: ((String) -> Unit)? = null
    private var lastAuthoritativeTranscription: String? = null

    /**
     * Routes spoken/typed user text. P1.2: an affirmative/negative reply to a
     * pending approval is consumed here so confirmations work hands-free.
     */
    private fun handleUserTranscription(userText: String) {
        if (userText.isBlank()) return
        if (userText == lastAuthoritativeTranscription) {
            android.util.Log.d("AssistantEngine", "Skipping duplicate authoritative transcription for turn: '$userText'")
            return
        }
        lastAuthoritativeTranscription = userText

        // Autonomous task stop / cancellation check on user speech
        if (autonomousCoordinator.handleUserInterruption(userText)) {
            streamingResponseManager.stopAllPlayback()
            connectionManager.sendInterruptionSignal()
            val isTelugu = com.example.automation.computeruse.isTeluguGoal(userText)
            val stopMsg = if (isTelugu) "Task aapivesanu sir." else "Task stopped."
            conversationManager.addMessage("MAX", stopMsg)
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", stopMsg))
            return
        }

        // 1. Pending approval reply routing (P1.2)
        if (com.example.automation.approval.ApprovalCoordinator.hasPending()) {
            val ctx = com.example.automation.approval.ApprovalCoordinator.context() ?: context
            localExecutionScope.launch {
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
                localExecutionScope.launch {
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

        val lowerUserText = userText.lowercase()
        val looksLikeMemorySave = lowerUserText.contains("remember") ||
            lowerUserText.contains("gurthu") || lowerUserText.contains("gurtu") ||
            lowerUserText.contains("save memory") || lowerUserText.contains("note down") ||
            lowerUserText.contains("memory lo") || lowerUserText.contains("yaad rakhna")
        if (com.example.memory.retrieval.MemoryFactResolver.isMemoryQuery(userText) || looksLikeMemorySave) {
            conversationManager.addMessage("You", userText)
            AssistantEventBus.emit(AssistantEvent.TextTranscribed("You", userText))
            localExecutionScope.launch {
                val saved = com.example.memory.retrieval.MemoryFactResolver.extractAndSaveMemoryFact(context, userText)
                val factResult = if (saved == null && com.example.memory.retrieval.MemoryFactResolver.isMemoryQuery(userText)) {
                    com.example.memory.retrieval.MemoryFactResolver.resolve(context, userText)
                } else null
                val response = saved ?: factResult?.responseText
                if (!response.isNullOrBlank()) {
                    streamingResponseManager.stopAllPlayback()
                    responseTimeoutJob?.cancel()
                    responseTimeoutJob = null
                    conversationManager.addMessage("MAX", response)
                    AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", response))
                    val currentPref = com.example.data.preferences.SettingsManager(context).languagePreference
                    val resolved = com.example.domain.policy.LanguagePolicy.resolveResponseLanguage(userText, currentPref)
                    val langDirective = when (resolved) {
                        com.example.domain.policy.AssistantLanguage.TELUGU -> "natural spoken Telugu"
                        com.example.domain.policy.AssistantLanguage.ENGLISH -> "natural spoken English"
                        com.example.domain.policy.AssistantLanguage.HINDI -> "natural spoken Hindi"
                        com.example.domain.policy.AssistantLanguage.TINGLISH -> "natural Tinglish"
                    }
                    if (connectionManager.isConnected()) {
                        connectionManager.sendInterruptionSignal()
                        connectionManager.sendText("Speak this exact statement to the user in $langDirective: $response")
                    }
                } else {
                    // A phrase containing "remember" is not necessarily a
                    // memory command. Preserve ordinary conversation instead
                    // of swallowing an unsupported/ambiguous utterance.
                    sendTextMessageToModel(userText, userText, recordUserMessage = false)
                }
            }
            return
        }

        conversationManager.addMessage("You", userText)
        AssistantEventBus.emit(AssistantEvent.TextTranscribed("You", userText))

        val app = context.applicationContext as? com.example.MyApplication
        val gate = app?.entitlementGate

        // Voice quota gate check and real-time Memory Fact saving or retrieval
        localExecutionScope.launch {
            val quotaDecision = gate?.checkAndConsumeQuota() ?: com.example.firebase.manager.GateDecision.AllowLifetime
            if (!quotaDecision.isAllowed) {
                val blockMsg = when (quotaDecision) {
                    is com.example.firebase.manager.GateDecision.BlockDailyLimitReached ->
                        "Daily free limit reached (${quotaDecision.messageCountToday}/${quotaDecision.limit} messages). Upgrade to Premium to continue."
                    is com.example.firebase.manager.GateDecision.BlockTrialExpired ->
                        "Your 7-day free trial has ended. Activate MAX Premium to continue."
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
                if (connectionManager.isConnected()) {
                    connectionManager.sendInterruptionSignal()
                    val currentPref = com.example.data.preferences.SettingsManager(context).languagePreference
                    val resolved = com.example.domain.policy.LanguagePolicy.resolveResponseLanguage(userText, currentPref)
                    val langDirective = when (resolved) {
                        com.example.domain.policy.AssistantLanguage.TELUGU -> "natural spoken Telugu"
                        com.example.domain.policy.AssistantLanguage.ENGLISH -> "natural spoken English"
                        com.example.domain.policy.AssistantLanguage.HINDI -> "natural spoken Hindi"
                        com.example.domain.policy.AssistantLanguage.TINGLISH -> "natural Tinglish"
                    }
                    // Interrupted Gemini so it does not echo or repeat duplicate response
                }
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
                    if (connectionManager.isConnected()) {
                        connectionManager.sendInterruptionSignal()
                        val currentPref = com.example.data.preferences.SettingsManager(context).languagePreference
                        val resolved = com.example.domain.policy.LanguagePolicy.resolveResponseLanguage(userText, currentPref)
                        val langDirective = when (resolved) {
                            com.example.domain.policy.AssistantLanguage.TELUGU -> "natural spoken Telugu"
                            com.example.domain.policy.AssistantLanguage.ENGLISH -> "natural spoken English"
                            com.example.domain.policy.AssistantLanguage.HINDI -> "natural spoken Hindi"
                            com.example.domain.policy.AssistantLanguage.TINGLISH -> "natural Tinglish"
                        }
                        // Interrupted Gemini so it does not echo or repeat duplicate response
                    return@launch
                    }
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
            
            // Immediately stop any Gemini speculative audio/text playback and send interruption signal to cloud turn
            streamingResponseManager.stopAllPlayback()
            connectionManager.sendInterruptionSignal()
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
            localExecutionScope.launch {
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
        localExecutionScope.launch {
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
                    "Your 7-day free trial has ended. Activate MAX Premium to continue."
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
        lastAuthoritativeTranscription = null
        streamingResponseManager.handleUserSpeaking()
        connectionManager.sendInterruptionSignal()
        if (!connectionManager.isConnected()) {
            connectionManager.connect()
        }

        android.util.Log.d("VoiceLatency", "[VoiceLatency] speech_start ts=${System.currentTimeMillis()}")
        voiceSessionStartTime = System.currentTimeMillis()
        com.example.analytics.MaxActivityTracker.trackVoiceSessionStart(context)
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
            connectionManager.sendAudioStreamEnd()
        } catch (e: Exception) {
            android.util.Log.e("AssistantEngine", "Error stopping audio recorder", e)
        } finally {
            if (voiceSessionStartTime > 0) {
                val dur = System.currentTimeMillis() - voiceSessionStartTime
                com.example.analytics.MaxActivityTracker.trackVoiceSessionEnd(context, dur)
                voiceSessionStartTime = 0L
            }
            audioProcessor.reset()
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
        localExecutionScope.launch {
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
                autonomousCoordinator.cancel("Deterministic local goal completed")
                when {
                    result.isCancelled -> result.errorMessage ?: "Task cancelled; remaining steps not executed."
                    result.success -> result.stepResults.lastOrNull()?.message ?: "Task completed and verified."
                    else -> result.errorMessage ?: "Action failed."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                autonomousCoordinator.cancel("Deterministic local goal cancelled")
                AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", "Task cancelled; remaining steps not executed."))
                throw e
            } catch (e: Exception) {
                autonomousCoordinator.cancel("Deterministic local goal failed")
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

    private fun sendTextMessageToModel(userText: String, promptText: String, recordUserMessage: Boolean = true) {
        val settings = com.example.data.preferences.SettingsManager(context)
        val keyPresent = settings.hasValidGeminiApiKey()
        android.util.Log.d("GeminiFlow", "[GeminiFlow] key_present=$keyPresent")
        if (!keyPresent) {
            android.util.Log.w("GeminiFlow", "[GeminiFlow] error=missing_api_key")
            android.util.Log.w("AssistantEngine", "[SECURITY_GATE] Gemini API Key is missing. Text message dropped.")
            AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.PERMISSION, "Gemini API key required. Please configure in Settings."))
            return
        }
        if (recordUserMessage) {
            conversationManager.addMessage("You", userText)
        }
        stateMachine.transitionTo(SessionState.WAITING_FOR_MODEL)
        
        // Bounded response timeout (15s) so state is never permanently stuck in THINKING
        responseTimeoutJob?.cancel()
        responseTimeoutJob = localExecutionScope.launch {
            delay(15000)
            if (stateMachine.getState() == SessionState.WAITING_FOR_MODEL || stateMachine.getState() == SessionState.TOOL_EXECUTION) {
                android.util.Log.w("GeminiFlow", "[GeminiFlow] error=response_timeout")
                android.util.Log.d("GeminiFlow", "[GeminiFlow] thinking_cleared")
                val fallbackState = if (connectionManager.isConnected()) SessionState.CONNECTED else SessionState.IDLE
                stateMachine.transitionTo(fallbackState)
                AssistantEventBus.emit(AssistantEvent.ErrorOccurred(ErrorType.NETWORK, "Response timed out. Please try again."))
            }
        }

        localExecutionScope.launch {
            // Central Entitlement & Daily Quota Gate Check
            val app = context.applicationContext as? com.example.MyApplication
            val gate = app?.entitlementGate
            val decision = gate?.checkAndConsumeQuota() ?: com.example.firebase.manager.GateDecision.AllowLifetime
            coroutineContext.ensureActive()
            if (!decision.isAllowed) {
                val blockMessage = when (decision) {
                    is com.example.firebase.manager.GateDecision.BlockDailyLimitReached -> {
                        "Daily free limit reached (${decision.messageCountToday}/${decision.limit} messages). Upgrade to Premium to continue."
                    }
                    is com.example.firebase.manager.GateDecision.BlockTrialExpired -> {
                        "Your 7-day free trial has ended. Activate MAX Premium to continue."
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
            coroutineContext.ensureActive()
            connectionManager.sendText(effectivePrompt)
        }
    }

    fun reconnect() {
        connectionManager.forceReconnect()
    }

    fun updateLanguage(newLanguage: String) {
        languageSettings.languagePreference = newLanguage
    }

    fun pause() {
        autonomousCoordinator.cancel("Session paused/stopped")
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
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
        languageScope.coroutineContext.cancelChildren()
        localExecutionScope.coroutineContext.cancelChildren()
        com.example.automation.engine.TaskCancellationCoordinator.unregisterCancellationListener(this)
        responseTimeoutJob?.cancel()
        responseTimeoutJob = null
        stop()
        connectionManager.destroy()
    }

    /** Account boundary: do not resume the previous user's Gemini context. */
    fun resetAccountContext() {
        localExecutionScope.coroutineContext.cancelChildren()
        pause()
        conversationManager.clear()
        connectionManager.resetAccountContext()
    }

    companion object {
        /**
         * Pure function resolving the intent string used for authorization.
         * For confirmed calls, valid consent replies, or valid candidate selections,
         * uses the original call intent. Otherwise preserves the raw user utterance.
         */
        fun resolveAuthorizationIntent(
            action: String,
            userText: String,
            callState: com.example.automation.engine.CallState,
            originalPendingIntent: String?,
            target: String = "",
            effectiveGoal: String = "",
            isSelectionMatch: Boolean = false
        ): String {
            val isCallAction = action == "CALL_PHONE" || action == "CALL_CONTACT"
            if (!isCallAction) {
                return userText.ifEmpty { effectiveGoal }
            }

            val isConfirmedState = callState is com.example.automation.engine.CallState.Confirmed
            val pendingTargetName = when (callState) {
                is com.example.automation.engine.CallState.AwaitingConfirmation -> callState.displayName
                is com.example.automation.engine.CallState.Confirmed -> callState.displayName
                else -> null
            }
            val hasLivePending = callState is com.example.automation.engine.CallState.AwaitingConfirmation ||
                    callState is com.example.automation.engine.CallState.Confirmed ||
                    callState is com.example.automation.engine.CallState.AwaitingContactSelection
            val isConsentReply = hasLivePending && com.example.automation.approval.ApprovalCoordinator.isConsentUtterance(userText, pendingTargetName)
            val isSelectionReply = (callState is com.example.automation.engine.CallState.AwaitingContactSelection) && isSelectionMatch

            return if (isConfirmedState || isConsentReply || isSelectionReply) {
                originalPendingIntent ?: (if (target.isNotBlank()) "Call $target" else "Call")
            } else {
                userText.ifEmpty { effectiveGoal }
            }
        }
    }
}
