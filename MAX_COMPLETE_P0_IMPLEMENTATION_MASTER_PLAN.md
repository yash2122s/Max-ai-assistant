# MAX Complete P0 Implementation Master Plan

**Mode:** Audit + design + implementation planning only  
**Source inspection date:** 2026-10-03  
**Scope:** Execution truth, task-bound approval, completion authority, unlock verification, persistence/recovery, routing, concurrency, budgets, observability, and release hardening.

## 1. Executive decision

MAX should not be rewritten. The existing stable execution departments should remain in place:

`Gemini Live / ingress → ToolDispatcher or canonical request adapter → task/plan owner → ActionDispatcher / ExecutionEngine → specialized tool or computer-use subsystem → verifier → persisted task truth → UI/voice response`

The implementation should introduce one task-scoped execution contract around those departments. The contract must make task identity, step identity, execution identity, approval identity, and verification identity explicit, durable, and correlated. Existing specialized tools, WhatsApp safeguards, accessibility actions, Telecom actions, Shizuku policy, Gemini providers, and UI-agent providers should be adapted rather than replaced.

The release invariant is:

> MAX MUST NOT report success unless the originating task and step are identified, real execution occurred, an awaited result was received, required verification passed, the authoritative state transition succeeded, and the result was persisted against the same task/step/execution identity.

## 2. Source-first current-state findings

Documentation is inconsistent with the source and is not treated as authoritative.

### 2.1 Active architecture

- The active Android UI is native Jetpack Compose through `MainActivity`, `MaxApp`, and `MaxNavGraph`. `MAX_ARCHITECTURE.md` still describes Flutter as the sole UI authority; that description is stale.
- The active application package is `com.example`, application ID `com.maxaiagent.yash`.
- Gemini Live enters through `GeminiWebSocketClient`, with tool calls routed through `ToolDispatcher` and then into automation dispatch.
- Native direct and planned execution is distributed across `ActionDispatcher`, `ExecutionEngine`, `GoalPlanner`, `LongTaskOrchestrator`, `ComputerUseEngine`, and specialized tools.
- Current worktree source already contains partial P0 remediation: `ExecutionTruthCoordinator`, `AgentTaskStore`, `AgentTaskRecord`, `AgentTaskStepRecord`, and task-bound fields in `ApprovalCoordinator`. These are foundations, not a complete implementation.

### 2.2 Actual execution path

The source supports several paths rather than one pipeline:

1. **Home chat:** `HomeViewModel` / chat handling → assistant or dispatcher paths → `ActionDispatcher` / `ExecutionEngine`.
2. **Voice/Gemini Live:** `AssistantEngine` / `GeminiWebSocketClient` → `ToolDispatcher` → tool/action dispatch.
3. **Overlay:** `MaxCapsuleOverlayService` and chat/view-model state can directly trigger assistant and action behavior.
4. **Assistant entry:** `MainActivity`, `JarvisVoiceTriggerActivity`, and voice services accept assistant/voice intents.
5. **Offline:** `OfflineCommandEngine` provides a second deterministic command path.
6. **Scheduler:** `TaskReceiver`, scheduled task persistence, and task execution invoke automation outside the conversational path.
7. **Notifications:** notification services and auto-reply receivers can execute or request actions independently.
8. **Computer use:** `ComputerUseEngine`, planner, action executor, verifier, and recovery logic operate beside the older action/tool path.
9. **Direct tools:** `ToolDispatcher`, `ActionDispatcher`, and tool registry paths can bypass a durable parent task.
10. **Windows companion:** `CompanionConnectionService`, `WindowsAgentClient`, and command idempotency provide a remote execution boundary with separate lifecycle/state.

### 2.3 Confirmed current risks

