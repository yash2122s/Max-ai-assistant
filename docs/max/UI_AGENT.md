# MAX UI Automation Agent Architecture

## Overview

The MAX UI Automation Agent is a provider-independent visual grounding and action execution framework designed for complex, non-deterministic screen workflows on Android.

MAX strictly adheres to the **Token-Efficiency Rule**:
- **Known Operations / Tools** (e.g. Flashlight, Alarm, Reminder, Memory, Contacts, SMS, Volume, Media, App Launch): Handled deterministically via direct Android APIs and specialized Kotlin `Tool` implementations in `ToolRegistry` with zero LLM vision overhead.
- **Dynamic / Unknown UI Tasks** (e.g. "Order food on Swiggy", "Accept invite in group on WhatsApp", "Click the 3rd blue item"): Dispatched to the `UiVerificationLoop` via `UiAgentRouter`.

---

## Provider Architecture

```
                                  [ User / Gemini Live Tool ]
                                              │
                                              ▼
                                      [ UiAgentRouter ]
                                              │
                     ┌────────────────────────┴────────────────────────┐
                     ▼                                                 ▼
        [ OpenRouterUiAgentProvider ]                       [ GeminiUiAgentProvider ]
          (PRIMARY UI AGENT)                                  (FALLBACK UI AGENT)
          - Default: stealth/ox-alpha                         - gemini-2.0-flash
          - Dynamic model selection                           - Low-latency backup
                     │                                                 │
                     └────────────────────────┬────────────────────────┘
                                              ▼
                                   [ UiVerificationLoop ]
                                 (Max 8 Bounded Steps)
                                              │
                        ┌─────────────────────┴─────────────────────┐
                        ▼                                           ▼
             [ ScreenCaptureProvider ]                    [ UiActionExecutor ]
             - RAM JPEG compression                       - Tier 1: Accessibility Gestures
             - ScreenContextProvider                      - Tier 2: Shizuku / Root ADB
```

### 1. Primary Provider: OpenRouter (`OpenRouterUiAgentProvider`)
- **Default Model**: `stealth/ox-alpha`
- **Dynamic Model Selection**: Connects to `https://openrouter.ai/api/v1/models` and filters for multimodal models supporting tool calling and vision.
- **Curated High-Performance Models**:
  - `stealth/ox-alpha` (Preferred default for MAX)
  - `anthropic/claude-3.5-sonnet`
  - `openai/gpt-4o`
  - `google/gemini-2.5-flash`
  - `qwen/qwen-2.5-vl-72b-instruct`

### 2. Fallback Provider: Google Gemini (`GeminiUiAgentProvider`)
- Activated automatically when OpenRouter is unconfigured, rate-limited, or encounters network failure.
- Uses `gemini-2.5-flash` (with secondary fallback to `gemini-1.5-flash`) via direct REST `generateContent` function calling.
- Model names are centralized in `UiAgentModels.kt` / `GeminiModelConfig.kt` and strictly separated from Gemini Live voice models (`gemini-3.8-live`).

---

## Atomic UI Actions (`UiAction`)

The UI Agent can emit the following verified actions:
1. `TAP(x, y)`: Screen coordinate click.
2. `LONG_PRESS(x, y, durationMs)`: Long press.
3. `SWIPE(startX, startY, endX, endY, durationMs)`: Directional gesture.
4. `SCROLL(direction)`: UP, DOWN, LEFT, RIGHT swipe.
5. `TYPE(text)`: Input text into focused field.
6. `KEY_BACK`: System navigation back.
7. `KEY_HOME`: System home screen.
8. `LAUNCH_APP(packageName)`: Intent launcher.
9. `FINISH(success, message)`: Termination condition.

---

## Action Execution & Verification

### Execution Tiers (`UiActionExecutor`)
- **Tier 1 (Accessibility)**: Dispatches gestures via `JarvisAccessibilityService.dispatchGesture`.
- **Tier 2 (Shizuku / ADB Shell)**: Executes `input tap`, `input swipe`, `input text`, `input keyevent` when Shizuku permission is available.

### Verification Loop (`UiVerificationLoop`)
Every action is followed by:
1. Settle delay (600ms) for UI transition.
2. Observation comparison (screenshot + accessibility tree before vs after).
3. State change validation (e.g. did target app open? did text register? did screen change?).
4. Strict bounded loop (max 8 steps) preventing infinite loops.

---

## Configuration & Security
- **Secure Storage**: API Keys (`ui_agent_api_key`, `gemini_api_key`) are encrypted in `EncryptedSharedPreferences`.
- **Zero API Key Leakage**: Keys are never logged, never rendered in plaintext in the UI, and never exposed in debug transcripts.
- **Onboarding & Settings**: Configurable from both Onboarding Step 2 (`AiConfigScreen`) and System Settings (`SettingsScreen`).
