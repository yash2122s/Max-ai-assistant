# UI Subsystem — MAX

## Purpose
MAX uses Flutter as its complete, unified user-facing UI architecture:
1. **User-Facing UI Layer (Flutter — 100%)**: Flutter (`max_flutter/`) is the primary and sole user-facing UI for Onboarding (`WelcomeScreen`, `PermissionsScreen`, `AiConfigScreen`), the main interactive Voice & Chat workspace (`HomeScreen`), `SettingsScreen`, `MemoryScreen`, `PeriodTrackerScreen`, `RemindersScreen`, `NotificationAccessScreen`, `AboutScreen`, and `PrivacyPolicyScreen`. Configured with edge-to-edge fullscreen presentation in the Premium Dark design system, connected via `MaxNativeBridge` (`max/native` MethodChannel).
2. **Backend & Host Layer (Kotlin)**: `AssistantEngine`, `GeminiWebSocketClient`, Room DAOs (`PeriodLogDao`, `ReminderDao`, `PermanentMemoryDao`), `JarvisAccessibilityService`, `IncomingCallManager`, and `NotificationAppAccessManager`.

## Entry Points
- `com.example.flutter.MaxFlutterActivity` — Primary entry point launching the Flutter UI (`android.intent.action.MAIN`).
- `com.example.MainActivity` — Native host activity.

## Navigation Routes (Flutter)
- `/` — Resolves to onboarding stage or `/home` based on `SettingsManager.onboardingStage`
- `/welcome` — `WelcomeScreen` (Stage 1)
- `/onboarding/permissions` — `PermissionsScreen` (Stage 2)
- `/permissions` — `PermissionsScreen` (Manager Mode with dedicated AppBar & full capabilities list)
- `/ai_config` — `AiConfigScreen` (Stage 3)
- `/home` — `HomeScreen` (Stage 4 / Chat & Voice, including `MaxQuickActionsCarousel`)
- `/routines` — `RoutinesScreen` (Preset & Custom Macro Automations)
- `/settings` — `SettingsScreen` (Full Configuration, Voice, Pairing, Security)
- `/memory` — `MemoryScreen` (Encrypted Memory Vault)
- `/period_tracker` — `PeriodTrackerScreen` (Cycle Prediction, Health Tracking)
- `/reminders` — `RemindersScreen` (Tasks & Alarms)
- `/notification_access` — `NotificationAccessScreen` (Per-App Privacy & WhatsApp Auto-Reply)
- `/about` — `AboutScreen` (System Specs & Creator Credits)
- `/privacy` — `PrivacyPolicyScreen` (Local-First Zero-Telemetry Disclosures)

## Screens (100% Flutter UI)

All user-facing screens are built in Flutter with the MAX Premium Dark design system:

| Screen | File | Route | Notes |
|--------|------|-------|-------|
| `HomeScreen` | `max_flutter/lib/features/home/home_screen.dart` | `/home` | Main chat & voice interface, Live Audio Visualizer, Quick Actions Carousel |
| `AiConfigScreen` | `max_flutter/lib/features/onboarding/ai_config_screen.dart` | `/ai_config` | Gemini Live Voice + OpenRouter Ox Alpha UI Agent setup |
| `PermissionsScreen` | `max_flutter/lib/features/onboarding/permissions_screen.dart` | `/permissions` | Full 11/11 system capabilities manager with granular controls |
| `SettingsScreen` | `max_flutter/lib/features/settings/settings_screen.dart` | `/settings` | Complete system preferences, theme, voice, pairing, UI agent config |
| `MemoryScreen` | `max_flutter/lib/features/memory/memory_screen.dart` | `/memory` | Encrypted Memory Vault with category tags, search, and pin/delete |
| `PeriodTrackerScreen` | `max_flutter/lib/features/period_tracker/period_tracker_screen.dart` | `/period_tracker` | Cycle predictions, history logs, symptoms, and health stats |
| `RemindersScreen` | `max_flutter/lib/features/reminders/reminders_screen.dart` | `/reminders` | Task CRUD, date/time scheduling, and completion status |
| `RoutinesScreen` | `max_flutter/lib/features/routines/routines_screen.dart` | `/routines` | Preset system automations and custom multi-step macro creator |
| `NotificationAccessScreen` | `max_flutter/lib/features/notifications/notification_access_screen.dart` | `/notification_access` | Per-app notification monitoring and WhatsApp auto-reply toggles |
| `AboutScreen` | `max_flutter/lib/features/about/about_screen.dart` | `/about` | System information, hardware specs, and creator credits |
| `PrivacyPolicyScreen` | `max_flutter/lib/features/privacy/privacy_policy_screen.dart` | `/privacy` | Local-first disclosures and zero-telemetry commitments |
| `MaxDrawer` | `max_flutter/lib/shared/widgets/max_drawer.dart` | Embedded | Left-edge drag drawer with categorized navigation destinations |

