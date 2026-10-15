MAX — COMPLETE END-TO-END IMPLEMENTATION ARCHITECTURE PLAN

MODE:
AUDIT + DESIGN + IMPLEMENTATION PLANNING ONLY.

DO NOT MODIFY ANY SOURCE CODE.
DO NOT IMPLEMENT ANY FIXES.
DO NOT CREATE PATCHES.

Your job is to deeply inspect the existing MAX repository and produce a complete,
implementation-ready, phased engineering plan for resolving the identified P0
execution-truth, orchestration, approval, verification, persistence, recovery,
concurrency, and release-hardening problems.

IMPORTANT:
This is NOT a Phase-1 / Phase-2 planning exercise.

I need the COMPLETE implementation roadmap covering ALL required phases from
current-state analysis through final validation and release gates.

==================================================
PRIMARY SOURCE OF TRUTH
==================================================

Use the actual repository source code as the primary truth.

Also inspect and use these existing documents:

- MAX_CRITICAL_FINDINGS.md
- MAX_ARCHITECTURE.md
- MAX_PROJECT_MAP.md
- MAX_PROJECT_STATE.md
- MAX_SECURITY_AUDIT.md
- MAX_TASK_EXECUTION_FORENSIC.md
- MAX_LONG_TASK_DEEP_AUDIT_AND_IMPLEMENTATION_PLAN.md
- MAX_AUTOMATION_PIPELINE.md
- MAX_PROVIDER_ARCHITECTURE.md
- MAX_MULTITASKING_AUDIT.md
- MAX_UI_AUTOMATION_PHASE*.md
- MAX_FINAL_RELEASE_READINESS_REPORT.md
- GATES.md
- AGENT.md
- README.md

Do not blindly trust documentation.

Whenever documentation and implementation differ:
- inspect the source code,
- identify the mismatch,
- state which one is actually active,
- explain the evidence.

The real repository implementation is authoritative.

==================================================
CURRENT P0 PROBLEMS TO INVESTIGATE
==================================================

At minimum, fully investigate:

P0-1:
Approval is not reliably bound to the originating task.

P0-2:
Fast-path orchestration can report execution/success without actually
executing the relevant action.

P0-3:
MAX has multiple competing completion/status authorities.

P0-4:
Unlock automation can report success without independently proving that
the device is actually unlocked.

P0-5:
Production security hardening is incomplete.

Also investigate implementation dependencies required by these P0 fixes:

- non-durable long-running orchestration state
- incomplete approval result propagation
- duplicated command/routing paths
- global mutable task state
- concurrency collisions
- insufficient execution time/step budgets
- persistence/recovery gaps
- observability/tracing gaps

Do NOT automatically turn every P1/P2 finding into a scope item.

Only include a P1/P2 item when it is:
A) directly required for a P0 fix,
B) required to prevent the P0 fix from being bypassed,
or
C) required by an acceptance criterion for trustworthy execution.

For each such dependency, explicitly explain why.

==================================================
STEP 1 — FULL CURRENT-STATE RECONSTRUCTION
==================================================

Before designing anything, reconstruct the CURRENT implementation.

Trace actual runtime flows from:

User request
→ ingress
→ normalization/classification
→ planning
→ task creation
→ approval
→ tool/action dispatch
→ execution
→ result propagation
→ verification
→ task state update
→ persistence
→ UI/voice reporting
→ recovery

Trace every relevant ingress surface separately:

- Home chat
- Voice
- Gemini Live
- Overlay
- Assistant entry
- Offline command engine
- Scheduler
- Notifications
- Computer-use
- Direct tool execution
- Windows companion where relevant

For every path identify:
- entry class
- controller/viewmodel
- router
- planner
- task owner
- approval owner
- dispatcher
- execution engine
- verification engine
- persistence layer
- completion authority
- user-facing response generator

Create an actual runtime dependency/data-flow map.

==================================================
STEP 2 — FILE-BY-FILE FORENSIC ANALYSIS
==================================================

For every affected class/file, document:

