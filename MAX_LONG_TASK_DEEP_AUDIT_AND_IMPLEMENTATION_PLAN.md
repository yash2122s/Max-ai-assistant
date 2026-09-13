# MAX AI — DEEP ARCHITECTURE AUDIT & WEEKEND IMPLEMENTATION PLAN
**Document Version:** 1.0.0 | **Audit Date:** 2026-09-13 | **Author:** Senior Android AI-Agent Architect  
**Workspace:** `d:\max app testing\gemini-live` | **Target Package:** `com.example` | **App ID:** `com.aistudio.geminilive.abcde`  
**Target Hardware:** Redmi K20 Pro (`3bc00016`) — Android 16 (API 36) — Root + Shizuku  

---

## 1. Executive Summary

### 1.1 Objective & Context
This document presents an exhaustive architectural and forensic code audit of MAX AI to transition the system from single-step app triggering to a **true general-purpose long-horizon Android AI agent**. The target behavior enables natural-language multi-step requests across arbitrary Android applications (e.g., *"Open Chrome, search YouTube, open the first result and play the video"*, *"Open Google Maps, search Palakollu to Bhimavaram, open directions"*, *"Open Instagram, search a username, open profile and inspect the first post"*, *"Send WhatsApp message to Yaswanth that I am in a meeting and will call back later"*) without hardcoded app scripts and without stopping prematurely after opening an application.

### 1.2 Core Audit Verdict
MAX AI contains world-class foundational primitives:
- Low-latency real-time voice streaming (`GeminiWebSocketClient` over WSS to `gemini-3.1-flash-live-preview`).
- Highly capable accessibility integration (`JarvisAccessibilityService` with gesture escalation, PIN unlock, and screen hierarchy parsing).
- Deterministic action dispatching and execution (`ActionDispatcher`, `ExecutionEngine`, `RetryEngine`, `VerificationRegistry`).
- Robust input controls (`KeyboardController`, `InputTargetResolver`, `InputVerifier`).
- Bulletproof safety gating and intent collision protection (`ActionAuthorizer`).

**However, long-horizon multi-step execution currently breaks down due to 5 systemic structural failures:**
1. **The Second Source of Truth & Validation Disconnect:** `KeyboardActionTool` exposes `PRESS_KEY` to `PlannerToolDiscovery` and Gemini's prompt, but `GoalPlanner.SUPPORTED_ACTIONS` acts as a second hardcoded source of truth that omitted `PRESS_KEY`. When Gemini generates `PRESS_KEY`, `GoalPlanner.validatePlan()` immediately aborts with `"Invalid generated plan: Unsupported actionType 'PRESS_KEY'"`. Rather than patching another hardcoded whitelist string, the architectural solution is dynamic validation: `ToolRegistry → capabilities → Planner validation`.
2. **The "Four Orchestrators" Fragmentation (Single Owner Failure):** The codebase contains 4 disjointed orchestrators (`GoalPlanner`, `LongTaskOrchestrator`, `GenericUiStepOrchestrator`, and `UiVerificationLoop`). None of them form a unified closed loop, and no single component owns step progression:
   - `GoalPlanner` generates a static plan and executes blind to post-step UI changes.
   - `LongTaskOrchestrator` is wired only to `TASK_INTENT` with hardcoded 3-app DAGs; for arbitrary apps (Tier 3), it generates a 1-step plan (`OpenApp`) and halts.
   - `GenericUiStepOrchestrator` is the proper closed-loop candidate, but is orphaned (called only in unit tests).
   - `UiVerificationLoop` calls cloud LLMs on every single step with high latency (15–30s).
3. **Broken Perception-Action Pipeline:** After launching an app or completing a step, `GoalPlanner` captures `ScreenContextProvider.captureScreenContext()`, but **discards it completely**. The next step executes using pre-planned static targets without re-grounding or verifying what is actually rendered on screen.
4. **Target Resolution Semantic Blindspots:** `UiTargetResolver` cannot resolve ordinal or semantic descriptors (e.g., *"first result"*, *"first video"*, *"Directions"*, *"Play"*). It only does text matching, failing when Gemini outputs intent-level target names.
5. **Premature / Orphaned Skill Memory:** `SkillMemoryRepository` (204 lines) is uncalled, and attempts to cache skills before stabilizing the underlying closed loop (`PLAN → OBSERVE → GROUND → EXECUTE → VERIFY → RECOVER → CONTINUE`) would poison memory with broken execution traces.

This plan details the complete root-cause breakdown and specifies the unified 10-phase target architecture to be implemented over the weekend.

---

## 2. Current Architecture

The MAX runtime architecture is partitioned into several independent subsystems:

```
                                  USER VOICE / TEXT
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                               ▼
         [Voice Streaming]                               [Text / HUD Ingress]
     AudioRecorder / AudioFocus                         ChatViewModel / HomeViewModel
                 │                                               │
                 ▼                                               ▼
     GeminiWebSocketClient                              AssistantUseCase
  (gemini-3.1-flash-live-preview)                                │
                 │                                               ▼
                 ├─────────────────────────────► OfflineCommandEngine (0-token rules)
                 │                                               │
                 ▼                                               ▼
       AssistantEngine.kt ◄─────────────────────── SemanticUiCommandParser
                 │
  ┌──────────────┴───────────────────────────────┐
  ▼                                              ▼
[Tool Call Path]                       [Deterministic Intercept]
ToolDispatcher.kt                      GoalPlanner.planAndExecute()
  │                                              │
  ▼                                              ▼
ActionDispatcher.kt                     GeminiPlanningProvider (REST)
  │                                              │
  ├───────────────────────────────┐              ▼
  ▼                               ▼     GoalPlanner.validatePlan()
[TASK_INTENT]              [Single Action]       │
LongTaskOrchestrator.kt    ExecutionEngine.kt    ▼
  │                               │     GoalPlanner.executePlan()
  ▼                               ▼              │
CapabilityRouter.kt        ToolRegistry.kt       ▼
  │                               │     ExecutionEngine.execute()
  ▼                               ▼              │
KeyboardController / Accessibility ◄─────────────┘
```

