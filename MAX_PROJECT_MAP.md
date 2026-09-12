# MAX PROJECT MAP
**Navigation Index — "Where should I look for this feature?"**
> Read MAX_PROJECT_STATE.md first, then use this map to locate relevant files.  
> Source code always wins over documentation.

---

## Package Root
```
com.example
Workspace: d:\max app testing\gemini-live\
App ID:    com.aistudio.geminilive.abcde
Package:   com.example
DB name:   jarvis_database  (Room v14)
```

---

## MAX
```
MAX
├── Runtime Lifecycle & Control (Developer Mode / Master Switch)
│   ├── MaxRuntimeManager        app/src/main/java/com/example/core/lifecycle/MaxRuntimeManager.kt
│   │     — Central global ON/OFF lifecycle controller; suspends/restores non-essential services safely
│   └── SettingsManager          app/src/main/java/com/example/data/preferences/SettingsManager.kt
│
├── AI / Core Engine
│   ├── AssistantEngine          app/src/main/java/com/example/voice/assistant/AssistantEngine.kt
│   ├── AssistantLifecycleManager app/src/main/java/com/example/voice/assistant/AssistantLifecycleManager.kt
│   ├── AssistantEventBus        app/src/main/java/com/example/voice/assistant/AssistantEventBus.kt
│   ├── AssistantVoiceController app/src/main/java/com/example/voice/assistant/AssistantVoiceController.kt
│   ├── AssistantLogger          app/src/main/java/com/example/voice/assistant/AssistantLogger.kt
│   ├── ConversationManager      app/src/main/java/com/example/voice/assistant/ConversationManager.kt
│   ├── FeatureManager           app/src/main/java/com/example/voice/assistant/FeatureManager.kt
│   └── SessionStateMachine      app/src/main/java/com/example/voice/assistant/SessionStateMachine.kt
│
├── UI Automation Agent (OpenRouter / Ox Alpha / Gemini Fallback)
│   ├── UiAgentRouter            app/src/main/java/com/example/automation/agent/UiAgentRouter.kt
│   ├── OpenRouterUiAgentProvider app/src/main/java/com/example/automation/agent/OpenRouterUiAgentProvider.kt
│   ├── GeminiUiAgentProvider    app/src/main/java/com/example/automation/agent/GeminiUiAgentProvider.kt
│   ├── UiVerificationLoop       app/src/main/java/com/example/automation/agent/UiVerificationLoop.kt
│   ├── UiActionExecutor         app/src/main/java/com/example/automation/agent/UiActionExecutor.kt
│   ├── UiTask / UiAction / UiObservation app/src/main/java/com/example/automation/agent/
│   └── UiAgentTool              app/src/main/java/com/example/automation/tools/UiAgentTool.kt
│
├── AI / Gemini Live Network
│   ├── GeminiWebSocketClient    app/src/main/java/com/example/network/GeminiWebSocketClient.kt
│   │     — Gemini Live WSS connection, setup, tool-call dispatch, audio/text I/O
│   ├── ConnectionManager        app/src/main/java/com/example/voice/assistant/ConnectionManager.kt
│   ├── StreamingResponseManager app/src/main/java/com/example/voice/assistant/StreamingResponseManager.kt
│   ├── PromptBuilder            app/src/main/java/com/example/network/PromptBuilder.kt
│   │     — System instruction (identity, personality, language, tools)
│   ├── ToolDispatcher           app/src/main/java/com/example/network/ToolDispatcher.kt
│   │     — Maps Gemini tool-call function names to action JSON
│   ├── MessageModels            app/src/main/java/com/example/network/MessageModels.kt
│   ├── GeminiPlanningClient     app/src/main/java/com/example/network/GeminiPlanningClient.kt
│   └── GeminiPlanningProvider   app/src/main/java/com/example/network/provider/GeminiPlanningProvider.kt
│         — Injected into GoalPlanner for LLM-based multi-step planning
│
├── Voice / Audio
│   ├── AudioRecorder            app/src/main/java/com/example/voice/audio/AudioRecorder.kt
│   ├── AudioPlayer              app/src/main/java/com/example/voice/audio/AudioPlayer.kt
│   ├── AudioProcessor           app/src/main/java/com/example/voice/audio/AudioProcessor.kt
│   └── AudioFocusManager        app/src/main/java/com/example/voice/audio/AudioFocusManager.kt
│
├── Voice / Session & Foreground
│   ├── AssistantEngineService   app/src/main/java/com/example/voice/session/AssistantEngineService.kt
│   ├── JarvisVoiceInteractionService app/src/main/java/com/example/voice/session/JarvisVoiceInteractionService.kt
│   ├── JarvisRecognitionService app/src/main/java/com/example/voice/session/JarvisRecognitionService.kt
│   ├── JarvisVoiceSession       app/src/main/java/com/example/voice/session/JarvisVoiceSession.kt
│   └── JarvisVoiceSessionService app/src/main/java/com/example/voice/session/JarvisVoiceSessionService.kt
│
├── Voice / Trigger
│   └── JarvisVoiceTriggerActivity  app/src/main/java/com/example/voice/trigger/JarvisVoiceTriggerActivity.kt
│
├── Voice / HUD UI
│   ├── AssistantHud             app/src/main/java/com/example/voice/ui/AssistantHud.kt
│   ├── BottomPill               app/src/main/java/com/example/voice/ui/BottomPill.kt
│   ├── SoundWave                app/src/main/java/com/example/voice/ui/SoundWave.kt
│   ├── TranscriptBubble         app/src/main/java/com/example/voice/ui/TranscriptBubble.kt
│   ├── GlowBorder               app/src/main/java/com/example/voice/ui/GlowBorder.kt
│   └── LiquidScreenEdges        app/src/main/java/com/example/voice/ui/LiquidScreenEdges.kt
│
├── Vision / Screen
│   ├── ScreenCaptureProvider    app/src/main/java/com/example/voice/vision/ScreenCaptureProvider.kt
│   ├── ScreenContextProvider    app/src/main/java/com/example/voice/vision/ScreenContextProvider.kt
│   ├── GeminiVisionReasoner     app/src/main/java/com/example/voice/vision/GeminiVisionReasoner.kt
│   └── VisionReasoner           app/src/main/java/com/example/voice/vision/VisionReasoner.kt
│
├── Automation / Engine
│   ├── ActionDispatcher         app/src/main/java/com/example/automation/engine/ActionDispatcher.kt
│   ├── ExecutionEngine          app/src/main/java/com/example/automation/engine/ExecutionEngine.kt
│   ├── GoalPlanner              app/src/main/java/com/example/automation/engine/GoalPlanner.kt
│   ├── AgentOrchestrator        app/src/main/java/com/example/automation/engine/AgentOrchestrator.kt
│   ├── IntentClassifier         app/src/main/java/com/example/automation/engine/IntentClassifier.kt
│   ├── NaturalCommandNormalizer app/src/main/java/com/example/automation/engine/NaturalCommandNormalizer.kt
│   ├── OfflineCommandEngine     app/src/main/java/com/example/automation/engine/OfflineCommandEngine.kt
│   ├── ActionAuthorizer         app/src/main/java/com/example/automation/engine/ActionAuthorizer.kt
│   ├── PendingCallManager       app/src/main/java/com/example/automation/engine/PendingCallManager.kt
│   ├── ActionQueue              app/src/main/java/com/example/automation/engine/ActionQueue.kt
│   └── WorldStateManager        app/src/main/java/com/example/automation/state/WorldStateManager.kt
│
├── Automation / Tools (45 registered)
│   └── app/src/main/java/com/example/automation/tools/  [45 *Tool.kt files]
│       Key: Tool.kt, ToolRegistry.kt, ToolResult.kt
│
├── Automation / Actions (27 action classes)
│   └── app/src/main/java/com/example/automation/actions/
│
├── Automation / Verification (26 verifiers)
│   └── app/src/main/java/com/example/automation/verification/
│       Key: VerificationRegistry, RetryEngine, DeviceContext
│
├── Automation / Scheduler
│   └── app/src/main/java/com/example/automation/scheduler/
│       Key: AlarmScheduler, TaskManager, TaskReceiver, ScheduledTask/Dao
│
├── Automation / Activity Tracking
│   └── app/src/main/java/com/example/automation/activity/
│       Key: AgentActivityTracker, AgentActivityState
│
├── WhatsApp
│   ├── WhatsAppController       app/src/main/java/com/example/automation/WhatsAppController.kt
│   ├── WhatsAppTool             app/src/main/java/com/example/automation/tools/WhatsAppTool.kt
│   ├── WhatsAppVerifier         app/src/main/java/com/example/automation/verification/WhatsAppVerifier.kt
│   ├── WhatsAppNotificationService app/src/main/java/com/example/service/WhatsAppNotificationService.kt
│   └── AutoReplyController      app/src/main/java/com/example/automation/AutoReplyController.kt
│
├── Telegram
│   ├── TelegramBotService       app/src/main/java/com/example/service/TelegramBotService.kt
│   ├── TelegramController       app/src/main/java/com/example/automation/TelegramController.kt
│   └── CloudSocketManager       app/src/main/java/com/example/telegram/CloudSocketManager.kt
│
├── Incoming Calls
│   ├── IncomingCallReceiver     app/src/main/java/com/example/receiver/IncomingCallReceiver.kt
│   ├── IncomingCallManager      app/src/main/java/com/example/automation/IncomingCallManager.kt
│   └── CallController           app/src/main/java/com/example/automation/CallController.kt
│
├── Memory
│   ├── MemoryRepository         app/src/main/java/com/example/memory/data/MemoryRepository.kt
│   ├── PermanentMemory          app/src/main/java/com/example/memory/data/PermanentMemory.kt
│   ├── PermanentMemoryDao       app/src/main/java/com/example/memory/data/PermanentMemoryDao.kt
│   ├── GeminiEmbeddingClient    app/src/main/java/com/example/memory/data/GeminiEmbeddingClient.kt
│   ├── MemoryRetrieval          app/src/main/java/com/example/memory/retrieval/MemoryRetrieval.kt
│   ├── MemoryFormatter          app/src/main/java/com/example/memory/retrieval/MemoryFormatter.kt
│   ├── MemoryCryptography       app/src/main/java/com/example/memory/security/MemoryCryptography.kt
│   ├── SkillMemoryRepository    app/src/main/java/com/example/memory/skill/SkillMemoryRepository.kt
│   ├── MemoryViewModel          app/src/main/java/com/example/memory/viewmodel/MemoryViewModel.kt
│   └── MemoryScreen             app/src/main/java/com/example/memory/ui/MemoryScreen.kt
│
├── Data / Persistence
│   ├── AppDatabase (Room v14)   app/src/main/java/com/example/data/local/AppDatabase.kt
│   ├── SettingsManager          app/src/main/java/com/example/data/preferences/SettingsManager.kt
│   └── JarvisRepository         app/src/main/java/com/example/data/repository/JarvisRepository.kt
│
├── Security
│   ├── MaxLockAutomationEngine  app/src/main/java/com/example/security/MaxLockAutomationEngine.kt
│   ├── UnlockManager            app/src/main/java/com/example/security/UnlockManager.kt
│   └── SecuritySettings         app/src/main/java/com/example/security/SecuritySettings.kt
│
├── Shizuku
│   ├── ShizukuManager           app/src/main/java/com/example/automation/ShizukuManager.kt
│   ├── ShizukuExecutor          app/src/main/java/com/example/automation/ShizukuExecutor.kt
│   ├── ShizukuShellPlugin       app/src/main/java/com/example/automation/ShizukuShellPlugin.kt
│   └── ShizukuShellService      app/src/main/java/com/example/automation/ShizukuShellService.kt
│
├── Windows / PC Companion
│   ├── CompanionConnectionService app/src/main/java/com/example/network/agent/CompanionConnectionService.kt
│   ├── WindowsAgentClient       app/src/main/java/com/example/network/agent/WindowsAgentClient.kt
│   ├── WindowsToolExecutor      app/src/main/java/com/example/network/agent/WindowsToolExecutor.kt
│   └── CommandIdempotencyCache  app/src/main/java/com/example/network/agent/CommandIdempotencyCache.kt
│
├── UI / Compose
│   ├── MainActivity             app/src/main/java/com/example/MainActivity.kt
│   ├── ChatViewModel            app/src/main/java/com/example/viewmodel/ChatViewModel.kt
│   ├── Screens/                 app/src/main/java/com/example/ui/screens/
│   ├── Onboarding/              app/src/main/java/com/example/ui/screens/onboarding/
│   ├── Components/              app/src/main/java/com/example/ui/components/
│   ├── Overlay/                 app/src/main/java/com/example/ui/overlay/
│   └── Theme/                   app/src/main/java/com/example/ui/theme/
│
├── Flutter UI Subsystem (max_flutter/)
│   ├── Main / Router            max_flutter/lib/main.dart
│   ├── Native Bridge (Dart)     max_flutter/lib/core/bridge/max_native_bridge.dart
│   ├── Chat Controller          max_flutter/lib/core/bridge/chat_controller.dart
│   ├── Theme Tokens             max_flutter/lib/core/theme/
│   ├── Welcome Screen           max_flutter/lib/features/onboarding/welcome_screen.dart
│   ├── Permissions Screen       max_flutter/lib/features/onboarding/permissions_screen.dart
│   ├── AI Config Screen         max_flutter/lib/features/onboarding/ai_config_screen.dart
│   ├── Home / Chat Screen       max_flutter/lib/features/home/home_screen.dart
│   ├── Settings Screen          max_flutter/lib/features/settings/settings_screen.dart
│   └── Memory Vault Screen      max_flutter/lib/features/memory/memory_screen.dart
│
├── Bridge / Host Integration
│   ├── MaxFlutterActivity       app/src/main/java/com/example/flutter/MaxFlutterActivity.kt
│   └── MaxNativeBridge (Kotlin) app/src/main/java/com/example/flutter/MaxNativeBridge.kt
│
└── Entry Points
    ├── MyApplication            app/src/main/java/com/example/MyApplication.kt
    ├── MaxFlutterActivity       app/src/main/java/com/example/flutter/MaxFlutterActivity.kt
    └── AndroidManifest.xml      app/src/main/AndroidManifest.xml
```

