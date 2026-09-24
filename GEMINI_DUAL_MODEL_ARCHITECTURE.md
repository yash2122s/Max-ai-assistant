# MAX — Gemini 3.8 Live + Extended Thinking Dual-Model Architecture

## 1. Architectural Philosophy & Overview

MAX employs a **unified, single-pipeline dual-model routing architecture** for real-time voice and reasoning. Rather than maintaining separate voice systems or parallel audio engines, MAX routes all interactions through a single, shared audio infrastructure (`AudioRecorder`, `AudioPlayer`, `AssistantVoiceController`) and a centralized tool execution engine (`ToolDispatcher`, `ActionDispatcher`, `ExecutionEngine`).

```
                                  MAX AI Assistant
                                         │
                                   ModelRouter
                                         │
                   ┌─────────────────────┴─────────────────────┐
                   ↓                                           ↓
         gemini-3.8-live                       gemini-3.8-live-extended-thinking
            FAST MODE                                      DEEP MODE
                   │                                           │
         • Real-time conversational voice            • Complex multi-step reasoning
         • Quick device commands (<500ms)            • Code debugging & stacktrace analysis
         • Low-latency single tools                  • Deep screen diagnosis & UI workflows
         • Telugu / English / Tenglish               • Windows PC computer-use automation
                   │                                           │
                   └─────────────────────┬─────────────────────┘
                                         │
                                    ToolRouter
                                         │
                               ┌─────────┴─────────┐
                               ↓                   ↓
                         Android Agent       Windows Agent
```

### Core Invariants
1. **Single Audio Pipeline**: Captured microphone frames (16-bit 16kHz PCM) flow through the active WebSocket without buffering duplication or separate audio engines.
2. **Deterministic Model Routing**: The LLM never selects its own model or endpoints; routing is strictly governed on-device by `ModelRouter` and user preferences.
3. **Seamless State Handoff**: When transitioning between models, `ModelHandoffContext` injects the ongoing objective, completed steps, pending actions, and screen state into the new session without conversation disruption.
4. **Resilient Fallback**: Fallback to `gemini-3.1-flash-live-preview` is targeted to model unreachability or policy error code 1008, isolating transient network disconnects to standard auto-reconnect backoffs.

---

## 2. Model Selection Matrix

| Mode | Model Identifier | Latency Target | Use Cases & Capabilities |
| :--- | :--- | :--- | :--- |
| **FAST MODE** | `gemini-3.8-live` | < 500 ms | Instant voice conversation, weather/time queries, hardware toggles (flashlight, DND, volume, Bluetooth), contact calls, single-step app launches. |
| **DEEP MODE** | `gemini-3.8-live-extended-thinking` | Multi-turn reasoning | Complex problem solving, code compilation/debugging, stacktrace inspection, multi-step automation planning, Windows companion agent orchestration, and deep visual screen analysis. |
| **FALLBACK** | `gemini-3.1-flash-live-preview` | Resilient fallback | Active when primary models encounter service unavailability, regional model rollout restrictions, or policy error 1008. |

---

## 3. Model Routing & Intent Classification (`ModelRouter`)

The `ModelRouter` is responsible for evaluating user queries, session state, and user settings to assign the optimal Gemini model.

### 3.1 Routing Priorities
1. **Explicit Voice Commands (Highest Priority)**:
   - Voice trigger to DEEP: *"use deep thinking"*, *"think deeply"*, *"deep mode"*, *"analyze this thoroughly"*, *"enable deep thinking"*.
   - Voice trigger to FAST: *"switch to fast mode"*, *"fast mode"*, *"be quick"*, *"quick response"*, *"turn off deep mode"*.
2. **User Settings Configuration**:
   - `AUTO` (Default): Evaluates heuristic classifiers on user transcription and context flags.
   - `FAST`: Forces `gemini-3.8-live` unless explicit deep voice trigger is spoken.
   - `DEEP`: Forces `gemini-3.8-live-extended-thinking` unless explicit fast voice trigger is spoken.
3. **Heuristic Task Classifiers (Under AUTO Mode)**:
   - **DEEP Routing Triggers**:
     - *Coding / Build / Debug Keywords*: `debug`, `stacktrace`, `compile`, `build error`, `nullpointerexception`, `gradle`, `refactor`, `algorithm`, `syntax error`.
     - *Multi-Step Workflow Keywords*: `multi-step`, `plan and execute`, `automate this workflow`, `step by step`, `then after that`.
     - *Screen / Visual Troubleshooting*: `look at this screen and figure out why`, `what's wrong on this page`, `diagnose this screen`.
     - *Context Flags*: `TaskContext.requiresCodingOrDebug`, `TaskContext.requiresDeepVisionAnalysis`, `TaskContext.isMultiStep`.
   - **FAST Routing Triggers**:
     - Standard questions, short greetings, basic single-action device commands.

---

## 4. Context Handoff Architecture (`ModelHandoffContext`)

When transitioning between modes (e.g., a conversational voice turn in FAST mode requests debugging an error that escalates to DEEP mode), MAX preserves context through `ModelHandoffContext`:

```kotlin
data class ModelHandoffContext(
    val taskId: String = UUID.randomUUID().toString(),
    val conversationSummary: String = "",
    val currentObjective: String = "",
    val completedActions: List<String> = emptyList(),
    val pendingActions: List<String> = emptyList(),
    val toolState: Map<String, Any> = emptyMap(),
    val screenContextSummary: String? = null
)
```