- `ApprovalCoordinator.pending` is process-global and single-slot. It has task IDs when supplied, but the approval itself is not a durable approval record and does not prevent replacement by a later approval.
- `ApprovalCoordinator.executeApproved()` awaits `ActionDispatcher.dispatchWithResult()`, but it treats executor success as verified success and uses fallback execution IDs such as `approval_exec` when correlation is absent.
- `ExecutionTruthCoordinator` stores only `lastTurnTruth` in an `AtomicReference`; it is not a durable or multi-task completion authority.
- `AgentTaskStore` derives parent status from step rows, but its writes are not transactionally coupled to all execution and verification events. It also does not persist canonical action payloads, approval hashes, or complete plan data.
- `AgentOrchestrator` fast-path currently calls `ActionDispatcher.dispatchWithResult()` and parses the result. Older documentation claiming it performs no dispatch is stale. It still records only a global world-state event and is not consistently parent-task correlated.
- `ExecutionEngine` has a 25-second timeout, emits its own `ExecutionState`, publishes event-bus state, and records global truth. It does not universally receive parent task/step IDs.
- `LongTaskOrchestrator` owns in-memory task progression but only partially mirrors it to Room. It has `DEFAULT_PLAN_TIMEOUT_MS = 60_000L`, `MAX_TOTAL_PLAN_STEPS = 12`, and multiple local status models.
- `MaxLockAutomationEngine` now checks `KeyguardManager.isKeyguardLocked` in shown completion paths, but downstream safety still needs an explicit verified-unlock contract including expected package/UI and re-lock handling.
- `AppDatabase` uses `.allowMainThreadQueries()` and `.fallbackToDestructiveMigration(dropAllTables = true)`.
- Static/global state remains in approval, accessibility automation, chat view-model state, world state, service registries, and other workflow components.
- Release configuration has minification disabled; the manifest has a broad privileged surface and backup is enabled. Sensitive preference recovery must remain fail-closed.

## 3. Runtime dependency map

```text
Ingress adapter
  ├─ HomeViewModel / ChatViewModel
  ├─ AssistantEngine / GeminiWebSocketClient / ToolDispatcher
  ├─ MainActivity / voice trigger / voice services
  ├─ OfflineCommandEngine
  ├─ TaskReceiver / scheduler
  ├─ notification and auto-reply services
  ├─ ComputerUseEngine
  └─ WindowsAgentClient
        ↓
CanonicalRequestAdapter
        ↓
TaskCoordinator creates durable AgentTaskRecord + AgentTaskStepRecord
        ↓
TaskPlanner / GoalPlanner / existing computer-use planner
        ↓
ActionAuthorizer
        ↓
ApprovalCoordinator (only when policy requires approval)
        ↓
ExecutionCoordinator
  ├─ ActionDispatcher / ExecutionEngine / ToolRegistry
  ├─ ComputerUseEngine
  ├─ Accessibility / Telecom / Shizuku / Windows adapters
  └─ scheduler-specific execution adapter
        ↓
VerificationCoordinator
        ↓
TaskStateRepository transaction
        ↓
Task event stream, activity trace, assistant response, UI state
        ↓
RecoveryManager on process/service/device restart
```

Ingresses may retain their local presentation and transport responsibilities, but they must not independently decide authoritative completion.

## 4. Target identity model

| Identity | Meaning | Lifetime | Persistence |
|---|---|---|---|
| `requestId` | One user, scheduler, notification, or remote request | Request lifetime | Task metadata |
| `taskId` | One parent goal/workflow | Until terminal state | `agent_tasks` |
| `stepId` | Stable logical step within a task | Plan lifetime | `agent_task_steps` |
| `executionId` | One concrete attempt to execute one step | Attempt lifetime | Step execution record |
| `approvalId` | One approval decision for one immutable step payload | Approval lifetime | Approval/checkpoint record |
| `verificationId` | One verification attempt and evidence set | Verification lifetime | Verification record/evidence summary |
| `eventId` | Idempotent state/event write | Event lifetime | Event/outbox or dedupe record |

Relationships are strict: `requestId → taskId → stepId → executionId`, with `approvalId` and `verificationId` belonging to the same step attempt. No callback may update a record using only an action name or a global “current” value.

## 5. Target persistence model

Extend the existing Room model rather than creating a parallel database.

### Required parent fields

- request ID and source
- goal and canonical request hash
- lifecycle status
- current step ID/index
- plan version/hash
- created/updated/deadline timestamps
- cancellation and recovery state
- last error and terminal reason

### Required step fields

- stable step ID/index and action type
- canonical payload or protected payload reference
- payload hash
- status and allowed transition version
- execution ID and attempt count
- executor result summary
- verification ID/result summary
- approval ID/status where applicable
- timeout/deadline values
- idempotency key and external side-effect marker
- updated timestamp

### Required approval fields

- approval ID, task ID, step ID, execution ID
- canonical payload hash
- request identity and display-safe description
- created/expiry timestamps
- `PENDING`, `APPROVED`, `DENIED`, `EXPIRED`, `CONSUMED`, `REJECTED_STALE`
- consumed event ID and decision timestamp