---

## Quick Lookup: Feature → File

| Feature | Primary File |
|---------|-------------|
| Flutter Entry & Routing | `max_flutter/lib/main.dart` |
| Flutter Native Bridge | `max_flutter/lib/core/bridge/max_native_bridge.dart` + `app/.../flutter/MaxNativeBridge.kt` |
| Flutter Onboarding Flow | `max_flutter/lib/features/onboarding/` |
| Flutter Home / Chat UI | `max_flutter/lib/features/home/home_screen.dart` |
| Gemini Live connection | `network/GeminiWebSocketClient.kt` |
| System instruction / persona | `network/PromptBuilder.kt` |
| Tool call routing | `network/ToolDispatcher.kt` |
| Tool execution | `automation/engine/ActionDispatcher.kt` + `ExecutionEngine.kt` |
| Tool registration | `MyApplication.kt` |
| WhatsApp send | `automation/WhatsAppController.kt` |
| WhatsApp accessibility | `service/JarvisAccessibilityService.kt` |
| Incoming calls | `receiver/IncomingCallReceiver.kt` + `automation/IncomingCallManager.kt` |
| Telegram bot | `service/TelegramBotService.kt` |
| Memory save | `automation/tools/SaveMemoryTool.kt` + `memory/data/MemoryRepository.kt` |
| Memory vectors | `memory/data/GeminiEmbeddingClient.kt` |
| All settings | `data/preferences/SettingsManager.kt` |
| Onboarding state | `SettingsManager.OnboardingStage` |
| Room database | `data/local/AppDatabase.kt` (v14) |
| Screen capture | `voice/vision/ScreenCaptureProvider.kt` |
| Reminders | `automation/tools/ReminderTool.kt` + `receiver/ReminderReceiver.kt` |
| Period tracker | `automation/tools/PeriodTrackerTool.kt` + `ui/screens/PeriodTrackerScreen.kt` |
| Windows companion | `network/agent/CompanionConnectionService.kt` |
| Overlay capsule | `service/MaxCapsuleOverlayService.kt` + `ui/overlay/DynamicCapsuleView.kt` |
| Multi-step planning | `automation/engine/GoalPlanner.kt` |
| Auto-reply | `automation/AutoReplyController.kt` |
| Theme colors | `max_flutter/lib/core/theme/max_colors.dart` & `ui/theme/Color.kt` |
| Security/Unlock | `security/MaxLockAutomationEngine.kt` |
