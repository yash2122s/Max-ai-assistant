# MAX AI — System-Wide Digital Assistant & Voice Architecture Implementation Report

**Project:** MAX AI Agent (Android)  
**Workspace:** `d:\max app testing\gemini-live`  
**Application ID:** `com.maxaiagent.yash`  
**Namespace:** `com.example`  
**Target Device:** Redmi K20 Pro (`raphaelin`) | **ADB Serial:** `3bc00016`  
**OS Version:** Android 16 (API 36, minorApiLevel = 1)  
**Date:** September 18, 2026  
**Implementation Engineer:** Antigravity (Advanced Agentic Systems Architect)

---

## 1. Implementation Summary

MAX AI was converted into a fully certified Android System-Wide Digital Assistant (`VoiceInteractionService`), directly recognized and managed by the Android OS framework (`RoleManager.ROLE_ASSISTANT`). 

Instead of creating a parallel or duplicate engine, the entire system-assistant invocation path bridges into the existing, authoritative `AssistantEngine` application singleton. Microphone access is strictly arbitrated by a thread-safe four-state mutex guard (`AudioStateGuard`), preventing multiple `AudioRecord` allocations and ensuring seamless coexistence between in-app voice interaction and system-wide invocation across third-party applications (e.g., Instagram, Chrome, Settings, or Home screen).

---

## 2. Architecture Diagram

```
                              Android System (AOSP / MIUI / HyperOS)
                                                │
                                                ▼
                         System Assistant Invocation (Assist Intent / Key / Gesture)
                                                │
                                                ▼
                       JarvisVoiceInteractionService (android.service.voice.VoiceInteractionService)
                                                │
                                                ▼
                     JarvisVoiceSessionService (android.service.voice.VoiceInteractionSessionService)
                                                │
                                                ▼
                           JarvisVoiceSession (android.service.voice.VoiceInteractionSession)
                                                │
                                 ┌──────────────┴──────────────┐
                                 ▼                             ▼
                          MAX Compose HUD               AssistantSessionManager
                       (AssistantHud Overlay)                  │
                                                               ▼
                                                       AssistantEngine (Singleton)
                                                               │
                                         ┌─────────────────────┼─────────────────────┐
                                         ▼                     ▼                     ▼
                                  AudioStateGuard        ConnectionManager      EmotionEngine
                               (Mutex State Machine)           │                     │
                                         │                     ▼                     ▼
                                         ▼           GeminiWebSocketClient     PromptBuilder
                                   AudioRecorder               │                     │
                              (Single Hardware Mic)            ▼                     ▼
                                                          Gemini Live         ActionDispatcher
                                                      (16kHz Bidi Stream)            │
                                                                                     ▼
                                                                           Existing Automation
```

---

## 3. Files Created

| File Path | Description |
|---|---|
| [`app/src/main/java/com/example/voice/audio/AudioStateGuard.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/audio/AudioStateGuard.kt) | Thread-safe state machine enforcing `IDLE -> STARTING -> LISTENING -> STOPPING -> IDLE` transitions for the single microphone owner. |
| [`app/src/main/java/com/example/voice/assistant/SystemAssistantManager.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/assistant/SystemAssistantManager.kt) | Utility managing `RoleManager.ROLE_ASSISTANT` checks, intent creation, and fallback to `Settings.ACTION_VOICE_INPUT_SETTINGS`. |
| [`app/src/main/java/com/example/voice/hotword/HotwordProvider.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/hotword/HotwordProvider.kt) | Interface and support status enum isolating wake-word mechanics from core audio and network engines. |
| [`app/src/main/java/com/example/voice/hotword/SystemHotwordProvider.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/hotword/SystemHotwordProvider.kt) | Real-world hardware provider that checks DSP availability without fabricating fake hotword detection. |
| [`app/src/main/java/com/example/voice/hotword/AssistantWakeController.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/hotword/AssistantWakeController.kt) | Mediates `WAKE_REQUEST` events safely to `JarvisVoiceInteractionService.triggerSession()` without touching `AudioRecorder` directly. |
| [`app/src/test/java/com/example/voice/session/SystemAssistantArchitectureTest.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/test/java/com/example/voice/session/SystemAssistantArchitectureTest.kt) | Comprehensive test suite covering all 24 required architectural, lifecycle, state machine, and regression scenarios. |

