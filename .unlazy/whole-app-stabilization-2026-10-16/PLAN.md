# Plan: Whole-app bug and intent-comprehension stabilization

Scope: whole-app-stabilization-2026-10-16
Depth: tree 2
Mode: orchestrated

## Contract

- Interfaces: preserve `GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine`; preserve existing `ToolResult` and durable task identity contracts; successful user-facing completion requires execution plus verification.
- Ownership: leaf-1.1 owns automation/network code and tests; leaf-1.2 owns voice/core/data/UI/service code and tests; no concurrent leaf owns the same path.
- Dependencies: both leaves are independent for the first wave; root integration follows both.
- Host launch mode: native OmniRush subagents; two-leaf wave launched together.
- Wave policy: one wave with two independent leaves; checks run sequentially unless a gate explicitly proves independence.
- Toolchain: Windows PowerShell project root; use `C:\Program Files\Android\Android Studio\jbr`; Gradle wrapper only; no device, WhatsApp, calls, messages, or external side effects.
- Conventions: minimal focused fixes; preserve unrelated worktree changes; fail closed on ambiguous execution; do not add secrets or change Room schema without migration/tests.
- Manual review: driver reviews cross-pipeline routing, user-intent interpretation, completion truth, security boundaries, and all unresolved gaps.

## Current contract inventory

Contract revision: 1. The requested outcome is a broad stabilization pass: inspect the whole app, fix high-confidence bugs/errors, and make the assistant execute only the clearly understood user task.

| ID | Required outcome or constraint | Owner | Observing gate or manual review | Disposition | Revision |
|---|---|---|---|---|---|
| C1 | Automation and network paths have no high-confidence correctness regressions in the owned scope; failures remain fail-closed. | leaf-1.1 | leaf-1.1:G1, leaf-1.1:G2, node-1:G1 | VERIFIED | 1 |
| C2 | Voice/core/data/UI/service paths have no high-confidence lifecycle, state, persistence, or build regressions in the owned scope. | leaf-1.2 | leaf-1.2:G1, leaf-1.2:G2, node-1:G1 | VERIFIED | 1 |
| C3 | User intent is normalized and authorized before execution; ambiguous or mismatched actions cannot be reported as completed. | leaf-1.1 + leaf-1.2 | node-1:G2 + manual review | VERIFIED | 1 |
| C4 | Existing architecture and unrelated worktree changes are preserved; no unsafe live-device actions are performed. | root | node-1:G3 | VERIFIED | 1 |
| C5 | Full unit tests and debug APK build are measured after integration. | root | root:G1, root:G2 | VERIFIED | 1 |

## State vocabulary

Leaf state is exactly one of WAITING, READY, IN-FLIGHT, VERIFIED, ABANDONED.
Branch state is exactly one of OPEN, VERIFIED, or ABANDONED.

## Tree

- 1 whole-app stabilization .............. GATES.md (VERIFIED)
  - 1.1 automation and network .......... gates/node-1.1.md (VERIFIED)
    - 1.1.1 automation/network audit and fixes ...... gates/leaf-1.1.1.md (VERIFIED)
  - 1.2 app lifecycle and user intent .... gates/node-1.2.md (VERIFIED)
    - 1.2.1 voice/core/data/UI/service audit and fixes ...... gates/leaf-1.2.1.md (VERIFIED)

## Leaf dispatch table

| Leaf | Owns | Needs | Tier | Planned wave | State |
|---|---|---|---|---|---|
| 1.1.1 | app/src/main/java/com/example/automation/**, app/src/main/java/com/example/network/**, app/src/test/java/com/example/automation/**, app/src/test/java/com/example/network/** | - | judgment | 1 | VERIFIED |
| 1.2.1 | app/src/main/java/com/example/voice/**, app/src/main/java/com/example/core/**, app/src/main/java/com/example/data/**, app/src/main/java/com/example/ui/**, app/src/main/java/com/example/service/**, app/src/main/java/com/example/MyApplication.kt, app/src/main/AndroidManifest.xml, app/src/test/java/com/example/voice/**, app/src/test/java/com/example/core/**, app/src/test/java/com/example/data/**, app/src/test/java/com/example/ui/** | - | judgment | 1 | VERIFIED |

## Status log

Status is appended to `.unlazy/whole-app-stabilization-2026-10-16/status.log`.
