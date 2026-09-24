# MAX — Gemini Dual-Model Architecture & Codebase Audit

**Audit Date:** September 2026  
**Subject:** Dual-Model Routing Architecture (`gemini-3.8-live` Fast Mode & `gemini-3.8-live-extended-thinking` Deep Mode)  
**Target Codebases:** MAX Android Assistant (`com.example`) & Windows Companion Agent (`windows-agent`)  

---

## 1. Executive Summary & Existing Architecture Inspection

MAX is an AI assistant ecosystem operating across an Android client and a paired Windows Companion. Real-time voice interaction is powered by Google's Gemini Live WebSocket API (`BidiGenerateContent`).

Before adding extended thinking, an audit was conducted across all core components to identify model selection points, audio pipelines, tool execution flows, and session boundaries.

```
                                  ┌────────────────────────┐
                                  │      User Request      │
                                  └───────────┬────────────┘
                                              │ (Voice / Text)
                                              ▼
                                  ┌────────────────────────┐
                                  │    AssistantEngine     │
                                  └───────────┬────────────┘
                                              │
                                              ▼
                                  ┌────────────────────────┐
                                  │   ConnectionManager    │
                                  └───────────┬────────────┘
                                              │
                                              ▼
                                  ┌────────────────────────┐
                                  │ GeminiWebSocketClient  │
                                  └───────────┬────────────┘
                                              │ (WSS Bidi)
                                              ▼
                                   [Active Model Selection]
                                  - Default: gemini-3.8-live
                                  - Fallback: gemini-3.1-flash-live-preview
```

---

## 2. Component-by-Component Audit Findings

### 2.1 Android Client (`com.example`)

1. **`GeminiWebSocketClient.kt`**:
   - Manages the full-duplex OkHttp WebSocket connection to `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`.
   - Setup payload sends `models/$activeModelName`, `responseModalities: ["AUDIO"]`, speechConfig with voice persona (`Aoede`), and 45 function declarations.
   - Streams audio chunks (16kHz mono linear PCM) and video frames (JPEG).
   - Tool calling runs asynchronously on `clientScope.launch(Dispatchers.IO)` and responds with `toolResponse`.
   - *Current Model Selection*: `DEFAULT_LIVE_MODEL = "gemini-3.8-live"` with fallback candidate `gemini-3.1-flash-live-preview`.
   - *Needs for Dual-Model*: Dynamic mode switching without tearing down audio pipelines, support for `ModelHandoffContext` in system instruction, and extended thinking model targeting (`gemini-3.8-live-extended-thinking`).

2. **`ConnectionManager.kt`**:
   - Lifecycle orchestrator for WebSocket connection creation, destruction, network callbacks, and memory context assembly.
   - Reads `SettingsManager.liveVoiceModelName` and passes `preferredModel` to `GeminiWebSocketClient`.
   - *Needs for Dual-Model*: Integration with `ModelRouter` to resolve model mode (AUTO / FAST / DEEP) per task or user preference, and handle session handoffs cleanly.

3. **`StreamingResponseManager.kt`**:
   - Receives text chunks, audio bytes, and tool calls from `GeminiWebSocketClient`.
   - Sanitizes text with `InternalReasoningFilter.sanitize()` before passing to UI or TTS.
   - Dispatches tool calls to `toolRegistry.executeToolAsync()` and returns results to `onExecuteToolResponse`.
   - *Needs for Dual-Model*: Preserve low-latency playback in Fast mode while accommodating longer reasoning pauses in Deep mode.

4. **`AssistantEngine.kt` & `AssistantVoiceController.kt`**:
   - Single central audio recorder (`AudioRecorder`), focus manager (`AudioFocusManager`), and audio playback engine (`AudioPlayer` / `AudioPlaybackManager`).
   - Single state machine (`SessionStateMachine`) and conversation manager (`ConversationManager`).
   - *Audit Confirmation*: Audio recording and playback MUST NOT be duplicated for the Deep model. The single audio layer handles both modes seamlessly.

5. **`ToolDispatcher.kt` & `AssistantToolRegistry`**:
   - 45 supported tools (system settings, phone calls, WhatsApp, media, routines, screenshots, Windows agent commands).
   - Normalizes incoming Gemini `FunctionCall` into internal action JSON strings (`TASK_INTENT`, `OPEN_APP`, `SEND_WHATSAPP`, `WINDOWS_AGENT`, etc.).
   - *Audit Confirmation*: Both FAST and DEEP models will feed into the exact same `ToolDispatcher` / `ToolRouter`. No separate tool routers will be created.

6. **`SettingsManager.kt` & `SettingsUiState.kt`**:
   - Stores `liveVoiceModelName` (default: `"gemini-3.8-live"`), `liveVoiceName` (`"Aoede"`), and `languagePreference`.
   - *Needs for Dual-Model*: Add `modelRoutingMode` preference (`AUTO`, `FAST`, `DEEP`) to allow explicit user control from the Settings UI.

### 2.2 Paired Windows Companion (`windows-agent`)

1. **`config.py`**:
   - Central configuration for `GEMINI_API_KEY`, `GEMINI_MODEL`, `INPUT_SAMPLE_RATE` (16kHz), `OUTPUT_SAMPLE_RATE` (24kHz).
   - *Needs for Dual-Model*: Add `GEMINI_MODEL_FAST = "gemini-3.8-live"` and `GEMINI_MODEL_DEEP = "gemini-3.8-live-extended-thinking"`.

2. **`core/realtime_voice.py`**:
   - Manages Google GenAI WebSocket Live connection (`client.aio.live.connect`), session resumption handles, and 48 Windows desktop automation tools.
   - *Needs for Dual-Model*: Support dual-model routing and bounded computer-use loop (Observe -> Reason -> Action -> Observe).

---

## 3. Dual-Model Architecture Design Matrix

| Feature | FAST Mode (`gemini-3.8-live`) | DEEP Mode (`gemini-3.8-live-extended-thinking`) |
|---|---|---|
| **Primary Use Case** | Voice chat, quick QA, single-step tools, device control | Multi-step planning, project debugging, screen diagnosis, deep workflows |
| **Latency Target** | < 500ms first audio packet | Thoughtful reasoning before action execution |
| **Thinking Process** | Standard zero-shot / few-shot | Extended thinking reasoning tokens scrubbed from final voice |
| **Tool Execution** | Direct via `ToolRouter` | Multi-action chain via `ToolRouter` with dependency tracking |
| **Observation Loop** | Single screenshot on demand | Bounded multi-step observation loop (Max 8 actions, 60s timeout) |
| **Audio Layer** | `AssistantVoiceController` | Same `AssistantVoiceController` (Zero duplication) |
| **Tool Router** | Unified `ToolRouter` | Unified `ToolRouter` (Zero duplication) |

---

## 4. Key Decisions & Safeguards

1. **MAX Controls Routing**: The LLM does not decide its own model; MAX's `ModelRouter` and heuristic task classifier evaluate queries and context before dispatching.
2. **Seamless Context Handoff**: When a task escalates from Fast to Deep mode, `ModelHandoffContext` injects a compact summary, current objective, and prior tool states into the Deep session.
3. **Fail-Safe Fallback**: Model fallback only triggers on genuine model availability or 1008 policy errors, avoiding silent downgrades on unrelated network or authentication issues.
