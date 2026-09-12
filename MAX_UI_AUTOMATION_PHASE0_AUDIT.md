# MAX UI AUTOMATION — PHASE 0 FORENSIC AUDIT

> **Audit Date:** 2026-09-11  
> **Target Scope:** Forensic inspection of existing MAX Android UI automation components before implementing Generic Runtime UI Interaction Engine.

---

## 1. Existing Actions & Architecture

| Component | File Path | Existing Capabilities |
|---|---|---|
| **`JarvisAccessibilityService`** | `app/src/main/java/com/example/service/JarvisAccessibilityService.kt` | Core Android AccessibilityService (API 24+). Singleton instance holder. Manages active window roots, event listeners, gesture dispatching, and foreground app tracking. |
| **`ActionDispatcher`** | `app/src/main/java/com/example/automation/engine/ActionDispatcher.kt` | Maps high-level JSON action payloads into normalized `ExecutionRequest`s and dispatches them asynchronously through `ExecutionEngine`. |
| **`ExecutionEngine`** | `app/src/main/java/com/example/automation/engine/ExecutionEngine.kt` | Executes `ExecutionRequest`s against registered tools in `ToolRegistry` with timeout (25s), parameter interpolation, lifecycle event publishing, and verification via `RetryEngine`. |
| **`ToolDispatcher`** | `app/src/main/java/com/example/network/ToolDispatcher.kt` | Maps Gemini Live function calls (`open_app`, `click_element`, `scroll_screen`, etc.) to structured action JSON payloads. |
| **`RetryEngine`** | `app/src/main/java/com/example/automation/verification/RetryEngine.kt` | Executes tools with configurable `RetryPolicy` (`NoRetry`, `ImmediateRetry`, `ExponentialBackoff`, `WaitForUi`, `CompositeRetry`). |
| **`DeviceContext`** | `app/src/main/java/com/example/automation/verification/DeviceContext.kt` | Captures screen UI snapshot (package name, hierarchy nodes) + device telemetry (volume, brightness, DND, Wi-Fi, battery). |
| **`WorldStateManager`** | `app/src/main/java/com/example/automation/state/WorldStateManager.kt` | StateFlow repository holding current foreground app, activity, screen signature, UI flags, and action execution history. |
| **`OpenAppAction` & `Verifier`** | `app/src/main/java/com/example/automation/actions/OpenAppAction.kt`<br>`app/src/main/java/com/example/automation/verification/OpenAppVerifier.kt` | App launch via `PackageManager` with installed-app lookup + hardcoded aliases; bounded adaptive polling verification. |
| **`ScreenGroundingEngine`** | `app/src/main/java/com/example/automation/ui/ScreenGroundingEngine.kt` | Priority-based matching for resource IDs, exact/normalized text, content descriptions, and indexed UI elements. |
| **`UiActionExecutor`** | `app/src/main/java/com/example/automation/agent/UiActionExecutor.kt` | Executes low-level UI gestures (tap, long press, swipe, scroll, type, enter, back, home) via AccessibilityService and Shizuku/ADB fallback. |

---

## 2. Existing Interaction Primitives

### A. Target Resolution
- **Resource ID Matching:** `ScreenGroundingEngine.groundTarget()` checks exact resource ID and entry name (`id.substringAfterLast('/')`) with alias support.
- **Text & Description Matching:** Exact visible text, case-insensitive text, full-word boundary match, and recursive content description matching in `JarvisAccessibilityService` (`findNodeByDescRecursive`, `clickVisibleText`) and `ScreenGroundingEngine`.
- **Indexed Elements:** `AccessibilityTreePruner` + `JarvisAccessibilityService.getIndexedElements()` assigns dynamic numbered indices to interactive nodes.

### B. Editable Node Detection & Text Input (`ACTION_SET_TEXT`)
- `JarvisAccessibilityService.findEditableNode()` recursively scans active window nodes for `isEditable == true`.
- Text input uses `AccessibilityNodeInfo.ACTION_SET_TEXT` with `AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE` via `Bundle`.
- Fallbacks: focused node resolution via `rootInActiveWindow.findFocus(FOCUS_INPUT)`, plus Shizuku shell `input text` fallback in `UiActionExecutor`.

### C. IME & Enter Handling (`PRESS_ENTER`)
- `JarvisAccessibilityService.pressImeAction()`:
  1. On Android 11+ (API 30+), executes `AccessibilityAction.ACTION_IME_ENTER` on the editable node.
  2. Falls back to clicking visible onscreen buttons ("search", "go", "done", "enter", "send", "next").
  3. `UiActionExecutor` / Shizuku fallback executes `input keyevent 66` (KEYCODE_ENTER).

### D. Click Escalation & Gesture Fallbacks (`TAP`)
- `JarvisAccessibilityService.performClickWithEscalation(node)`:
  1. **Polite Accessibility Click:** Traverses up to 8 ancestor levels for `isClickable` and executes `AccessibilityNodeInfo.ACTION_CLICK`.
  2. **Verification Check (150ms):** Checks if screen signature, target state (`isChecked`), foreground package, active window, or focus changed.
  3. **Escalation to Physical Gesture:** If unconfirmed/inert, computes node center coordinates bounded within screen dimensions and dispatches a 100ms touch stroke via `dispatchGesture()`.

### E. Scrolling & Swiping
- `JarvisAccessibilityService.scrollScreen(direction)`:
  1. Finds `isScrollable == true` node and performs `ACTION_SCROLL_FORWARD` (down) or `ACTION_SCROLL_BACKWARD` (up).
  2. Fallback: Vertical swipe gesture within the scrollable container's bounds (250ms).
  3. Fallback: Screen-level vertical swipe gesture between 75% and 25% height.

### F. Foreground Package Tracking & Screen Signatures
- `JarvisAccessibilityService.onAccessibilityEvent()` listens to `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED` to update `WorldStateManager.setForegroundApp(eventPkg)`.
- `JarvisAccessibilityService.getScreenSignature(root)` produces a hash/signature of visible hierarchy for rapid change detection.

---

## 3. Findings & Reusability Strategy

1. **Zero Reinvention Needed for Low-Level Gestures:** `JarvisAccessibilityService` already contains complete, battle-tested implementations of `performClickWithEscalation`, `pressImeAction`, `scrollScreen`, `dispatchGesture`, and `ACTION_SET_TEXT`.
2. **Target Resolution Unification:** We will build `UiTargetResolver.kt` in Phase 2 that cleanly formalizes the resolution pipeline (Resource ID -> Text -> Content Description -> Semantic Role -> Editable -> Clickable -> OCR -> Visual -> Coordinate) while leveraging existing node traversal and `ScreenGroundingEngine` algorithms.
3. **Execution Pipeline Stability:** All actions will flow through existing `ExecutionEngine` and `ActionDispatcher` without duplicating pipelines.
