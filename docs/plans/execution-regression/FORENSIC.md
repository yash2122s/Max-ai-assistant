# MAX execution regression — pre-change forensic findings

## Evidence limits

Audit performed before production code edits. ADB `-s 3bc00016 get-state` returned `device '3bc00016' not found`; `adb devices -l` returned no devices. The exact stop on the user's installed APK is therefore **not yet observed**. Source paths and failing JVM reproductions below are facts; attributing the physical-device stall to a particular database, Binder, or model callback without logs would be speculation.

## Current runtime chain

Typed Home input → `HomeScreen` → `HomeViewModel.sendTextMessage` → `AssistantUseCase.sendTextMessage` → `OfflineCommandEngine.executeIfMatched` → `GoalPlanner.resolveFastPathPlan` → `GoalPlanner.planAndExecute` → validate/authorize → `PlanCreated` → global `initializeGoalPlan` → `plannerMutex` → `executePlanInternal` → **LongTaskOrchestrator.executePlan even for one action** → Room `AgentTaskStore.create` → register token → foreground service start → plan timeout begins → authorize → accessibility pre-observation → persist RUNNING → service progress → `ExecutionEngine.execute` → ToolRegistry → RetryEngine → actual tool → DeviceContext / verifier → ExecutionTruth → persist step → derive parent → ExecutionFinished → return through UseCase → Home persists response and clears isThinking.

Voice transcription → `GeminiWebSocketClient.handleMessage` → `AssistantEngine.handleUserTranscription` → local detached IO coroutine → same GoalPlanner/LongTaskOrchestrator chain → final TextTranscribed event. Exceptions escaping this detached coroutine have no final assistant event. Each accumulated transcription can enter this path again.

Live function → `GeminiWebSocketClient.executeFunctionCall` → tracked call-ID job → `roomWriteMutex` → `ToolDispatcher.dispatch` → 90-second automation deadline → ConnectionManager callback → `AssistantEngine.onExecuteAutomation` → normalized JSON / intent authorizer → deterministic goal intercept re-executes the whole fast-path plan, or `ActionDispatcher.dispatchWithResult` → normal ExecutionEngine (OPEN_APP/FLASHLIGHT), TaskPlanner/LongTaskOrchestrator (TASK_INTENT), or preserved ComputerUseEngine (complex UI).

Live IDLE/turnComplete gates only model interaction callbacks; `toolJobs` must be empty before completion. No await on global orchestrator idle exists. Engine execution is not waiting for Gemini IDLE. Foreground service never waits for a completion callback. AutomationEventBus uses tryEmit, no blocking channel receive.

ChatViewModel typed input calls OfflineCommandEngine first, then shared AssistantEngine. Home observes assistant event/session StateFlows; AgentActivityCard independently observes AgentActivityTracker.active. PlanCreated makes the activity card active; missing ExecutionFinished leaves it there. Home has an eight-second cosmetic thinking failsafe, which does not execute/terminate work. This audit does not use that failsafe as a fix.

## Exact command selection (source trace, no fabricated runtime IDs)

| Field | Turn on flashlight | Open YouTube | Open WhatsApp |
|---|---|---|---|
| Planner output | step_1 FLASHLIGHT_ON | step_1 OPEN_APP, com.google.android.youtube | step_1 OPEN_APP, com.whatsapp |
| Tool | flashlight | open_app | open_app |
| Pre-dispatch task | PLANNED, then RUNNING | same | same |
| ActionDispatcher reached by typed fast path | No; orchestrator calls ExecutionEngine directly | No | No |
| Android API | CameraManager.setTorchMode(cameraIds[0], true) | startActivity(launch intent, NEW_TASK) | same |
| Verification | disabled in FlashlightTool; synthetic success | OpenAppVerifier polling | same |
| Extra lifecycle | unconditional Room/FGS/UI observation | same | same |
| Device taskId/executionId/result/UI/final reply | unavailable without connected device | unavailable | unavailable |

## Historical comparison

Git `1fdab24` GoalPlanner executed steps directly through ExecutionEngine with a bounded plan loop and cleared the goal on terminal failure. `16e02fc` already introduced delegation to LongTaskOrchestrator for all plans. Current uncommitted WAIT/STOP changes add mandatory foreground service startup/progress, token registration, and durable step recording; October 4 changes add persisted parent completion gating. HEAD is not a proven working device baseline and recent changes are largely uncommitted; chronology cannot be reduced to one recent commit.

## Proven lifecycle defects

1. LongTaskOrchestrator creates Room task/steps **outside** its timeout and try/finally. An exception/hang before the tool path has no terminal publication; an exception inside only stops FGS/unregisters token, leaving PLANNED/RUNNING and PlanCreated.active. This is a concrete orphan-state mechanism, not proof that the user's device currently hits it.
2. GoalPlanner serializes single actions behind a process-global planner mutex and initializes mutable goal/queue before acquiring it. Simple commands inherit all long-plan prerequisites; overlapping inputs can replace pending goal data. Terminal results without failedStepId, and exceptions, do not always clear the goal.
3. ExecutionEngine publishes early validation/tool-not-found failure events but does not record ExecutionTruth. A cancelled token is checked only **after** tool.execute by RetryEngine: the actual action still runs before cancellation is noticed.
4. WAIT cancellation becomes ordinary failed ToolResult and can enter recovery/replanning. Cancellation writes INTERRUPTED rather than CANCELLED; executeTask checks nullable original token instead of its registered effective token. STOP does not cancel Live jobs/queued calls.
5. FlashlightTool sets verificationRequired=false and uses the first camera, without flash capability detection or torch callback evidence. Successful setTorchMode return is treated as verified ON.
6. OpenAppVerifier accepts WorldStateManager/snapshot cache as foreground proof and broad OEM substring matching. The launch has a bounded poll, but cached package is not actual package observation.
7. Generic ExecutionTruth response guard rejects failed truth but accepts success language when truth is absent. Explicit scoped generic lookup can also use unrelated turn truth.

## Reproductions before production edits

`BasicExecutionRegressionTest`: 6 tests, 4 failed. Proven: missing-tool truth absent; cancelled request invokes executor; cancelled WAIT is not terminal cancelled; generic success without truth passes. Existing `LongHorizonTaskExecutionTest`: passed, illustrating why the old suite missed these boundaries. Additional targeted simple/long tests will exercise the correction, not pretend to reproduce physical torch or foreground behavior.

## Fix boundary

Use existing GoalPlanner interpretation with task-local one-action dispatch into existing ExecutionEngine; keep LongTaskOrchestrator for multi-step/explicit WAIT. Keep existing truth/store and legal transitions. Cover initialization/result/exception/timeout/cancellation terminal publication; preserve call and messaging controllers. Require callback torch evidence and live foreground package evidence. No new planner, dispatcher, engine, truth store, or task state machine.