1. File path
2. Class/object/function
3. Responsibility
4. Current inputs
5. Current outputs
6. Mutable state
7. Persistence behavior
8. Lifecycle owner
9. Concurrency model
10. Callers
11. Callees
12. Failure handling
13. Timeout behavior
14. Retry behavior
15. Existing tests
16. Existing logic that MUST be preserved
17. Existing logic that is unsafe/incorrect
18. Existing logic that can be reused
19. Existing logic that should be replaced
20. Existing logic that should be deleted

Do NOT propose new abstractions before understanding what already exists.

==================================================
STEP 3 — ROOT-CAUSE ANALYSIS FOR EVERY PROBLEM
==================================================

For each P0 and every required dependency:

Explain:

- symptom
- actual root cause
- why current logic appears to work in simple cases
- exact failure scenario
- race/lifecycle condition involved
- why existing tests did not catch it
- downstream consequences
- security/correctness implications
- architectural dependency

Provide one concrete execution example for each issue.

==================================================
STEP 4 — DESIGN THE TARGET ARCHITECTURE
==================================================

Design the target architecture ONLY after understanding the existing one.

The goal is NOT a full rewrite.

Prefer:
- minimal invasive changes
- reuse of stable existing components
- clear ownership
- backward compatibility
- task-scoped state
- persisted execution truth
- deterministic transitions
- explicit concurrency
- verified completion

Define:

A) authoritative task identity
B) authoritative step identity
C) execution identity
D) approval identity
E) verification identity
F) persistence model
G) completion authority
H) recovery authority
I) routing authority
J) concurrency authority

Explain exactly how these identities relate.

==================================================
STEP 5 — TARGET STATE MACHINE
==================================================

Design the complete task lifecycle/state machine.

Include states such as appropriate to the existing system, for example:

CREATED
PLANNING
WAITING_APPROVAL
APPROVED
DISPATCHING
RUNNING
VERIFYING
SUCCEEDED
FAILED
CANCELLED
TIMED_OUT
RECOVERING
BLOCKED

Do NOT blindly use those names if the repository already has equivalent
states.

Map the existing states to the target states.

For every transition define:

- source state
- event
- validation
- state update
- persistence update
- side effects
- failure transition
- retry behavior
- idempotency behavior
- who is allowed to perform the transition

==================================================
STEP 6 — TASK-SCOPED APPROVAL DESIGN
==================================================

Deeply inspect the existing ApprovalCoordinator and all approval handlers.

Explain exactly how current approval state works.

Then design the replacement.

Approval must be associated with enough immutable context to prevent
cross-task or stale approval, including as required by repository evidence:

- parent task ID
- step ID
- action ID
- action payload or canonical payload hash
- creation time
- expiry
- originating request identity
- resume location/state
- approval status
- consumption state

Explain:

- approval creation
- approval display
- approval acceptance
- approval rejection
- expiry
- stale approval detection
- duplicate approval
- replay protection
- wrong-task approval
- process death
- service restart
- concurrent approvals

Define exact validation order.

==================================================
STEP 7 — EXECUTION-RESULT TRUTH
==================================================

Deeply inspect AgentOrchestrator, ActionDispatcher,
ToolDispatcher, direct execution paths, and all fast paths.

Find every path that can produce:
- success
- completed
- executed
- sent
- done
- verified
- task finished

Determine whether each one is:
A) real execution evidence,
B) classification/planning evidence,
C) optimistic state,
D) UI-only state.

Then design the new execution-result contract.

Define:

execution requested
→ execution started
→ execution result
→ verification
→ authoritative completion

Completion MUST NOT be inferred from:
- intent generation
- classification
- dispatch request creation
- optimistic UI
- callback existence
- timeout expiration
- child completion without parent correlation

Document how false-success paths will be removed.

==================================================
STEP 8 — SINGLE COMPLETION AUTHORITY
==================================================

Inspect all current completion/status systems:

- ExecutionState
- TaskStatus
- scheduler status
- computer-use status
- WorldStateManager
- AgentActivityTracker
- UI state
- assistant state
- long-task orchestration state
- any other status stores discovered during analysis