Sensitive payloads must not be copied into logs or assistant text. If persistence of a payload is required for recovery, use protected storage or a redacted canonical representation appropriate to the existing security model.

## 6. Target lifecycle state machine

Use the existing names where compatible, but make one reducer authoritative:

```text
CREATED → PLANNING → READY
READY → WAITING_FOR_APPROVAL | DISPATCHING
WAITING_FOR_APPROVAL → APPROVED | DENIED | EXPIRED | CANCELLED
APPROVED → DISPATCHING
DISPATCHING → RUNNING | FAILED
RUNNING → VERIFYING | FAILED | CANCELLED | TIMED_OUT | INTERRUPTED
VERIFYING → SUCCEEDED | RECOVERING | FAILED
RECOVERING → DISPATCHING | BLOCKED | FAILED | CANCELLED
INTERRUPTED → RECOVERING | FAILED | CANCELLED
```

Parent completion is derived only from all required child steps. A child failure, unexecuted child, invalid verification, stale callback, or missing persisted result prevents `SUCCEEDED`.

Every transition must validate current status, task/step/execution identity, actor authority, version, and idempotency key before writing. Invalid transitions are rejected and logged as structured failures.

## 7. Approval design

### Current behavior

`ApprovalCoordinator` stores one volatile `PendingApproval`, emits `AssistantEvent.NeedsUserInput`, optionally writes waiting status through `AgentTaskStore`, removes the pending value on `resolve()`, then dispatches the payload. It supports task fields but not durable, replay-safe approval identity.

### Required behavior

1. Create a durable approval checkpoint in the same logical transition that moves the step to `WAITING_FOR_APPROVAL`.
2. Compute a canonical payload hash after authorization and before display.
3. Show only a display-safe question containing task/step-safe context.
4. Accept a reply only if the approval is pending, unexpired, not consumed, task identity matches the active conversation context when one is supplied, and payload hash is unchanged.
5. Atomically mark the approval consumed before executing, using a unique approval ID/event ID.
6. Execute the exact approved payload with the original execution ID or a deterministically linked attempt ID.
7. Await result, verify it, persist both step result and parent derivation, then emit the user response.
8. On process death, reload pending approvals and never execute an approval whose expiry, hash, or task state is invalid.
9. Support multiple pending approvals by task ID, while applying an explicit device/app concurrency policy.

Validation order: parse intent → locate approval ID/context → load approval → check status/expiry → check task and step status → check payload hash → atomically consume → execute → verify → persist → publish result.

## 8. Execution-result truth

Introduce a single result envelope around existing `ToolResult`, `ExecutionResult`, and computer-use results:

```text
ExecutionRequested(requestId, taskId, stepId, executionId)
ExecutionStarted(...)
ExecutionReturned(executorResult)
VerificationStarted(verificationId)
VerificationReturned(evidence, verified)
StepCompleted(finalResult)
TaskCompleted(derivedResult)
```

Classification, plan creation, dispatch request creation, optimistic UI, callback invocation, timeout expiry, and child completion without parent correlation are never completion evidence.

The existing `ExecutionTruthCoordinator` should become a repository-backed validator/event facade, not the sole in-memory store. Its response-correction logic can remain as a defensive presentation layer, but it must never substitute for task persistence.

The fast path must use the same task/step lifecycle as planned execution. It may skip expensive planning, but it may not skip task creation, authorization, execution result handling, verification policy, or persisted completion.

## 9. Unlock verification design

`MaxLockAutomationEngine` should expose separate outcomes:

- `UNLOCK_ATTEMPTED`
- `UNLOCK_ACTION_COMPLETED`
- `DEVICE_UNLOCKED`
- `EXPECTED_CONTEXT_READY`
- `UNLOCK_FAILED`
- `UNLOCK_TIMEOUT`
- `UNLOCK_CONTEXT_CHANGED`

Completion is safe for downstream automation only when all required predicates hold:

1. `KeyguardManager.isKeyguardLocked == false`.
2. Screen is interactive and accessibility has a usable root when required.
3. Expected package/window predicate is present, or the caller explicitly permits any unlocked home context.
4. No re-lock or package transition occurred between verification and downstream dispatch.

