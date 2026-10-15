# MAX Full Codebase Audit

**Date:** September 25, 2026  
**Auditor:** Antigravity Forensic Engineering  
**Mode:** AUDIT ONLY (No production code modified, deleted, or refactored)  
**Repository Commit:** `0f37553`  
**Test Verification Status:** 729 / 729 Android Robolectric Unit Tests Passed (`BUILD SUCCESSFUL in 3m 19s`)

---

## 2026-10-02 Audit Addendum — Current Worktree Forensic Review

This addendum supersedes optimistic “working” labels where current source evidence disagrees. It was performed in audit-only mode; no source implementation was made for this audit. The worktree already contained unrelated modifications before this audit, which were preserved.

### Executive verdict

MAX has real Gemini Live, Compose UI, tools, accessibility, scheduler, memory, Firebase entitlement, and computer-use infrastructure. It is **not production-ready for arbitrary long-horizon autonomous control** because execution truth is fragmented, interactive plans are mostly in memory, approval is detached from parent plans, and privileged paths lack consistent final verification.

### Critical findings

1. `ApprovalCoordinator` stores one process-global pending payload only. It does not retain parent goal, remaining steps, task ID, or step ID. `AssistantEngine`, `HomeViewModel`, and `ChatViewModel` each launch post-approval dispatch and ignore the returned result.
2. `AgentOrchestrator.processUserPrompt()` direct-fast-path branch records an action and returns “Fast-path executed tool ...” without invoking a tool.
3. Completion is declared by multiple independent models: `ExecutionEngine`, `LongTaskOrchestrator`, `ComputerUseEngine`, `AgentTask.advance()`, scheduler `TaskManager`, `AgentActivityTracker`, ViewModels, and model prose.
4. Active plan state (`GoalPlanner`, `WorldStateManager`, approval, accessibility pending task) is process-local; process/service death loses or disconnects work.
5. Unlock automation invokes completion callbacks after fallbacks/delays without checking keyguard state.
6. The requested workflow exceeds the 60-second plan/computer-use budgets because it contains two explicit 30-second waits plus additional app transitions and verification.
7. `usesCleartextTraffic=true`, release minification is disabled, and the application requests a broad privileged surface.

### End-to-end trace summary

Input reaches `HomeViewModel`/`AssistantEngine`, then can be intercepted by quota, memory, offline, deterministic parser, Gemini Live, or tool routing. `ToolDispatcher` maps model calls to `ActionDispatcher`; plans enter `GoalPlanner`/`LongTaskOrchestrator`; tools use `ExecutionEngine`, accessibility, intents, Shizuku, or computer-use. Observation/verifier code exists, but the parent plan and approval checkpoint are not durably persisted. Final chat/UI state can therefore outlive, detach from, or contradict actual execution.

### Multitasking verdict

Scheduled database tasks can coexist as records, but true concurrent interactive tasks are not supported safely. Global planner/world/approval state and the device-control lock prevent independent contexts. See `MAX_MULTITASKING_AUDIT.md`.

### Required priority

Fix execution truth and task-scoped approval before expanding features. Preserve the current GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine pipeline; add persistence, correlation, evidence, and tests incrementally. See `MAX_CRITICAL_FINDINGS.md` and `MAX_PHASED_ROADMAP.md`.

### Audit limitations

Static inspection cannot prove OEM UI behavior, model quality, latency percentiles, or the screenshot-specific runtime reproduction. Those require controlled emulator/device traces and failure-injection tests.

## 1. Executive Technical Overview

MAX is a dual-tier autonomous assistant consisting of:
1. **Android Native OS Host (`com.example` / `com.maxaiagent.yash`):** A Kotlin & Jetpack Compose Android 14+ application designed as a default System Assistant, featuring real-time multimodal bidirectional streaming with Google Gemini Live (`gemini-3.8-live` & `gemini-3.8-live-extended-thinking`), an Accessibility-driven Computer-Use Engine, an offline cryptographic license verification system with Firebase Cloud Sync, and a 60 FPS Dynamic HUD overlay.
2. **Windows Desktop Agent (`windows-agent/`):** A Python 3.10+ async daemon exposing WebSocket/HTTP servers, integrating Windows UI Automation (UIA), Win32 API, PyAutoGUI, Gemini Realtime Voice, and an MCP (Model Context Protocol) tool runtime.

