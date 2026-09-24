# MAX — Gemini 3.8 Live Architecture & Migration Audit

**Document Version:** 1.0.0  
**Target Migration Model:** `gemini-3.8-live`  
**Previous Baseline Model:** `gemini-3.1-flash-live-preview`  
**Target Codebase:** MAX Android Assistant & Paired Windows Companion Agent  

---

## 1. Executive Summary & Architecture Overview

MAX employs a bidirectional full-duplex real-time voice and automation streaming pipeline built on Google's Gemini Live API over WebSockets (`BidiGenerateContent`). This audit details the complete codebase inspection, identifying all model dependencies, audio pipeline contracts, tool invocation schemas, and safety mechanisms required for migration to **`gemini-3.8-live`**.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           MAX Real-Time Streaming Architecture                   │
└─────────────────────────────────────────────────────────────────────────────────┘
         │
         ├── [Microphone Capture] ──► AudioRecorder (16kHz 16-bit Mono PCM)
         │                                  │ (RealtimeInput.audio chunks)
         │                                  ▼
         │                      GeminiWebSocketClient (WSS Bidi)
         │                        [Endpoint: /ws/...BidiGenerateContent]
         │                        [Active Model: gemini-3.8-live]
         │                                  │
         │         ┌────────────────────────┴────────────────────────┐
         │         ▼                                                 ▼
         │   [Audio Stream Out]                               [Tool Calls]
         │   24kHz 16-bit Mono PCM                        FunctionDeclaration
         │         │                                                 │
         │         ▼                                                 ▼
         │   AudioPlaybackManager                             ToolDispatcher
         │   (Low latency AudioTrack)                                │
         │                                                           ▼
         │                                                    ActionDispatcher /
         │                                                    ExecutionEngine
```

---

## 2. Codebase Model Audit Findings

### 2.1 Android Client (`com.example`)
1. **`GeminiWebSocketClient.kt`**:
   - *Previous state*: Hardcoded `activeModelName = "gemini-3.1-flash-live-preview"` and a no-op `sanitizeModelName()`.
   - *Audit result*: Centralized to `DEFAULT_LIVE_MODEL = "gemini-3.8-live"`, fallback candidate `"gemini-3.1-flash-live-preview"`, and dynamic `preferredModel` sanitization (stripping `models/` prefix, handling `"Auto"`).
2. **`SettingsManager.kt`**:
   - *Previous state*: `liveVoiceModelName` defaulted to `"gemini-3.1-flash-live-preview"`.
   - *Audit result*: Updated default to `"gemini-3.8-live"`.
3. **`SettingsUiState.kt`**:
   - *Previous state*: Legacy string default `"gemini-2.5-flash-native-audio-latest"`.
   - *Audit result*: Updated default to `"gemini-3.8-live"`.
4. **`ConnectionManager.kt`**:
   - Passes `settings.liveVoiceModelName` into `GeminiWebSocketClient(preferredModel = savedModel)`.
   - Verified intact and correctly propagating preferences.

### 2.2 Paired Windows Companion (`windows-agent`)
1. **`config.py`**:
   - *Previous state*: `GEMINI_MODEL = "gemini-3.1-flash-live-preview"`.
   - *Audit result*: Centralized to `GEMINI_MODEL = "gemini-3.8-live"`.
2. **`core/realtime_voice.py`**:
   - Dynamically reads `GEMINI_MODEL` from `config.py` into `types.LiveConnectConfig` and `client.aio.live.connect(model=GEMINI_MODEL)`.
3. **`scripts/find_bad_tool_schemas.py`**:
   - Updated validation test harness to `gemini-3.8-live`.

---

## 3. Protocol & Communication Specifications

### 3.1 Live API Setup Frame
The setup payload is transmitted immediately upon WebSocket `onOpen`:
```json
{
  "setup": {
    "model": "models/gemini-3.8-live",
    "generationConfig": {
      "responseModalities": ["AUDIO"],
      "speechConfig": {
        "voiceConfig": {
          "prebuiltVoiceConfig": {
            "voiceName": "Aoede"
          }
        }
      }
    },
    "inputAudioTranscription": {},
    "outputAudioTranscription": {},
    "systemInstruction": {
      "parts": [{ "text": "<System Prompt & Memory Context>" }]
    },
    "tools": [ { "functionDeclarations": [ ... ] } ]
  }
}
```

### 3.2 Audio Ingress & Egress
- **Ingress (Microphone)**: 16,000 Hz, 16-bit Linear PCM, 1 channel (mono). Payload format:
  ```json
  { "realtimeInput": { "audio": { "mimeType": "audio/pcm;rate=16000", "data": "<base64>" } } }
  ```
- **Egress (Speaker)**: 24,000 Hz, 16-bit Linear PCM, 1 channel (mono). Extracted from `serverContent.modelTurn.parts[].inlineData.data` and streamed into `AudioPlaybackManager`.
- **Keep-Alive**: 25-second silent PCM pulse sent during idle sessions to prevent upstream gateway timeouts.

### 3.3 Visual & Screen Streaming
- Real-time screenshot and vision frames are transmitted via:
  ```json
  { "realtimeInput": { "video": { "mimeType": "image/jpeg", "data": "<base64>" } } }
  ```

---

## 4. Tool Execution & Interruption Compatibility

### 4.1 Asynchronous Tool Calling
1. Model generates `toolCall.functionCalls`.
2. `GeminiWebSocketClient` matches tool name against `ToolDispatcher.supportedTools`.
3. `ToolDispatcher` maps parameters to normalized intent JSON.
4. Tool executes in background coroutine (`clientScope.launch(Dispatchers.IO)`).
5. Result is returned via clean `toolResponse` frame:
   ```json
   {
     "toolResponse": {
       "functionResponses": [
         {
           "id": "<callId>",
           "name": "<toolName>",
           "response": { "output": { ... } }
         }
       ]
     }
   }
   ```

### 4.2 Interruption & Barge-In
- When the user begins speaking while model is returning audio:
  - Cloud server sends `serverContent.interrupted: true`.
  - Client immediately calls `resetInputTranscriptionAccumulator()` to prevent previous turn transcript bleed.
  - `AudioPlaybackManager.stopAndFlush()` purges queued audio buffers instantly with zero audio artifacts.

---

## 5. Migration Safety & Fallback Matrix

| Component | Primary Target | Fallback Candidate | Failure Behavior |
|-----------|----------------|-------------------|------------------|
| **Model** | `gemini-3.8-live` | `gemini-3.1-flash-live-preview` | Automatic reconnect to fallback model candidate on code 1008 or failure |
| **Microphone** | 16kHz PCM Mono | Re-init AudioRecord | Failsafe recovery via `AudioStateGuard` |
| **Audio Playback** | 24kHz AudioTrack | Re-create AudioTrack | Immediate flush on barge-in |
| **Tool Execution** | Async Dispatch | Structured error JSON | Safe error response returned to Gemini to prevent hang |