Coordinate fallback remains permitted only as an attempt mechanism. It must not invoke the success callback directly. Verification must be repeated immediately before a sensitive downstream action, and failure must transition the parent step to `BLOCKED` or `FAILED`.

PIN values must not enter model prompts, task goals, ordinary logs, activity traces, persisted payloads, or untrusted exported intent pathways.

## 10. Persistence and recovery

Recovery sequence:

```text
process/service restart
  → load non-terminal tasks
  → mark in-flight attempts INTERRUPTED
  → inspect execution/verification evidence
  → determine whether external side effect is known
  → resume only idempotent/known-safe step
  → otherwise BLOCKED and request user confirmation
```

Do not blindly retry side effects such as calls, messages, SMS, or remote commands. Require idempotency keys, provider-specific confirmation, or a user decision.

Room changes must include explicit migrations and migration tests. Remove destructive fallback only after all current database versions and upgrade paths are covered. Remove main-thread queries and audit every DAO caller for dispatcher correctness.

## 11. Routing and concurrency

All ingress adapters converge on:

`Request → CanonicalCommand → Task → Plan → Authorization → Approval → Execution → Verification → Completion`.

Existing specialized subsystems remain execution implementations. `OfflineCommandEngine` becomes an ingress classifier/adapter rather than a second completion authority. `ToolDispatcher` remains Gemini transport translation; `ActionDispatcher` remains compatibility normalization and dispatch adapter until callers migrate.

Initial concurrency policy:

- One active physical-device UI automation task at a time.
- Independent non-UI tasks may run concurrently only when explicitly classified safe.
- Per-app UI operations are serialized.
- Multiple approvals are allowed, but each is task-scoped and independently consumed.
- Cancellation is task-scoped and must propagate to child execution.
- Stale callbacks are rejected by task/step/execution/version matching.
- Duplicate requests use request hash/idempotency keys and must not create duplicate side effects.

## 12. Timeout and retry budgets

Replace one undifferentiated plan timeout with:

- action execution budget
- verification budget
- per-step deadline
- total task deadline
- recovery/replan budget
- approval expiry

Budgets must be configurable per action class. Messaging, call, unlock, app launch, computer-use, and Windows operations require distinct defaults. Any timeout must produce a persisted `TIMED_OUT`/`RECOVERING` result, never success.

## 13. Security hardening plan

| Current | Risk | Target | Code area | Required test |
|---|---|---|---|---|
| Broad exported activities/services/receivers | Untrusted intents may reach privileged behavior | Explicit intent validation, least export surface, caller/state checks | `AndroidManifest.xml`, entry activities/services/receivers | Invalid and unauthorized intent tests |
| Backup enabled for sensitive app data | Secrets or memories may be exported | Explicitly exclude/review sensitive stores | `backup_rules.xml`, `data_extraction_rules.xml`, manifest | Backup inspection |
| Release minification disabled | Larger attack surface and weaker release hardening | Enable and validate R8 rules | `app/build.gradle.kts`, ProGuard rules | Release build/install/smoke test |
| Encrypted preference recovery deletes/recreates storage | Potential credential loss and unsafe recovery policy | Fail closed, preserve forensic signal, require explicit reset | `SettingsManager.kt` | Corruption/recovery tests |
| PIN accepted by automation paths | Credential leakage or unauthorized unlock | Strict source validation, no logging/persistence/model forwarding | `MaxLockAutomationEngine.kt`, unlock callers | Secret isolation tests |
| Shizuku/root execution | High-impact command execution | Maintain allowlist; add parser, length, encoding, authorization, and release checks | `ShellCommandPolicy.kt`, Shizuku classes | Fuzz/adversarial command tests |
| Privileged action boundaries vary by ingress | Remote or stale requests may bypass authorization | Canonical authorization before execution for every ingress | dispatchers, receivers, companion client | Cross-ingress authorization matrix |

Do not broaden scope into generic security work unrelated to these paths.

## 14. Complete phased implementation plan

### Phase A — Baseline and contract freeze

**Objective:** Establish source-derived inventories and prevent undocumented behavior changes.

**Inspect:** all files listed in the file table below, current tests, manifest, Gradle config, Room schema, and existing audit documents.

**Modify/create:** add no runtime behavior; create test fixtures and a versioned P0 contract document only if desired by the implementation team.

**Preserve:** specialized automation and all verified routing guards.

**Work:** capture current event/status names, caller graph, migration versions, runtime entry points, and known unknowns. Mark stale documentation explicitly.