For each one explain:
- why it exists
- whether it should remain
- whether it becomes derived state
- whether it writes authoritative state
- synchronization risks

Design one canonical persisted execution record.

Then explain how all other systems read/derive from it.

==================================================
STEP 9 — UNLOCK / SECURITY-CRITICAL VERIFICATION
==================================================

Inspect MaxLockAutomationEngine and related unlock logic.

Trace:
request
→ unlock attempt
→ UI/coordinate fallback
→ completion callback
→ downstream workflow

Identify all ways current logic can incorrectly conclude "unlocked".

Design independent verification.

Use repository-supported mechanisms and Android APIs as appropriate.

Verification must distinguish:
- unlock attempt
- unlock action completed
- device actually unlocked
- expected package/UI present
- safe to continue downstream workflow

Define failure states.

Define what happens if:
- screen remains locked
- wrong screen appears
- unlock times out
- device re-locks
- callback fires without correct state
- accessibility state changes
- screen turns off
- package changes

==================================================
STEP 10 — PERSISTENCE + RECOVERY
==================================================

Inspect:
- Room entities
- DAOs
- database configuration
- migration strategy
- scheduler persistence
- agent task persistence
- long-task persistence
- service lifecycle
- process death recovery
- reboot recovery
- accessibility restart recovery

Define exactly what must be persisted for trustworthy recovery.

Design:

process death
→ restart
→ load unfinished tasks
→ reconstruct state
→ verify whether previous execution actually happened
→ resume/recover/fail safely

Avoid duplicate execution.

Also analyze:
- destructive migration
- main-thread DB access
- data integrity
- migration compatibility

==================================================
STEP 11 — ROUTING CONSOLIDATION
==================================================

Trace all command routing paths.

Identify duplicated:
- normalization
- classification
- planning
- routing
- direct execution

Design the canonical pipeline.

Ingress adapters should converge into:

Request
→ canonical command representation
→ task
→ plan
→ approval if required
→ execution
→ verification
→ completion

Do not rewrite specialized execution subsystems merely for cosmetic consistency.

==================================================
STEP 12 — MULTITASKING + CONCURRENCY
==================================================

Inspect all process-global and singleton workflow state.

Especially investigate:

- GoalPlanner
- ApprovalCoordinator.pending
- WorldStateManager
- JarvisAccessibilityService.pendingTask
- ChatViewModel._instance
- service registries
- static mutable state
- task queues

Determine what is truly global and what must be task-scoped.

Design explicit concurrency rules:

- max concurrent tasks
- per-device automation serialization
- per-app serialization if necessary
- approval concurrency
- cancellation
- preemption
- duplicate requests
- race conditions
- stale callbacks
- callback correlation
- idempotency

Do not assume unlimited concurrency is safe.

==================================================
STEP 13 — TIMEOUT / RETRY / STEP BUDGET DESIGN
==================================================

Inspect all existing:
- task timeout
- step timeout
- retry count
- action timeout
- verification timeout
- wait behavior

Map them to real MAX workflows.

Define separate budgets for:
- individual action
- verification
- task
- recovery

Prevent valid workflows from being incorrectly terminated while also preventing
infinite execution.

==================================================
STEP 14 — SECURITY HARDENING
==================================================

Inspect:
- AndroidManifest
- exported components
- permissions
- backup configuration
- cleartext configuration
- encrypted preferences
- PIN handling
- logs
- intent entry points
- Shizuku/root execution
- release build configuration
- minification
- secrets/configuration

For every relevant finding provide:

CURRENT
→ RISK
→ TARGET
→ EXACT CODE AREA
→ MIGRATION IMPACT
→ TEST REQUIRED

Do not expand into unrelated generic security recommendations.

==================================================
STEP 15 — COMPLETE PHASED IMPLEMENTATION PLAN
==================================================

Create ALL phases required to reach production-ready P0 completion.

Do not limit the plan to two phases.

A phase should represent a coherent implementation milestone.