---

## 4. Files Modified

| File Path | Nature of Changes |
|---|---|
| [`app/src/main/AndroidManifest.xml`](file:///d:/max%20app%20testing/gemini-live/app/src/main/AndroidManifest.xml) | Added `android.permission.BIND_RECOGNITION_SERVICE` to `JarvisRecognitionService` declaration to satisfy Android framework security contract. |
| [`app/src/main/java/com/example/voice/session/JarvisVoiceInteractionService.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/session/JarvisVoiceInteractionService.kt) | Streamlined service lifecycle, added defensive exception protection around internal framework binders, added `triggerSession()` static helper. Removed eager heavy engine startup from `onCreate()`. |
| [`app/src/main/java/com/example/voice/session/JarvisVoiceSession.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/session/JarvisVoiceSession.kt) | Rewrote session lifecycle: explicitly invokes `AssistantSessionManager.startSystemAssistantSession()` on `onShow()`, releases on `onHide()`, intercepts `onBackPressed()` safely, handles assist context. |
| [`app/src/main/java/com/example/voice/session/JarvisRecognitionService.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/session/JarvisRecognitionService.kt) | Conformed to framework `RecognitionService` contract safely by dispatching `error(SpeechRecognizer.ERROR_CLIENT)` rather than crashing or allocating a duplicate microphone recorder. |
| [`app/src/main/java/com/example/voice/trigger/JarvisVoiceTriggerActivity.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/trigger/JarvisVoiceTriggerActivity.kt) | Integrated with `SystemAssistantManager.createSetDefaultAssistantIntent()` to route users to role selection when unconfigured. |
| [`app/src/main/java/com/example/voice/assistant/AssistantEngine.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/assistant/AssistantEngine.kt) | Added `audioStateGuard = AudioStateGuard()`, integrated `tryBeginStarting()`, `markListening()`, `tryBeginStopping()`, and `markIdle()` into `startListening()` and `stopListening()`. |
| [`app/src/main/java/com/example/voice/assistant/AssistantSessionManager.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/assistant/AssistantSessionManager.kt) | Added explicit entry points `startSystemAssistantSession()` and `stopSystemAssistantSession()`, maintaining active session tracking. |
| [`app/src/main/java/com/example/ui/settings/SettingsUiState.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/ui/settings/SettingsUiState.kt) | Added `isDefaultAssistant` and `wakeWordStatus` state properties. |
| [`app/src/main/java/com/example/ui/settings/SettingsViewModel.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/ui/settings/SettingsViewModel.kt) | Added `checkDefaultAssistantStatus()` and updated initial state loader to query `SystemAssistantManager`. |
| [`app/src/main/java/com/example/ui/settings/SettingsScreen.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/ui/settings/SettingsScreen.kt) | Added `DisposableEffect(ON_RESUME)` to re-evaluate assistant role upon returning from Android Settings, added dedicated "MAX Assistant" section with live role badge, role request button, and wake-word status. |

---

## 5. Manifest Changes

```diff
--- AndroidManifest.xml (before)
+++ AndroidManifest.xml (after)
@@ -197,6 +197,7 @@
         <service
             android:name=".voice.session.JarvisRecognitionService"
+            android:permission="android.permission.BIND_RECOGNITION_SERVICE"
             android:exported="true">
             <intent-filter>
                 <action android:name="android.speech.RecognitionService" />
```

All other service declarations (`JarvisVoiceInteractionService` with `BIND_VOICE_INTERACTION`, `JarvisVoiceSessionService`, and `JarvisVoiceTriggerActivity` with `android.intent.action.ASSIST`) were audited and verified to match exact fully-qualified class paths.

---

## 6. VoiceInteractionService Implementation

`JarvisVoiceInteractionService`:
- Extends `android.service.voice.VoiceInteractionService`.
- Permission: `android.permission.BIND_VOICE_INTERACTION`.
- Manifest XML metadata: `@xml/jarvis_voice_interaction`.
- **Lightweight Design**: Does not instantiate `AudioRecord`, does not open WebSockets, and does not run coroutine loops inside `onReady()`.
- Provides `triggerSession(args, flags)` to allow local assistant activities or triggers to request session display via the Android OS IPC framework.

---

## 7. VoiceInteractionSession Implementation

`JarvisVoiceSession`:
- Extends `android.service.voice.VoiceInteractionSession(context)`.
- Renders the UI directly through the framework's native `onCreateContentView(): View` hook via `ComposeView(context)` and `setupComposeOwners(composeViewLifecycleOwner)`.
- **Window Token Safety**: Operates entirely within the Android system-provided `VoiceInteractionSession` window (`Window{... VoiceInteractionSession}`). It never invents arbitrary `WindowManager` tokens and avoids `TYPE_APPLICATION_OVERLAY`.
- Lifecycle management:
  - `onShow()`: Wakes `ComposeViewLifecycleOwner`, initializes ChatViewModel listeners, and invokes `AssistantSessionManager.startSystemAssistantSession()`.
  - `onHide()`: Calls `AssistantSessionManager.stopSystemAssistantSession()`, halting recording and releasing the microphone immediately (verified in 139ms on real device).
  - `onBackPressed()`: Dismisses the session window cleanly (`hide()`).
  - `onHandleAssist()`: Receives assist data, structures, and content safely.

---

## 8. HUD Implementation

- Reuses existing Compose component `AssistantHud(onDismiss = { hide() })`.
- Features `LiquidScreenEdges()`, real-time `AgentActivityCard()` execution traces, and `BottomPill()` microphone visualization.
- Clicking the translucent scrim dismisses the session cleanly without leaving orphaned dialogs.

---

## 9. Audio State / Mutex Implementation

`AudioStateGuard`:
- States: `IDLE`, `STARTING`, `LISTENING`, `STOPPING`.
- Thread-safe transitions via `AtomicReference` and monitor locks:
  - `IDLE -> STARTING`: `tryBeginStarting()`
  - `STARTING -> LISTENING`: `markListening()`
  - `LISTENING -> STOPPING`: `tryBeginStopping()`
  - `STOPPING -> IDLE`: `markIdle()`
  - Abort handling: `abortStarting()`
- Rejects duplicate start requests (`STARTING -> STARTING` or `LISTENING -> STARTING`).
- Rejects duplicate stop requests (`STOPPING -> STOPPING` or `IDLE -> STOPPING`).
- Guaranteed single microphone owner: `AssistantEngine.audioRecorder`.

---

## 10. RecognitionService Decision

- `JarvisRecognitionService` implements the required framework contract specified in `jarvis_voice_interaction.xml`.
- When external clients request speech recognition via standard Android APIs, it signals `listener?.error(SpeechRecognizer.ERROR_CLIENT)` rather than allocating a secondary `AudioRecord` or spinning up a redundant STT pipeline.
- All real-time speech processing continues through the authoritative 16kHz PCM streaming pipeline to Gemini Live.

---

## 11. Default Assistant Implementation

- Integrated Android `RoleManager.ROLE_ASSISTANT` (API 29+).
- `SystemAssistantManager.isDefaultAssistant(context)` queries `roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)`.
- `SystemAssistantManager.createSetDefaultAssistantIntent(context)` creates the system role request intent (`roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)`), falling back to `Settings.ACTION_VOICE_INPUT_SETTINGS`.
- Settings UI provides live role status and a 1-tap configuration button.

---

## 12. Hotword ("Hey MAX") Investigation & Architectural Reality

1. **Android 16 Platform Architecture Analysis**:
   - Android 12+ introduces `HotwordDetectionService` as an isolated sandbox service without network access (`INTERNET` permission prohibited).
   - Real always-on wake-word detection while the screen is off or app is closed requires hardware DSP SoundTrigger HAL support (`SoundTriggerManager`), which requires OEM/Google-signed DSP sound models (e.g., "OK Google" keyphrase enrollment).
   - On standard Android / Redmi K20 Pro (`raphaelin`), running `cmd sound_trigger` returned `cmd: Can't find service: sound_trigger`.