### Reality vs Declaration Summary
- **Working Core:** Android Gemini Live bidirectional audio streaming, native tool routing (`ActionDispatcher`), YouTube automation, offline contact & phone control, dynamic HUD capsule overlays, and cryptographic offline licensing operate as implemented.
- **Partial / Brittle:** WhatsApp and Telegram automations rely on accessibility node text heuristics that break on WhatsApp dynamic DOM or UI layout updates; Windows companion pairing relies on hardcoded local network IP defaults (`192.168.0.152:9000`) before manual config.
- **Disconnected / Dead Code:** Several older intent receivers and unreferenced Room tables exist from previous prototypes. The Windows pytest suite fails on missing optional media dependencies (`pypdf`, `cv2`) and strict window activation assertions when Notepad is not physically focused.

---

## 2. Repository Inventory

### Codebase Metrics
- **Android Kotlin / Java Classes:** 512 source files across 19 packages.
- **Windows Python Modules:** 1,646 files (excluding `.venv` and build artifacts).
- **Test Suites:** 91 test classes in Android (729 test methods, 0 failures); 85+ test files in Windows agent.

### Package Inventory

| Package / Module | Purpose | Active Status | Primary Callers | Key Dependencies |
| :--- | :--- | :--- | :--- | :--- |
| `com.example` | App lifecycle & MainActivity entry point | **WORKING** | Android OS | Compose, Haptics |
| `com.example.network` | Gemini Live WebSocket client & ToolDispatcher | **WORKING** | `AssistantEngine` | OkHttp, Gson |
| `com.example.network.router` | Gemini 3.8 Dual Model Auto/Fast/Deep router | **WORKING** | `GeminiWebSocketClient` | Telemetry, Regex |
| `com.example.network.agent` | Windows Companion WebSocket client | **PARTIAL** | `WindowsAgentTool` | OkHttp, TLS |
| `com.example.automation.engine` | ActionDispatcher & GoalPlanner | **WORKING** | `ToolDispatcher`, `TaskReceiver` | Coroutines, JSON |
| `com.example.automation.computeruse` | Autonomous UI State Machine | **WORKING** | `ActionDispatcher` | AccessibilityService |
| `com.example.automation.agent` | Multimodal UI reasoning loop (OpenRouter / Gemini) | **WORKING** | `UiAgentTool` | OkHttp, Base64 |
| `com.example.service` | Accessibility, Overlay HUD, Background Services | **WORKING** | Android OS, `MaxRuntimeManager` | WindowManager |
| `com.example.voice.assistant` | Voice session, Audio recorder/player | **WORKING** | `ChatViewModel`, Overlay | AudioRecord, AudioTrack |
| `com.example.firebase` | Auth, AppCheck, Firestore licensing | **WORKING** | `MyApplication`, `LicenseViewModel` | Firebase SDK |
| `com.example.license` | Cryptographic offline license verification | **WORKING** | `MaxLicenseManager` | Tink, SHA-256 |
| `com.example.memory` | Room DB memories, vector facts | **WORKING** | `AssistantEngine`, `ChatViewModel` | Room SQLite |
| `com.example.rag` | Local & Gemini Embedding RAG engine | **PARTIAL** | `ChatViewModel` | Room, CosineSim |
| `com.example.ui` | Jetpack Compose iOS Luxury Themed UI | **WORKING** | `MainActivity` | Material3, Compose |
| `windows-agent/core` | Windows UIA, PyAutoGUI, Audio loop | **PARTIAL** | `main.py` | PyWin32, Google GenAI |
| `windows-agent/server` | WebSocket & HTTP Companion server | **WORKING** | `main.py` | websockets, aiohttp |

---

## 3. Real Architecture

