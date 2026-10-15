# MAX Multitasking Audit

## Verdict

MAX supports scheduled independent records and one active automation path, but it does **not** currently provide robust true multitasking for concurrent interactive tasks. A long sequence with many steps is not equivalent to multiple independent task contexts.

## Evidence

- `GoalPlanner` stores one global `actionQueue`, `executionOutputs`, `completedStepIds`, `failedStepIds`, `completedSteps`, one waiting state, one cancellation token, and one mutex.
- `ApprovalCoordinator` stores one global pending approval.
- `WorldStateManager` stores one global foreground app, current goal, remaining steps, and previous action.
- `JarvisAccessibilityService.pendingTask` is global; the service rejects collisions younger than 15 seconds.
- `ComputerUseConcurrency` provides a device-control lock, intentionally preventing concurrent computer-use control.
- `TaskManager`/Room support multiple scheduled task rows, but that is scheduling persistence, not concurrent interactive execution.

## Capability matrix

| Capability | Assessment | Evidence |
|---|---|---|
| Multiple independent tasks | Partial at database scheduling level; no safe concurrent interactive contexts | Scheduler entities vs global GoalPlanner/WorldState |
| Parallel execution | Not supported for device UI; computer-use lock serializes control | `ComputerUseConcurrency`, `pendingTask` |
| Queued execution | Partial | `ActionQueue` is per object, but main `GoalPlanner` queue is singleton/global |
| Priorities | Not demonstrated | No priority field in `ActionStep`, `AgentTask`, or `ScheduledTask` |
| Cancellation | Partial | `CancellationToken`, `GoalPlanner.cancelActivePlan()`, scheduler cancellation |
| Pause/resume | Partial for one in-memory `WaitingForInputState`; not restart-safe | `GoalPlanner.resumePlanWithInput()` |
| Independent contexts | No | global world state, approval, planner state |
| Dependencies | Yes within a plan | `DependencyGraphResolver`, `ActionQueue.dequeueReadyStep()` |
| Background tasks | Yes for alarms/foreground services, with limits | `TaskReceiver`, services, alarms |
| Concurrent AI reasoning | Connection/session may stream, but task context is not isolated | singleton engine/session patterns |
| Conflict handling | Only coarse collision rejection/lock | service pending task and computer-use lock |

## A/B/C scenario

Task A WhatsApp message, Task B file download, and Task C scheduled reminder cannot be asserted safe at the same time. C can be persisted as a scheduler row. A and B may compete for the single accessibility/device-control surface, while global `WorldStateManager` and planner/approval state can be overwritten or misinterpreted. There is no task-scoped lease, resource graph, or parent task identity carried through all events.

## Recommended compatible orchestrator

Add an incremental `TaskOrchestrator` above the existing `ActionDispatcher`/`ExecutionEngine`, not a rewrite:

1. Persist task and step records in Room.
2. Give every task an immutable context and resource requirements (`UI`, `microphone`, `network`, `screen`, `contacts`, etc.).
3. Serialize conflicting resources while allowing independent non-UI work in parallel.
4. Keep one device-control executor lease; queue UI tasks explicitly.
5. Store priority, cancellation, pause, approval, retries, and parent/child dependencies.
6. Emit task-ID-correlated events and derive UI from those events.
7. Recover `RUNNING`/`WAITING` tasks after process/service restart.

This preserves the existing tool registry and execution engine while fixing ownership and isolation.
