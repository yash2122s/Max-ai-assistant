## Purpose
MAX uses a **Dual Intelligence Engine Architecture**:
1. **Conversational AI & Reasoning (Gemini 3.8 Live)**: AUTO now selects `gemini-3.8-live-extended-thinking`. Explicit FAST mode remains available. Actual model access and response latency require device/account validation; model configuration alone is not evidence of support.
2. **UI Automation Agent (OpenRouter / Ox Alpha / Gemini Fallback)**: Provider-independent visual grounding and reasoning engine (`stealth/ox-alpha`) dedicated to executing complex, multi-step on-screen UI tasks with verification.

## Entry Points
- `AssistantEngine` → `ConnectionManager` → `ModelRouter` → `GeminiWebSocketClient` (Voice, Reasoning & Chat)
- `UiAgentRouter` → `UiVerificationLoop` → `OpenRouterUiAgentProvider` / `GeminiUiAgentProvider` (UI Automation)

## Main Classes

| Class | File | Role |
|-------|------|------|
| `AssistantEngine` | `voice/assistant/AssistantEngine.kt` | Central coordinator: audio, connection, tools, vision |
| `ModelRouter` | `network/router/ModelRouter.kt` | Defaults AUTO to DEEP; honors explicit FAST/DEEP settings and voice overrides |
| `LiveSessionState` | `network/LiveSessionState.kt` | In-memory model-bound resumption handle and bounded tool-call duplicate ledger; not a durable workflow store |
| `GeminiLiveModels` | `network/model/GeminiModelConfig.kt` | Central source of truth for FAST, DEEP, and FALLBACK models |
| `ModelHandoffContext` | `network/model/GeminiModelConfig.kt` | Preserves objective, actions, and screen state across model transitions |
| `DualModelTelemetry` | `network/telemetry/DualModelTelemetry.kt` | Telemetry for model switches, routing decisions, and latency metrics |
| `OpenRouterUiAgentProvider` | `automation/agent/OpenRouterUiAgentProvider.kt` | Primary UI automation agent (OpenRouter REST API, default `stealth/ox-alpha`, dynamic model discovery) |
| `GeminiUiAgentProvider` | `automation/agent/GeminiUiAgentProvider.kt` | Fallback UI automation agent using Google Gemini REST API (`gemini-2.0-flash`) |
| `UiAgentRouter` | `automation/agent/UiAgentRouter.kt` | Dynamic provider routing and credentials management |
| `UiVerificationLoop` | `automation/agent/UiVerificationLoop.kt` | Bounded 8-step observation -> reasoning -> action -> verification loop |
| `UiActionExecutor` | `automation/agent/UiActionExecutor.kt` | Multi-tier atomic action executor (Accessibility gestures + Shizuku ADB) |
| `UiAgentTool` | `automation/tools/UiAgentTool.kt` | ToolRegistry integration for Gemini Live tool delegation |
| `GeminiWebSocketClient` | `network/GeminiWebSocketClient.kt` | Raw WSS; sendSetupMessage; tool call handling; candidate fallback |
| `ToolDispatcher` | `network/ToolDispatcher.kt` | Maps function names → action JSON; 73 supported tools |
| `PromptBuilder` | `network/PromptBuilder.kt` | Builds system instruction: identity, personality, language, memories, emotions |
| `SessionStateMachine` | `voice/assistant/SessionStateMachine.kt` | IDLE→CONNECTING→CONNECTED→LISTENING→STREAMING |
| `ConversationManager` | `voice/assistant/ConversationManager.kt` | In-memory message history |
| `GeminiPlanningProvider` | `network/provider/GeminiPlanningProvider.kt` | REST Gemini call for multi-step plan generation |

## Data Flow
```
User speech → AudioRecorder → PCM chunks → GeminiWebSocketClient.sendAudio()
                                                     │
                                         Gemini 3.8 Live (WSS)
                                   (gemini-3.8-live / extended-thinking)
                                                     │
                             ┌───────────────────────┤
                             │                       │
                      text/audio               toolCall JSON
                             │                       │
                     AudioPlayer/UI           ToolDispatcher
                                                     │
                                              ActionDispatcher
                                                     │
                                              ExecutionEngine
```

