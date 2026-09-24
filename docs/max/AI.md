## Purpose
MAX uses a **Dual Intelligence Engine Architecture**:
1. **Conversational AI & Reasoning (Gemini 3.8 Live Dual-Model)**: Real-time multimodal voice WebSocket session with dynamic routing between **FAST MODE** (`gemini-3.8-live` for sub-500ms voice and tool actions) and **DEEP MODE** (`gemini-3.8-live-extended-thinking` for multi-step reasoning, coding/debug, and screen troubleshooting), with automatic fallback to `gemini-3.1-flash-live-preview`.
2. **UI Automation Agent (OpenRouter / Ox Alpha / Gemini Fallback)**: Provider-independent visual grounding and reasoning engine (`stealth/ox-alpha`) dedicated to executing complex, multi-step on-screen UI tasks with verification.

## Entry Points
- `AssistantEngine` → `ConnectionManager` → `ModelRouter` → `GeminiWebSocketClient` (Voice, Reasoning & Chat)
- `UiAgentRouter` → `UiVerificationLoop` → `OpenRouterUiAgentProvider` / `GeminiUiAgentProvider` (UI Automation)

## Main Classes

| Class | File | Role |
|-------|------|------|
| `AssistantEngine` | `voice/assistant/AssistantEngine.kt` | Central coordinator: audio, connection, tools, vision |
| `ModelRouter` | `network/router/ModelRouter.kt` | Routes between FAST (`gemini-3.8-live`) and DEEP (`gemini-3.8-live-extended-thinking`) based on heuristics, context, and voice overrides |
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
- WSS endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
- Auth: `?key=API_KEY` query param (from SettingsManager.geminiApiKey → EncryptedSharedPreferences)
- **SECRET PRESENT — DO NOT RECORD VALUE**

## Error Handling & Resilience
- WebSocket failure → `tryNextModelOrReportError()` → candidate fallback model → reconnect
- WS 1008/GOAWAY → acknowledged with `webSocket.close()` → reconnect with next candidate
- Exponential backoff reconnection for network disconnections (1s to 30s)
- Ping interval: 10 seconds (TCP keep-alive) + 25s silent keep-alive frame heartbeat

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

