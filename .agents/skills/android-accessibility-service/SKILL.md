---
name: android-accessibility-service
description: MAX-specific engineering patterns for Android AccessibilityService automation, AccessibilityNodeInfo traversal, dynamic UI readiness, safe node targeting, gesture dispatch, WhatsApp/Telegram controller boundaries, Shizuku fallback, and targeted verification. Use when modifying or debugging Android UI automation, JarvisAccessibilityService, or accessibility-driven actions.
metadata:
  scope: workspace-max-android
  author: MAX Architecture Team
  project: MAX AI Assistant
---

# MAX Android AccessibilityService Automation Skill

This skill defines the engineering rules for Android UI automation inside the MAX AI Assistant. Optimize for reliability, bounded execution, minimal latency, safe targeting, and evidence-based verification.

---

## Primary MAX Code References

- **Core Service**: [`app/src/main/java/com/example/voice/session/JarvisAccessibilityService.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/voice/session/JarvisAccessibilityService.kt)
- **Execution & Dispatch**:
  - [`app/src/main/java/com/example/automation/engine/ExecutionEngine.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/engine/ExecutionEngine.kt)
  - [`app/src/main/java/com/example/automation/engine/ActionDispatcher.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/engine/ActionDispatcher.kt)
- **UI Agent & Loop**:
  - [`app/src/main/java/com/example/automation/agent/UiVerificationLoop.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/agent/UiVerificationLoop.kt)
  - [`app/src/main/java/com/example/automation/agent/UiActionExecutor.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/agent/UiActionExecutor.kt)
- **Input & Keyboard**:
  - [`app/src/main/java/com/example/automation/keyboard/KeyboardController.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/keyboard/KeyboardController.kt)
- **App Resolvers**:
  - [`app/src/main/java/com/example/automation/whatsapp/WhatsAppContactResolver.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/whatsapp/WhatsAppContactResolver.kt)
  - [`app/src/main/java/com/example/automation/verification/YoutubeVerifier.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/automation/verification/YoutubeVerifier.kt)
