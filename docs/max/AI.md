## Purpose
MAX uses a **Dual Intelligence Engine Architecture**:
1. **Conversational AI (Gemini Live)**: Real-time multimodal voice WebSocket session (`gemini-3.1-flash-live-preview`), intent understanding, personality, and deterministic tool dispatching.
2. **UI Automation Agent (OpenRouter / Ox Alpha / Gemini Fallback)**: Provider-independent visual grounding and reasoning engine (`stealth/ox-alpha`) dedicated to executing complex, multi-step on-screen UI tasks with verification.

## Entry Points
- `AssistantEngine` → `ConnectionManager` → `GeminiWebSocketClient` (Voice & Chat)
- `UiAgentRouter` → `UiVerificationLoop` → `OpenRouterUiAgentProvider` / `GeminiUiAgentProvider` (UI Automation)

## Main Classes

| Class | File | Role |
|-------|------|------|
| `AssistantEngine` | `voice/assistant/AssistantEngine.kt` | Central coordinator: audio, connection, tools, vision |
| `OpenRouterUiAgentProvider` | `automation/agent/OpenRouterUiAgentProvider.kt` | Primary UI automation agent (OpenRouter REST API, default `stealth/ox-alpha`, dynamic model discovery) |
| `GeminiUiAgentProvider` | `automation/agent/GeminiUiAgentProvider.kt` | Fallback UI automation agent using Google Gemini REST API (`gemini-2.0-flash`) |
| `UiAgentRouter` | `automation/agent/UiAgentRouter.kt` | Dynamic provider routing and credentials management |
| `UiVerificationLoop` | `automation/agent/UiVerificationLoop.kt` | Bounded 8-step observation -> reasoning -> action -> verification loop |
| `UiActionExecutor` | `automation/agent/UiActionExecutor.kt` | Multi-tier atomic action executor (Accessibility gestures + Shizuku ADB) |
| `UiAgentTool` | `automation/tools/UiAgentTool.kt` | ToolRegistry integration for Gemini Live tool delegation |
| `GeminiWebSocketClient` | `network/GeminiWebSocketClient.kt` | Raw WSS; sendSetupMessage; tool call handling |
| `ToolDispatcher` | `network/ToolDispatcher.kt` | Maps function names → action JSON; 73 supported tools |
| `PromptBuilder` | `network/PromptBuilder.kt` | Builds system instruction: identity, personality, language, memories |
| `SessionStateMachine` | `voice/assistant/SessionStateMachine.kt` | IDLE→CONNECTING→CONNECTED→LISTENING→STREAMING |
| `ConversationManager` | `voice/assistant/ConversationManager.kt` | In-memory message history |
| `GeminiPlanningProvider` | `network/provider/GeminiPlanningProvider.kt` | REST Gemini call for multi-step plan generation |

## Data Flow
```
User speech → AudioRecorder → PCM chunks → GeminiWebSocketClient.sendAudio()
                                                     │
                                              Gemini Live (WSS)
                                                     │
                             ┌──────────────────────┤
                             │                      │
                      text/audio              toolCall JSON
                             │                      │
                     AudioPlayer/UI          ToolDispatcher
                                                     │
                                              ActionDispatcher
                                                     │
                                              ExecutionEngine
```

## System Instruction
Built by `PromptBuilder.buildSystemInstruction(language, memories)`:
1. Identity: MAX, female AI, created by Yaswanth
2. Personality: warm, caring, uses "bangaram" sparingly
3. Language: Telugu / English / Tenglish (Tenglish is default)
4. Automation policy
5. Tool selection rules (strict — no unsolicited tool calls)
6. Memory injection (top relevant PermanentMemory entries)

## Gemini Live Model
- `gemini-3.1-flash-live-preview` (hardcoded in `sanitizeModelName()`)
- WSS endpoint: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent`
- Auth: `?key=API_KEY` query param (from SettingsManager.geminiApiKey → EncryptedSharedPreferences)
- **SECRET PRESENT — DO NOT RECORD VALUE**

## Error Handling
- WebSocket failure → `tryNextModelOrReportError()` → reconnect
- WS 1008/GOAWAY → acknowledged with `webSocket.close()` → reconnect
- Ping interval: 10 seconds (TCP keep-alive)

## WorkerMode
When Windows Companion connects:
- `AssistantLifecycleManager.enterWorkerMode()` suspends mic + Gemini session
- Local voice returns to `exitWorkerMode()` on PC disconnect

## Dependencies
- `SettingsManager` — API key, voice name, language, model
- `MemoryRepository` — memories injected into prompt
- `ScreenCaptureProvider` — vision queries
- `ActionDispatcher` / `ActionAuthorizer` — tool execution

## Current Status: IMPLEMENTED ✅
Voice pipeline working. Tool calling working. Barge-in working.

## Known Issues
- `sanitizeModelName()` ignores user's `liveVoiceModelName` setting — always uses hardcoded model
- No local wake word; relies on ASSIST intent or manual mic button