For EACH phase provide:

PHASE NAME

Objective

Why this phase exists

Prerequisites

Repository files to inspect

Files to modify

Files to create

Files to delete, if any

Existing logic to preserve

Existing logic to replace

New logic to introduce

Detailed implementation logic

Detailed data flow

State transitions

Error handling

Concurrency considerations

Persistence considerations

Migration considerations

Observability/logging considerations

Security considerations

Tests to add

Existing tests to update

Manual validation

Acceptance criteria

Rollback strategy

Dependencies on previous phases

Dependence of later phases on this phase

Explicit "DO NOT CHANGE" boundaries

==================================================
STEP 16 — FILE-BY-FILE CHANGE PLAN
==================================================

After defining phases, create a master table:

File
Current responsibility
Problem
Required change
Phase
Risk
Tests
Migration required?
Can remain unchanged?

Do not invent files.

==================================================
STEP 17 — IMPLEMENTATION ORDER
==================================================

Produce an exact sequence such as:

Phase A
→ Phase B
→ Phase C
→ ...

For every phase explain why its order matters.

Identify parallelizable work versus work that must remain sequential.

==================================================
STEP 18 — TESTING STRATEGY
==================================================

Design a complete regression matrix.

Include at minimum:

Approval:
- correct task approval
- wrong task approval
- expired approval
- duplicate approval
- replay
- process death during approval

Execution:
- real execution
- dispatch failure
- execution failure
- timeout
- cancellation
- stale callback
- false-success prevention

Completion:
- child success
- child failure
- parent failure
- verification failure
- duplicate completion
- process death

Unlock:
- unlocked success
- locked failure
- wrong UI
- timeout
- re-lock

Persistence:
- process death
- service restart
- reboot
- migration

Concurrency:
- simultaneous tasks
- simultaneous callbacks
- simultaneous approvals
- conflicting automation

Security:
- invalid exported intent
- unauthorized privileged action
- sensitive data leakage
- unsafe logging

==================================================
STEP 19 — ACCEPTANCE GATES
==================================================

Define explicit gates.

A P0 issue is NOT considered fixed merely because code compiles.

Each issue must have:
- implementation evidence
- automated test evidence
- runtime validation evidence where needed
- persisted-state validation
- failure-path validation

Define exact pass/fail conditions.

Especially enforce:

MAX MUST NEVER report success unless:
1. originating task is correct,
2. originating step is correct,
3. real execution occurred,
4. awaited result was received,
5. required verification succeeded,
6. authoritative state was updated,
7. result was persisted to the same task/step identity.

==================================================
STEP 20 — FINAL ENGINEERING DELIVERY PLAN
==================================================

At the end produce:

A. Executive architecture decision
B. Current architecture map
C. Target architecture map
D. State machine
E. Data model
F. Approval model
G. Execution-result model
H. Verification model
I. Recovery model
J. Concurrency model
K. Security model
L. Complete phased implementation plan
M. File-by-file change plan
N. Test matrix
O. Acceptance gates
P. Rollback strategy
Q. Risk register
R. Final implementation checklist

==================================================
STRICT CONSTRAINTS
==================================================

1. NO source-code modifications.
2. NO speculative redesign.
3. NO unnecessary rewrites.
4. Preserve existing working MAX departments.
5. Reuse stable existing logic whenever possible.
6. Every proposed change must cite repository evidence.
7. Clearly distinguish:
   - existing behavior
   - required correction
   - newly proposed logic
8. Do not treat documentation as proof when source code contradicts it.
9. Identify unknowns instead of inventing behavior.
10. Do not expand scope into unrelated P1/P2 work.
11. Protect backward compatibility.
12. Include testability in every design decision.

FINAL DELIVERABLE:

Create:

MAX_COMPLETE_P0_IMPLEMENTATION_MASTER_PLAN.md

This must be detailed enough that a separate coding agent can execute the
implementation phase-by-phase without having to redesign the architecture.

Again:
DO NOT modify source code.
ONLY inspect, reason, design, and produce the complete implementation master plan.
