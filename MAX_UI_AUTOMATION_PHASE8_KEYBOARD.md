# MAX UI Automation — Phase 8: Universal Input & Keyboard Control Layer

## 1. Overview & Objective
Phase 8 establishes a deterministic **Keyboard Control Layer** for MAX, transforming simple text entry into full multi-tier keyboard and IME control.

This resolves the false capability reporting issue where MAX previously reported inability to type ("I can't type" / "type cheyalenu") despite having underlying Android text insertion capabilities.

---

## 2. Architecture & 3-Tier Capability Router

```text
User Request: "Maps open cheyyi → Palakollu to Bhimavaram type cheyyi → Enter kottu"
                      ↓
           Gemini Live / ToolDispatcher
                      ↓
             KeyboardController
                      ↓
┌──────────────────────────────────────────────────────────────┐
│  Tier 1: Accessibility Actions (Primary, Low Latency)        │
│  - ACTION_SET_TEXT (arbitrary Unicode & multiline text)      │
│  - ACTION_IME_ENTER (native editor search/go/done actions)   │
│  - ACTION_FOCUS & cursor placement                           │
├──────────────────────────────────────────────────────────────┤
│  Tier 2: Grounded On-Screen Semantic & IME Controls          │
│  - Search / Go / Done / Submit button grounding & click      │
│  - Generic service IME actions                               │
├──────────────────────────────────────────────────────────────┤
│  Tier 3: Shizuku / ADB Shell Fallback                        │
│  - `input text <string>` (Canvas, Flutter, RN, WebViews)     │
│  - `input keyevent <keycode>` (ENTER=66, BACK=4, TAB=61...)  │
└──────────────────────────────────────────────────────────────┘
                      ↓
           InputVerifier (100ms Adaptive Polling)
                      ↓
           Instant Success Verification
```

---

## 3. New & Enhanced Components

### 1. `KeyboardController` (`com.example.automation.keyboard.KeyboardController`)
- **Key Map**: Maps keys like `ENTER`, `BACK`, `TAB`, `SPACE`, `DELETE`, `BACKSPACE`, `ESC`, `HOME`, `END`, `ARROW_UP`, `ARROW_DOWN`, `ARROW_LEFT`, `ARROW_RIGHT`, `SHIFT`, `CTRL`, `ALT`.
- **`typeText(context, text, targetField, replace, pressEnterAfter, executionId)`**:
  - Validates security restrictions (password/PIN/OTP blocked).
  - Emits `AutomationEvent.TargetGrounded` and `AutomationEvent.TypingStarted`.
  - Dispatches via Accessibility `ACTION_SET_TEXT` $\rightarrow$ Generic Service $\rightarrow$ Shizuku `input text`.
  - Verifies text via `InputVerifier.verifyTextEntered`.
- **`clearText(context, targetField)`**:
  - Clears text in active or target field using empty bundle `ACTION_SET_TEXT` or Shizuku backspace.
- **`pressEnter(context, targetField)`**:
  - Dispatches `ACTION_IME_ENTER` $\rightarrow$ Grounded Search/Go button $\rightarrow$ Service IME $\rightarrow$ Shizuku `input keyevent 66`.
- **`pressKey(context, keyNameOrCode, targetField)`**:
  - Handles `GLOBAL_ACTION_BACK` for Back key, or dispatches keyevent via Shizuku.

### 2. `InputTargetResolver` (`com.example.automation.resolver.InputTargetResolver`)
- **Priority Resolution Order**:
  1. Explicit target hint resolution (via `UiTargetResolver.resolve`)
  2. Focused input node (`findFocus(FOCUS_INPUT)` or `isFocused`)
  3. Single active editable field (`isEditable` or `EditText` class)
  4. Search bar / query input heuristics (`search`, `search_box`, `search_bar`, `query`, `omnibox`)
  5. First interactive editable container

### 3. `InputVerifier` (`com.example.automation.verification.InputVerifier`)
- **Focus Verification**: `verifyFocusAcquired` (100ms polling).
- **Text Insertion Verification**: `verifyTextEntered` (validates actual text in refreshed target node, fresh root hierarchy, or visible text pool).
- **Clear Text Verification**: `verifyClearText`.
- **Keyboard Action Verification**: `verifyKeyboardActionExecuted` (signature change or non-crashing execution).

### 4. New Registered Tools
- **`type_text`** (`TypeTextTool`): Arbitrary string input into active/grounded field with optional `press_enter` parameter.
- **`press_enter`** (`PressEnterTool`): Dispatches IME search/go/submit or Enter key.
- **`press_key`** (`KeyboardActionTool`): Dispatches physical/virtual keys (`enter`, `back`, `tab`, `space`, `delete`, `esc`, arrows).
- **`clear_text`** (`ClearTextTool`): Clears focused/targeted text field.

### 5. Gemini Live System Prompt Rules
- Explicitly documented native keyboard tools in PromptBuilder Section 7.
- MAX is instructed never to respond with "I cannot type" / "type cheyalenu". Instead, MAX directly calls `type_text`, `press_enter`, `press_key`, or `clear_text`.

---

## 4. Verification & Testing

- **Unit Tests**: `KeyboardControllerUnitTest.kt` and `TypeTextToolTest.kt` passing 100% (272 total tests passing in suite).
- **Build**: `./gradlew assembleDebug` passed in 21 seconds.
- **Deployment**: APK installed on Redmi K20 Pro (`3bc00016`).