- **Shizuku / Root Fallback**:
  - [`app/src/main/java/com/example/shizuku/ShizukuManager.kt`](file:///d:/max%20app%20testing/gemini-live/app/src/main/java/com/example/shizuku/ShizukuManager.kt)

---

## 1. AccessibilityNodeInfo Traversal & Lifetime

- **Short-Lived Node Snapshots**: Do not retain `AccessibilityNodeInfo` references across long suspensions, retries, or unrelated UI transitions. Treat nodes as short-lived snapshots.
- **API 33+ Recycle Deprecation**: Android API 33 deprecated `AccessibilityNodeInfo.recycle()` because object pooling was discontinued; calling `recycle()` now has no effect. Do not write new code that relies on recycling for leak prevention.
- **Lightweight State Extraction**: Prefer extracting the small amount of state needed for a decision (text, class name, package, bounds, actions, IDs) and then reacquiring the node when executing a later action.
- **Stale Node Detection & Refresh**: Use `refresh()` when a known node may have become stale after a UI transition. If `refresh()` returns `false`, treat the node as obsolete and reacquire it.
- **Bounded Traversal Depth & Budget**: Bound traversal depth and node count. A default MAX guard is around 25-30 levels / a configurable node budget, but do not assume every app uses that depth.
- **Pathological Tree Protection**: Avoid unbounded recursive traversal through large WebViews or pathological/custom accessibility trees.
- **Timeout Bounds**: Keep traversal bounded by both node budget and wall-clock timeout. Never let tree traversal block the main service flow indefinitely.

---

## 2. Dynamic UI Readiness & Polling

- **Event-Driven Readiness First**: Prefer event-driven readiness from `AccessibilityEvent` changes whenever practical.
- **Bounded Condition Polling**: Use bounded condition-based polling only when an event does not reliably expose the required state. Never use a blind fixed delay such as `delay(5000)` as the primary synchronization mechanism.
- **Exponential Backoff**: Use short initial intervals (e.g., 100-250 ms), exponential/backoff growth, and a hard timeout.
- **Typical Timeout Budgets**:
  - Warm navigation: ~5 seconds.
  - Cold launch or slower transition: ~10 seconds.
- **Package Validation & Reacquisition**: After launching an app or navigating, `rootInActiveWindow` may be `null`, stale, or still belong to another package. Validate the active package against the expected target package before acting. When package validation fails, reacquire the root rather than interacting with the stale tree.

---

## 3. Safe Node Targeting

- **Never Click on Blind Text Matches**: Never click the first text match without confirming that it represents the intended UI target.
- **Stable Semantics Hierarchy**: Prefer stable semantics in this order:
  1. Resource ID / View ID when available.
  2. Accessibility class + role/state.
  3. Exact content description.
  4. Exact text.
  5. Tightly bounded contextual match.
  6. Coordinate fallback only when necessary.
- **Walk Up to Clickable Ancestor**: If a matched node is not clickable, walk up a bounded number of parents until the nearest suitable clickable ancestor is found.
- **Pre-Click Verification**:
  - Verify the node is still relevant.
  - Verify it is visible when required.
  - Refresh/reacquire after major UI transitions.
  - Verify package ownership.
  - Avoid fuzzy text matching when a destructive or externally visible action depends on the match.
- **Input Targets**:
  - Prefer `ACTION_SET_TEXT`.
  - Then use IME actions (`ACTION_IME_ENTER`) when appropriate.
  - Use coordinate input only as fallback.
- **Gesture Tap Safety**:
  - Obtain screen bounds using `getBoundsInScreen(rect)`.
  - Ensure bounds are non-empty.
  - Ensure target is visible/relevant.
  - Tap a safe point such as the center of the validated bounds `(rect.centerX(), rect.centerY())`.
  - Keep gesture duration minimal unless the UI specifically requires a long-press.

---

## 4. Multi-Tier Execution

MAX uses this strict preference order:

### Tier 1 — Accessibility Action API
- **Examples**: `ACTION_CLICK`, `ACTION_SET_TEXT`, scrolling actions, supported accessibility actions exposed by the target.
- **Rationale**: Provides semantic interaction without screen coordinate or resolution dependence. Use this first.

### Tier 2 — Accessibility Gesture Dispatch
- **Usage**: Use `dispatchGesture()` when:
  - The target is not actionable through the accessibility API.
  - The target is a custom canvas/Flutter/game surface lacking semantic tree exposure.
  - Semantic actions fail but a validated screen coordinate is available.
- **Rule**: Do not use coordinate gestures before attempting an appropriate semantic action.

### Tier 3 — Shizuku / ADB Shell Fallback
- **Usage**: Shell-level input (`input tap`, `input text`, `input keyevent`) is used only when Tier 1 and Tier 2 are unavailable or have been validated as ineffective for the current state, and only where the MAX architecture explicitly authorizes that fallback.
- **Rule**: Do not use shell commands as the default path for normal accessibility-capable interactions.

---

## 5. Domain Controller Boundaries

- **User Authorization**: Do not send messages, make calls, or perform external side effects without explicit user instruction or an already-authorized MAX workflow. Keep contact resolution separate from message composition and dispatch.
- **WhatsApp (`WhatsAppContactResolver`)**:
  - Use `WhatsAppContactResolver` for contact identification.
  - Never treat message snippets such as `msg_snippet` or `conversations_row_message` as proof that the intended contact has been selected.
  - Before sending: confirm target conversation/contact header, confirm input target, dispatch action, verify post-send state.
- **Telegram**:
  - Enforce the currently authorized command set (`ALLOWED_ACTIONS`) at the controller boundary.
  - Do not let generic natural-language input bypass command authorization.

---

## 6. State Verification

- **Dispatch Acceptance ≠ Success**: Do not report success merely because `performAction()` or `dispatchGesture()` returned `true`. That indicates dispatch acceptance, not necessarily successful application state.
- **Mandatory Post-Action Verification**: After every externally meaningful action, verify the resulting state before reporting success:
  - Active package equals expected package.
  - Target screen/header is present.
  - Input field contains or clears expected text.
  - Conversation changed as expected.
  - Action-specific confirmation is observable.

---

## 7. Logging & Diagnostics

- **Structured Logging**: Use structured logs through the MAX logging system (`AssistantLogger`).
- **Preferred Tags**: `[UiActionExecutor]`, `[Accessibility]`.
- **Log Attributes**: Action type, target resolution method, target package, relevant resource ID/text/class (mask sensitive user content), fallback tier used, verification result, failure reason.
- **Avoid**:
  - Dumping the entire node tree on every action.
  - Repeated identical logs inside fast polling loops.
  - Logging sensitive message content unnecessarily.

---

## 8. Retry & Failure Handling

- **Transient Failures Only**: Retry only when the failure is plausibly transient.
- **State Reacquisition**: Reacquire UI state before retrying an action. Do not repeatedly execute the same click against a stale node.
- **Bounded Budgets**: Use bounded retry counts and timeouts. After the retry budget is exhausted, return a structured failure with the last observed state.
- **Standard Loop**:
  $$\text{Observe} \longrightarrow \text{Resolve} \longrightarrow \text{Validate} \longrightarrow \text{Act} \longrightarrow \text{Verify} \longrightarrow \text{Retry only if justified}$$

---

## 9. Performance Rules

- **Targeted Subtrees**: Prefer one targeted tree search over repeated full-tree scans.
- **Immutable State Caching**: Cache stable identifiers or immutable extracted state, not long-lived node objects. Reacquire nodes after significant navigation.
- **Condition-Based Waits**: Use condition-based waits instead of long arbitrary sleeps.
- **Avoid Redundant Screenshots**: Avoid repeated screenshot capture when the accessibility tree already contains sufficient information.
- **Lazy Fallback Tiers**: Keep fallback tiers lazy: do not prepare or execute Shizuku/ADB work unless Tier 1 and Tier 2 cannot satisfy the action.
- **Measure First**: Measure before introducing aggressive caching, concurrency, or additional polling complexity.

---

## 10. Accessibility Automation Debugging Workflow

When automation fails:
1. Capture the current package and window state.
2. Determine whether the root window is available.
3. Inspect the relevant subtree only.
4. Determine why target resolution failed (stale node, layout shift, non-clickable wrapper).
5. Reacquire or refresh stale state.
6. Try the lowest-cost valid execution tier.
7. Verify the resulting UI state.
8. Preserve the failure evidence in structured logs.
9. **Rule**: Do not respond to a stuck automation path by adding arbitrary delays first.

---

## 11. MAX-Specific Rule: Reuse Existing Implementations

- **First Source of Truth**: The existing working implementation is the first source of truth.
- **Before Creating New Paths**:
  - Locate equivalent working functionality in MAX.
  - Compare the execution path.
  - Reuse shared helpers/controllers where behavior is equivalent.
  - Modify the smallest possible surface.
- **Example**: If scheduled WhatsApp message automation already has a working `search -> contact resolution -> open chat -> type -> send -> verify` flow in `WhatsAppContactResolver` and `ExecutionEngine`, direct chat-triggered automation must reuse that execution path rather than creating a second independent implementation.

---

## 12. Verification Requirement

A change involving `AccessibilityService` automation is not complete until the relevant behavior has been verified at the narrowest useful level:
- **Node Locator Change** $\rightarrow$ Targeted UI/device verification.
- **Keyboard Change** $\rightarrow$ Targeted input verification.
- **WhatsApp/Telegram Controller Change** $\rightarrow$ Targeted flow verification.
- **Fallback Change** $\rightarrow$ Targeted fallback-path verification.

Always report:
1. What changed.
2. What was verified.
3. What remains unverified.