### Component Inventory & Roles:
- **`GeminiWebSocketClient` (`network/GeminiWebSocketClient.kt`):** Full-duplex audio/text WebSocket client. Declares 45 native tools in `toolsJsonStr`.
- **`AssistantEngine` (`voice/assistant/AssistantEngine.kt`):** Session state coordinator, VAD handler, barge-in controller, and voice ingress gateway.
- **`ToolDispatcher` (`network/ToolDispatcher.kt`):** Maps Gemini tool function calls into internal action JSON objects.
- **`ActionDispatcher` (`automation/engine/ActionDispatcher.kt`):** Action normalizer, intent collision protector, and dispatcher.
- **`GoalPlanner` (`automation/engine/GoalPlanner.kt`):** 1085-line central planning coordinator. Uses `GeminiPlanningProvider` for LLM plans, `validatePlan()` for constraints, and `executePlan()` for queue execution.
- **`TaskPlanner` (`automation/engine/planning/TaskPlanner.kt`):** 191-line planner with hardcoded WhatsApp, Maps, and Chrome DAGs.
- **`LongTaskOrchestrator` (`automation/engine/execution/LongTaskOrchestrator.kt`):** 61-line loop calling `CapabilityRouter`, `VerificationEngine`, and `TaskRecoveryEngine`.
- **`GenericUiStepOrchestrator` (`automation/engine/GenericUiStepOrchestrator.kt`):** 306-line closed-loop step executor with `UiStep` models.
- **`UiVerificationLoop` (`automation/agent/UiVerificationLoop.kt`):** 313-line per-step LLM reasoning agent loop.
- **`ExecutionEngine` (`automation/engine/ExecutionEngine.kt`):** Deterministic tool execution engine with `RetryEngine` and `VerificationRegistry`.
- **`UiTargetResolver` (`automation/resolver/UiTargetResolver.kt`):** 349-line accessibility node search engine.
- **`KeyboardController` (`automation/keyboard/KeyboardController.kt`):** 536-line 3-tier keyboard controller (Accessibility $\rightarrow$ Semantic IME $\rightarrow$ Shizuku).
- **`ActionAuthorizer` (`automation/engine/ActionAuthorizer.kt`):** 472-line security and authorization engine.
- **`JarvisAccessibilityService` (`service/JarvisAccessibilityService.kt`):** 1739-line primary accessibility service.

---

## 3. Actual Runtime Execution Graph

```
                                  USER COMMAND
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
             [VOICE INGRESS]                       [TEXT INGRESS]
          AssistantEngine (L329)                AssistantUseCase (L94)
                    │                                     │
         Is Deterministic/FastPath?              OfflineCommandEngine
             ┌──────┴──────┐                              │
            YES            NO                   Unmatched Complex Goal
             │             │                              │
             │     Stream to Gemini Live                  │
             │             │                              │
             │      Gemini Tool Call                      │
             │             │                              │
             │      ToolDispatcher (L147)                 │
             │             │                              │
             │     AssistantEngine (L66)                  │
             │             │                              │
             │      ActionDispatcher                      │
             │             │                              │
             ├─────────────┴──────────────────────────────┤
             ▼                                            ▼
   [Path A: TASK_INTENT]                       [Path B: GoalPlanner]
   ActionDispatcher (L109)                     GoalPlanner.planAndExecute()
             │                                            │
   TaskPlanner.createPlan()                     GeminiPlanningProvider.generatePlan()
             │                                            │
   Is Hardcoded App (WA/Maps/Chrome)?                     ▼
       ┌─────┴─────┐                            GoalPlanner.validatePlan()
      YES          NO (Generic Tier 3)                    │
       │           │                            ┌─────────┴─────────┐
       │     1 Step Plan: OPEN_APP ONLY!     [PRESS_KEY?]        [Other Actions]
       │           │                                │                   │
       ▼           ▼                                ▼                   ▼
   LongTaskOrchestrator                        REJECTED WITH:      GoalPlanner.executePlan()
   Executes Steps via CapabilityRouter         "Invalid plan:           │
       │                                        Unsupported action  Step 1: OPEN_APP
       ▼                                        'PRESS_KEY'"            │
   WA: Taps search, types, sends                        │           Verifies Foreground
   Maps: Taps search, types, presses enter              ▼               │
   Chrome: Taps URL, types, presses enter         HALTS HERE       Captures Context
       │                                                           (AND DISCARDS IT!)
       ▼                                                                │
   HALTS HERE: No further actions planned!                         Step 2: CLICK_ELEMENT
                                                                        │
                                                                   UiTargetResolver
                                                                   ("first result")
                                                                        │
                                                                   Target Not Found!
                                                                        │
                                                                        ▼
                                                                   ReEvaluationEngine
                                                                   (Sends empty context)
                                                                        │
                                                                        ▼
                                                                   PLAN ABANDONED
```

---

## 4. Working Components

The following components are thoroughly implemented, functionally sound, and **MUST BE PRESERVED WITHOUT REGRESSION**:

1. **`GeminiWebSocketClient.kt`:** Full-duplex WebSocket connection to `gemini-3.1-flash-live-preview`, keep-alive pings, barge-in cancellation, and tool declaration generation.
2. **`ActionAuthorizer.kt`:**
   - Multi-tier approval modes (`MANUAL`, `SAFE_AUTO`, `FULL_AUTO`).
   - Hard security gates for critical keywords (`UPI`, `PIN`, `PASSWORD`, `PAYMENT`, `OTP`, `FACTORY_RESET`, `WIPE`, `FORMAT`, `UNLOCK`).
   - WhatsApp message collision protection (lines 409–439): Prevents messages containing the word "call" from becoming `CALL_PHONE`.
   - Authorized call phrases: Explicit calls (`"call "`, `"dial "`, `"call on whatsapp"`) correctly pass.
3. **`KeyboardController.kt`:**
   - Tier 1: Accessibility `ACTION_SET_TEXT` / `ACTION_IME_ENTER`.
   - Tier 2: Grounded On-Screen Semantic & IME Controls.
   - Tier 3: Shizuku / ADB shell `input text` and `input keyevent`.
   - Clean handling of `ACTION_SET_TEXT` and clear text.
4. **`InputTargetResolver.kt`:**
   - Resolves focused input nodes (`findFocus(FOCUS_INPUT)`), editable nodes, and search heuristics.
5. **`InputVerifier.kt`:**
   - 100ms adaptive polling verifying focus acquisition, text presence, and IME transitions.
6. **`JarvisAccessibilityService.kt`:**
   - Robust node traversal, screen signature hashing, gesture taps, swipes, PIN unlock automation, and click escalation (`performClickWithEscalation`).
7. **`ExecutionEngine.kt`:**
   - Parameter interpolation, execution metrics, `RetryEngine` integration, and 25s failsafe timeout.
8. **`WhatsAppContactResolver.kt`:**
   - Isolated contact name matching, chat row traversal, message snippet filtering, and top chat toolbar verification.
9. **`WhatsAppController.kt`:**
   - Dual-path execution: direct phone number deep-link (`api.whatsapp.com/send`) + accessibility search fallback.
10. **`MaxRuntimeManager.kt`:**
    - Developer master switch (`MAX ACTIVE` vs `MAX OFF`) checked before all background/UI actions.
11. **`Room Database (v14)` & `MemoryRepository.kt`:**
    - AES-encrypted permanent memories, hybrid vector search (cosine + keyword), tag filtering.

---

## 5. Broken Components

1. **`GoalPlanner.kt` (`SUPPORTED_ACTIONS` Whitelist, Line 626):**
   - **Problem:** Missing `PRESS_KEY`, `KEYBOARD_ACTION`, `TAP`, `SCROLL`, `SWIPE`, `CLEAR_TEXT`.
   - **Result:** Any plan containing `PRESS_KEY` generated by Gemini is immediately rejected at validation.
