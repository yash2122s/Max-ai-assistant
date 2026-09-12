# MAX AI — SENIOR ARCHITECTURE MIGRATION & STABILITY PLAN

**Document Version:** 3.1.0-REFINED  
**Status:** APPROVED & IN EXECUTION  
**Author:** MAX Senior Flutter + Kotlin Android Architecture Team  
**Target Codebase:** MAX (`gemini-live`)  

---

## 1. Executive Summary & Architectural Refinements

MAX is an autonomous AI assistant for Android combining real-time multimodal intelligence (Gemini Live over WebSocket) with physical Android device automation (Accessibility, Gestures, Shizuku, Vision/Screen Reasoning).

### Key Architectural Refinements:
1. **Pragmatic Capability Boundaries (No Bridge Over-Engineering):**
   Instead of fragmenting into 10+ distinct `MethodChannel` instances, we organize Flutter services into logical domain services backed by cohesive platform capability delegates:
   ```
   Flutter
   │
   ├── RuntimeService      (Coordinates MAX ON/OFF state, lifecycle, session)
   ├── AutomationService   (Executes UI tasks, routines, action dispatcher)
   ├── MemoryService       (Queries and updates Memory Vault)
   ├── PeriodService       (Queries and updates cycle tracker & stats)
   └── Platform
         ├── Accessibility (Gestures, tree snapshots, click/scroll)
         ├── Device        (Hardware toggles, PC companion, screenshot)
         ├── Permissions   (OS runtime permissions & settings intents)
         ├── Communication (Calls, SMS, Contacts lookup)
         └── Notifications (Notification listener & app toggles)
   ```
2. **Dedicated Runtime Ownership Contract (Fixing MAX OFF):**
   `MAX OFF ≠ AccessibilityService disabled at OS level`  
   `MAX OFF = MAX-owned runtime work stopped`  
   Every owned resource (`Gemini session`, `Voice capture`, `AI streams`, `Automation loop`, `Timers`, `Workers`, `Reconnect jobs`, `Foreground overlays`) implements explicit `START`, `OWNER`, `STOP`, `CANCEL`, `RESTART`, and `STATUS` contracts.
3. **Provider-Level UI Agent Fallback:**
   ```
   UiAgentProvider (Contract)
          │
          ├── OpenRouterUiAgentProvider (stealth/ox-alpha or user-selected model)
          └── GeminiUiAgentProvider     (gemini-2.0-flash fallback)
   ```
   Execution Flow:
   ```
   Is OpenRouter configured?
          │
         YES ────────► OpenRouter ───[Success]───► Return Result
          │                             │
          │                          [Failure]
          │                             │
          NO ───────────────────────────┴───────► Gemini Fallback ──► Return Result
   ```
4. **Database Architecture Frozen:**
   Room SQLite database schemas (`permanent_memories`, `period_logs`, `reminders`, `chat_messages`) are strictly preserved without any schema modifications or migrations.
5. **Strict Incremental Execution Order:**
   Execute one phase at a time and verify with `flutter analyze`, `flutter test`, and Gradle build after every step.

---

## 2. Incremental Migration Pipeline

```
STEP 1: Flutter becomes Sole UI Authority (Verify routes, clean entry, no Compose UI)
       ↓ (Verify: analyze, test, build)
STEP 2: Kotlin Capability Separation (Cohesive modules for a11y, device, perms, comms, notifications)
       ↓ (Verify: analyze, test, build)
STEP 3: Bridge Modularization (Clean platform delegate handlers behind MaxNativeBridge facade)
       ↓ (Verify: analyze, test, build)
STEP 4: Automation Pipeline & Screen Optimization (Observe-Decide-Act-Verify-Recover, adaptive delays, cancellation)
       ↓ (Verify: analyze, test, build)
STEP 5: Runtime Lifecycle & Ownership Contract (Deterministic MAX ON / OFF resource management)
       ↓ (Verify: analyze, test, build)
STEP 6: Decommission Obsolete Compose UI code
       ↓ (Verify: analyze, test, build)
STEP 7: Performance & Scoped State Rebuild Optimization (Zero-jank streaming, lifecycle safety)
```

---

## 3. Runtime Ownership Contract

| Owned Resource | Owner Component | Start Trigger | Stop / Cancel Action on MAX OFF | Status Flow |
|---|---|---|---|---|
| **Audio Recorder** | `AudioRecorder` | Voice toggle / assistant listening | `stopRecording()`, release AudioRecord | `isRecording: false` |
| **Gemini WebSocket**| `GeminiWebSocketClient` | App init / assistant resume | `sleep()`, disconnect socket, abandon audio focus | `connectionState: OFF` |
| **Audio Player** | `AudioPlaybackManager` | Model speech chunks | `stopAudioResponse()`, flush AudioTrack | `isAiSpeaking: false` |
| **UI Automation** | `UiVerificationLoop` | User goal / routine trigger | `cancellationToken.cancel()`, halt loop immediately | `isExecuting: false` |
| **Telegram Bot** | `TelegramBotService` | User settings enabled + MAX ON | `stopService()`, cancel long-polling coroutine | Stopped |
| **Capsule Overlay** | `MaxCapsuleOverlayService` | Overlay permission + MAX ON | `stopService()`, remove floating window | Stopped |
| **Reconnect Jobs** | `ChatViewModel` / `AssistantEngine` | Network reconnect timer | `job.cancel()`, pause timers | Cancelled |
| **A11y Event Loop**| `JarvisAccessibilityService` | OS accessibility event | **Ignore events while MAX OFF** (OS binding preserved) | Idle / Bypass |

---

## 4. Deliverables Checklist

- [x] `MAX_ARCHITECTURE_MIGRATION_PLAN.md` (Updated)
- [ ] `MAX_ARCHITECTURE.md` (Architecture Reference)
- [ ] `MAX_NATIVE_MODULES.md` (Native Modules Reference)
- [ ] `MAX_AUTOMATION_PIPELINE.md` (Automation Reference)
- [ ] `MAX_PROVIDER_ARCHITECTURE.md` (Provider Fallback Reference)
- [ ] `MAX_STABILITY_REPORT.md` (Verification & Stability Report)
