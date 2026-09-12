# MAX AI — SENIOR SYSTEM ARCHITECTURE SPECIFICATION

**Version:** 3.1.0  
**Platform:** Android (Kotlin) + Flutter (Add-to-App)  
**Status:** Production Ready  

---

## 1. Architectural Philosophy & Separation of Concerns

MAX employs a strict unidirectional capability flow:

```
┌────────────────────────────────────────────────────────┐
│                   Flutter UI Layer                     │
│  (100% Sole UI Authority, Design System, Screens)     │
└──────────────────────────┬─────────────────────────────┘
                           │ Typed MethodChannel ("max/native")
┌──────────────────────────▼─────────────────────────────┐
│                 MaxNativeBridge Facade                 │
│      (Deduplicating Dispatcher & Push Coordinator)     │
└──────┬────────────┬────────────┬────────────┬──────────┘
       │            │            │            │
┌──────▼──────┐ ┌───▼────────┐ ┌─▼──────────┐ ┌▼─────────┐
│ Accessibility│ │Permission  │ │ Device /   │ │Automation│
│   Delegate   │ │  Delegate │ │QuickActions│ │ Delegate │
└─────────────┘ └────────────┘ └────────────┘ └──────────┘
       │            │            │            │
┌──────▼──────┐ ┌───▼────────┐ ┌─▼──────────┐ ┌▼─────────┐
│   Runtime    │ │  Data /   │ │Notification│ │  Memory  │
│   Delegate   │ │ Room DB   │ │  Delegate  │ │Repository│
└─────────────┘ └────────────┘ └────────────┘ └──────────┘
```

### Core Architecture Laws
1. **Flutter is Sole UI Authority:** All screens (Onboarding, Home Chat, Settings, AI Config, Memory Vault, Period Tracker, Reminders, Routines, Notification Access, Permissions, About, Privacy Policy) are built with Flutter and Vanilla styling tokens. No Compose UI screens are launched.
2. **Native Subsystem Independence:** Android `AccessibilityService` is managed as an OS daemon decoupled from UI activity lifecycles. When MAX is turned OFF, OS binding is preserved while all MAX automation tasks and event processing are bypassed.
3. **Dedicated Runtime Ownership Contract:** Every owned background resource implements explicit `START`, `OWNER`, `STOP`, `CANCEL`, `RESTART`, and `STATUS` handlers.
4. **Provider-Level UI Reasoning Fallback:** UI Agent execution dynamically targets OpenRouter (`stealth/ox-alpha`) with automatic, resilient fallback to `GeminiUiAgentProvider` (`gemini-2.0-flash`).
5. **Frozen Database Integrity:** Room SQLite database tables (`permanent_memories`, `period_logs`, `reminders`, `chat_messages`) remain 100% intact with zero destructive migrations.

---

## 2. Flutter Architecture & Service Layer

```
lib/
├── core/
│   ├── bridge/
│   │   ├── max_native_bridge.dart   (Typed Facade & Event Stream)
│   │   └── chat_controller.dart     (ChangeNotifier managing chat & voice state)
│   └── theme/
│       ├── max_colors.dart          (Color tokens & gradients)
│       ├── max_typography.dart      (Google Fonts typography)
│       └── max_radius.dart          (Border radius tokens)
├── services/
│   ├── runtime_service.dart         (Coordinates MAX ON/OFF state & chat lifecycle)
│   ├── automation_service.dart      (UI Agent execution, routines, quick actions)
│   ├── memory_service.dart          (Memory Vault CRUD operations)
│   ├── period_service.dart          (Cycle tracking & statistical predictions)
│   └── platform_service.dart        (Permissions, A11y, Notifications, Settings)
├── features/
│   ├── home/                        (Scoped zero-jank Home & Chat workspace)
│   ├── settings/                    (API config, PC companion, PIN, theme)
│   ├── memory/                      (Memory Vault cards & search)
│   ├── period_tracker/              (Cycle stats & menstrual phase cards)
│   ├── reminders/                   (Task filters & scheduler)
│   ├── routines/                    (Preset & custom routine builder)
│   ├── onboarding/                  (Permissions & AI config wizard)
│   ├── notifications/               (Per-app notification interception toggles)
│   └── capabilities/                (Searchable capability catalog)
└── shared/
    └── widgets/                     (MaxAppBar, MaxButton, MaxVoiceVisualizer, MaxCard)
```

---

## 3. Native Android Kotlin Architecture

```
com.example/
├── flutter/
│   ├── MaxFlutterActivity.kt        (Primary FlutterActivity entry point)
│   ├── MaxNativeBridge.kt           (Clean MethodChannel facade)
│   └── delegates/
│       ├── AccessibilityDelegate.kt (A11y checks & settings navigation)
│       ├── PermissionDelegate.kt    (Runtime permissions & status mapping)
│       ├── DeviceDelegate.kt        (Quick actions & PC pairing)
│       ├── RuntimeDelegate.kt       (MAX ON/OFF switch & chat engine)
│       ├── AutomationDelegate.kt    (UI agent tasks & routines)
│       ├── NotificationDelegate.kt  (Notification listener & monitored apps)
│       └── DataDelegate.kt          (Settings, Room DB Memory, Period, Reminders)
├── automation/
│   ├── agent/
│   │   ├── UiVerificationLoop.kt    (Observe-Decide-Act-Wait-Verify-Recover)
│   │   ├── UiAgentRouter.kt         (OpenRouter vs Gemini provider router)
│   │   ├── OpenRouterUiAgentProvider.kt (OpenRouter vision/reasoning engine)
│   │   ├── GeminiUiAgentProvider.kt (Gemini vision/reasoning fallback engine)
│   │   └── UiActionExecutor.kt      (Physical tap, swipe, type, scroll, keys)
│   └── tools/                       (ToolRegistry, RoutineTool, WhatsAppTool)
├── core/
│   └── lifecycle/
│       └── MaxRuntimeManager.kt     (Master ON/OFF ownership contract manager)
└── service/
    ├── JarvisAccessibilityService.kt (A11y gestures & event bypass when OFF)
    └── AccessibilityTreePruner.kt   (Compact token-optimized node normalization)
```