## System Instruction & Handoff Injection
Built by `PromptBuilder.buildSystemInstruction(language, memories, emotionState)` + `ModelHandoffContext.toSystemInstructionPrompt()`:
1. Identity: MAX, female AI, created by Yaswanth
2. Personality: warm, caring, uses "bangaram" sparingly
3. Language: Telugu / English / Tenglish (Tenglish is default)
4. Automation policy
5. Tool selection rules (strict — no unsolicited tool calls)
6. Memory injection (top relevant PermanentMemory entries)
7. Handoff context (Task ID, current objective, completed/pending actions, screen state)

## Gemini Live Models
- **FAST Mode**: `gemini-3.8-live` (conversational voice, sub-500ms latency, direct tools)
- **DEEP Mode**: `gemini-3.8-live-extended-thinking` (complex reasoning, coding/debugging, multi-step workflows, desktop automation)
- **FALLBACK**: `gemini-3.1-flash-live-preview` (resilient candidate fallback)
- WSS endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent`
- Auth: `?key=API_KEY` query param (from SettingsManager.geminiApiKey → EncryptedSharedPreferences)
- **SECRET PRESENT — DO NOT RECORD VALUE**
- **Session Lifecycle Contract**: Process all final-frame content before `IDLE`; intermediate `turnComplete` does not complete a deep interaction or reset its user-intent accumulator. Local tool jobs also finish before the interaction-complete callback. This callback never writes task success. Legacy turn-only completion applies only to non-deep models.
- **Thinking**: `SettingsManager.liveThinkingLevel` supports LOW/MEDIUM/HIGH (default HIGH). Deep setup sends `includeThoughts=false`. Thought-marked parts produce only a fixed progress indicator; raw thought content is not forwarded to UI callbacks or frame logs.
- **Tools**: Deep declarations use `NON_BLOCKING`; progress uses `willContinue=true`/`SILENT`, results use `willContinue=false`/`WHEN_IDLE`. Jobs remain serialized to protect shared device state. Tool-call cancellation and a bounded call-ID ledger prevent same-session re-execution. This does not constitute durable exactly-once execution across process death.
- **Protocol source**: official `googleapis/python-genai`, `google/genai/types.py` (LiveServerContent, FunctionResponse, Behavior, ThinkingLevel, resumption/compression definitions).

## Error Handling & Resilience
- Transport failure reconnects without changing the model. Fallback is limited to pre-setup 1008 responses explicitly reporting unsupported/missing models.
- GOAWAY closes the session cleanly. Model-bound resumable handles are retained in ConnectionManager memory; non-resumable updates invalidate the handle. Context-window compression is requested. In-flight task recovery still requires durable workflow evidence.
- Exponential backoff reconnection for network disconnections (1s to 30s)
- Ping interval: 10 seconds (TCP keep-alive) + 25s silent keep-alive frame heartbeat
- Memory refresh is independent of connection startup; setup uses the latest available memory snapshot.

## 2026-10-04 validation status
139 focused regressions passed. Final full suite: 860 tests, 3 failures in pre-existing REST UI-agent model expectations. App and instrumentation APK builds pass. Installation was blocked because ADB could not find device `3bc00016`; no live model/access/latency acceptance is claimed. See `docs/plans/live-extended-thinking/REPORT.md`.

## WorkerMode
When Windows Companion connects:
- `AssistantLifecycleManager.enterWorkerMode()` suspends mic + Gemini session
- Local voice returns to `exitWorkerMode()` on PC disconnect

## Dependencies
- `SettingsManager` — API key, voice name, language, model, modelRoutingMode (AUTO, FAST, DEEP)
- `MemoryRepository` — memories injected into prompt
- `ScreenCaptureProvider` — vision queries
- `ActionDispatcher` / `ActionAuthorizer` — tool execution

## Current Status: IMPLEMENTED ✅
Dual-model routing architecture active. `gemini-3.8-live` and `gemini-3.8-live-extended-thinking` integrated with seamless handoff, settings UI, and unit test verification.

## Known Issues
- No local wake word; relies on ASSIST intent or manual mic button