2. **`KeyboardController.kt` (Line 333):**
   - **Problem:** `val keyCode = KEY_MAP[normKey] ?: normKey.toIntOrNull() ?: KeyEvent.KEYCODE_ENTER`.
   - **Result:** Any unknown key string silently defaults to `KEYCODE_ENTER` instead of failing or throwing an unsupported key error.
3. **`ReEvaluationEngine.kt` (Lines 185–197 & 178–182):**
   - **Problem 1:** Passes an empty `ScreenContextPayload` (`prunedNodes = emptyList()`, `visibleText = ""`, `clickableElements = emptyList()`, `screenshotJpeg = null`) during failure re-evaluation. Gemini has zero visibility into why the action failed.
   - **Problem 2:** Prompts Gemini for an Object (`{"decision": "MODIFY", ...}`), but `GeminiPlanningProvider` configures a strict `ARRAY` schema on the Gemini REST API. This causes JSON schema validation or parse errors.
4. **`VerificationEngine.kt` (Line 81):**
   - **Problem:** `verifyMessageStatus` asserts `RuntimeStepVerifier.verifyTargetVisible(context, outcome.contactName)`. In WhatsApp, the contact name is already rendered in the top toolbar *before* the message is sent. The verification returns `true` even if typing or sending failed.
5. **`ScreenContextTool.kt` (Lines 91–97):**
   - **Problem:** Puts extracted screen information into `metadata: JSONObject`, leaving `outputData: Map<String, Any?>` empty.
   - **Result:** `ParameterInterpolationEngine` cannot interpolate extracted screen text into subsequent plan steps (`$step_1.text` is always unresolved).

---

## 6. Missing Components

1. **Dynamic Re-Observation Between Plan Steps:**
   - In `GoalPlanner.kt`, after Step 1 completes, `freshContext` is captured at line 462 but **never provided to Step 2**. Step 2 blindly executes pre-planned arguments.
2. **Ordinal & Relative UI Grounding in `UiTargetResolver`:**
   - Cannot resolve queries like `"first result"`, `"second item"`, `"top post"`, `"directions button"`, `"video thumbnail"`.
3. **Generic Long-Horizon Task Loop:**
   - Neither `GoalPlanner` nor `LongTaskOrchestrator` implements an adaptive:
     $$\text{OBSERVE} \longrightarrow \text{GROUND} \longrightarrow \text{AUTHORIZE} \longrightarrow \text{ACT} \longrightarrow \text{VERIFY} \longrightarrow \text{ADAPT}$$
     loop that dynamically decides the next step based on live screen state.
4. **`press_key` Verifier in `VerificationRegistry`:**
   - `KeyboardActionTool` specifies `verificationRequired = true`, but no verifier is registered in `VerificationRegistry`. It silently falls back to a unverified pass.
5. **Direct Intent Deep-Link Strategy for Common Tasks:**
   - Chrome search, Maps directions, and YouTube playback use cumbersome UI tap/type steps rather than safe, instant Android Intents (`google.navigation:q=...`, `vnd.youtube:...`, `https://www.google.com/search?q=...`).

---

## 7. Duplicate & Conflicting Components

| Area | Component 1 | Component 2 | Nature of Conflict / Duplication |
|---|---|---|---|
| **Task Context** | `com.example.automation.engine.TaskContext` | `com.example.automation.engine.state.TaskContext` | Exact identical class name in different packages; incompatible field schemas. |
| **Task Orchestration** | `com.example.automation.engine.GoalPlanner` | `com.example.automation.engine.execution.LongTaskOrchestrator` | Parallel planners with separate step models (`ActionStep` vs `TaskStep`), separate verifiers, and separate dispatching. |
| **Step Execution** | `com.example.automation.engine.GenericUiStepOrchestrator` | `com.example.automation.engine.execution.CapabilityRouter` | Duplicate execution switch statements for `UiStep` actions; `GenericUiStepOrchestrator` is never invoked in production. |
| **UI Grounding** | `com.example.automation.resolver.UiTargetResolver` | `com.example.automation.ui.ScreenGroundingEngine` | Both attempt fuzzy node resolution with slightly different heuristics and scoring thresholds. |
| **Tool Registry** | `com.example.automation.tools.ToolRegistry` | `com.example.voice.tools.ToolRegistry` | Two tool registries: static global registry vs assistant voice-scoped registry. |

---

## 8. Root Causes of Task Premature Termination

Why does a command like *"Open Chrome, search YouTube, open first result and play video"* stop after opening Chrome?

1. **Voice Ingress Single-Action Trap:**
   When user speaks the multi-step goal, Gemini Live WebSocket chooses to invoke `open_app(app_name="Chrome")`. It receives a successful execution response, announces "Opened Chrome", and closes its turn without emitting subsequent actions.
2. **`TaskPlanner` Tier 3 Stub:**
   When dispatched via `TASK_INTENT`, `TaskPlanner.routeTier3LongHorizon()` generates exactly **one** `TaskStep`: `UiStep.OpenApp`. It contains no subsequent steps, expecting dynamic replanning which is hardcoded to return `false`.
3. **`GoalPlanner` `PRESS_KEY` Schema Rejection:**
   When dispatched via `GoalPlanner`, the REST planning client produces a multi-step plan containing `PRESS_KEY`. `GoalPlanner.validatePlan()` rejects it, terminating before step 1 even begins.
4. **Blind Step 2 Execution:**
   Even if the plan passes validation, Step 1 opens Chrome. Chrome may display a bottom sheet, account chooser, or blank omnibox. Step 2 attempts to click `"Search or type URL"`. If Chrome is still rendering or animates, `UiTargetResolver` times out at 2500ms.
5. **Failure of Recovery Engine:**
   When Step 2 times out, `ReEvaluationEngine` passes an empty screen context to Gemini, which fails schema parsing and abandons the plan.

---

## 9. PRESS_KEY Contract Audit

### 9.1 Declaration & Exposure
- **Declared in:** `com.example.automation.tools.KeyboardActionTool`
  - `name`: `"press_key"`
  - `supportedActions`: `{"KEYBOARD_ACTION", "PRESS_KEY", "KEY_PRESS", "KEY_ENTER", "KEY_BACK", ...}`
  - `capabilities.supportsPlanner`: `true`
- **Exposed to Gemini Live:** `GeminiWebSocketClient.kt` line 217 (`toolsJsonStr` contains `press_key` function declaration).
- **Exposed to Gemini REST Planner:** `PlannerToolDiscovery.buildDescriptors()` discovers `KeyboardActionTool` because `supportsPlanner == true`. It renders `PRESS_KEY` into `PromptBuilder.buildPlanningSystemInstruction()`.

