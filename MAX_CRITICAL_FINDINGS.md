# MAX Critical Findings

**Mode:** Audit only. No source implementation was performed for this audit.
**Evidence basis:** Static source inspection of the current worktree, project knowledge files, manifest/build configuration, tests, and targeted execution-path tracing. Runtime/device claims are marked unknown unless directly supported by source or existing project records.

## P0 — Fix before major feature work

### P0-1 — Parent-task truth is not bound to approval execution

- **Problem:** A sensitive step can pause for confirmation, but approval stores only one `PendingApproval` payload and does not retain the parent goal, remaining steps, step index, or task ID.
- **Root cause:** `ApprovalCoordinator` is a process-global single-slot approval object. `AssistantEngine`, `HomeViewModel`, and `ChatViewModel` each independently consume approval replies and launch `ActionDispatcher.dispatchWithResult()` without awaiting, recording, or resuming the original plan.
- **Evidence:** `ApprovalCoordinator.kt:26-47,52-83`; `AssistantEngine.kt:320-335`; `HomeViewModel.kt:268-294`; `ChatViewModel.kt:284-311`.
- **Impact:** The UI/conversation can say approval was accepted while the original multi-step task is lost, detached, or still running. A failed approved action is not necessarily surfaced as parent-task failure.
- **Severity:** Critical correctness.
- **Recommendation:** Persist a task-scoped approval checkpoint containing task/step IDs, immutable payload hash, remaining DAG, and explicit `WAITING_FOR_APPROVAL` state. Resume the same orchestrator only after verified approval execution.

### P0-2 — Fast-path execution can claim execution without executing a tool

- **Problem:** `AgentOrchestrator` returns a success-like string for direct classification without dispatching a tool.
- **Evidence:** `AgentOrchestrator.kt:32-39` records `WorldStateManager.recordActionExecuted(...)` and returns `Fast-path executed tool ...` but contains no `ExecutionEngine`/tool invocation in that branch.
- **Impact:** A caller can receive an apparent successful result for work that was only classified.
- **Severity:** Critical correctness.
- **Recommendation:** Make fast path return a plan/dispatch request, or require a real `ToolResult` plus verifier evidence before recording completion.

### P0-3 — Multiple completion authorities permit state divergence

- **Problem:** Completion is represented independently by `ExecutionState`, `engine.state.TaskStatus`, scheduler `TaskStatus`, `ComputerUseState`, `ComputerUseModels.TaskStatus`, `WorldState`, `AgentActivityTracker`, and chat text.
- **Evidence:** `ExecutionState.kt`; `engine/state/TaskStatus.kt`; `scheduler/ScheduledTask.kt`; `computeruse/ComputerUseModels.kt`; `LongTaskOrchestrator.kt:223-237,562-585`; `AgentActivityTracker.kt:212-220`; `HomeViewModel.kt:325-335,348-355`.
- **Impact:** A failed child, detached approval, timeout, or stale event can leave one surface failed while another displays completed.
- **Recommendation:** Establish one persisted execution record and one authoritative transition reducer. UI and assistant text must derive from that record.

### P0-4 — Unlock/PIN automation has a success callback without verified unlocked state

- **Problem:** `MaxLockAutomationEngine.start()` invokes `onComplete` after coordinate fallback and delays, without checking `KeyguardManager.isKeyguardLocked`.
- **Evidence:** `MaxLockAutomationEngine.kt:104-109,132-138`; coordinate fallback is used after null/unknown UI state.
- **Impact:** A long workflow may continue to send messages or interact with apps while the device is still locked.
- **Recommendation:** Treat unlock as `VERIFIED` only after an independent keyguard/package/UI predicate; otherwise return `FAILED` or `NEEDS_USER`.

### P0-5 — Production release is not hardened enough for an assistant with privileged controls

- **Evidence:** `AndroidManifest.xml:69` enables cleartext traffic; `app/build.gradle.kts:78-81` leaves release minification disabled; manifest requests broad capabilities including accessibility, SMS, call log, exact alarms, storage, overlay, usage stats, and query-all-packages.
- **Impact:** Expanded attack surface, weaker transport/build hardening, and higher consequence if a prompt/tool authorization boundary fails.
- **Recommendation:** Release security review must explicitly justify every privilege, enforce TLS policy, verify R8/obfuscation compatibility, and test exported components and deep links.

## P1 — Major product correctness and reliability

### P1-1 — No durable persistence for active long-horizon plans

`GoalPlanner` and `LongTaskOrchestrator` keep queues, outputs, completed IDs, waiting state, and cancellation tokens in process memory. `WorldStateManager` is a `MutableStateFlow`. Only scheduled tasks and chat messages use Room. Process death/service death therefore loses active plans.

### P1-2 — Long plan and computer-use time budgets are incompatible with requested workflows

`LongTaskOrchestrator.DEFAULT_PLAN_TIMEOUT_MS` is 60 seconds and caps plans at 8/12 steps; `ComputerUseEngine.MAX_TASK_TIMEOUT_MS` is 60 seconds and defaults to 10 steps. The tester's request includes multiple 30-second waits plus app switching, unlock, messaging, and screen-off, exceeding these budgets before verification overhead.

### P1-3 — Approval result is ignored by all approval reply paths

Approval reply handlers launch `dispatchWithResult()` but do not inspect its returned JSON, do not emit a verified completion/failure message, and do not reconnect it to the original plan. Exceptions are logged or placed in UI error state only in some paths.

### P1-4 — LLM, deterministic, offline, and direct tool paths duplicate routing

The documented core path exists, but `AssistantEngine`, `HomeViewModel`, `ChatViewModel`, `OfflineCommandEngine`, `ToolDispatcher`, and `ActionDispatcher` all normalize or intercept requests. This creates drift and makes the same utterance depend on ingress surface.

### P1-5 — Multiple global/singleton mutable states prevent real multitasking

Examples include `GoalPlanner` global queue/state, `ApprovalCoordinator.pending`, `WorldStateManager`, `JarvisAccessibilityService.pendingTask`, `ChatViewModel._instance`, and global service registries. `JarvisAccessibilityService.setPendingTaskSafely()` explicitly rejects concurrent automation within a 15-second collision window.

## P2 — Important quality/performance/UX

- `ScreenCaptureProvider` synchronously waits up to 2 seconds for accessibility screenshot capture and downscales to a 720px max dimension; vision quality and latency are constrained.
- `ConversationManager` retains only 15 turns in memory; task state is not included as a durable structured context.
- UI has real-time assistant state and agent activity traces, but `HomeViewModel` leaves tool-progress and thought-summary events largely unimplemented and uses an 8-second thinking timeout that can hide work still executing.
- `AgentActivityTracker` is process-local with a 100-entry trace cap and no durable task correlation.

## P3 — Future enhancements

- Controlled workflow learning from verified traces already has a `SkillMemoryRepository` hook, but needs evaluation, provenance, rollback, and privacy policy before being treated as learning.
- Full Telegram NLP/AI control, wake word, cloud memory sync, live camera vision, and continuous location are not complete according to `MAX_PROJECT_STATE.md`.

## Release gate recommendation

Do not market arbitrary long-horizon autonomous completion until P0-1 through P0-5 have evidence-backed tests and a device run proving failure/approval/restart semantics.
