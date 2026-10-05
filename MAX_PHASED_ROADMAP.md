# MAX Phased Roadmap

This is a proposed implementation roadmap only. It was not implemented in this audit.

## Phase 0 — Baseline and instrumentation
Objective: correlate request, step, approval, verifier, and response with redacted trace IDs. Components: AssistantEngine, ToolDispatcher, ActionDispatcher, ExecutionEngine, LongTaskOrchestrator, ComputerUseEngine, AutomationEventBus. Tests: ordering, redaction, duplicate events. Done: failed fixtures cannot produce a success receipt.

## Phase 1 — Execution truth/state correctness
Persist one task and child-step lifecycle in Room. Include `WAITING_FOR_APPROVAL`, `NEEDS_USER`, `BLOCKED`, `TIMED_OUT`, `PAUSED`, and evidence references. Parent success requires all required child steps plus final predicate verification. Test failed child, verifier failure, process death, restart, and out-of-order events.

## Phase 2 — Task orchestration
Give each task an immutable context and resource requirements. Serialize the device-control resource while allowing independent network/scheduled work. Replace global planner/world/approval ownership with task-scoped records. Test A/B/C concurrency, cancellation, pause/resume, priority, and resource conflict.

## Phase 3 — Reliability and recovery
Make readiness predicates and event-driven polling authoritative. Verify unlock, app transitions, typing, sending, calls, media, and screen-off independently. Test slow apps, stale nodes, denied permission, locked screen, and OEM variations.

## Phase 4 — Complex planning
Represent long waits, app transitions, dependencies, and final outcomes in a durable DAG. Keep existing `TaskPlanner`, `GeminiPlanningProvider`, `DependencyGraphResolver`, and `ReEvaluationEngine`; add budgets and evidence rather than rewriting the pipeline. Test long-horizon fixtures and blocked/replanned plans.

## Phase 5 — Screen vision/control
Use semantic accessibility targeting first, vision grounding second, coordinates last. Measure target accuracy, scroll/type/click reliability, resolution/orientation behavior, and capture latency.

## Phase 6 — AI/latency optimization
After correctness metrics exist, consolidate routing, reduce duplicate model calls, separate chat/task context, optimize capture, and add network backoff. Done only if latency improves without reducing verified-success rate.

## Phase 7 — UI modernization
Resolve the Compose/Flutter documentation conflict. Build task-centric progress, approval, recovery, evidence, and terminal-state UI from the authoritative task stream. Test rotation, process recreation, accessibility, and long-running tasks.

## Phase 8 — Controlled learning
Reuse only redacted, verified, user-approved workflow traces through `SkillMemoryRepository`; preserve provenance, versioning, rollback, deletion, and consent. Do not use unrestricted self-modifying code.

## Phase 9 — Security hardening
Require TLS, remove plaintext secret fallback, review backup/export, fuzz shell/intents, minimize permissions, isolate PIN flows, and perform a release security review.

## Phase 10 — Production validation
Use local fake providers, recorded responses, deterministic fixtures, Robolectric, emulator instrumentation, and limited real-device smoke tests. Preserve production licensing; only inject debug/test entitlement through explicit test seams. Release requires zero P0 findings and documented runtime unknowns.
