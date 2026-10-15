# MAX AI Optimization Audit

**Audit-only document. No source implementation is included.**

## Current topology

`AssistantEngine` owns the session, `ConnectionManager` owns Gemini Live connectivity, `GeminiWebSocketClient` handles bidirectional audio/text/tool messages, `ToolDispatcher` maps function calls, and `ActionDispatcher`/`ExecutionEngine` execute deterministic tools. `GeminiPlanningProvider` supports deeper plan generation. OpenRouter/Gemini UI-agent providers and vision/computer-use paths are also present.

## Findings

- Model candidates and fast/deep/fallback concepts exist in `GeminiWebSocketClient`, but routing is spread across the client, `AssistantEngine`, `HomeViewModel`, `OfflineCommandEngine`, `GoalPlanner`, and parsers.
- Deterministic routing can save model calls, but duplicated normalization and policy checks create ingress-dependent behavior.
- Conversation context is short-lived (`ConversationManager` keeps 15 turns); active task graph, approvals, verifier evidence, and remaining steps are not durable structured model context.
- Screenshot capture may block up to 2 seconds on accessibility capture and always targets a 720px maximum dimension.
- Tool execution has a 25s timeout; long-plan and computer-use execution have 60s budgets. The tester's request alone has more than 60 seconds of explicit waits.
- Replanning and recovery exist, but are bounded and operate alongside global in-memory planner state.

## Latency breakdown (static estimate)

| Segment | Likely contribution | Evidence/unknown |
|---|---:|---|
| STT/audio | Variable | Device/model dependent; no release percentiles found |
| WebSocket connect/setup | 100ms to seconds | Client telemetry exists, no SLO report |
| Model/tool selection | 100ms to seconds | Network/provider dependent |
| Planning | 0ms deterministic; network RTT for LLM | Fast path vs planning provider |
| Observation | 150ms polling plus capture cost | UI observer and screenshot paths |
| Vision | Hundreds ms to seconds | Remote reasoner; no benchmark |
| Execution | App/permission dependent | 25s tool timeout |
| Verification/recovery | 200ms settle plus retries/replans | Orchestrator/computer-use code |
| Response | Streaming normally; approval path detached | Result is ignored after approval |

## Priority optimization plan

1. Add redacted end-to-end trace IDs and p50/p95 metrics for every segment.
2. Decide routing once at ingress; consolidate normalization and authorization boundaries.
3. Use deterministic tools for open/wait/back/media/system actions; reserve vision for uncertain grounding.
4. Replace arbitrary waits with event/readiness predicates.
5. Separate chat context from task context and verifier evidence.
6. Optimize screenshot resolution/capture only after measuring accuracy and latency.
7. Add bounded network backoff and distinguish retryable transport failure from tool failure.

Do not optimize away verification, approval, or final evidence gates.
