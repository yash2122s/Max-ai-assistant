# MAX PROJECT STATE
**Current Status — "Where are we NOW?"**
> Updated: 2026-08-26 | Source of truth: actual source code.

---

## Current Project Status: ACTIVE PRODUCTION (100% Flutter UI • OpenRouter/Ox-Alpha UI Agent)

**Build**: Gradle 8.x / AGP 8.x / Kotlin 2.x / Flutter 3.x / compileSdk 36 (API 36.1)  
**Target Device**: Redmi K20 Pro — Android 16 (API 36) — Root + Shizuku  
**App ID**: `com.aistudio.geminilive.abcde`  
**Package**: `com.example`  
**DB**: Room v14 (`jarvis_database`)

---

## Feature Status

### COMPLETED ✅

| Feature | Status Notes |
|---------|-------------|
| **WhatsApp Contact Resolution & Top Header Verification** | Isolated search result contact name matching in `WhatsAppContactResolver.kt`. Rejects message preview snippets (`msg_snippet`, `conversations_row_message`), ignores group chat collisions, traverses up to clickable row container, and strictly verifies top toolbar chat header before typing or sending messages. |
| **White Light Theme & Haptic Feedback Upgrade** | Complete White Light Theme transformation with clean tokens (`MaxBgDark = #F8FAFC`, `MaxSurface = #FFFFFF`, `MaxSurfaceVariant = #F1F5F9`, `MaxTextPrimary = #0F172A`, `MaxBorder = #E2E8F0`, `MaxPrimary = #0066FF`). Upgraded edge lighting with 7.5dp stroke + 18dp outer glow and multi-tier tactile sensations in `MaxHapticFeedback.kt`. |
| **Phase 8 — Universal Input & Keyboard Control Layer** | Full multi-tier keyboard & IME control layer (`KeyboardController`, `InputTargetResolver`, `InputVerifier`, `type_text`, `press_enter`, `press_key`, `clear_text`) with 3-tier capability router (Accessibility `ACTION_SET_TEXT` / `ACTION_IME_ENTER` → Grounded Semantic / IME Controls → Shizuku / ADB Shell fallback). Resolves "I can't type" false capability reporting. |
| **Production Stability Hardening Master Pass** | Unified runtime state authority between Kotlin core and Flutter UI. Single authoritative `assistantState` (`OFF`, `CONNECTING`, `ONLINE`, `LISTENING`, `THINKING`, `SPEAKING`, `ERROR`) with platform channel payload deduplication (zero redundant bridge events). Target-specific animation lifecycles for `MaxVoiceVisualizer`, `MaxStatusBadge`, and `HomeScreen` (zero CPU/battery waste when dormant or offline). Debounced single-push navigation across all routes. |
| **Global MAX ON/OFF Mode (Developer Mode)** | Central `MaxRuntimeManager` controlling global operating state (`MAX ACTIVE` vs `MAX OFF`). When OFF, safely stops Gemini Live session, microphone recording, UI agent loops, Telegram bot, and dynamic capsule overlay while preserving essential system alarms (`AlarmManager`, `ReminderDao`, task scheduler). Persisted across process restarts and boot. |
| **Real-Time Assistant State Pipeline** | Event-driven status pipeline pushing exact Kotlin runtime events (`AssistantEventBus`, `AudioPlaybackManager.onPlaybackStateChanged`, `SessionStateMachine`) to Flutter via `MaxNativeBridge.onChatStateChanged`. True real-time status transitions: `OFF`, `OFFLINE`, `CONNECTING`, `ONLINE`, `LISTENING`, `THINKING`, `SPEAKING`, `ERROR`. |
| **Left-Edge Navigation Drawer Fix** | Optimized `MaxScaffold` gesture configuration (`drawerEdgeDragWidth: 100.0`, `drawerDragStartBehavior: DragStartBehavior.down`, `drawerEnableOpenDragGesture: true`). Smooth, reliable swipe from left edge without conflicting with child scrollables or carousels. |
| **100% Flutter UI & Compose Removal** | Flutter is the ONLY user-facing UI. Jetpack Compose UI screens deleted. `MainActivity.kt` converted to lightweight intent forwarder to `MaxFlutterActivity`. |
| **UI Automation Agent Layer** | Dynamic provider-independent UI agent (`com.example.automation.agent`) featuring `UiVerificationLoop`, `UiActionExecutor`, `UiAgentRouter`, `OpenRouterUiAgentProvider` (primary with `stealth/ox-alpha` default), and `GeminiUiAgentProvider` (fallback). Automatically aborts when MAX is OFF. |
| **Fast First-Frame Startup** | Removed blocking initializations from application launch. Installed apps scanning and non-critical background services run asynchronously on `Dispatchers.IO`. |
| **UI Agent Flutter Config** | Dual intelligence configuration in Onboarding Step 2 (`AiConfigScreen`) and `SettingsScreen` supporting OpenRouter API Key, dynamic model selector, and real-time connection testing. |
| **Home Navigation Intent Normalization** | Resolved Telugu + English home navigation variants in `NaturalCommandNormalizer.kt`, `OfflineCommandEngine.kt`, `ToolDispatcher.kt`, and `ActionAuthorizer.kt` (`"హోమ్ స్క్రీన్‌కి వెళ్ళు"`, `"హోమ్ స్క్రీన్కి వెళ్ళు"`, `"హోమ్ ఓపెన్ చేయి"`, `"Go to home screen"`, etc.). |
| **Screenshot Capture & Disk Persistence** | Screen capture saves directly to `/sdcard/Pictures/Screenshots/MAX_Screenshot_<timestamp>.jpg` and indexes in `MediaScannerConnection`. Verified live on Redmi K20 Pro. |
| **Memory Vault Full Persistence** | Memory Vault and Save Memory Quick Action fully persisted via Room `PermanentMemoryDao` with AES encryption, tag search, category filtering, and pin/delete controls. |
| **Comprehensive Permissions Manager (11/11)** | Exposes and controls all 11 system capabilities: Microphone, Accessibility, Notifications, Contacts, Phone, SMS, Location (GPS), Camera, Overlay, Battery optimization, and Notification Listener. Verified `11 / 11 Active` on device. |
| **Camera Quick Action** | Opens Android camera viewfinder via `MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA` with manufacturer package fallbacks. |
| **Drawer Left-Edge Swipe & Back Button Behavior** | Configured `drawerEdgeDragWidth: 60.0` and `drawerEnableOpenDragGesture: true` in `MaxScaffold`. Android Back button closes the drawer without closing or backgrounding the app. |
| **Flutter Permission Manager** | Implemented dedicated Permissions Manager screen in Flutter (`max_flutter/lib/features/onboarding/permissions_screen.dart` via `/permissions` route) accessible directly from navigation drawer. Features `MaxAppBar` with back navigation, active capabilities count, real-time status refresh, individual toggles/grant actions for all 11 capabilities, plus direct link to Android App Info settings via `MaxNativeBridge`. |
| **Flutter Routine Automation** | Implemented dedicated Routines & Automation screen in Flutter (`max_flutter/lib/features/routines/routines_screen.dart` via `/routines` route) wired in `MaxDrawer` (fixing prior routing bug). Features system preset routines (Morning Wake-Up, Bedtime Sleep Mode, Focus/Work Mode, Gaming Turbo Mode) with formatted step badges, live sequential execution feedback, and custom macro routine creator modal (DND, volume, brightness, bluetooth steps) dispatching directly to Kotlin `RoutineTool`. |
| **Horizontal Quick Actions Carousel** | Implemented horizontally scrollable Quick Actions carousel (`max_flutter/lib/shared/widgets/max_quick_actions.dart`) on Home Screen (`max_empty_state.dart`). Features 10 real hardware and backend tool triggers: Torch ON/OFF (`QuickActions.toggleFlashlight`), WhatsApp deep-link, Screenshot capture (`ExecutionEngine.TAKE_SCREENSHOT`), Send SMS dialog (`SEND_SMS`), Create Contact dialog (`CREATE_CONTACT`), Set Reminder modal (`addReminder`), Save Memory modal (`saveMemory`), GPS Location query (`GET_LOCATION`), and Camera launcher (`OPEN_CAMERA`). |
| **Drawer Navigation & Back Button Handling** | Clean drawer edge swipe, menu icon trigger, categorized destinations, and Android back button handling (dismisses drawer first before backgrounding app). |
| **Gemini Live Voice** | WebSocket to `gemini-3.1-flash-live-preview`. Audio in/out working. |
| **AssistantEngine** | Full session lifecycle, barge-in, audio stutter fix, WS keep-alive |
| **Tool Calling (45 tools)** | All tools registered in MyApplication; ToolDispatcher wired to GeminiWebSocketClient |
| **ActionDispatcher / ExecutionEngine** | Verified retry+verification loop, 25s timeout |
| **WhatsApp Send** | Deep-link (phone#) + search-based accessibility automation both working |
| **WhatsApp Auto-Reply** | AutoReplyController with AI fallback; notification listener working |
| **Telegram Bot** | Long-polling foreground service; 5-command whitelist (FLASHLIGHT_ON/OFF, GET_LOCATION, TAKE_SCREENSHOT, HELP) |
| **Incoming Call Assistant (Complete Flow)** | Full end-to-end incoming call detection, state tracking (`IncomingCallFlowState`), layered caller resolution (`PhoneLookup`, `CommonDataKinds.Phone`, `JarvisAccessibilityService` 6-retry scraping window, `CallLog.Calls`, and "Unknown caller" fallback), natural voice announcement, automatic microphone listening (`startListening()`), Gemini Live tool declarations & routing for `answer_call` / `reject_call`, dual-path call control (`TelecomManager` API + `JarvisAccessibilityService` gestures), busy message follow-up prompts with SMS (`SmsTool` / `SendSmsAction`) and WhatsApp (`WhatsAppController`), clean state reset on IDLE, and targeted logging. Verified on Redmi K20 Pro (`3bc00016`). |
| **Answer/Reject Call Tools** | `AnswerCallTool` + `RejectCallTool` registered with TelecomManager and accessibility fallback |
| **SMS Tool** | `SmsTool` + `SendSmsAction` registered and wired to `SmsManager` |
| **Memory (Room + Embeddings)** | PermanentMemory with AES encryption; 768-dim embeddings via text-embedding-004; hybrid cosine+keyword scoring |
| **Vector Search** | Hybrid (0.7*cosine + 0.3*keyword) with keyword fallback |
| **Memory Backfill** | Rate-limited backfill (10/batch, 500ms delay) |
| **SaveMemoryTool / ForgetMemoryTool** | Voice-triggered memory save/forget working |
| **Reminders** | ReminderTool + ReminderReceiver + AlarmManager + Room + RemindersScreen |
| **Period Tracker** | PeriodTrackerTool + PeriodTrackerScreen; 2025/2026 seed data preloaded |
| **Routines** | RoutineTool (create/run/delete/list) |
| **Screen Capture** | Tiered: Shizuku exec-out → Accessibility takeScreenshot(). RAM only, no disk write. |
| **Vision Queries** | isVisionQuery() detection; JPEG sent to Gemini Live as video frame |
| **Onboarding** | 3-stage Flutter UI: Welcome → Permissions → AI Config (Streamlined Gemini AI Studio API Key input; provider/model selectors removed from UI, internal model configuration preserved; Test Connection validation enables Complete Setup); persisted in SettingsManager |
| **Settings** | SettingsManager with EncryptedSharedPreferences; all settings exposed in SettingsScreen |
| **Theme** | MAX Premium Dark (Electric Blue + Vivid Purple); Flutter & Compose design systems aligned |
| **Dynamic Capsule Overlay** | MaxCapsuleOverlayService + DynamicCapsuleView; auto-starts if SYSTEM_ALERT_WINDOW granted |
| **Windows Companion** | CompanionConnectionService (NSD discovery); WindowsAgentClient; WorkerMode suspend/resume |
| **Multi-step GoalPlanner** | GeminiPlanningProvider injected; LLM-based plan generation; WorldStateManager |
| **Accessibility Service** | JarvisAccessibilityService (1739 lines); WhatsApp/YouTube/generic UI automation; gestures; PIN unlock |
| **Shizuku Integration** | ShizukuManager/Executor/Plugin/Service; screen capture tier |
| **Boot Restore** | BootReceiver reschedules reminders + scheduled tasks on boot |
| **Security/Unlock** | MaxLockAutomationEngine; UnlockManager; PIN unlock via accessibility |
| **Notification Bridge** | NotificationListenerBridge + WhatsAppNotificationService |
| **Agent Activity Card** | Live trace UI showing agent steps in dashboard |
| **Memory UI** | Full MemoryScreen with Add/Edit/Delete dialogs |
| **Voice Interaction Service** | JarvisVoiceInteractionService registered as ASSIST handler |

---

### PARTIALLY IMPLEMENTED ⚠️

| Feature | Gap |
|---------|-----|
| **Telegram Bot** | Only 5 commands allowed (hardcoded ALLOWED_ACTIONS). No NLP / Gemini forwarding. Cannot send arbitrary messages back. Not a full AI remote control. |
| **Incoming Call Auto-Answer** | Receiver + Manager working, but full "answer/reject by voice" agentic loop needs user testing. |
| **Windows Agent** | CompanionConnectionService + WindowsAgentClient exist; WorkerMode suspend/resume implemented. Integration depends on hermes-agent-main companion running on PC. |
| **GoalPlanner Multi-step** | Engine present; GeminiPlanningProvider injected. Depends on agentic mode being enabled in settings AND daily budget. Not tested exhaustively. |
| **Auto-Reply AI Fallback** | isAiFallbackEnabled exists in settings. AI fallback reply generation path needs end-to-end verification. |
| **Voice Memory Categories** | MemoryCategory enum exists but UI filtering may not be complete. |

---

### IN PROGRESS / EXPERIMENTAL 🔄

| Feature | Notes |
|---------|-------|
| **Agentic Loop** | AgenticMode setting; daily budget counter. Multi-step loop exists but reliability varies. |
| **OfflineCommandEngine** | 23KB class — local rule-based fallback for common commands without LLM. Integration with main flow unclear. |
| **Cloud Socket / Backend** | CloudSocketManager.kt; backend URL in settings. Not actively used in current primary flow. |

---

### KNOWN ISSUES 🐛

| Issue | Severity | Location |
|-------|----------|----------|
| Hardcoded model name in `sanitizeModelName()` always returns `gemini-3.1-flash-live-preview` regardless of user setting | MEDIUM | `GeminiWebSocketClient.kt` |
| `fallbackToDestructiveMigration(dropAllTables=true)` will silently destroy all user data if migration fails | HIGH | `AppDatabase.kt` |
| `ChatViewModel._instance` is a singleton anti-pattern — risks stale context leaks | MEDIUM | `ChatViewModel.kt` |
| `pendingTask` in `JarvisAccessibilityService` is a static mutable var — not thread-safe | MEDIUM | `JarvisAccessibilityService.kt` |
| Telegram ALLOWED_ACTIONS whitelist is very restrictive; full remote AI control not implemented | MEDIUM | `TelegramBotService.kt` |
| `EncryptedSharedPreferences` recovery path may silently fall back to plaintext prefs | HIGH | `SettingsManager.kt` |
| Many tools require Context but take it from calling scope — no DI framework | LOW | Multiple tools |
| `ActionDispatcher.dispatch()` uses `runBlocking` indirectly in some paths — potential ANR risk | MEDIUM | `ActionDispatcher.kt` |
| `JarvisAccessibilityService` has a 20s failsafe timeout but automation may silently fail without user feedback | LOW | `JarvisAccessibilityService.kt` |
| `OfflineCommandEngine` (23KB) appears to duplicate tool dispatch logic — relationship to main pipeline unclear | LOW | `automation/engine/OfflineCommandEngine.kt` |

---

### TECHNICAL DEBT 📋

- **Two ToolRegistries**: `automation/tools/ToolRegistry.kt` (static, app-level) vs `voice/tools/ToolRegistry.kt` (AssistantToolRegistry, voice-scoped). Must not confuse them.
- **Two action routing paths**: `ToolDispatcher` (Gemini Live tool calls) and `ActionDispatcher` (direct automation calls). Both ultimately call `ExecutionEngine`.
- **Legacy color tokens**: `Color.kt` has many legacy aliases (BgDark, SurfaceDark, NeonPink...) all pointing to MAX tokens — mostly harmless but creates confusion.
- **Static `instance` pattern** in `JarvisAccessibilityService` and `ChatViewModel` — not idiomatic Kotlin; concurrency risk.
- **`runBlocking` usage** in several coroutine paths.

---

### NOT IMPLEMENTED / PLANNED 🗓️

| Feature | Notes |
|---------|-------|
| Wake word (local) | No Vosk or local wake-word engine found. ASSIST intent trigger exists. |
| Cloud memory sync | No cloud sync found. Memory is local Room DB only. |
| Firebase Auth | Commented out in build.gradle.kts |
| Camera (live preview) | Camera permissions declared; CameraTool exists for photo. No live camera feed to Gemini. |
| Location continuous tracking | LocationTool exists; no background location tracking service. |

---

## Architecture Stability
- **STABLE — DO NOT REDESIGN** without explicit request.
- Core pipeline: `GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine → ToolRegistry → Tool.execute()`
- Memory pipeline: `SaveMemoryTool → MemoryRepository → PermanentMemoryDao → Room`
- Voice pipeline: `AudioRecorder → ConnectionManager → GeminiWebSocketClient → Gemini Live`

---

## Next Logical Development Areas

1. **Telegram Bot expansion** — Full Gemini-powered remote command execution (not just 5 hardcoded commands)
2. **Agentic loop reliability** — Testing and hardening multi-step GoalPlanner execution
3. **Incoming call voice flow** — Full end-to-end: "Yash, X is calling. Answer/reject?" → voice response → action
4. **Windows Companion** — Testing CompanionConnectionService with hermes-agent-main
5. **Auto-Reply AI** — End-to-end test of AI fallback reply generation
6. **Wake word** — Local wake word trigger (Vosk or ASSIST intent improvement)
7. **Period Tracker** — AI-driven predictions and notifications