## Overlay

| Component | File | Notes |
|-----------|------|-------|
| `DynamicCapsuleView` | `ui/overlay/DynamicCapsuleView.kt` | 17KB; floating capsule Compose UI |
| `MaxCapsuleOverlayService` | `service/MaxCapsuleOverlayService.kt` | 10KB; foreground/specialUse; manages overlay window |

The capsule overlay auto-starts from `MyApplication` if `SYSTEM_ALERT_WINDOW` permission is granted.

## Theme

### Color System (`ui/theme/Color.kt`)
| Token | Value | Use |
|-------|-------|-----|
| `MaxPrimary` | `#00A8FF` Electric Blue | Primary brand color |
| `MaxAccent` | `#7C4DFF` Vivid Purple | Accent, gradients |
| `MaxBgDark` | `#0B0F14` | App background |
| `MaxSurfaceDark` | `#151B23` | Cards, containers |
| `MaxSurfaceVariant` | `#1C232D` | Inputs, elevated surfaces |
| `MaxTextPrimary` | `#FFFFFF` | Primary text |
| `MaxTextMuted` | `#94A3B8` | Secondary text |
| `MaxSuccess` | `#42B72A` | Success states |
| `MaxError` | `#F02849` | Error states |

Legacy tokens (BgDark, SurfaceDark, NeonPink, NeonBlue, etc.) all alias to the above MAX tokens.
**Theme is forced dark** — no light mode (`BgLight = MaxBgDark`).

### Typography (`ui/theme/Type.kt`)
Uses Material3 type system with custom overrides.

### Theme (`ui/theme/Theme.kt`)
`MyApplicationTheme` — always dark; applies MAX color scheme.

## ViewModel
`ChatViewModel` (`viewmodel/ChatViewModel.kt`):
- Holds `ChatUiState` (messages, connection state, captions)
- Manages `AssistantEngine` reference via `AssistantLifecycleManager`
- Exposes `rmsFlow` for sound visualizer
- **Anti-pattern**: static `_instance` singleton — risk of context leak

## State Management & Synchronization
- **Authoritative Kotlin State**: `ChatViewModel.uiState` is the single source of truth for the entire assistant lifecycle.
- **Event-Driven Platform Channel**: Pushed via `MaxNativeBridge.onChatStateChanged` directly to `chatStateStream` without polling timers.
- **Payload Deduplication**: `MaxNativeBridge` deduplicates serialized state payloads so that Flutter only re-renders on actual state transitions.
- **Targeted Component Rebuilds**:
  - `MaxStatusBadge`: Animates pulsing glow only during active states (`ONLINE`, `LISTENING`, `THINKING`, `SPEAKING`, `CONNECTING`); renders static badge during `OFF` and `OFFLINE` (0 idle repaints/sec).
  - `MaxVoiceVisualizer`: Evaluates active state in `didUpdateWidget()`; runs only the relevant controller (`_ambientCtrl`, `_speakingCtrl`, or `_thinkingCtrl`) and shuts down all animations when MAX is `OFF`.
  - `HomeScreen`: Auto-scrolls only on new messages or caption updates; animates bottom mic pulse only while `isRecording == true`.
  - `MaxDrawer`: Features 450ms debounced navigation (`_navigate`) to eliminate duplicate route pushes and transition glitches.

## Current Status: PRODUCTION STABLE ✅
- 100% Pure Flutter UI stack with zero Compose UI screens.
- Event-driven state synchronization without polling.
- Hardware-accurate real-time audio playback tracking.
- Smooth, gesture-conflict-free left drawer edge drag.
- Verified on Redmi K20 Pro (`3bc00016`).