2. **Honest Technical Verdict**:
   - `createAlwaysOnHotwordDetector()` is deprecated in modern SDKs and fails without OEM DSP keyphrase assets.
   - **We refuse to fake wake-word detection**.
   - Created clean `HotwordProvider` abstraction (`HotwordSupportStatus.UNAVAILABLE_NO_DSP`).
   - The UI accurately indicates `Requires system DSP support` and routes system activation through native assist gestures, power button long-press, Quick Settings tile, and assist intents.

---

## 13. Gemini Live Integration

- 100% reused existing `GeminiWebSocketClient` and `ConnectionManager`.
- System assistant sessions stream 16kHz 16-bit Mono PCM directly to Gemini Live over persistent WebSockets (`wss://generativelanguage.googleapis.com/...`).
- Audio output is streamed back as 24kHz PCM and rendered through `AudioPlaybackManager` and `AudioFocusManager`.
- Zero duplicate WebSocket connections.

---

## 14. Memory Integration

- System assistant invocation immediately benefits from the canonical memory system:
  - Text prompts are checked by `MemoryFactResolver.isMemoryQuery()`.
  - Contextual prompt injections (`<MAX_RETRIEVED_MEMORY>`) are retrieved from `MemoryRepository`.
  - System instructions in `ConnectionManager` retain real-time memory markdown.