**Tests/acceptance:** baseline unit tests and a clean source/build inventory are reproducible; no P0 behavior is silently reclassified.

**Rollback:** no runtime rollback required.

### Phase B — Canonical identity and persistence schema

**Objective:** Make task, step, execution, approval, verification, request, and event identities durable.

**Modify:** `AgentTaskRecord.kt`, `AgentTaskDao.kt`, `AppDatabase.kt`, `AgentTaskStore.kt`, related migrations.

**Create:** only repository-confirmed persistence classes needed for approval/checkpoint, execution attempt, verification evidence, and event dedupe; names must be finalized after source inventory.

**Work:** add schema fields/tables, transaction boundaries, optimistic versioning, hashes, idempotency keys, and explicit terminal/non-terminal queries. Add migration from current Room version 19.

**Preserve:** existing agent task rows and backwards-compatible reads.

**Tests:** Room migration, transaction, duplicate event, partial write, corruption, and process-restart fixtures.

**Acceptance:** every active task can be loaded with stable IDs and no destructive migration path is required.

### Phase C — Single transition and completion authority

**Objective:** Centralize legal state transitions and parent derivation.

**Modify:** `ExecutionTruth.kt`, `AgentTaskStore.kt`, `ExecutionEngine.kt`, `LongTaskOrchestrator.kt`, event/status adapters.

**Work:** implement a reducer/repository boundary; require identity/version validation; make `ExecutionTruthCoordinator` delegate to persisted records; derive UI and assistant completion from stored task state.

**Preserve:** existing event bus and activity presentation as derived observers.

**Tests:** transition matrix, invalid transition, child failure, duplicate completion, stale callback, and false-success tests.

**Acceptance:** no component other than the transition authority can write terminal task status.

### Phase D — Task-scoped approval and result propagation

**Objective:** Eliminate single-slot and detached approval behavior.

**Modify:** `ApprovalCoordinator.kt`, all approval consumers in `AssistantEngine.kt`, `HomeViewModel.kt`, `ChatViewModel.kt`, and task orchestration callers.

**Work:** durable approval records, canonical hash, expiry, atomic consume, replay prevention, multiple pending approvals, exact payload execution, awaited result, verifier result, parent resume/failure.

**Preserve:** existing conversational Allow/Cancel wording and localization behavior.

**Tests:** correct/wrong/expired/duplicate/replay/process-death/concurrent approvals.

**Acceptance:** an approval cannot execute a different task or payload, and approval success is impossible without persisted execution truth.

### Phase E — Unify execution-result contract and remove false success

**Objective:** Ensure every direct, planned, computer-use, scheduled, notification, and remote action uses the same execution contract.

**Modify:** `AgentOrchestrator.kt`, `ActionDispatcher.kt`, `ToolDispatcher.kt`, `ExecutionEngine.kt`, `LongTaskOrchestrator.kt`, `ComputerUseEngine.kt`, direct ingress adapters.

**Work:** create task/step context at fast-path entry; wrap legacy results; distinguish requested/started/returned/verified/completed; remove optimistic completion writes and UI-only success claims.

**Preserve:** action normalization and provider-specific execution.

**Tests:** real execution, no-tool dispatch, dispatch failure, executor failure, verifier failure, timeout, cancellation, and parent correlation.

**Acceptance:** every success response can be traced to execution, verification, persisted transition, and matching IDs.

### Phase F — Unlock and security-critical execution gates

**Objective:** Make unlock and privileged execution fail closed.

**Modify:** `MaxLockAutomationEngine.kt`, `UnlockManager.kt`, unlock callers, sensitive logging, intent validation, `ShellCommandPolicy.kt` and Shizuku boundary where required.

**Work:** separate attempt/action/unlocked/context-ready states; verify keyguard and expected context; recheck before sensitive actions; isolate PIN; reject invalid external intents.

**Tests:** unlocked success, locked failure, wrong UI, timeout, re-lock, screen-off, package change, invalid PIN source, shell fuzzing.

**Acceptance:** downstream sensitive action cannot start from an unverified unlock callback.

### Phase G — Durable orchestration and recovery

**Objective:** Recover safely from process, service, accessibility, reboot, and network interruption.

**Modify:** `LongTaskOrchestrator.kt`, `TaskRecoveryEngine.kt`, `AgentTaskStore.kt`, boot/service lifecycle classes, scheduler integration.

