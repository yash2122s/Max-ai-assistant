# MAX — LIVE UI TOOL-CALLING & GEMINI AGENT ARCHITECTURE
## TECHNICAL REASSESSMENT & SPECIFICATION

**Target:** `d:\max app testing\gemini-live`  
**Focus:** Architectural Precision, Protocol Capabilities, Concrete Contracts, and Implementation Blueprint  
**Status:** RE-EVALUATED & TIGHTENED (Read-Only Analysis)  

---

## 1. RE-EVALUATION OF INITIAL AUDIT CLAIMS

| Original Audit Claim | Precise Assessment & Codebase Reality | Architectural Implication |
|---|---|---|
| **“Gemini emits one single tool call”** | [`GeminiWebSocketClient.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/network/GeminiWebSocketClient.kt#L470) iterates over `message.toolCall?.functionCalls`. While individual frames may batch calls, Gemini Live can emit subsequent `toolCall` messages in subsequent turns once previous responses return. The current code dispatches calls reactively; it does **not** limit the session to a single turn. Current single-call behavior is driven by **PromptBuilder system instructions** telling Gemini to invoke compound tools (`send_whatsapp_message`, `computer_use`), not a transport limit. | The WebSocket transport supports multi-turn iteration naturally. |
| **“Missing WebSocket protocol support”** | [`GeminiWebSocketClient.sendToolResponse()`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/network/GeminiWebSocketClient.kt#L651) wraps arbitrary JSON objects into `{"toolResponse":{"functionResponses":[{"id":...,"name":...,"response":{"output":...}}]}}`. | Returning structured, compact UI observations requires **zero transport or WebSocket protocol redesign**. It is entirely an application-level schema and return-payload design. |
| **“No internal state machine means no iterative loop”** | An iterative agent loop does not require a monolithic local state machine. The LLM itself acts as the state transition engine across turns if provided with state evidence. | Client-side tracking is required only for: (1) session/task boundaries, (2) turn/time budgets, (3) authorization & approval, and (4) cancellation. |
| **“No screen feedback exists”** | Screen feedback **already exists** in the repository! [`ScreenContextTool.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/tools/ScreenContextTool.kt#L48-L80) extracts `packageName`, `clickableElements`, and `visibleText` and packages them into `ToolResult.outputData` and `metadata`. Furthermore, `ScreenContextProvider.captureScreenContext` can stream JPEG frames directly via `GeminiWebSocketClient.sendVideoFrame()`. | Primitives already exist; what is missing is unifying the output contract so that actions or observation tools return this state systematically to Gemini Live. |
| **“Sub-second audio deadlines block UI observation”** | Speculative. Audio turns stream concurrently over the Bidi WebSocket. Compact textual observations (500–1,500 bytes containing foreground package + visible interactive elements) fit easily within standard MTU and frame sizes without audio disruption. | Treat latency, token growth, and visual frame rate as **engineering parameters to calibrate**, not fatal architectural barriers. |
| **“Execution waits 20s or 60s”** | Line 480 of `GeminiWebSocketClient.kt` sets a 20,000ms outer timeout on `withTimeoutOrNull(20_000L)`. `ComputerUseEngine` sets 60,000ms internally. | Outer timeouts currently do not cleanly cancel the underlying Coroutine if the dispatcher does not pass a `CancellationToken`. Propagation must be made explicit. |
| **“Accessibility has zero coordinate drift risk”** | Node-based actions avoid screen-resolution coordinate drift, but face **stale node references, view recycling, overlay occlusion, and ambiguous node matching**. | `LiveNodeResolver` already addresses re-grounding, but post-action verification and staleness detection remain critical. |
| **“Outgoing message proves success”** | In `TaskPlanner.kt`, `wa_6` verifies only `ExpectedOutcome.MessageSent(contact)` which merely checks if the contact name is visible on screen. | A clean separation must be enforced between: (1) `ACTION_DISPATCHED`, (2) `COMPOSER_SUBMITTED`, (3) `OUTGOING_BUBBLE_OBSERVED`, and (4) `DELIVERY_ACKNOWLEDGED`. |

---

## 2. UNIFIED ARCHITECTURAL MODEL: THE LIVE OBSERVE-ACT LOOP

Instead of maintaining fragmented execution paths across five different engines, MAX can consolidate under **one execution authority** with **one next-action planner per task**:

```
                       ┌─────────────────────────────────────────┐
                       │          Gemini Live (Planner)          │
                       │    (BiDi WebSocket - PCM / JSON)        │
                       └───────────────────┬─────────────────────┘
                                           │
                        1. Tool Call       │ 6. Tool Response
                     (e.g., tap, type)     │  (Evidence + Fresh Observation)
                                           ▼
                       ┌─────────────────────────────────────────┐
                       │       Live Task Controller & Policy     │
                       │  - Step/Time Budget Check               │
                       │  - Authorization & Sensitive Gates      │
                       │  - Snapshot & Target Validation         │
                       └───────────────────┬─────────────────────┘
                                           │
                               2. Validate │ 5. Settle & Observe
                              & Resolve    │    (Accessibility/Vision)
                                           ▼
                       ┌─────────────────────────────────────────┐
                       │     Unified Action & Grounding Core     │
                       │  - LiveNodeResolver (Accessibility)     │
                       │  - UiActionExecutor (Gestures/Shizuku)  │
                       │  - VerificationEngine (Outcome Check)   │
                       └─────────────────────────────────────────┘
```

---

## 3. COMPACT CONTRACT SPECIFICATIONS

To keep WebSocket payloads lean, responsive, and token-efficient, the interface between Gemini Live and MAX is divided into four clean boundaries:

### 3.1 Observation Contract (What Gemini Sees)
Returned as the `output` of `get_screen_context` or piggybacked onto action completions:

```json
{
  "snapshot_id": "snap_1743123456_01",
  "timestamp_ms": 1743123456789,
  "foreground_package": "com.whatsapp",
  "screen_classification": "CHAT_LIST",
  "interactive_elements": [
    {
      "ref": "e_01",
      "text": "Search",
      "role": "Button",
      "clickable": true
    },
    {
      "ref": "e_02",
      "text": "Suresh",
      "desc": "Suresh: Hey bro, where are you?",
      "role": "ConversationRow",
      "clickable": true
    }
  ],
  "screen_text_summary": "WhatsApp. Chats (3 unread). Suresh: Hey bro... Mom: Call me..."
}
```

### 3.2 Action Contract (What Gemini Emits)
Single-step primitives emitted by Gemini Live:

```json
{
  "name": "tap_element",
  "arguments": {
    "task_id": "task_wa_send_01",
    "expected_snapshot_id": "snap_1743123456_01",
    "target_ref": "e_02",
    "target_text": "Suresh",
    "action_purpose": "Open chat thread with Suresh"
  }
}
```

### 3.3 Result & Verification Contract (What MAX Returns)
Returned immediately via `sendToolResponse`:

```json
{
  "status": "success",
  "task_id": "task_wa_send_01",
  "action_executed": "tap_element",
  "verification": {
    "verified": true,
    "confidence": 0.95,
    "state_changed": true,
    "evidence": "Foreground screen changed from CHAT_LIST to CHAT_CONVERSATION with Suresh"
  },
  "fresh_observation": {
    "snapshot_id": "snap_1743123457_02",
    "foreground_package": "com.whatsapp",
    "screen_classification": "CHAT_CONVERSATION",
    "interactive_elements": [
      { "ref": "e_10", "text": "Type a message", "role": "EditText", "editable": true },
      { "ref": "e_11", "desc": "Voice message", "role": "Button", "clickable": true }
    ]
  }
}
```

### 3.4 Sensitive Action & Approval Boundary (Messaging Safety)
For high-impact or outgoing communication actions:

1. **Staging / Drafting Phase:**
   Typing a message is auto-approved (`type_text` executes, verifying text in composer).
2. **Pre-Send Verification Gate:**
   Prior to calling `tap(Send)`, the model must verify the recipient and message body.
   If the recipient is ambiguous or the action is irreversible:
   - Live Model asks user: *"Suresh ki 'వస్తున్నా' ani message send cheyamantara?"*
   - Only on user voice affirmative (`"Yes" / "Pampu"`) does Gemini emit `confirm_and_send`.
3. **Delivery Outcome Disambiguation:**
   - **Level 1 (Dispatched):** Send button clicked, composer cleared.
   - **Level 2 (Outgoing Bubble Present):** New message bubble matching text appears in chat list with clock/single check.
   - **Level 3 (Server Ack):** Double checkmark received.
   MAX reports Level 2 as confirmed completion. It **never** blindly retries if the outcome is uncertain.

---

## 4. LIFECYCLE & EXECUTION SAFEGUARDS

1. **Step & Time Budgets:**
   - Maximum 8 steps per user goal.
   - 20-second global task timeout.
   - If budget is exceeded, task halts gracefully and returns `STEP_LIMIT_EXCEEDED` to Gemini.
2. **Cancellation & Barge-in:**
   - When user interrupts via speech, `GeminiWebSocketClient` receives an interruption signal.
   - Active Coroutine jobs in `ActionDispatcher` must be bound to a cancellable `Job` so device taps halt immediately.
3. **Idempotency & Duplicate Protection:**
   - Actions require `expected_snapshot_id`.
   - If Gemini emits a duplicate action for an expired snapshot (e.g. Due to network retransmission), MAX rejects execution with `STALE_SNAPSHOT`.

---

## 5. SUMMARY FOR IMPLEMENTATION

* **Transport:** Fully capable today via existing `GeminiWebSocketClient` and `sendToolResponse`.
* **Sensing:** Fully capable today via `ScreenContextProvider` and `CurrentUiStateStore`.
* **Execution:** Fully capable today via `LiveNodeResolver`, `GenericActionExecutor`, and `UiActionExecutor`.
* **Changes Needed:**
  1. Define compact observation payloads in tool responses.
  2. Update `PromptBuilder` to instruct Gemini on the step-by-step Observe $\rightarrow$ Act loop when appropriate.
  3. Wire cancellation tokens from WebSocket turns down to the execution engine.
