# MAX — Gemini 3.8 Live Migration Guide & Implementation Report

**Migration Objective:** Upgrade MAX Assistant & Windows Companion to `gemini-3.8-live`  
**Status:** Completed & Verified ✅  
**Date:** September 2026  

---

## 1. Summary of Changes

| File | Change Description |
|------|--------------------|
| `app/src/main/java/com/example/network/GeminiWebSocketClient.kt` | Centralized `DEFAULT_LIVE_MODEL = "gemini-3.8-live"`, added `FALLBACK_LIVE_MODEL = "gemini-3.1-flash-live-preview"`, and implemented dynamic model sanitization & candidate fallback list. |
| `app/src/main/java/com/example/data/preferences/SettingsManager.kt` | Updated default `liveVoiceModelName` preference to `"gemini-3.8-live"`. |
| `app/src/main/java/com/example/ui/settings/SettingsUiState.kt` | Updated UI default state `liveVoiceModelName` to `"gemini-3.8-live"`. |
| `windows-agent/config.py` | Updated `GEMINI_MODEL = "gemini-3.8-live"`. |
| `windows-agent/scripts/find_bad_tool_schemas.py` | Updated test harness model reference to `"gemini-3.8-live"`. |
| `app/src/test/java/com/example/network/GeminiWebSocketClientUnitTest.kt` | Added unit tests for model name sanitization, prefix handling, and candidate initialization. |
| `docs/max/AI.md`, `docs/max/VOICE.md`, `MAX_PROJECT_STATE.md`, `docs/max/KNOWN_ISSUES.md` | Updated system architecture documentation and resolved issue H-02. |

---

## 2. Technical Details & Configuration

### 2.1 Model Centralization & Sanitization
In `GeminiWebSocketClient.kt`:
```kotlin
companion object {
    const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"
    const val FALLBACK_LIVE_MODEL = "gemini-3.1-flash-live-preview"

    fun sanitizeModelName(name: String): String {
        val trimmed = name.trim()
        return when {
            trimmed.isBlank() || trimmed.equals("Auto", ignoreCase = true) -> DEFAULT_LIVE_MODEL
            trimmed.startsWith("models/") -> trimmed.substringAfter("models/")
            else -> trimmed
        }
    }
}
```

### 2.2 Model Fallback Sequence
If the primary candidate encounters an authorization, quota, or policy error (`1008`), the client automatically steps through `modelCandidates` before failing:
1. `gemini-3.8-live` (Primary)
2. `gemini-3.1-flash-live-preview` (Fallback)

### 2.3 Audio Specifications
- **Input (PCM Microphone)**: `16,000 Hz`, 16-bit Mono linear PCM (`audio/pcm;rate=16000`).
- **Output (AudioTrack Speaker)**: `24,000 Hz`, 16-bit Mono linear PCM.
- **Interruption**: Server-side VAD emits `interrupted: true` -> resets local input transcript accumulator and flushes playback queue.

---

## 3. Verification & Validation

### 3.1 Unit Testing
- Executed full unit test suite: `gradlew.bat testDebugUnitTest`
- Verified model sanitization across edge cases:
  - Blank string -> `gemini-3.8-live`
  - `"Auto"` / `"auto"` -> `gemini-3.8-live`
  - `"models/gemini-3.8-live"` -> `gemini-3.8-live`
  - Custom model names preserved without distortion.

### 3.2 Build Verification
- Debug APK: `gradlew.bat assembleDebug` -> SUCCESS
- Release APK: `gradlew.bat assembleRelease` -> SUCCESS

---

## 4. Rollback Plan
If rollback to previous model is ever required:
1. In `GeminiWebSocketClient.kt`, set `DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"`.
2. In `SettingsManager.kt`, set default `liveVoiceModelName` to `"gemini-3.1-flash-live-preview"`.
3. In `windows-agent/config.py`, set `GEMINI_MODEL = "gemini-3.1-flash-live-preview"`.
4. Rebuild project.
