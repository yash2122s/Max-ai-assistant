# MAX Migration Changelog

## [2026-08-29] - MAX Home Screen Chat-First UI Redesign
- **Design Transformation**: Redesigned `HomeScreen.kt` from a dashboard-style header layout into a premium, chat-first AI assistant conversational interface.
- **Key Enhancements**:
  - **Minimal Top Bar**: Compact title bar with mini glowing MAX status dot, real-time connection badge (`MaxStatusBadge`), drawer hamburger icon, and options dropdown.
  - **Chat Canvas**: Full-height `LazyColumn` with stable keys (`key = { it.id }`), smart auto-scrolling, and a floating "↓ New messages" button when scrolling through history.
  - **Asymmetric Message Bubbles**: Sleek gradient right-aligned user bubbles and left-aligned MAX bubbles with avatar indicator, copy action, and timestamp.
  - **Animated Thinking Indicator**: 3-dot pulsing typing indicator when `isThinking` is active.
  - **Collapsible Voice Live Banner**: Smoothly expands during voice input / AI speech with `MaxVoiceVisualizer` driven by `audioRms` (isolated 60fps leaf recomposition) and live captions.
  - **Floating Composer**: Floating rounded pill composer with multiline support, dynamic Send / Mic button, keyboard insets padding (`imePadding()`), and collapsible quick action strip (`+` button).
  - **Interactive Empty State**: Centered `JarvisNeuralOrb` with 4 actionable suggestion prompt cards.
- **Verification**: Clean build and 191/191 unit tests passed (`BUILD SUCCESSFUL`).

## [2026-08-28] - MAX 100% Native Kotlin Migration Complete (Phases 0–16 Verified)
- **Architecture**: 100% Native Kotlin, 100% Jetpack Compose UI, Material 3, Kotlin ViewModels with StateFlow/SharedFlow.
- **Flutter & Dart Elimination**:
  - `max_flutter/` directory completely deleted.
  - `com.example.flutter` package (`MaxNativeBridge.kt`, `MaxFlutterActivity.kt`, and 7 platform bridge delegates) deleted.
  - Removed all `MethodChannel`, `BasicMessageChannel`, `EventChannel`, and `FlutterEngine` references.
  - Removed `project(":flutter")` dependency from `app/build.gradle.kts` and Flutter gradle scripts from `settings.gradle.kts`.
  - APK size reduced from ~193 MB to ~29.9 MB.
- **All 13 Native Compose Screens Implemented & Verified**:
  1. *Home & Voice*: `HomeScreen.kt`, `HomeViewModel.kt` (Gemini Live real-time audio, 60fps Neural Orb, text chat, Room persistence, quick actions).
  2. *Settings*: `SettingsScreen.kt`, `SettingsViewModel.kt` (Gemini API Key, OpenRouter keys, Persona selection, Master switch).
  3. *Permissions*: `PermissionsScreen.kt`, `PermissionsViewModel.kt` (Accessibility service, Notification listener, Overlay, Battery, Shizuku IPC).
  4. *Memory Wallet*: `MemoryScreen.kt`, `MemoryViewModel.kt` (Room vector & text memory CRUD, categories, pinning, search).
  5. *Import Memory*: `MemoryImportScreen.kt`, `MemoryImportViewModel.kt` (`UniversalMemoryImporter` with ChatGPT, Claude, Obsidian parser).
  6. *Period Tracker*: `PeriodScreen.kt`, `PeriodViewModel.kt` (`PeriodLogDao` cycle calculation, ovulation prediction, logging).
  7. *Reminders*: `RemindersScreen.kt`, `RemindersViewModel.kt` (`ReminderDao`, system AlarmManager scheduling).
  8. *Routines & Automation*: `RoutinesScreen.kt`, `RoutinesViewModel.kt` (`RoutineTool`, built-in routines + custom routines execution).
  9. *Notifications & Auto-Reply*: `NotificationsScreen.kt`, `NotificationsViewModel.kt` (`AutoReplyRuleDao`, WhatsApp auto-reply rules).
  10. *Capabilities Index*: `CapabilitiesScreen.kt`, `CapabilitiesViewModel.kt` (Tool & action registry live inspection of 45 tools and 108 actions).
  11. *About MAX*: `AboutScreen.kt` (System specs, version info, developer attribution).
  12. *Privacy Policy*: `PrivacyScreen.kt` (Local-first architecture, zero telemetry statement).
  13. *Onboarding*: `OnboardingScreen.kt`, `OnboardingViewModel.kt` (Welcome -> Permissions -> AI Key/Voice setup -> Home).
- **Automated & Physical Device Verification**:
  - `gradlew clean testDebugUnitTest assembleDebug`: 191/191 unit tests passed, 0 failures, 0 errors.
  - Streamed install of 29.9 MB native APK on Redmi K20 Pro (`3bc00016`).
  - Cold launch, Room persistence, drawer navigation, capabilities index, and back-stack transitions physically verified.