### 9.2 Validation & Schema Mismatch
- **Where validated:** `GoalPlanner.kt` line 663 in `validatePlan()`.
- **The Defect:**
  ```kotlin
  // GoalPlanner.kt Line 626
  val SUPPORTED_ACTIONS = setOf(
      "OPEN_APP", "OPEN_SETTINGS", "LAUNCH_APP", "SETTINGS", "CLICK_ELEMENT", "CLICK_TEXT", "CLICK_INDEX", "CLICK_UI", "FIND_ELEMENT", "TYPE_TEXT", "SCROLL_SCREEN",
      "PRESS_ENTER", "IME_ENTER", "ENTER_KEY", "PERFORM_ENTER", "BACK",
      // ... NO PRESS_KEY! NO KEYBOARD_ACTION!
  )
  ```
- **Execution Path:**
  Gemini Planner emits `ActionStep(actionType = "PRESS_KEY")` $\longrightarrow$ `GoalPlanner.validatePlan()` checks `normalizedAction !in SUPPORTED_ACTIONS` $\longrightarrow$ returns `"Unsupported actionType 'PRESS_KEY' at step '...'"` $\longrightarrow$ `GoalPlanner.planAndExecute()` returns `"Invalid generated plan: Unsupported actionType 'PRESS_KEY'"`.

### 9.3 Dispatch & Execution
- **`ToolDispatcher.kt`:** Maps `"press_key"` to `"PRESS_KEY"`.
- **`ActionDispatcher.kt`:** Normalizes `"KEYBOARD_ACTION"`, `"PRESS_KEY"`, `"KEY_PRESS"` to `"PRESS_KEY"`.
- **`ExecutionEngine.kt`:** Finds `KeyboardActionTool` in `ToolRegistry` via `supportedActions`.
- **`KeyboardController.kt`:**
  - Defect: Line 333 defaults unknown keys to `KeyEvent.KEYCODE_ENTER`.
  - Back key routes to `GLOBAL_ACTION_BACK` or Shizuku `input keyevent 4`.
  - Enter key routes to `pressEnter()`.
  - Other keys route to Shizuku `input keyevent <coderoll>`.

### 9.4 Clean Target Architecture for Keyboard Actions
1. **Eliminate Dual Source of Truth (Dynamic Capability Validation):** Do **NOT** merely add `"PRESS_KEY"` to `GoalPlanner.SUPPORTED_ACTIONS`. `GoalPlanner`'s hardcoded whitelist is a secondary source of truth that inevitably drifts from `ToolRegistry`. Instead, establish a unified capability validation pipeline:
   ```text
   ToolRegistry ───► capabilities ───► Planner Validation
   ```
   The planner queries `ToolRegistry.getAllActions()` / capability metadata dynamically. Any tool capability exposed to `PromptBuilder` is automatically recognized by `GoalPlanner.validatePlan()`, preventing recurring `Unsupported actionType 'XYZ'` failures forever.
2. **Strict Key Mapping in `KeyboardController`:** Add explicit supported keys to `KeyboardController`:
   `ENTER`, `BACKSPACE`, `TAB`, `ESCAPE`, `SPACE`, `ARROW_UP`, `ARROW_DOWN`, `ARROW_LEFT`, `ARROW_RIGHT`, `HOME`, `END`, `DELETE`.
3. **Eliminate Silent `ENTER` Fallback:** Never default unknown keys to `KeyEvent.KEYCODE_ENTER` (line 333 defect). If a key string is unknown and not a valid integer keycode, return an explicit structured error: `ToolResult.failure(errorCode = "UNSUPPORTED_KEY")`.
4. **Register `KeyboardActionVerifier`:** Register a dedicated verifier in `VerificationRegistry` to confirm IME state, query submission, and view transitions.

---

## 10. Long-Horizon Task Management Audit

| Concept | Existing Class | Package | Current Status | Verdict |
|---|---|---|---|---|
| **Task Model** | `AgentTask` | `automation.engine.state` | Ephemeral data class, in-memory only | Functional but not persistent |
| **Step Model** | `TaskStep` / `ActionStep` | `automation.engine.state` / `automation.state` | Duplicate step representations across modules | Needs consolidation |
| **Task Status** | `TaskStatus` | `automation.engine.state` | Enum (`PLANNING`, `READY`, `RUNNING`, `RECOVERING`...) | Fully defined |
| **Context Model** | `TaskContext` | `automation.engine.state` AND `automation.engine` | Two conflicting classes with identical names | Must unify into `engine.state` |
| **Task Manager** | `TaskManager` | `automation.scheduler` | AlarmScheduler / ScheduledTask manager | Not an agent task manager |
| **Long Orchestrator**| `LongTaskOrchestrator`| `automation.engine.execution` | 61-line loop, hardcoded DAGs, no re-observation | Incomplete |
| **Recovery Engine** | `TaskRecoveryEngine`| `automation.engine.execution` | 15-line stub (`step.retryCount++`) | Non-functional stub |
| **Replanner** | `ReEvaluationEngine`| `automation.engine` | Calls Gemini with empty screen context & wrong schema | Broken |

---

## 11. Observe $\rightarrow$ Act $\rightarrow$ Verify Loop Audit

The intended loop:
$$\text{OBSERVE} \longrightarrow \text{RESOLVE} \longrightarrow \text{AUTHORIZE} \longrightarrow \text{EXECUTE} \longrightarrow \text{WAIT} \longrightarrow \text{RE-OBSERVE} \longrightarrow \text{VERIFY} \longrightarrow \text{NEXT STEP}$$

### Current Reality:
1. **App Launch Transition:**
   `OPEN_APP` triggers `startActivity`. `OpenAppVerifier` polls foreground package for up to 2500ms. Once foreground is detected, the step succeeds immediately. However, the app's initial UI layout is rarely fully loaded or settled at that exact moment.
2. **Missing Post-Transition Wait:**
   The next step (`CLICK_ELEMENT`) immediately executes against a half-rendered hierarchy, failing to locate targets.
3. **Screen Hierarchy Refresh:**
   `AccessibilityNodeInfo.refresh()` in `ScreenContextProvider` occasionally returns `false` or throws if the root node was recycled by Android during an activity switch.
4. **Verification Lag:**
   `ClickUiVerifier` only checks if the click execution returned `true` (i.e., whether the accessibility event was dispatched), **not** whether the screen transitioned or the expected outcome occurred!

---

## 12. Target Resolution Audit

### 12.1 Priority in Code vs Reality
Current implementation in `UiTargetResolver.kt`:
1. **Resource ID Matching:** Confidence 0.99
2. **Exact Visible Text Matching:** Confidence 0.98 (Case-insensitive 0.95)
3. **Content Description Matching:** Confidence 0.93
4. **Accessibility Semantic Role Matching:** Confidence 0.89
5. **Editable Node Detection:** Confidence 0.88
6. **Clickable Ancestor Escalation:** Confidence 0.86
7. **OCR Fallback:** **MISSING** (Documented in comments, code returns failure)
8. **Coordinate Fallback:** **MISSING**

### 12.2 Handling Ordinals & Arbitrary Controls
- When a user asks to *"click the first video"*, Gemini emits `CLICK_ELEMENT(target="first video")` or `CLICK_ELEMENT(target="first result")`.
- `UiTargetResolver` searches for text literally matching `"first video"`, fails, and halts the task.
- **Solution:** Add Ordinal Grounding to `UiTargetResolver`: If target contains `"first"`, `"second"`, `"top"`, resolve the list/collection container node and select its $N$-th interactive child element.

