# MAX Production Readiness

## Verdict

**Not production-ready for unrestricted autonomous multi-step device control.** Infrastructure is substantial, but execution ownership, durable active-task state, approval continuation, release hardening, and device-level evidence are incomplete.

| Area | Assessment | Evidence |
|---|---|---|
| Build/module | Implemented | One `:app` module; Compose/Kotlin/KSP/Room/Firebase |
| Error handling | Partial | Many catches; some broadcast/task errors are only logged |
| Process/service death | Weak | Global planner, approval, world state, service registries |
| Network loss | Partial | WebSocket fallback/reconnect concepts; no parent recovery proof |
| Permissions | Partial | Capability/authorization exists; tool behavior varies |
| Timeouts | Mismatched | 25s tool, 60s plan/computer-use, 8s UI thinking |
| Retry storms | Partial risk | Retry/recovery/replan/fallback exist in several layers |
| State corruption | High risk | Multiple mutable global state machines |
| Diagnostics | Partial | Logs/traces exist; correlation/redaction need hardening |
| Release security | Gap | Cleartext enabled, minification disabled, broad privileges |
| Tests | Broad but incomplete | 107 test files found; long-horizon failure/restart matrix unproven |

## Release requirements

Define SLOs for verified success, false completion, approval latency, recovery, and crash-free sessions. Add redacted task trace IDs, durable failure receipts, restart recovery, and a support export that excludes secrets/message content by default.
