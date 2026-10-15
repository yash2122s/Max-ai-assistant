# MAX Task Execution Forensic Trace

## Scope and expected behavior

Target request: “Open WhatsApp, wait 30 seconds, open YouTube, wait 30 seconds, close both, open Spotify and play a song, minimize Spotify, open WhatsApp, unlock the phone, send a message, then close apps and turn the screen off.”

The request requires a durable parent task, ordered dependencies, explicit waits, multiple app transitions, unlock verification, a sensitive send approval, and final verification. Static inspection cannot prove device success.

## Actual request path

| Stage | Actual component/path | State and evidence | Assessment |
|---|---|---|---|
| Input | `MainActivity`/`HomeViewModel` or voice services; `AssistantEngine.handleUserTranscription()` | Text is persisted to chat and emitted as an event; voice also enters Gemini Live. | Implemented, multiple ingress paths |
| Quota/auth gate | `AssistantEngine.handleUserTranscription()` and `startListening()`; entitlement manager | Trial/daily quota may block or consume before full execution. | Implemented, asynchronous coupling |
| Intent/model | `GeminiWebSocketClient` receives Live model tool calls; `ToolDispatcher` maps names; deterministic parser may intercept first | `ToolDispatcher` has `task_intent`, `computer_use`, app and messaging tools; `AssistantEngine` also checks fast paths. | Implemented but routing is duplicated |
| Planning | `GoalPlanner`, `TaskPlanner`, `GeminiPlanningProvider`, `LongTaskOrchestrator` | Plan validation includes max steps, dependencies, supported actions, and authorization. | Partially implemented; max 8/12 steps and no durable task record |
| Task creation | `GoalPlanner.initializeGoalPlan()` and in-memory `ActionQueue`; `WorldStateManager.setCurrentGoal()` | Queue and completed/output maps are global process memory. | Not durable; not independent per task |
| Decomposition | `SemanticUiCommandParser`, `GoalPlanner.resolveFastPathPlan()`, LLM planner | Creates `ActionStep` objects and dependencies. | Implemented for supported patterns; complex natural-language coverage unknown |
| Tool selection | `ActionDispatcher`, `CapabilityRouter`, `ExecutionEngine`, `ToolRegistry` | Action normalization and tool lookup are deterministic after model output. | Implemented but multiple normalization layers can drift |
| Execution | `ExecutionEngine.execute()` → `RetryEngine` → tool → action/controller; UI routes through `JarvisAccessibilityService`, intents, Shizuku, or computer-use engine | 25s per tool, 60s plan/computer-use budgets; retries depend on tool policy and recovery. | Implemented but unsuitable for long parent workflow |
| Waits | `WAIT_FOR_UI` exists; parser/planner can generate wait-like steps | No evidence that arbitrary “wait 30 seconds” is represented durably in the tested end-to-end path. | Unknown/partial |
| Observation | `LightweightUiObserver`, `ScreenObservationProvider`, `CurrentUiStateStore`, `DeviceContext`, accessibility events | Accessibility tree plus optional screenshot; screen capture fallback can block 2s. | Implemented, runtime quality unknown |
| Verification | `VerificationRegistry`, tool-specific verifiers, `ComputerUseEngine` evidence checks | Generic `ExecutionEngine` uses verifier when registered; computer-use refuses unverified `FinishTask`. | Mixed; some verifiers are strong, others are optimistic or shallow |
| Sensitive action | `ActionAuthorizer`/`ApprovalCoordinator` | Approval is single-slot, process-global, and returns `executionState=RUNNING` while awaiting approval in `AssistantEngine.kt:244-256`. | Critical state disconnect |
| Approval continuation | `AssistantEngine`, `HomeViewModel`, or `ChatViewModel` reply handler | Resolves one payload and launches asynchronous dispatch; returned result is ignored. | Parent task is not resumed or persisted |
| State update | `AutomationEventBus`, `AgentActivityTracker`, `WorldStateManager`, in-memory planner maps | Several observers update independent states; events carry UUIDs but no durable parent task identity in many paths. | Divergence risk |
| Persistence | Room for chat/scheduled tasks/memory/license; no Room entity for active agent plan | Process death loses queue/waiting state. | Missing for long tasks |
| Final response | `GeminiWebSocketClient`/streaming response and ViewModels display model text/events; deterministic paths emit “Done sir.” only when local result says success | UI chat can show model output independent of final verified parent state. | Completion truth is not centralized |

## Concrete false-success paths

1. `AgentOrchestrator.processUserPrompt()` direct-fast-path branch records an action and returns a success-like string without tool execution.
2. Approval request returns JSON with `status=awaiting_approval`, but `executionState=RUNNING`; no task-scoped waiting state is recorded.
3. Approval handlers ignore `dispatchWithResult()` output, so failed execution has no guaranteed conversational result.
4. `HomeViewModel` has hardcoded completion wording for offline/paused plan results, but this is safe only if the upstream result is authoritative; it is not linked to a durable parent task.
5. `AgentTask.advance()` marks `COMPLETED` when its final index advances, with no embedded final verification or child-result invariant.

## Who can declare completion?

- `ExecutionEngine`: emits `ExecutionFinished(success)` after tool result/verifier loop.
- `LongTaskOrchestrator`: returns success and publishes completion after queue exhaustion.
- `ComputerUseEngine`: returns verified success only after evidence checks, but has separate status model.
- `AgentTask.advance()`: sets `TaskStatus.COMPLETED` at final index.
- `TaskManager`: marks scheduled tasks completed from `ExecutionResult.success`.
- `AgentActivityTracker`: maps `ExecutionFinished(true)` to UI completed state.
- ViewModels/chat: render completion strings based on returned results or model text.
- LLM: can produce natural-language claims, and the current architecture does not show a final response gate that rejects unverified claims.

There is more than one completion authority; the executor/verifier is not the sole source of truth.

## State-machine assessment

Existing state sets cover pieces of the desired lifecycle:

- `ExecutionState`: `QUEUED`, `RUNNING`, `VERIFYING`, `RETRYING`, `WAITING_FOR_USER_INPUT`, `SUCCEEDED`, `FAILED`, `CANCELLED`.
- Long task `TaskStatus`: `PLANNING`, `READY`, `RUNNING`, `WAITING_FOR_UI`, `RECOVERING`, `COMPLETED`, `FAILED`, `CANCELLED`.
- Computer-use state: `IDLE`, `OBSERVING`, `ASSESSING`, `PLANNING`, `EXECUTING`, `VERIFYING`, `RECOVERING`, `COMPLETED`, `FAILED`, `CANCELLED`.
- Scheduler state: only `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.

Missing or inconsistent: durable `WAITING_FOR_APPROVAL`, `BLOCKED`, `PAUSED`, `TIMED_OUT`, explicit parent/child linkage, versioned transitions, and restart recovery. Invalid transitions are not centrally rejected.

## Corrected design recommendation (not implemented)

Use one persisted `AgentTaskRecord` plus child `TaskStepRecord`s. Transitions should be reduced by a single owner:

`PLANNED → READY → EXECUTING → WAITING_FOR_UI/WAITING_FOR_APPROVAL/NEEDS_USER → VERIFYING → RECOVERING → EXECUTING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT`.

Every transition stores task ID, step ID, attempt, evidence reference, verifier result, and state version. Approval resumes the same task ID. Parent success requires every required child to be verified and a final goal predicate to pass. Natural-language completion is generated only from the persisted terminal state.