---

## 13. Capability Router & Execution Hierarchy Audit

### 13.1 The Architectural Invariant: Intent is an Optimization, Generic UI is the Foundation
A critical architectural decision must be preserved: **Android Intents are an optimization, NOT the foundation**.
The primary mandate for MAX AI is **generic, closed-loop UI automation** capable of operating across arbitrary third-party Android applications where no APIs or deep-links exist.

The Long Task Engine must be fully capable of executing directives such as:
> *"Click the first result"*  
> *"Inspect the top post"*  
> *"Tap the play button on the third video"*  

within arbitrary apps (e.g. Chrome, YouTube, Instagram, Spotify, Amazon, Swiggy, Uber).

The authoritative execution hierarchy is:
```text
Capability available?
        │
        ├──► Direct API / Intent (Tier 1 & Tier 2: Instant Optimization)
        │
        └──► [If unavailable or in-app step]
                 │
                 ├──► Accessibility Grounded Node (Tier 3: Core Foundation)
                 │
                 └──► [If unavailable / custom view]
                          │
                          └──► Coordinate Gesture / Vision (Tier 4: Dynamic Fallback)
```

### 13.2 Execution Tiers
```text
Tier 1: Direct Android System API (<10ms)  ───► Flashlight, Bluetooth, Volume, DND, Telephony Call
Tier 2: Android Intent Optimization (<100ms) ─► Fast-path App Launch, Maps Directions, Web URL load
Tier 3: Accessibility Grounded Node (150-300ms) ► IN-APP CORE: Omnibox, Search, Results, Play, Chat Rows
Tier 4: Coordinate / Gesture Fallback (300ms) ─► Canvas, WebViews, Flutter, Unlabeled Player Controls
```

**Current Defect:**
`TaskPlanner` either routes blindly to hardcoded fragile scripts or halts after a 1-step Tier 3 launch. The unified engine must execute Tier 2 as an acceleration when available, but seamlessly transition to Tier 3 accessibility grounding to complete in-app sub-tasks (*"click the first result and play"*).

---

## 14. Recovery & Replanning Audit

### Missing Recovery Behaviors:
- **Keyboard Obscuration:** When the virtual keyboard is open, bottom navigation buttons or list items are hidden. MAX does not currently perform `GLOBAL_ACTION_BACK` or IME dismissal before re-resolving targets.
- **System Dialogs / Permissions:** When a permission or "What's New" dialog appears, MAX has no dedicated handler to dismiss or approve it before retrying the step.
- **Directional Scroll to Reveal:** If a target is off-screen, MAX fails immediately instead of executing a bounded scroll in the relevant direction.

---

## 15. Safety Audit

The existing safety architecture in `ActionAuthorizer.kt` is exceptionally well-structured and **MUST REMAIN INVIOLABLE**:

### Preserved Invariants:
1. **Critical Actions Hard Gate:** Actions involving `UPI`, `PIN`, `PASSWORD`, `PAYMENT`, `PAY`, `OTP`, `FACTORY_RESET`, `WIPE`, `FORMAT`, `UNLOCK` are never auto-approved.
2. **WhatsApp Intent Collision Guard:**
   ```kotlin
   // ActionAuthorizer.kt Line 432
   if (isMessagingUtterance && !isExplicitVoiceCall) {
       AuthorizationResult(false, "CALL_PHONE rejected: user intent is a messaging command, not a phone call.")
   }
   ```
   *"Send WhatsApp message to Yaswanth that I am in a meeting and will call back later"* is strictly prevented from executing `CALL_PHONE`.
3. **Explicit Call Authorization:**
   *"Call Yaswanth"* and *"Call Yaswanth on WhatsApp"* remain fully authorized.

---

## 16. Performance Audit

### Current Latency Bottlenecks:
1. **Unnecessary Discarded Observations:** Capturing `ScreenContextPayload` after every step takes 300–800ms, yet its result is discarded in `GoalPlanner`.
2. **Per-Step LLM Loop Overhead:** In `UiVerificationLoop`, querying OpenRouter/Gemini for every action introduces 3–5 seconds of round-trip latency per step.
3. **Target Architecture Target:**
   - Gemini Live / REST creates the multi-step plan **once** ($1.0 - 1.5\text{s}$).
   - Android executes deterministic steps locally with fresh hierarchy checks ($150 - 300\text{ms}$ per step).
   - Gemini is re-invoked **ONLY** if an unexpected screen state or unrecoverable error occurs.

---

## 17. Cross-App Task Audit

### Context Continuity Across Applications:
- **Scenario:** *"Open Maps, find nearby restaurants, then open ChatGPT and tell me the result."*
- **Current Failure Point:** `ScreenContextTool` stores captured text into `metadata: JSONObject`, but leaves `outputData` empty. Therefore, `executionOutputs` contains no text for `ParameterInterpolationEngine` to substitute into the second app's prompt.
- **Fix:** Ensure `ScreenContextTool` and all UI extraction tools write their primary text output into `ToolResult.outputData["text"]`.

---

## 18. Memory & Self-Learning Audit

### Subsystem Separation:
1. **Permanent Memory (Vault):** Room DB (`jarvis_database`), `PermanentMemoryDao`, AES-GCM encryption. Used for user facts and preferences. (Working)
2. **RAG (Retrieval Augmented Generation):** Vector search over permanent memory for LLM prompts. (Working)
3. **Execution Trace:** `AgentActivityTracker` logging steps to the HUD and UI. (Working)
4. **Skill Memory:** `SkillMemoryRepository` (204 lines in `com.example.memory.skill`). Orphaned and uncalled.

### 18.2 Sequencing Rule: Defer Skill Memory to Phase 9 (Correction 3)
A critical engineering constraint: **Do NOT implement `SkillMemory` yet.**
Before saving or retrieving automated skills, the underlying engine must reliably execute:
```text
PLAN ──► OBSERVE ──► GROUND ──► EXECUTE ──► VERIFY ──► RECOVER ──► CONTINUE
```
If Skill Memory is implemented prematurely on an unstable execution pipeline, the system will record and replay corrupted, failed, or brittle step traces. 

The proper progression is:
```text
Successful closed-loop execution
               ↓
     Verified ground truth trace
               ↓
    Skill Memory encoding (Phase 9)
               ↓
      Future local retrieval
```
Therefore, `SkillMemoryRepository` integration is explicitly deferred to **Phase 9**, strictly after live observation, generic target resolution, keyboard execution, and replanning are verified end-to-end.

---

## 19. Test Coverage Audit

### Existing vs Missing Test Matrix:

| Subsystem | Existing Test File | Current Coverage | Missing Coverage |
|---|---|---|---|
| **GoalPlanner** | `GoalPlannerUnitTest.kt`, `PlanGenerationUnitTest.kt` | Queue init, basic steps, parameter validation | **`PRESS_KEY` execution**, ordinal target resolution, multi-step continuation |
| **Keyboard** | `KeyboardControllerUnitTest.kt` | Keycode resolution, focus acquisition | **Unknown key rejection**, `pressKey` verification, Shizuku shell fallback |
| **Runtime Orchestrator** | `GenericUiOrchestratorUnitTest.kt` | `WaitForUi` and `Finish` steps only | **Real multi-step sequences** (`OpenApp` $\rightarrow$ `Tap` $\rightarrow$ `Type` $\rightarrow$ `Enter`) |
| **Capability Router** | None | 0% | Verification of Tier 1 $\rightarrow$ Tier 2 $\rightarrow$ Tier 3 delegation |
| **Re-Evaluation** | None | 0% | LLM recovery replanning, modal dialog dismissal |
| **Safety** | `ActionAuthorizerUnitTest.kt`, `ApprovalPolicyUnitTest.kt` | High coverage on collision & approval | Comprehensive regression suite for WhatsApp "call" variants |

---

## 20. Target Architecture & Single Owner Invariant

### 20.1 The Core Problem vs The Target Solution

**The Root Failure in Current Architecture:**
```text
Gemini generates task
        ↓
multiple planners/orchestrators (GoalPlanner vs LongTaskOrchestrator vs GenericUiStepOrchestrator vs UiVerificationLoop)
        ↓
different step models (ActionStep vs TaskStep vs UiStep)
        ↓
different executors (ExecutionEngine vs CapabilityRouter)
        ↓
different verifiers (VerificationRegistry vs VerificationEngine)
        ↓
TASK STOPS PREMATURELY
```

**The Unified Closed-Loop Target Architecture:**
```text
                     MAX TASK
                        │
                  Task Manager
                        │
              LongTaskOrchestrator  ◄─── [SINGLE COMPONENT OWNS STEP PROGRESSION]
                        │
              ┌─────────┴─────────┐
              ↓                   ↓
           Observe              Plan
              ↓                   ↓
           Ground              Step
              ↓                   ↓
           Authorize           Execute
              └─────────┬─────────┘
                        ↓
                      Verify
                        │
                 ┌──────┴──────┐
                 ↓             ↓
              SUCCESS        FAILURE
                 ↓             ↓
            Next Step    Recover/Replan
```

### 20.2 Architectural Invariants
1. **The Single Owner Invariant:**  
   **Only ONE component owns step progression**: `LongTaskOrchestrator`. It absorbs `GenericUiStepOrchestrator`'s closed-loop state machine (`STEP → OBSERVE → GROUND → AUTHORIZE → EXECUTE → VERIFY → NEXT`). `GoalPlanner` only produces the initial plan and delegates step-by-step progression exclusively to `LongTaskOrchestrator`. `UiVerificationLoop` and the Tier-3 1-step stub in `TaskPlanner` are deprecated.
2. **Intent is Optimization, Generic UI is Foundation:**  
   Direct APIs and Android Intents accelerate high-frequency tasks when available, but the Long Task Engine must be able to perform generic multi-step automation (*"click first result and play"*) inside arbitrary applications via Accessibility and coordinate fallback.
3. **Dynamic Capability Validation:**  
   Validation flows strictly as `ToolRegistry → capabilities → Planner validation`. Hardcoded action whitelists are prohibited.
4. **Deferred Skill Memory:**  
   Skill memory is implemented strictly after closed-loop execution is verified end-to-end.

---

## 21. Unified 10-Phase Implementation Roadmap (For Weekend Execution)

The implementation sequence is strictly prioritized to solve structural orchestration first before layering optimizations or caching:

```text
PHASE 1: Unified Action/Step Contract
    ↓
PHASE 2: Unified LongTask Orchestrator (Single component owns step progression)
    ↓
PHASE 3: Live Observe → Ground → Execute → Verify
    ↓
PHASE 4: Generic Target Resolution (Ordinal & semantic grounding)
    ↓
PHASE 5: PRESS_KEY / Keyboard Contract & Verifier
    ↓
PHASE 6: Recovery + Replanning (Live context feeding)
    ↓
PHASE 7: Capability Router / Intent Optimization
    ↓
PHASE 8: Cross-App Context Continuity (outputData population)
    ↓
PHASE 9: Skill Memory / Self-Learning (Deferred post-verification)
    ↓
PHASE 10: Full Device Validation (Redmi K20 Pro: DEV-01 through DEV-04)
```

### PHASE 1: Unified Action/Step Contract
- Define `UnifiedTaskStep` consolidating `ActionStep`, `TaskStep`, and `UiStep`.
- Eliminate `GoalPlanner.SUPPORTED_ACTIONS` as a second source of truth; implement dynamic validation against `ToolRegistry.getAllActions()` / capabilities.
- Consolidate duplicate `TaskContext` classes (`com.example.automation.engine.TaskContext` vs `com.example.automation.engine.state.TaskContext`) into a single model in `automation.engine.state`.

### PHASE 2: Unified LongTask Orchestrator (Single Owner of Step Progression)
- Enforce the **Single Owner Invariant**: Establish `LongTaskOrchestrator` as the sole orchestrator that owns step progression and execution state.
- Absorb the proven closed-loop execution logic from `GenericUiStepOrchestrator` into `LongTaskOrchestrator`.
- Re-wire `GoalPlanner` and `ActionDispatcher` so all multi-step plans execute through `LongTaskOrchestrator`.
- Deprecate fragmented parallel loops (`UiVerificationLoop`, `TaskPlanner` Tier-3 1-step stub).

### PHASE 3: Live Observe → Ground → Execute → Verify Loop
- Implement mandatory post-step observation: after each action completes, capture live `ScreenContextPayload` from `ScreenContextProvider`.
- Pass fresh screen hierarchy to the next step so execution is grounded in current reality rather than pre-planned assumptions.
- Add adaptive post-transition settling wait (150–400ms polling for window state and hierarchy stabilization after app launches or clicks).

### PHASE 4: Generic Target Resolution (Ordinal & Relative Grounding)
- Upgrade `UiTargetResolver` to support ordinal and relative grounding (*"first result"*, *"second video"*, *"top link"*, *"third post"*).
- Implement scrollable list/container detection and $N$-th interactive child element selection.
- Implement clickable ancestor escalation and exact coordinate gesture fallback for custom views and WebViews.

### PHASE 5: PRESS_KEY / Keyboard Contract & Verifier
- Add explicit, comprehensive key mapping to `KeyboardController` (`ENTER`, `BACK`, `TAB`, `BACKSPACE`, `ESCAPE`, `SPACE`, arrow keys).
- Remove line 333 silent fallback to `KEYCODE_ENTER`; return structured `UNSUPPORTED_KEY` on unknown keycodes.
- Implement and register `KeyboardActionVerifier` in `VerificationRegistry` to verify IME transitions, text submissions, and focus changes.

