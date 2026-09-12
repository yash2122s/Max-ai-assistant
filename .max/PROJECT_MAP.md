# MAX Project Map

## Module Structure

```text
gemini-live/
├── app/                  # Primary Native Android Application Module (Kotlin + Jetpack Compose)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/
│   │   │   ├── automation/   # 164 files: UI agents, tools, actions, verifiers, schedulers
│   │   │   ├── core/         # 11 files: MaxRuntimeManager, event bus, state manager
│   │   │   ├── data/         # 16 files: Room AppDatabase, DAOs, entities, exporters
│   │   │   ├── flutter/      # 9 files: Legacy Flutter bridge delegates (TO BE REMOVED)
│   │   │   ├── knowledge/    # 1 file: InstalledAppsRepository
│   │   │   ├── memory/       # 25 files: Room memory, crypto, UniversalMemoryImporter, sync
│   │   │   ├── network/      # 11 files: GeminiWebSocketClient, planning, Windows companion
│   │   │   ├── receiver/     # 6 files: System broadcast receivers
│   │   │   ├── security/     # 3 files: Lock automation & unlock manager
│   │   │   ├── service/      # 12 files: Accessibility, overlays, voice & notification services
│   │   │   ├── telegram/     # 1 file: Cloud socket bridge
│   │   │   ├── ui/           # Jetpack Compose UI (Theme, Components, Screens, Navigation)
│   │   │   ├── utils/        # 7 files: Audio, stats, notification utilities
│   │   │   ├── viewmodel/    # Native Kotlin ViewModels
│   │   │   └── voice/        # 37 files: AssistantEngine, Gemini Live, audio pipeline
│   │   └── res/              # Android resources (drawables, values, mipmaps)
│   └── build.gradle.kts      # Android application dependencies & plugins
├── max_flutter/          # Legacy Flutter module (TO BE REMOVED in Phase 16)
│   └── lib/              # 41 Dart files (Theme, screens, services, bridge)
└── build.gradle.kts      # Root build configuration
```

## Package Responsibilities

| Package | Files | Core Responsibilities | Key Symbols |
| :--- | :---: | :--- | :--- |
| `com.example.core.lifecycle` | 2 | Master runtime state & service control | `MaxRuntimeManager` |
| `com.example.voice.assistant` | 11 | Gemini Live voice pipeline, session state, event bus | `AssistantEngine`, `AssistantLifecycleManager`, `AssistantEventBus`, `SessionStateMachine` |
| `com.example.data.local` | 13 | Room database schema v12 & persistent entities | `AppDatabase`, `ChatMessageDao`, `PeriodLogDao`, `ReminderDao`, `AutoReplyRuleDao` |
| `com.example.memory.data` | 11 | Encrypted permanent memory, embeddings, sync | `MemoryRepository`, `PermanentMemory`, `PermanentMemoryDao` |
| `com.example.memory.universal`| 4 | Universal import across ChatGPT, Claude, etc. | `UniversalMemoryImporter`, `ProviderDetector`, `ImportParsers` |
| `com.example.data.export` | 1 | Period isolated data markdown exporter | `PeriodMarkdownExporter` |
| `com.example.automation.tools`| 28 | 40+ native hardware, OS, and app tools | `ToolRegistry`, `OpenAppTool`, `WhatsAppTool`, `VolumeTool`, `ShizukuTool` |
| `com.example.automation.engine`| 13 | Autonomous execution, goal planning, loop detection | `ExecutionEngine`, `GoalPlanner`, `SmartRouter`, `RetryEngine` |
| `com.example.service` | 12 | System services (Accessibility, Overlay, Notification) | `JarvisAccessibilityService`, `AccessibilityTreePruner`, `MaxCapsuleOverlayService` |
| `com.example.ui` | - | Jetpack Compose Design System & UI Shell | `MaxTheme`, `MaxNavGraph`, `HomeScreen`, `JarvisNeuralOrb` |