```
                                      ┌────────────────────────────────────────────────────────┐
                                      │                      USER / SENSORS                    │
                                      └──────────────────────────┬─────────────────────────────┘
                                                                 │
                                          ┌──────────────────────┴──────────────────────┐
                                          ▼                                             ▼
                                  [Touch / Text Chat]                         [Microphone Voice / VAD]
                                          │                                             │
                                          ▼                                             ▼
                                     ChatViewModel                               AudioRecorder
                                          │                                             │
                                          └──────────────────────┬──────────────────────┘
                                                                 ▼
                                                       AssistantEngine
                                                                 │
                                                                 ▼
                                                       ModelRouter (Gemini 3.8)
                                                     /                        \
                                     (Conversational)                          (Deep Reasoning)
                                     gemini-3.8-live                 gemini-3.8-live-extended-thinking
                                                     \                        /
                                                      ▼                      ▼
                                                    GeminiWebSocketClient (OkHttp)
                                                                 │
                                                                 ▼ (Tool Call Received)
                                                          ToolDispatcher
                                                                 │
                                                                 ▼
                                                         ActionDispatcher
                                        ┌────────────────────────┼────────────────────────┐
                                        ▼                        ▼                        ▼
                               Direct Native Tools     ComputerUseEngine (CU)      WindowsAgentTool
                               (YouTube, Phone, Flash)           │                        │
                                        │                        ▼                        ▼
                                        │               JarvisAccessibility      WindowsAgentClient
                                        │               (Gesture / Node Tree)    (Local Network WS)
                                        │                        │                        │
                                        └────────────────────────┼────────────────────────┘
                                                                 ▼
                                                        VerificationEngine
                                                                 │
                                                                 ▼
                                                        AssistantEventBus
                                                                 │
                                        ┌────────────────────────┴────────────────────────┐
                                        ▼                                                 ▼
                              DynamicHudOverlayView                             AudioPlayer / Speaker
                             (60 FPS Pill / Edge Light)                         (24kHz PCM Audio Stream)
```

---

## 4. Runtime Startup Flow

1. **OS Process Creation:** `com.example.MyApplication.onCreate()` is called.
2. **Tool Registry Initialization:** `MyApplication` instantiates 25+ tool classes, registering them into `ToolRegistry` and `VerificationRegistry`.
3. **Firebase & Licensing Initialization:** `FirebaseInitHelper.initialize(this)` sets up AppCheck (Play Integrity or Debug Provider) and instantiates `MaxLicenseManager`.
4. **License State Cache Check:** `MaxLicenseManager` queries `FirebaseLicenseCacheDao`. If a cached or offline signed license exists, features are unlocked immediately without blocking network calls.
5. **Runtime Manager Initialization:** `MaxRuntimeManager.initialize(context)` reads `isMaxEnabled` from `SettingsManager`.
6. **MainActivity Launch:** Jetpack Compose `setContent` displays `MaxNavGraph`. If user is unauthenticated, redirects to `OnboardingScreen`; otherwise loads `HomeScreen`.
7. **Accessibility Binding:** When the user enables the service in Android Settings, `JarvisAccessibilityService.onServiceConnected()` binds `JarvisAccessibilityService.instance` and registers with `ServiceRegistry`.

---

## 5. Readiness Architecture

- **State Owner:** `com.example.core.lifecycle.MaxRuntimeManager`.
- **Global Kill-Switch:** When `isMaxEnabled` is toggled to `false`, `MaxRuntimeManager` terminates running coroutines, suspends accessibility actions, releases the microphone, and dismisses the HUD overlay.
- **Subsystem Readiness Isolation:**
  - Gemini Live: Lazily connects when voice session starts or mic button is pressed.
  - Windows Agent: Runs independently; does not block Android app startup if desktop companion is unreachable.
  - Accessibility: Functions conditionally. If permission is revoked, native Intent tools (YouTube, Settings) still operate via direct Android Intents.

---

## 6. Complete Component Map