### PHASE 6: Recovery + Replanning
- Fix `ReEvaluationEngine` to pass live `ScreenContextPayload` (with actual nodes and visible text) to Gemini instead of empty stubs.
- Resolve JSON schema collision in `ReEvaluationEngine` between `GeminiPlanningProvider` (strict array) and replanning instructions.
- Implement recovery primitives: virtual keyboard dismissal (`GLOBAL_ACTION_BACK`), system dialog dismissal, and directional scroll-to-reveal.

### PHASE 7: Capability Router / Intent Optimization
- Implement `IntentDeepLinkRouter` as an **acceleration optimization** for high-frequency operations:
  - Maps directions (`google.navigation:q=...`)
  - Web URL opening
  - Media playback
- Preserve generic accessibility UI fallback whenever intents fail, deep-links are absent, or in-app manipulation is required.

### PHASE 8: Cross-App Context Continuity
- Ensure `ScreenContextTool` and OCR tools populate `ToolResult.outputData["text"]` and `outputData["elements"]` (not merely `metadata: JSONObject`).
- Verify `ParameterInterpolationEngine` correctly interpolates variables across apps (`$step_1.text` into Step 2 inputs).

### PHASE 9: Skill Memory / Self-Learning (Deferred Post-Verification)
- Activate `SkillMemoryRepository` only after Phases 1–8 are proven stable and verified.
- Cache verified, successful step traces to Room DB for instant 0-token local replay on recurring identical goals.

### PHASE 10: Full Device Validation (Redmi K20 Pro)
- Deploy debug build to physical hardware (`3bc00016`, Android 16 / API 36, Root + Shizuku).
- Execute the complete test suite: `DEV-01` (Chrome $\rightarrow$ YouTube $\rightarrow$ play first video), `DEV-02` (Maps route), `DEV-03` (WhatsApp collision protection), `DEV-04` (Call).
- Confirm zero regressions on WhatsApp collision safety (`ActionAuthorizer`).

---

## 22. Exact Files to Modify

1. `app/src/main/java/com/example/automation/engine/GoalPlanner.kt`
2. `app/src/main/java/com/example/automation/keyboard/KeyboardController.kt`
3. `app/src/main/java/com/example/automation/resolver/UiTargetResolver.kt`
4. `app/src/main/java/com/example/automation/engine/ReEvaluationEngine.kt`
5. `app/src/main/java/com/example/automation/engine/ActionDispatcher.kt`
6. `app/src/main/java/com/example/automation/engine/GenericUiStepOrchestrator.kt`
7. `app/src/main/java/com/example/automation/engine/execution/CapabilityRouter.kt`
8. `app/src/main/java/com/example/automation/verification/VerificationRegistry.kt`
9. `app/src/main/java/com/example/automation/tools/ScreenContextTool.kt`
10. `app/src/main/java/com/example/MyApplication.kt`

---

## 23. Exact Classes & Functions to Modify

### 1. `GoalPlanner.kt`
- **Class:** `com.example.automation.engine.GoalPlanner`
- **Field:** `SUPPORTED_ACTIONS`
  - *Current Problem:* Static whitelist acting as a secondary source of truth, missing `PRESS_KEY` and creating recurring schema drift.
  - *Proposed Change (Correction 1):* Eliminate hardcoded whitelist. Implement dynamic validation against `ToolRegistry.getAllActions()` / capability metadata:
    $$\text{ToolRegistry} \longrightarrow \text{capabilities} \longrightarrow \text{Planner validation}$$
  - *Risk:* None; dynamically recognizes all registered tools.
  - *Tests:* `GoalPlannerUnitTest`, `PlanGenerationUnitTest`.
- **Function:** `executePlan()`
  - *Current Problem:* Discards `freshContext` captured after step completion and operates outside closed-loop orchestration.
  - *Proposed Change (Single Owner Invariant):* Delegate plan execution directly to `LongTaskOrchestrator.executePlan()`.
  - *Risk:* Low; unifies fragmented execution paths into one single owner.
  - *Tests:* `GenericUiOrchestratorUnitTest`, `LongHorizonTaskExecutionTest`.

### 2. `LongTaskOrchestrator.kt`
- **Class:** `com.example.automation.engine.execution.LongTaskOrchestrator`
- **Function:** `executeTask()` / `executePlan()`
  - *Current Problem:* Hardcoded 3-app DAGs; Tier-3 generic tasks produce only a 1-step `OpenApp` stub and halt immediately.
  - *Proposed Change:* Absorb closed-loop state machine from `GenericUiStepOrchestrator` (`OBSERVE → GROUND → AUTHORIZE → EXECUTE → VERIFY → NEXT`). Becomes the **single component owning step progression** for all multi-step agent flows.
  - *Risk:* Medium; requires consolidating duplicate step models.
  - *Tests:* `GenericUiOrchestratorUnitTest`, `LongHorizonTaskExecutionTest`.

### 3. `KeyboardController.kt`
- **Class:** `com.example.automation.keyboard.KeyboardController`
- **Function:** `pressKey()`
  - *Current Problem:* Line 333 defaults unknown keys silently to `KeyEvent.KEYCODE_ENTER`.
  - *Proposed Change:* Check `KEY_MAP`. If key is not found and not a valid integer keycode, return `ToolResult.failure(errorCode = "UNSUPPORTED_KEY")`.
  - *Risk:* None; prevents incorrect Enter presses.
  - *Tests:* `KeyboardControllerUnitTest`.

### 3. `UiTargetResolver.kt`
- **Class:** `com.example.automation.resolver.UiTargetResolver`
- **Function:** `resolve()`
  - *Current Problem:* Fails on ordinal queries like `"first result"`, `"second post"`.
  - *Proposed Change:* Add Tier 2.5: Ordinal Matching (`resolveOrdinalTarget()`). Detects index keywords and selects the $N$-th interactive child in the main scrollable list.
  - *Risk:* Low.
  - *Tests:* `UiTargetResolverUnitTest`.

### 4. `ReEvaluationEngine.kt`
- **Class:** `com.example.automation.engine.ReEvaluationEngine`
- **Function:** `evaluateWithLLM()`
  - *Current Problem:* Sends empty `ScreenContextPayload` and requests JSON object conflicting with array schema.
  - *Proposed Change:* Capture live `ScreenContextProvider.captureScreenContext()` and parse response safely.
  - *Risk:* Medium.
  - *Tests:* `ReEvaluationEngineUnitTest`.

### 5. `VerificationRegistry.kt` & `MyApplication.kt`
- **Class:** `VerificationRegistry` / `MyApplication`
- **Function:** `onCreate()`
  - *Current Problem:* No verifier registered for `press_key` / `KeyboardActionTool`.
  - *Proposed Change:* Create and register `KeyboardActionVerifier`.
  - *Risk:* None.
  - *Tests:* `MyApplicationTest`.