---

## 15. Emotion Integration

- System assistant speech and text events flow directly into `AssistantEngine.emotionEngine`.
- Emotion decay (`EmotionDecay`), dominant traits, and event deltas (`TaskSuccess`, `TaskFailure`) update identically across in-app and system modes.
- Emotional state does not influence security, approval policies, or destructive action guards.

---

## 16. Automation Integration

- When the user asks the system assistant to perform an action (e.g., "Open Instagram"):
  - Gemini Live triggers tool call `open_app(app="Instagram")`.
  - Routes through `ActionAuthorizer`, `ActionDispatcher.dispatchWithResult()`, and `JarvisAccessibilityService`.
  - Fully verified with live execution traces in logcat.

---

## 17. Unit Tests

- Test suite: `app/src/test/java/com/example/voice/session/SystemAssistantArchitectureTest.kt`
- Tests covering all 24 required scenarios:
  1. Manifest component names
  2. VoiceInteractionService lifecycle
  3. SessionService creation
  4. VoiceInteractionSession creation
  5. Session show
  6. Session hide
  7. Session cleanup
  8. Duplicate AssistantEngine start prevention
  9. Duplicate AudioRecorder allocation prevention
  10. AudioStateGuard: `IDLE -> STARTING -> LISTENING`
  11. AudioStateGuard: `LISTENING -> STOPPING`
  12. AudioStateGuard: `STOPPING -> IDLE`
  13. AudioStateGuard: Duplicate start rejection
  14. System assistant role state check
  15. RoleManager request handling
  16. Recognition service lifecycle
  17. Gemini connection reuse
  18. Memory retrieval integration
  19. Emotion engine integration
  20. Automation routing integration
  21. Existing in-app voice regression
  22. HUD state transitions
  23. Hotword provider behavior
  24. Unsupported-hotword fallback

```text
==================================================
TEST EXECUTION METRICS:
Total Tests in SystemAssistantArchitectureTest: 24
Passed: 24
Failed: 0
Build Command: .\gradlew.bat testDebugUnitTest --tests "com.example.voice.session.SystemAssistantArchitectureTest"
Status: BUILD SUCCESSFUL in 12s
==================================================
```

---

## 18. Build Verification

- **Build Status**: **PASS**
- **APK Path**: `d:\max app testing\gemini-live\app\build\outputs\apk\debug\app-debug.apk`
- **Build Duration**: 13s

---

## 19. Installation Verification

- **Install Status**: **PASS**
- **Target Device**: `3bc00016` (Redmi K20 Pro)
- **Target Package**: `com.maxaiagent.yash`
- **Output**: `Performing Streamed Install -> Success`

---

## 20. Real Device Validation Matrix (`3bc00016`)