**Work:** persist plan and checkpoint before side effects, mark in-flight attempts interrupted, inspect evidence, resume only safe/idempotent steps, block ambiguous side effects, reconnect observers.

**Tests:** process death at every transition, service restart, reboot, accessibility restart, network loss, duplicate recovery.

**Acceptance:** no task silently disappears and no side effect is blindly duplicated.

### Phase H — Routing consolidation and concurrency control

**Objective:** Remove duplicated command ownership and prevent global-state collisions.

**Modify:** `OfflineCommandEngine.kt`, `NaturalCommandNormalizer.kt`, `IntentClassifier.kt`, `ActionDispatcher.kt`, `ToolDispatcher.kt`, `GoalPlanner.kt`, `WorldStateManager`, accessibility pending-task integration, singleton consumers.

**Work:** converge ingress adapters into canonical requests; introduce task-scoped contexts; serialize physical UI automation; define cancellation/preemption and stale callback rejection.

**Preserve:** language support, WhatsApp collision guards, and stable provider fallbacks.

**Tests:** simultaneous tasks, simultaneous callbacks, conflicting apps, duplicate requests, cancellation, per-device serialization.

**Acceptance:** no process-global “current task” is used for task completion or callback correlation.

### Phase I — Budgets and observability

**Objective:** Match real workflows while retaining bounded execution and diagnosability.

**Modify:** `LongTaskOrchestrator.kt`, `ExecutionEngine.kt`, computer-use budget classes, telemetry/activity adapters.

**Work:** separate action/verification/task/recovery/approval budgets; persist structured transition and evidence summaries; redact secrets and PINs.

**Tests:** budget boundaries, long waits, retry exhaustion, recovery timeout, log redaction.

**Acceptance:** timeout is explicit and recoverable; every terminal result has a correlated trace.

### Phase J — Release hardening and final validation

**Modify:** `AndroidManifest.xml`, `app/build.gradle.kts`, backup rules, `SettingsManager.kt`, release configuration and tests.

**Work:** least-privilege/export review, backup review, fail-closed encrypted preference behavior, R8 validation, release signing/build, privileged intent tests.

**Acceptance:** all P0 gates below pass on unit, instrumented, and device validation; no unresolved critical security or execution-truth finding remains.

## 15. File-by-file change plan

