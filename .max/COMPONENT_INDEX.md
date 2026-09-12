# MAX Native Component Index

| Component Name | Canonical Location | Core Responsibility | Consumers | Migration Status |
| :--- | :--- | :--- | :--- | :---: |
| `AppDatabase` | `com.example.data.local.AppDatabase` | Room ORM schema v12 | DAOs, Repositories | AUTHORITATIVE (Preserved) |
| `ChatMessageDao` | `com.example.data.local.ChatMessageDao` | Chat history persistence | `HomeViewModel`, `AssistantEngine` | AUTHORITATIVE (Preserved) |
| `MemoryRepository` | `com.example.memory.data.MemoryRepository` | Encrypted memory & sync | `MemoryViewModel`, `UniversalMemoryImporter` | AUTHORITATIVE (Preserved) |
| `UniversalMemoryImporter`| `com.example.memory.universal.UniversalMemoryImporter`| Multi-provider memory import | `MemoryImportViewModel` | AUTHORITATIVE (Preserved) |
| `MemorySyncService` | `com.example.memory.sync.MemorySyncService` | Markdown mirror to file | `MemoryRepository` | AUTHORITATIVE (Preserved) |
| `PeriodMarkdownExporter` | `com.example.data.export.PeriodMarkdownExporter` | Isolated period log export | `PeriodViewModel` | AUTHORITATIVE (Preserved) |
| `ToolRegistry` | `com.example.automation.tools.ToolRegistry` | 40+ hardware/OS action tools | `ExecutionEngine`, `AssistantEngine` | AUTHORITATIVE (Preserved) |
| `ExecutionEngine` | `com.example.automation.engine.ExecutionEngine` | Plan execution & verification | `GoalPlanner`, `RoutineTool` | AUTHORITATIVE (Preserved) |
| `AssistantEngine` | `com.example.voice.assistant.AssistantEngine` | Gemini Live voice pipeline | `AssistantLifecycleManager`, `AssistantUseCase` | AUTHORITATIVE (Preserved) |
| `AssistantLifecycleManager`| `com.example.voice.assistant.AssistantLifecycleManager`| Engine lifecycle & worker mode | `HomeViewModel`, `MaxRuntimeManager` | AUTHORITATIVE (Preserved) |
| `AssistantEventBus` | `com.example.voice.assistant.AssistantEventBus` | Real-time event broadcasting | `AssistantUseCase`, `HomeViewModel` | AUTHORITATIVE (Preserved) |
| `MaxRuntimeManager` | `com.example.core.lifecycle.MaxRuntimeManager` | Master ON/OFF runtime switch | `HomeViewModel`, `RuntimeViewModel` | AUTHORITATIVE (Preserved) |
| `JarvisAccessibilityService`| `com.example.service.JarvisAccessibilityService`| OS UI tree & gesture engine | `UiVerificationLoop`, `ClickUiTool` | AUTHORITATIVE (Preserved) |
| `AccessibilityTreePruner`| `com.example.service.AccessibilityTreePruner`| UI node token optimization | `GeminiVisionReasoner` | AUTHORITATIVE (Preserved) |
| `UiVerificationLoop` | `com.example.automation.agent.UiVerificationLoop` | Closed-loop UI action loop | `UiAgentTool`, `AutomationViewModel` | AUTHORITATIVE (Preserved) |
| `InstalledAppsRepository`| `com.example.knowledge.apps.InstalledAppsRepository`| Local launcher app index | `OpenAppAction`, `GoalPlanner` | AUTHORITATIVE (Preserved) |
| `MaxFlutterActivity` | `com.example.flutter.MaxFlutterActivity` | Legacy Flutter container | Flutter Engine | DEPRECATED (Phase 16 deletion) |
| `MaxNativeBridge` | `com.example.flutter.MaxNativeBridge` | Legacy MethodChannel bridge | Flutter delegates | DEPRECATED (Phase 16 deletion) |
| `AssistantUseCase` | `com.example.domain.usecase.AssistantUseCase` | Voice & chat domain boundary | `HomeViewModel` | TO BE CREATED (Phase 2) |
| `HomeViewModel` | `com.example.ui.home.HomeViewModel` | Native state holder for Home | `HomeScreen` | TO BE CREATED (Phase 5) |