### System Instruction Injection
The context is serialized into a clean, structured Markdown block appended to the base system prompt during WebSocket setup:

```markdown
# TASK CONTINUITY & HANDOFF CONTEXT
Task ID: 9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d
Current Objective: Debug Gradle build failure in VS Code terminal
Context Summary: User asked MAX to inspect build output and resolve unresolved references
Completed Actions: windows_agent(core.terminal:run), get_clipboard
Pending Actions: replace_file_content, rebuild_project
Screen State: Terminal showing Unresolved reference: radiusSmall at SettingsScreen.kt:695
```

This guarantees zero loss of conversational memory or execution progress across model reconnects.

---

## 5. Audio & Session Unification

```
┌─────────────────────────────────────────────────────────────┐
│                 MAX Audio & Session Layer                   │
├──────────────────────────────┬──────────────────────────────┤
│ Audio Recording (16kHz PCM)  │ Audio Playback (WRITE_BLOCK) │
│ • AudioRecord                │ • AudioTrack                 │
│ • AudioFocusManager          │ • Barge-in Instant Flush     │
└──────────────┬───────────────┴──────────────▲───────────────┘
               │                              │
               ▼                              │
┌─────────────────────────────────────────────┴───────────────┐
│                    GeminiWebSocketClient                    │
│ • Setup frame with voiceName, tools, systemInstruction      │
│ • Input / Output audio transcription deltas                 │
│ • Bi-directional BidiGenerateContent stream                 │
│ • 25s silent keep-alive frame heartbeat                     │
└─────────────────────────────────────────────────────────────┘
```

- **Barge-in / Interruption Handling**: If the user speaks while Gemini is outputting audio, `StreamingResponseManager.handleUserSpeaking()` flushes the `AudioTrack` instantly.
- **Transcription Accumulation**: Incremental deltas from `serverContent.inputAudioTranscription` are thread-safely concatenated to ensure entire queries reach tool dispatching.

---

## 6. Tool Dispatching Integration

Both FAST and DEEP models share the exact same 45+ registered MAX tools declared in `sendSetupMessage()`:
- **Device Controls**: `flashlight`, `set_volume`, `set_brightness`, `set_dnd`, `set_bluetooth`, `toggle_wifi`, `system_action`, `unlock_device`.
- **Communication**: `search_contact`, `call_contact`, `answer_call`, `reject_call`, `send_sms`, `send_whatsapp_message`, `reply_notification`.
- **Productivity & Memory**: `save_memory`, `forget_memory`, `set_reminder`, `set_alarm`, `set_timer`, `add_calendar_event`, `run_routine`.
- **Health & Lifestyle**: `log_period_start`, `log_period_end`, `log_period_note`, `get_period_history`, `get_period_prediction`.
- **Vision & Diagnostics**: `get_screen_context`, `take_screenshot`, `take_photo`, `get_device_status`, `diagnostics`.
- **Desktop Companion**: `windows_cmd`, `windows_agent` (clipboard, window management, filesystem, terminal, vision).

---

## 7. Windows Companion Agent Integration

The Windows Companion Agent (`windows-agent/`) mirrors the Android routing architecture:
- `windows-agent/config.py`:
  - `GEMINI_MODEL_FAST = "gemini-3.8-live"`
  - `GEMINI_MODEL_DEEP = "gemini-3.8-live-extended-thinking"`
  - `GEMINI_MODEL_FALLBACK = "gemini-3.1-flash-live-preview"`
  - `DEFAULT_MODEL_MODE = "AUTO"`
- `windows-agent/core/realtime_voice.py`: Configured to use the centralized dual-model configuration with computer-use, shell automation, and vision capture capabilities.

---

## 8. Security & Privacy Safeguards

- **API Key Storage**: Stored exclusively in Android `EncryptedSharedPreferences` via `SettingsManager`. Never hardcoded, never printed in logs, never transmitted in plain text.
- **Log Sanitization**: Logs output only non-sensitive identifiers and truncated preview strings.
- **Telemetry Anonymization**: `DualModelTelemetry` logs task IDs, model names, latency numbers, and error categories without capturing user query text or PII.

---

## 9. Telemetry & Metrics (`DualModelTelemetry`)

`DualModelTelemetry` tracks real-time performance indicators:
- `recordRoutingDecision(taskId, selectedMode, selectedModel, reason, queryLength)`
- `recordModelSwitch(taskId, newMode, newModel, reason)`
- `recordLatency(taskId, model, ttfbMs, totalTurnMs)`
- `recordFallback(taskId, failedModel, fallbackModel, reason)`

---

## 10. Verification & Test Suite

The dual-model architecture is validated by automated unit tests:
1. `GeminiModelRouterTest.kt`: Fast command routing, complex coding triggers, explicit voice overrides, context flag escalation, and user preference resolution.
2. `GeminiModelHandoffTest.kt`: Payload serialization, system instruction formatting, minimal vs. full payloads, and task ID preservation.
3. `GeminiDualModelSessionTest.kt`: Model constants, Deep model detection, mode parsing, and dual-model session transitions.
4. `GeminiWebSocketClientUnitTest.kt`: Sanitization of model names and client initialization states.