| File | Current responsibility | Required change | Phase | Risk | Tests | Migration? |
|---|---|---|---|---|---|---|
| `app/src/main/java/com/example/automation/approval/ApprovalCoordinator.kt` | Conversational approval and immediate dispatch | Durable, task-scoped, hash-bound, replay-safe approval | D | High | Approval matrix | Yes |
| `app/src/main/java/com/example/automation/engine/truth/ExecutionTruth.kt` | In-memory truth and response correction | Persisted facade and strict correlation | C/E | High | False-success and transition tests | No |
| `app/src/main/java/com/example/automation/engine/state/AgentTaskStore.kt` | Room task/step writes and derivation | Transactional state boundary and recovery checkpoint | B/C/G | High | Room/recovery tests | Yes |
| `app/src/main/java/com/example/data/local/AgentTaskRecord.kt` | Parent/step entities | Add identity, plan, deadline, attempt, approval, and evidence fields as required | B | High | Migration tests | Yes |
| `app/src/main/java/com/example/data/local/AgentTaskDao.kt` | Task queries and updates | Atomic transition/approval/idempotency queries | B/C/D | High | DAO concurrency tests | Yes |
| `app/src/main/java/com/example/data/local/AppDatabase.kt` | Room database v19 | Explicit migration, no destructive fallback, no main-thread access | B/J | High | Migration tests | Yes |
| `app/src/main/java/com/example/automation/engine/ExecutionEngine.kt` | Tool execution and verification | Accept task context and publish/persist canonical result | C/E/I | High | Executor/verifier tests | No |
| `app/src/main/java/com/example/automation/engine/AgentOrchestrator.kt` | Fast and vision orchestration | Route fast path through task lifecycle | E | Medium | Fast-path truth tests | No |
| `app/src/main/java/com/example/automation/engine/execution/LongTaskOrchestrator.kt` | Multi-step execution | Use persisted checkpoints, transition authority, recovery and budgets | C/G/I | High | Long-task tests | Possibly |
| `app/src/main/java/com/example/automation/engine/ActionDispatcher.kt` | Compatibility normalization and dispatch | Require task context; remove independent completion authority | E/H | High | Routing matrix | No |
| `app/src/main/java/com/example/network/ToolDispatcher.kt` | Gemini tool-call translation | Create canonical request/task instead of direct terminal claims | E/H | High | Gemini dispatch tests | No |
| `app/src/main/java/com/example/automation/computeruse/ComputerUseEngine.kt` | UI computer-use workflow | Adapt to task/step/result contract and concurrency lease | E/H/I | High | Computer-use tests | No |
| `app/src/main/java/com/example/security/MaxLockAutomationEngine.kt` | PIN/gesture unlock attempts | Return verified state only after independent predicates | F | Critical | Unlock device matrix | No |
| `app/src/main/java/com/example/security/UnlockManager.kt` | Wake/unlock support | Enforce verification and sensitive-input boundaries | F | Critical | Unlock/security tests | No |
| `app/src/main/java/com/example/service/JarvisAccessibilityService.kt` | Accessibility UI automation and static pending task | Task-scoped callback correlation and serialized lease | F/H | High | Accessibility/concurrency tests | No |
| `app/src/main/java/com/example/viewmodel/ChatViewModel.kt` | Chat and singleton assistant state | Remove completion ownership and stale singleton dependency | D/H | Medium | View-model lifecycle tests | No |
| `app/src/main/java/com/example/ui/home/HomeViewModel.kt` | Home UI actions and approval responses | Observe task truth and await approval result | D/E | Medium | UI state tests | No |
| `app/src/main/java/com/example/voice/assistant/AssistantEngine.kt` | Voice session and response handling | Use canonical task/approval/result responses | D/E | High | Voice integration tests | No |
| `app/src/main/java/com/example/automation/engine/OfflineCommandEngine.kt` | Offline command execution | Become canonical ingress adapter, not completion owner | H | Medium | Offline parity tests | No |
| `app/src/main/AndroidManifest.xml` | Permissions and component exposure | Review exports, intent validation, least privilege | J | High | Intent security tests | No |
| `app/build.gradle.kts` | Build/release settings | Enable validated release hardening/minification | J | Medium | Release build tests | No |
| `app/src/main/java/com/example/data/preferences/SettingsManager.kt` | Settings and secrets | Fail-closed encrypted recovery and secret isolation | J | High | Corruption/security tests | No |
| `app/src/main/res/xml/backup_rules.xml` | Backup inclusion rules | Explicitly protect sensitive data | J | Medium | Backup inspection | No |
| `app/src/main/res/xml/data_extraction_rules.xml` | Data extraction rules | Explicitly protect sensitive data | J | Medium | Backup inspection | No |
| `app/src/main/java/com/example/automation/computeruse/ShellCommandPolicy.kt` | Shell command allowlist | Add release authorization and adversarial validation | F/J | High | Fuzz tests | No |

Files not listed must remain unchanged unless source tracing in implementation exposes a direct dependency required by a gate.

## 16. Exact implementation order

`A Baseline → B Persistence → C Transition authority → D Approval → E Execution truth → F Unlock/security gates → G Recovery → H Routing/concurrency → I Budgets/observability → J Release hardening`

Parallelizable after Phase B: migration tests, approval UI fixture tests, security inventory, and provider-specific adapter tests. Must remain sequential: persistence before reducer, reducer before approval/result propagation, result contract before routing consolidation, and all correctness work before final release hardening.

## 17. Testing and validation matrix

### Approval

- Correct task/step/payload approval succeeds once.
- Wrong task, wrong step, changed payload, expired, denied, duplicate, and replay approvals fail closed.
- Process death and service restart preserve pending approval without auto-execution.
- Concurrent approvals cannot replace or consume one another.

### Execution

- Real tool execution produces started, returned, verified, and persisted events.
- Classification-only, unsupported, dispatch failure, executor failure, verifier failure, timeout, cancellation, and stale callback never produce success.
- Fast, planned, computer-use, scheduler, notification, and Windows paths have parity.

### Completion

- Parent succeeds only when every required child step is verified.
- Child failure, missing child, duplicate completion, parent failure, and process death remain non-success.
- UI and voice derive from persisted state.

### Unlock

- Already unlocked device, successful PIN, locked failure, wrong UI, timeout, re-lock, screen-off, accessibility restart, and package change are covered.
- Callback without keyguard/context proof is rejected.