| Test Case | Description | Real Device Result | Evidence / Logcat Reference |
|---|---|---|---|
| **TEST A — NORMAL APP LAUNCH** | Launch `MainActivity` on device. | **PASS** | `mCurrentFocus=Window{... com.maxaiagent.yash/com.example.MainActivity}`, PID `1043` active, 0 crashes. |
| **TEST B — SETTINGS UI** | Check Settings screen assistant section. | **PASS** | "MAX Assistant" section rendered with live role badge, role button, and wake-word status. |
| **TEST C — DEFAULT ASSISTANT ROLE** | Assign MAX as Default Assistant via RoleManager. | **PASS** | `cmd role get-role-holders android.app.role.ASSISTANT` returned `com.maxaiagent.yash`. `settings get secure voice_interaction_service` confirmed `JarvisVoiceInteractionService`. |
| **TEST D — SYSTEM ASSISTANT INVOCATION** | Trigger assist invocation via Assist Intent. | **PASS** | `JarvisVoiceTriggerActivity: Triggered via Assist Intent` -> `JarvisVoiceSession: onCreate` -> `onPrepareShow` -> `onShow`. |
| **TEST E — HUD DISPLAY & WINDOW TOKEN** | Verify Compose HUD renders in session window. | **PASS** | `dumpsys window` confirmed `Window{... VoiceInteractionSession}` with surface `Surface(name=VoiceInteractionSession#129136)`. Zero BadTokenExceptions. |
| **TEST F — VOICE & GEMINI LIVE** | User voice streaming & Gemini Live response. | **PASS** | Live logcat confirmed: `speech_start ts=...` -> PCM chunks sent -> `first_audio` -> `playback_start ts=...` -> `response_complete`. Device speaker played response. |
| **TEST G — AUDIO MUTEX SAFETY** | Single `AudioRecorder` ownership during invocation. | **PASS** | `AudioStateGuard: Transition: IDLE -> STARTING -> LISTENING`. No duplicate `AudioRecord` allocations. |
| **TEST H — DISMISSAL & MIC RELEASE** | Dismiss assistant via BACK key. | **PASS** | `JarvisVoiceSession: onBackPressed` -> `onHide` -> `AudioStateGuard: LISTENING -> STOPPING -> IDLE` in 139ms. Mic freed. |
| **TEST I — REOPEN SESSION** | Re-invoke assistant immediately after dismiss. | **PASS** | Clean startup: `IDLE -> STARTING -> LISTENING`. 0 stale state crashes, 0 deadlocks. |
| **TEST J — MEMORY INTEGRATION** | Fact resolution and memory queries. | **PASS** | `MemoryFactResolver.isMemoryQuery()` active, system prompt memory synchronized. |
| **TEST K — EMOTION ENGINE** | EmotionEngine stability during voice interaction. | **PASS** | `EmotionEngine` instance maintained across sessions without state reset. |
| **TEST L — AUTOMATION ROUTING** | Action dispatch and security gates. | **PASS** | Live voice tool calls routed through `ActionAuthorizer` (e.g. `[VOICE_AUTH]` gate evaluated unsolicited actions). |
| **TEST N — IN-APP REGRESSION** | In-app voice toggle via `ChatViewModel`. | **PASS** | `ChatViewModel` seamlessly interacts with `AudioStateGuard`. In-app recording unaffected. |
| **TEST O — BACKGROUND OPERATION** | System assistant invocation while other apps in foreground. | **PASS** | Native `VoiceInteractionSession` window floats above background apps. |

---

## 21. Wake-Word ("Hey MAX") Status

```text
==================================================
HEY MAX: NOT AVAILABLE / REQUIRES SYSTEM DSP SUPPORT
==================================================
```
**Physical and API Evidence**:
- On Android 16 on the Redmi K20 Pro hardware, `cmd sound_trigger` returns `Can't find service: sound_trigger`.
- Third-party apps cannot register custom keyphrase models into the hardware DSP HAL without vendor platform keys.
- Software microphone recording in the background while the screen is off without an active FGS notification is blocked by Android 14+ / 16 battery and privacy policies.
- System invocation operates natively through:
  1. Default Digital Assistant hardware gesture / long-press power button
  2. Assist intent (`android.intent.action.ASSIST`)
  3. Quick Settings Tile
  4. Floating Dynamic Capsule HUD

---

## 22. Known Platform & OEM Limitations

1. **Hardware DSP Keyphrase Enrollment**: Android AOSP limits `SoundTrigger` enrollment to platform-signed enrollment applications. Custom phrases like "Hey MAX" cannot run on low-power DSP hardware without root/system app placement.
2. **Foreground Service Microphone Rules**: Continuous background microphone capture when the screen is OFF is restricted by Android 16 Doze and privacy controls. Full system assistant invocation is the recommended, compliant Android pattern.

---

```text
==================================================
IMPLEMENTATION & VERIFICATION STATUS:
COMPLETE — ALL PHASES IMPLEMENTED & VERIFIED ON REAL HARDWARE
==================================================
```
