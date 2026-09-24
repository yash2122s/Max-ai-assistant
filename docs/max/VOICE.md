# Voice Subsystem — MAX

## Purpose
Manages microphone input, audio output, session services, and voice interaction triggers.

## Main Classes

| Class | File | Role |
|-------|------|------|
| `AudioRecorder` | `voice/audio/AudioRecorder.kt` | PCM recording (16-bit, 16kHz mono) via AudioRecord |
| `AudioPlayer` | `voice/audio/AudioPlayer.kt` | Plays PCM audio from Gemini |
| `AudioProcessor` | `voice/audio/AudioProcessor.kt` | Pre-processes audio chunks before sending |
| `AudioFocusManager` | `voice/audio/AudioFocusManager.kt` | Android audio focus management |
| `AssistantEngineService` | `voice/session/AssistantEngineService.kt` | Bound foreground service (type=microphone); hosts AssistantEngine |
| `VoiceForegroundService` | `service/VoiceForegroundService.kt` | Alternative foreground service for continuous mic |
| `JarvisVoiceInteractionService` | `voice/session/JarvisVoiceInteractionService.kt` | Android VoiceInteractionService (BIND_VOICE_INTERACTION) |
| `JarvisRecognitionService` | `voice/session/JarvisRecognitionService.kt` | android.speech.RecognitionService stub |
| `JarvisVoiceSession` | `voice/session/JarvisVoiceSession.kt` | VoiceInteractionSession implementation |
| `JarvisVoiceTriggerActivity` | `voice/trigger/JarvisVoiceTriggerActivity.kt` | ASSIST intent handler; showWhenLocked=true; turnScreenOn=true |

## HUD UI Components

| Component | File | Notes |
|-----------|------|-------|
| `AssistantHud` | `voice/ui/AssistantHud.kt` | Full HUD wrapper |
| `BottomPill` | `voice/ui/BottomPill.kt` | Bottom voice control pill |
| `SoundWave` | `voice/ui/SoundWave.kt` | 6-bar gradient frequency visualizer |
| `TranscriptBubble` | `voice/ui/TranscriptBubble.kt` | Shows transcription |
| `GlowBorder` | `voice/ui/GlowBorder.kt` | Animated glow effect |
| `LiquidScreenEdges` | `voice/ui/LiquidScreenEdges.kt` | Edge animation during processing |

## Voice Pipeline
```
Mic → AudioRecord (PCM 16-bit 16kHz) → AudioRecorder
  └─ onAudioChunk → ConnectionManager.sendAudio()
        └─ GeminiWebSocketClient → WSS: realtime_input.media_chunks
              └─ Gemini Live processes speech
                    └─ Audio PCM response → onAudioReceived
                          └─ StreamingResponseManager → AudioPlayer (AudioTrack WRITE_BLOCKING)
```

## Session States
```
IDLE → CONNECTING → CONNECTED → LISTENING → WAITING_FOR_MODEL → STREAMING_AUDIO → CONNECTED
```

## Barge-in
When `startListening()` is called while Gemini is speaking:
- `streamingResponseManager.handleUserSpeaking()` → flush AudioTrack immediately
- Prevents echo/feedback loops

## Voice Trigger
- `JarvisVoiceTriggerActivity` handles `android.intent.action.ASSIST`
- Works even on lock screen (`showWhenLocked=true`, `turnScreenOn=true`)
- No local wake word (Vosk not present)

## Voice Settings (in SettingsManager)
- `isLiveVoiceMode`: true = Gemini Live audio; false = text-only
- `modelRoutingMode`: `AUTO` (default), `FAST`, `DEEP`
- `liveVoiceModelName`: model name (defaults to `gemini-3.8-live`, deep `gemini-3.8-live-extended-thinking`, fallback `gemini-3.1-flash-live-preview`)
- `liveVoiceName`: Aoede (default), Kore, Fenrir, Puck, etc.

## Performance Targets
- Voice response: <300ms
- Audio stutter fix: WRITE_BLOCKING ensures full PCM chunks queued

## Current Status: IMPLEMENTED ✅
Voice session working with dual-model routing (`gemini-3.8-live` and `gemini-3.8-live-extended-thinking`). Barge-in working. HUD components rendered.

## Known Issues
- No local wake word — user must manually trigger or use ASSIST intent
- Two foreground services for microphone: `AssistantEngineService` and `VoiceForegroundService` — potential duplication