| Component | File Path | Scope | Active Callers | Status |
| :--- | :--- | :--- | :--- | :--- |
| `GeminiWebSocketClient` | `app/.../network/GeminiWebSocketClient.kt` | Singleton per session | `ConnectionManager` | **WORKING** |
| `ModelRouter` | `app/.../network/router/ModelRouter.kt` | Stateless | `GeminiWebSocketClient` | **WORKING** |
| `ToolDispatcher` | `app/.../network/ToolDispatcher.kt` | Singleton | `GeminiWebSocketClient` | **WORKING** |
| `ActionDispatcher` | `app/.../automation/engine/ActionDispatcher.kt` | Singleton | `ToolDispatcher`, `TaskReceiver` | **WORKING** |
| `JarvisAccessibilityService`| `app/.../service/JarvisAccessibilityService.kt` | System Service | Android OS, `ComputerUseEngine` | **WORKING** |
| `ComputerUseEngine` | `app/.../automation/computeruse/ComputerUseEngine.kt` | Singleton | `ActionDispatcher` | **WORKING** |
| `YoutubeAutomation` | `app/.../automation/YoutubeAutomation.kt` | Object | `YoutubeTool` | **WORKING** |
| `MaxLicenseManager` | `app/.../firebase/manager/MaxLicenseManager.kt` | App-scoped | `MyApplication`, `LicenseViewModel`| **WORKING** |
| `LicenseVerifier` | `app/.../license/licensing/LicenseVerifier.kt` | Security Engine| `MaxLicenseManager` | **WORKING** |
| `MaxCapsuleOverlayService` | `app/.../service/MaxCapsuleOverlayService.kt` | Foreground Service | `HomeViewModel`, `AssistantEngine` | **WORKING** |
| `DynamicHudOverlayView` | `app/.../ui/overlay/hud/DynamicHudOverlayView.kt` | View | `MaxCapsuleOverlayService` | **WORKING** |
| `WindowsAgentClient` | `app/.../network/agent/WindowsAgentClient.kt` | Network Client | `WindowsToolExecutor` | **PARTIAL** |
| `ActionAuthority` | `windows-agent/core/computer_use/action_authority.py`| Action Router | Windows `ComputerUseEngine` | **WORKING** |
| `UnifiedToolRegistry` | `windows-agent/core/unified_tool_registry.py` | Tool Engine | Windows `RealtimeVoice`, WebSocket | **WORKING** |

---

## 7. Complete Feature Matrix

| Feature | User Entry Point | Main Execution Pipeline | Current Status | Evidence / Verification |
| :--- | :--- | :--- | :--- | :--- |
| **Voice Chat (Live)** | Mic Button / Assistant Trigger | `AudioRecorder` → `GeminiWebSocketClient` → `AudioPlayer` | **WORKING** | Tested via `GeminiWebSocketClientUnitTest` |
| **Gemini 3.8 Dual Routing** | Voice prompt / Auto heuristic | `ModelRouter` → `GeminiLiveModels.FAST`/`DEEP` | **WORKING** | Verified in `GeminiModelRouterTest` |
| **YouTube Playback** | Voice "Play [query] on YouTube"| `YoutubeTool` → `YoutubeAutomation.searchAndPlay` | **WORKING** | Verified in `YoutubeAutomationUnitTest` |
| **Android Computer Use** | Goal prompt / Complex action | `ComputerUseEngine` → `JarvisAccessibilityService` | **WORKING** | Monitored via `ActionVerifier` |
| **WhatsApp Automation** | Voice "Send WhatsApp to X" | `WhatsAppTool` → `WhatsAppContactResolver` | **PARTIAL** | Lacks dynamic DOM update resilience |
| **Telegram Automation** | Voice / Remote Bot | `TelegramBotService` / `TelegramTool` | **PARTIAL** | Polling works; text UI matching fragile |
| **Flashlight / System** | Voice / Quick Actions | `SystemTool` / `FlashlightTool` | **WORKING** | Direct Camera2 / AudioManager APIs |
| **Scheduled Tasks** | Voice "Remind me in 10 mins" | `TaskManager` → `AlarmScheduler` → `TaskReceiver` | **WORKING** | Verified via Room persistence |
| **Memory Fact Storage** | Voice / Text | `SaveMemoryTool` → `MemoryRepository` → Room DB | **WORKING** | Verified via `UniversalMemoryImportUnitTest` |
| **Offline Licensing** | License Screen / File import | `LicenseVerifier` → Ed25519 / Tink Verification | **WORKING** | Verified in `LicenseVerifierTest` |
| **Firebase Cloud License** | Google Sign-In | `MaxLicenseManager` → Firestore → Room Cache | **WORKING** | Verified in `FirebaseLicensingUnitTest` |
| **HUD Dynamic Overlay** | Background / Home trigger | `MaxCapsuleOverlayService` → `DynamicHudOverlayView` | **WORKING** | Verified in `DynamicCapsuleOverlayUnitTest` |
| **Windows Desktop Link** | Windows Tool command | `WindowsAgentTool` → `WindowsAgentClient` (WS:9000) | **PARTIAL** | Works when IP is manually configured |

---

## 8. Traced Pipelines

