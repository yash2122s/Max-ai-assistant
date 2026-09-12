# MAX Native Data Flows

## 1. Chat & Assistant Message Flow

```text
[User Text Input]
       │
       ▼
HomeScreen (triggers HomeViewModel.sendTextMessage(text))
       │
       ▼
HomeViewModel
  ├── Persists user message into Room (ChatMessageDao.insertMessage)
  └── Invokes AssistantUseCase.sendTextMessage(text)
       │
       ▼
AssistantUseCase
       │
       ▼
AssistantEngine
  ├── Checks OfflineCommandEngine first
  └── Sends prompt via GeminiWebSocketClient / connectionManager
       │
       ▼
[Gemini Live Server / Response Stream]
       │
       ▼
AssistantEngine.streamingResponseManager
  ├── Emits AssistantEvent.TextTranscribed to AssistantEventBus
  └── Plays incoming PCM audio via AudioPlayer (triggers RMS updates)
       │
       ▼
AssistantEventBus.events
       │
       ▼
HomeViewModel (observes AssistantEventBus)
  ├── Persists model response into Room (ChatMessageDao.insertMessage)
  └── Updates _uiState with live captions & speaking indicators
       │
       ▼
HomeScreen (recomposes chat stream & live caption without full UI rebuild)
```

## 2. Voice Live Audio & RMS Visualizer Flow

```text
HomeScreen (Mic Button clicked)
       │
       ▼
HomeViewModel.toggleRecording()
       │
       ▼
AssistantUseCase.toggleListening() -> AssistantEngine.startListening()
       │
       ├── Starts AudioRecorder with AudioProcessor
       └── Streams PCM audio chunks to Gemini WebSocket
              │
              ▼
       AudioRecorder computes audio RMS (0f..1f)
              │
              ▼
       AssistantEventBus.emit(AssistantEvent.AudioRmsChanged(rms))
              │
              ▼
       MaxVoiceVisualizer / JarvisNeuralOrb (Collects RMS directly in leaf composable)
```

## 3. Master Runtime Toggle Flow

```text
HomeScreen (Master Switch Toggled)
       │
       ▼
HomeViewModel.setRuntimeEnabled(enabled)
       │
       ▼
MaxRuntimeManager.enableMax() / disableMax()
       │
       ├── Updates SettingsManager persistence
       ├── Emits to _isMaxEnabledFlow
       └── Starts or Stops AssistantEngine, VoiceForegroundService, Overlay & Telegram
```
