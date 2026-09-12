# MAX Target Native Architecture

## Core Architectural Layers

```text
┌────────────────────────────────────────────────────────┐
│             Native Jetpack Compose UI                  │
│  (MaxApp, MaxNavGraph, HomeScreen, MaxTheme, MaxCard)  │
└───────────────────────────┬────────────────────────────┘
                            │ StateFlow / SharedFlow
┌───────────────────────────▼────────────────────────────┐
│                    Kotlin ViewModels                   │
│   (HomeViewModel, MemoryViewModel, SettingsViewModel)  │
└───────────────────────────┬────────────────────────────┘
                            │ suspend calls / Coroutine Flow
┌───────────────────────────▼────────────────────────────┐
│                  Kotlin Domain UseCases                │
│    (AssistantUseCase, MemoryUseCases, PeriodUseCase)   │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│              Authoritative Native Engines              │
│  ├── AssistantEngine (Gemini Live, Audio Recorder/Play)│
│  ├── MaxRuntimeManager (Master ON/OFF Controller)      │
│  ├── ExecutionEngine & ToolRegistry (40+ Tools)        │
│  ├── MemoryRepository & UniversalMemoryImporter        │
│  └── JarvisAccessibilityService & Tree Pruner          │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│              Persistence & Android Subsystems          │
│  ├── Room AppDatabase v12 (Messages, Memories, Reminders)│
│  ├── Local Filesystem Storage (MAX_DATA/MAX_MEMORY.md)  │
│  └── Android OS (Alarms, Audio, KeyStore, Shizuku)     │
└────────────────────────────────────────────────────────┘
```

## Architectural Contracts

1. **Unidirectional Data Flow**:
   - `ViewModel` exposes immutable `StateFlow<UiState>` for screen rendering.
   - `ViewModel` exposes `SharedFlow<UiEvent>` for one-shot side-effects (snackbars, navigation, errors).
   - `Compose UI` triggers user intent events directly into `ViewModel` functions.

2. **Subtree Recomposition & Performance**:
   - High frequency streams (e.g. `AudioRmsChanged`, streaming token text) are collected only at leaf composables (`MaxVoiceVisualizer`, `JarvisNeuralOrb`, `LiveCaptionView`).
   - Root composable (`HomeScreen` / `MaxApp`) remains stable.

3. **Master Runtime Authority**:
   - `MaxRuntimeManager` is the single source of truth for MAX global enabled state.
   - MAX OFF halts all active network streams, audio pipelines, overlays, and background tasks.

4. **Zero Bridge Architecture**:
   - No `MethodChannel`, no `MaxNativeBridge`, no intermediate serialization for UI communication.