### 8.1 Voice Input -> Real-time Audio Stream
`AudioRecorder.startRecording()` (16kHz 16-bit PCM)  
→ Coroutine buffer dispatch  
→ `GeminiWebSocketClient.sendRealtimeMedia(audioData)`  
→ Base64 JSON encapsulation to Gemini Live WebSocket  
→ Gemini model processes turn  
→ `onAudioReceived` receives raw 24kHz PCM chunks  
→ `StreamingResponseManager.handleIncomingAudio()`  
→ `AudioPlayer.playChunk()` (Low-latency Android AudioTrack)

### 8.2 Tool Execution Path
Gemini WebSocket receives `serverContent.modelTurn.parts.functionCall`  
→ `ToolDispatcher.dispatch(functionCall)`  
→ Normalizes JSON arguments  
→ `ActionDispatcher.execute(actionJson)`  
→ `ActionAuthorizer.authorizeAction()` verifies permissions and safety gating  
→ Calls targeted tool (`YoutubeTool`, `ComputerUseTool`, `WhatsAppTool`)  
→ `RuntimeStepVerifier` validates target state  
→ Result string returned to `GeminiWebSocketClient.sendFunctionResponse()`  
→ Model incorporates tool execution result into conversational voice reply

---

## 9. Failure & Recovery Audit

| Failure Scenario | Propagation / Handling | Result |
| :--- | :--- | :--- |
| **Gemini WebSocket Drop** | `GeminiWebSocketClient` exponential backoff reconnect | Isolated; attempts reconnection without crashing service |
| **Accessibility Revoked** | `JarvisAccessibilityService.isServiceRunning()` returns `false` | ActionDispatcher intercepts and returns graceful error toast |
| **Tool Execution Exception** | Handled in tool `try/catch` and returned as `ToolResult.failure` | Gemini receives error message and informs the user via speech |
| **Network Loss in Licensing** | `MaxLicenseManager` checks Room cache `FirebaseLicenseCacheEntity` | Operates offline using signed cache payload |
| **Computer-Use Loop Stuck** | Consecutive no-op counter detects repeated failures | Dispatches `KEY_BACK` self-healing escape |

---

## 10. Dead Code & Incomplete Features

1. **`MAX_DATA_BACKUP/` (Purged from Git index):** 62 historical JSON/CSV files and old test WAL dumps are preserved locally in `.gitignore` but deleted from the active git repository.
2. **Unused Dependencies in `app/build.gradle.kts`:** Commented out entries for `coil.compose`, `camera2`, and `accompanist.permissions`.
3. **Windows Pytest Collection Failures:** Tests in `windows-agent/tests/test_universal_file_tool.py` and `test_webcam_tool.py` fail collection if `pypdf` and `cv2` are missing from the virtual environment.

---

## 11. Security Audit

- **Secrets Handling:** Complies with rules. Neither API keys nor Firebase credentials are hardcoded in tracked files.
- **Firebase Service Credentials:** Excluded by `.gitignore` (`*serviceAccountKey*.json`).
- **Authorization Gating:** `ActionAuthorizer` enforces strict blocking on high-risk keywords (`UPI`, `PIN`, `PASSWORD`, `PAYMENT`, `FACTORY_RESET`, `UNLOCK`).

---

## 12. Final Technical Assessment

1. **What MAX actually is today:** A dual-platform AI assistant system with an advanced native Android client featuring live multimodal Gemini 3.8 voice streaming and accessibility-based UI automation, paired with a companion Windows agent.
2. **What genuinely works:**
   - 100% of the 729 Android unit tests pass.
   - Real-time Gemini Live voice conversations with dynamic model handoffs.
   - YouTube direct video searching and automated playback.
   - System controls: Flashlight, Volume, Brightness, DND, Bluetooth, App launching.
   - Dynamic 60 FPS Floating HUD and Edge Lighting overlays.
   - Cryptographic offline licensing and Firebase cloud synchronization.
3. **What is fragile / requires human attention:**
   - WhatsApp / Telegram automations need regular maintenance when third-party app layouts change.
   - Windows desktop link auto-discovery across subnets requires manual IP configuration.
4. **Stable Core:** `AssistantEngine`, `GeminiWebSocketClient`, `ToolDispatcher`, `ActionDispatcher`, and `AppDatabase` are stable and must be protected.