### Persistence/recovery

- Migration from every supported Room version through current schema.
- Process death at every transition.
- Foreground service restart, accessibility restart, reboot, network loss, and recovery duplication.
- Ambiguous external side effects are blocked rather than retried blindly.

### Concurrency

- Simultaneous task creation, callbacks, approvals, UI automation requests, cancellations, and duplicate requests.

### Security

- Invalid exported intents, unauthorized privileged actions, secret/PIN logging, backup extraction, encrypted preference corruption, shell parser abuse, and release minification smoke tests.

## 18. Acceptance gates

### Gate P0-1 — Approval

Pass only if approval has durable task/step/action/payload identity, expiry, atomic consumption, replay protection, process-death behavior, awaited execution, verification, and parent propagation.

### Gate P0-2 — Execution truth

Pass only if all success-producing paths are backed by real execution and no fast/optimistic/classification branch can emit completion.

### Gate P0-3 — Completion authority

Pass only if one persisted reducer owns terminal state and all UI, voice, activity, world-state, scheduler, and computer-use statuses are derived or explicitly adapted.

### Gate P0-4 — Unlock

Pass only if keyguard state, expected context, and downstream readiness are independently verified; callback timing alone is insufficient.

### Gate P0-5 — Release security

Pass only after exported intent review, backup review, release hardening, secret/PIN isolation, Shizuku policy tests, encrypted preference failure tests, and device validation.

### Universal success gate

For every reported success, evidence must prove:

1. Correct originating task.
2. Correct originating step.
3. Real execution.
4. Awaited execution result.
5. Required verification.
6. Authoritative transition.
7. Persistence against the same identity.

Compilation alone is not a gate.

## 19. Rollback strategy

- Keep schema migrations additive and reversible where possible.
- Gate canonical execution behind a runtime feature flag during migration, but never allow the legacy path to report success without truth validation.
- Preserve legacy action/tool implementations behind adapters until parity tests pass.
- If recovery logic is unstable, block ambiguous tasks and require user restart rather than retrying side effects.
- Roll back release hardening independently from task-state migrations only when security review confirms the rollback does not re-enable unsafe success behavior.
- Retain migration and event compatibility for one release cycle before deleting legacy status writers.

## 20. Risk register

| Risk | Effect | Mitigation |
|---|---|---|
| Existing dirty worktree differs from committed baseline | Plan may target already-changed code | Re-run source inventory before each implementation phase and preserve unrelated changes |
| Legacy callers cannot provide task identity | Uncorrelated execution remains | Create adapter task/step identity and mark limited legacy behavior explicitly |
| Room migration failure | Data loss or startup failure | Full migration tests, no destructive fallback, staged rollout |
| Side-effect duplication after recovery | Duplicate messages/calls/commands | Idempotency keys and block ambiguous attempts |
| Accessibility service lifecycle churn | Stale callbacks and lost UI context | Leases, callback correlation, and recovery states |
| Provider latency exceeds old budgets | Valid tasks time out | Separate budgets and bounded recovery |
| R8 breaks reflection/service registration | Release runtime failure | Mapping review and release smoke tests |
| Secret enters trace or payload | Credential exposure | Redaction, secure payload references, and automated log scans |

## 21. Final implementation checklist

- [ ] Source inventory and current runtime traces regenerated.
- [ ] Documentation/source mismatches recorded.
- [ ] Stable automation departments identified and protected.
- [ ] Durable identity model implemented.
- [ ] Room migration and transaction tests pass.
- [ ] One transition/completion authority implemented.
- [ ] Approval is task-scoped, durable, hash-bound, expiring, and replay-safe.
- [ ] Fast path uses real execution and verification.
- [ ] All success claims are correlated and persisted.
- [ ] Unlock has independent keyguard/context verification.
- [ ] Recovery handles process/service/reboot/accessibility interruption.
- [ ] Global task state is removed or restricted to explicit infrastructure roles.
- [ ] Physical UI automation concurrency is serialized.
- [ ] Action/verification/task/recovery budgets are explicit.
- [ ] Exported intents, backups, secrets, PINs, shell execution, and release build are hardened.
- [ ] Full regression matrix passes.
- [ ] Device validation proves failure, approval, restart, and recovery semantics.
- [ ] No P0 acceptance gate relies on documentation or compilation alone.