### 6. `ScreenContextTool.kt`
- **Class:** `com.example.automation.tools.ScreenContextTool`
- **Function:** `execute()`
  - *Current Problem:* Leaves `outputData` empty.
  - *Proposed Change:* Populate `outputData["text"] = payload.visibleText` and `outputData["elements"] = payload.clickableElements`.
  - *Risk:* None; enables variable interpolation.
  - *Tests:* `ScreenContextToolTest`.

---

## 24. New Files Required

1. **`app/src/main/java/com/example/automation/verification/KeyboardActionVerifier.kt`:**
   - Implements `Verifier` interface for `"press_key"`, `"KEYBOARD_ACTION"`.
2. **`app/src/main/java/com/example/automation/engine/planning/IntentDeepLinkRouter.kt`:**
   - Converts standard user requests (Maps navigation, Chrome URL search, YouTube search) into instant Android Intents.
3. **`app/src/test/java/com/example/automation/LongHorizonTaskExecutionTest.kt`:**
   - End-to-end multi-step unit and integration test suite.

---

## 25. Data Models

### Unified Agent Step Model (`UnifiedTaskStep.kt`)
```kotlin
data class UnifiedTaskStep(
    val stepId: String,
    val actionType: String,
    val target: String? = null,
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val params: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val expectedOutcome: String? = null,
    val maxRetries: Int = 2
)
```

---

## 26. Interfaces & Contracts

### Authoritative Step Verifier Contract:
```kotlin
interface Verifier {
    val supportedTools: Set<String>
    fun verify(
        context: Context,
        request: ExecutionRequest,
        result: ToolResult,
        snapshot: DeviceContext
    ): VerificationResult
}
```

---

## 27. Migration Strategy

1. **Dynamic Capability Validation:** Replace static `SUPPORTED_ACTIONS` in `GoalPlanner.kt` with dynamic validation against `ToolRegistry.getAllActions()` and tool capability metadata (`ToolRegistry → capabilities → Planner validation`). This breaks zero existing code, eliminates dual sources of truth, and guarantees immediate validation for all registered capabilities without recurring schema drift.
2. **Consolidation of Duplicate Classes:** Deprecate `com.example.automation.engine.TaskContext` in favor of `com.example.automation.engine.state.TaskContext`.
3. **No Room Database Migrations Required:** All changes reside in the automation, planning, and execution layers. Room schema remains at version 14.

---

## 28. Regression Risks & Mitigation

| Potential Regression Risk | Severity | Mitigation Strategy |
|---|---|---|
| **WhatsApp "call" utterance collision** | **CRITICAL** | Retain line 409 of `ActionAuthorizer.kt` and line 204 of `ToolDispatcher.kt` untouched. Verify with unit test. |
| **Breaking Existing Fast Paths** | High | Fast-path regex matching in `SemanticUiCommandParser` and `GoalPlanner` runs before any agent loops. |
| **Infinite UI Retry Loops** | High | Enforce strict `MAX_STEP_RETRIES = 2` in `GenericUiStepOrchestrator` and `MAX_REEVALUATIONS = 3`. |
| **Battery / CPU Drain** | Medium | Screen hierarchy observation must use `LightweightUiObserver` during execution; avoid heavy JPEG encoding unless LLM replanning is needed. |

---

## 29. Test Plan

### Unit Test Execution:
```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.automation.GoalPlannerUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.automation.KeyboardControllerUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.automation.GenericUiOrchestratorUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.automation.ActionAuthorizerUnitTest"
.\gradlew.bat testDebugUnitTest --tests "com.example.automation.LongHorizonTaskExecutionTest"
```

---

## 30. Device Validation Plan (Redmi K20 Pro)

| Test ID | Natural Language Command | Expected Device Flow | Success Verification |
|---|---|---|---|
| **DEV-01** | *"Open Chrome, search YouTube, open first result and play video"* | Opens Chrome $\rightarrow$ inputs query $\rightarrow$ presses Enter $\rightarrow$ clicks top link $\rightarrow$ video starts | Audio focus acquired / playback active |
| **DEV-02** | *"Open Maps, search Palakollu to Bhimavaram, open directions"* | Launches Maps intent or opens Maps $\rightarrow$ types route $\rightarrow$ opens directions | Directions route preview visible |
| **DEV-03** | *"Send WhatsApp message to Yaswanth that I am in a meeting and will call later"* | Routes to WhatsApp $\rightarrow$ verifies chat header $\rightarrow$ types message $\rightarrow$ clicks send | Message sent checkmark visible; **NO phone call triggered** |
| **DEV-04** | *"Call Yaswanth"* | Resolves contact $\rightarrow$ prompts confirmation $\rightarrow$ places call | Telephony call state active |

---

## 31. Weekend Implementation Order (10-Phase Sequence)

```text
PHASE 1: Unified Action/Step Contract
        ↓
PHASE 2: Unified LongTask Orchestrator (Single component owns step progression)
        ↓
PHASE 3: Live Observe → Ground → Execute → Verify
        ↓
PHASE 4: Generic Target Resolution (Ordinal & semantic grounding)
        ↓
PHASE 5: PRESS_KEY / Keyboard Contract & Verifier
        ↓
PHASE 6: Recovery + Replanning (Live context & fixed schema)
        ↓
PHASE 7: Capability Router / Intent Optimization (Accelerated Layer)
        ↓
PHASE 8: Cross-App Context Continuity (outputData population)
        ↓
PHASE 9: Skill Memory / Self-Learning (Deferred post-verification)
        ↓
PHASE 10: Full Device Validation (Redmi K20 Pro)
```

---

## 32. Definition of Done

The weekend implementation phase will be complete when:
1. **Dynamic Capability Validation:** `GoalPlanner.validatePlan()` dynamically validates all actions registered in `ToolRegistry` without maintaining a hardcoded secondary whitelist.
2. **Single Component Step Progression:** `LongTaskOrchestrator` solely drives multi-step task execution, absorbing closed-loop observation and verification from `GenericUiStepOrchestrator`.
3. **Closed-Loop Grounding:** An arbitrary command like *"Open Chrome, search YouTube, open first result and play video"* executes end-to-end without halting after opening Chrome.
4. **Ordinal Target Resolution:** Target resolution successfully locates and clicks ordinal items (*"first result"*, *"first post"*, *"top link"*).
5. **Strict Keyboard Contract:** `KeyboardController` rejects unknown keys, eliminates silent Enter defaults, and `KeyboardActionVerifier` validates keyboard execution.
6. **Intent Optimization Preserves Generic Fallback:** Intents accelerate navigation and search, while generic accessibility automation remains capable of arbitrary in-app execution.
7. **Collision Safety Invariant:** Messaging commands containing the word "call" are strictly prevented from triggering phone calls (`ActionAuthorizer` line 409 verified).
8. **Automated Test Suite:** All 300+ unit tests pass with `BUILD SUCCESSFUL`.
9. **Live Device Verification:** Hardware validation on Redmi K20 Pro passes all 4 end-to-end benchmark flows (`DEV-01` through `DEV-04`).
10. **Zero Premature Code Edits:** All source code changes remain pending until explicit user kickoff command for weekend implementation.
