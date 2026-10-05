# Accessibility-First Current UI State Store & Generic Action Engine Implementation Plan

> **For Claude / Antigravity Agent:** REQUIRED SUB-SKILL: Use TDD, bite-sized tasks, and unlazy acceptance gates to implement this plan task-by-task.

**Goal:** Transform MAX into an accessibility-first, real-time reactive automation system where `CurrentUiStateStore` is the persistent single source of truth for the current screen, `JarvisAccessibilityService` serves strictly as a platform sensor/executor, and `ComputerUseEngine` executes arbitrary UI tasks via exact semantic node matching and state-transition verification.

**Architecture:** 
1. `JarvisAccessibilityService` streams Android accessibility events into `CurrentUiStateStore`.
2. `CurrentUiStateStore` maintains an always-ready, indexed in-memory representation of foreground package, activity, interactive semantic nodes, and screen fingerprint.
3. `UiTargetResolver` and `GenericNodeExecutor` resolve targets and execute `ACTION_CLICK` / `ACTION_SET_TEXT` / `ACTION_FOCUS` directly on nodes.
4. `ComputerUseEngine` plans transitions from the instant in-memory state and verifies transitions against `CurrentUiStateStore` updates, reserving screenshot/vision purely as a fallback when accessibility is insufficient.

**Tech Stack:** Kotlin, Android AccessibilityService, Kotlin Coroutines & StateFlow, Android Jetpack, Robolectric unit testing.

---

### Architectural Boundaries & Invariants

```text
┌────────────────────────────────────────────────────────┐
│                      Gemini / Brain                    │
│      (High-level reasoning & intent understanding)     │
└───────────────────────────┬────────────────────────────┘
                            │ goal / task intent
                            ↓
┌────────────────────────────────────────────────────────┐
│                   ComputerUseEngine                    │
│  (Orchestrator: Plan -> Execute -> Verify -> Recover)  │
└──────────────┬───────────────────────────┬─────────────┘
               │ 1. Read instant state     │ 3. Execute action
               ↓                           ↓
┌──────────────────────────────┐ ┌───────────────────────┐
│     CurrentUiStateStore      │ │  GenericNodeExecutor  │
│  (Single Source of UI Truth) │ │ (ACTION_CLICK/FOCUS)  │
└──────────────▲───────────────┘ └───────────┬───────────┘
               │ pushes updates              │ performs action
┌──────────────┴─────────────────────────────▼───────────┐
│               JarvisAccessibilityService               │
│        (Platform Sensor & Native Executor ONLY)        │
└──────────────────────────────▲─────────────────────────┘
                               │ AccessibilityEvents / Tree
                     Android OS Platform
```

**Locked Rules:**
- **`Gemini`**: Never clicks arbitrary pixel coordinates when semantic accessibility nodes exist.
- **`ComputerUseEngine`**: Zero app-specific hardcoded steps (no 4-step WhatsApp machines).
- **`JarvisAccessibilityService`**: Platform sensor & executor ONLY; contains zero business logic.
- **`CurrentUiStateStore`**: In-memory, non-blocking, zero LLM calls, always ready.
- **`ActionVerifier`**: Never assumes `click() == success`; verifies state transitions in `CurrentUiStateStore`.

---

## Task Breakdown

### Task 1: Data Models & `CurrentUiStateStore`

**Files:**
- Create: `app/src/main/java/com/example/automation/state/CurrentUiStateStore.kt`
- Test: `app/src/test/java/com/example/automation/CurrentUiStateStoreTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/example/automation/CurrentUiStateStoreTest.kt
package com.example.automation

import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CurrentUiStateStoreTest {

    @Before
    fun setUp() {
        CurrentUiStateStore.reset()
    }

    @Test
    fun testUpdateAndRetrieveState() {
        val node = SemanticNode(
            text = "Create a new sticker",
            contentDescription = null,
            resourceId = "com.whatsapp:id/sticker_btn",
            isClickable = true,
            isEditable = false,
            className = "android.widget.Button"
        )
        val state = UiScreenState(
            packageName = "com.whatsapp",
            activityName = "HomeActivity",
            nodes = listOf(node)
        )

        CurrentUiStateStore.updateState(state)

        val current = CurrentUiStateStore.getCurrentState()
        assertEquals("com.whatsapp", current.packageName)
        assertEquals(1, current.nodes.size)
        assertEquals("Create a new sticker", current.nodes[0].text)

        val match = CurrentUiStateStore.findFirstMatching("Create a new sticker")
        assertNotNull(match)
        assertEquals("com.whatsapp:id/sticker_btn", match?.resourceId)
    }

    @Test
    fun testFindMatchingWithAliases() {
        val searchNode = SemanticNode(
            text = "Search here",
            contentDescription = "Search",
            resourceId = "com.google.android.apps.maps:id/search_bar",
            isClickable = true,
            isEditable = false,
            className = "android.widget.TextView"
        )
        CurrentUiStateStore.updateState(
            UiScreenState(packageName = "com.google.android.apps.maps", nodes = listOf(searchNode))
        )

        val match = CurrentUiStateStore.findFirstMatching("Search")
        assertNotNull(match)
        assertEquals("Search here", match?.text)
    }
}
```

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.CurrentUiStateStoreTest"`
Expected: Compilation failure (unresolved reference: `CurrentUiStateStore`).

**Step 3: Implement `CurrentUiStateStore`**
Create `app/src/main/java/com/example/automation/state/CurrentUiStateStore.kt` with:
- `data class SemanticNode(...)`
- `data class UiScreenState(...)`
- `object CurrentUiStateStore` with `MutableStateFlow<UiScreenState>`, fast indices by text / contentDescription / resourceId, monotonic version, and `findFirstMatching(query)`.

**Step 4: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.CurrentUiStateStoreTest"`
Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Reactive State Feeder in `JarvisAccessibilityService`

**Files:**
- Modify: `app/src/main/java/com/example/service/JarvisAccessibilityService.kt`
- Test: `app/src/test/java/com/example/automation/AccessibilityStateMonitorTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/example/automation/AccessibilityStateMonitorTest.kt
package com.example.automation

import android.view.accessibility.AccessibilityEvent
import com.example.automation.state.CurrentUiStateStore
import com.example.service.JarvisAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityStateMonitorTest {

    @Test
    fun testAccessibilityEventUpdatesCurrentUiStateStore() {
        CurrentUiStateStore.reset()
        JarvisAccessibilityService.publishUiStateSnapshot(
            packageName = "org.telegram.messenger",
            activityName = "LaunchActivity",
            rawTextList = listOf("Chats", "Contacts", "Settings")
        )

        val state = CurrentUiStateStore.getCurrentState()
        assertEquals("org.telegram.messenger", state.packageName)
        assertTrue(state.allVisibleTexts.contains("Chats"))
    }
}
```

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.AccessibilityStateMonitorTest"`
Expected: Compilation failure.

**Step 3: Implement Event Monitor in `JarvisAccessibilityService`**
- In `onAccessibilityEvent(event: AccessibilityEvent?)`:
  - When `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOWS_CHANGED`, or `TYPE_WINDOW_CONTENT_CHANGED` fires:
  - Extract root node tree into lightweight `SemanticNode` hierarchy.
  - Dispatch update to `CurrentUiStateStore.updateState(...)` (rate-limited / debounced at 100ms to eliminate UI thread overhead).
- Provide test hook `publishUiStateSnapshot` for unit testing.

**Step 4: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.AccessibilityStateMonitorTest"`
Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Generic Node Action Executor

**Files:**
- Create: `app/src/main/java/com/example/automation/execution/GenericNodeExecutor.kt`
- Test: `app/src/test/java/com/example/automation/GenericNodeExecutorTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/example/automation/GenericNodeExecutorTest.kt
package com.example.automation

import com.example.automation.execution.GenericNodeAction
import com.example.automation.execution.GenericNodeExecutor
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenState
import org.junit.Assert.*
import org.junit.Test

class GenericNodeExecutorTest {

    @Test
    fun testExecuteClickOnMatchingTarget() {
        val target = SemanticNode(
            text = "Calls",
            isClickable = true,
            className = "android.widget.TextView"
        )
        CurrentUiStateStore.updateState(
            UiScreenState(packageName = "com.whatsapp", nodes = listOf(target))
        )

        val plan = GenericNodeExecutor.resolveAction(
            action = GenericNodeAction.CLICK,
            targetQuery = "Calls"
        )

        assertTrue(plan.canExecute)
        assertEquals("Calls", plan.matchedNode?.text)
    }
}
```

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.GenericNodeExecutorTest"`
Expected: Compilation failure.

**Step 3: Implement `GenericNodeExecutor`**
- Handle standard actions: `CLICK`, `FOCUS`, `SET_TEXT`, `SCROLL`, `ENTER`, `BACK`.
- Target matching connects with `UiTargetResolver` + `CurrentUiStateStore`.
- Node dispatch uses `matchedNode.performAction(ACTION_CLICK)` / `ACTION_SET_TEXT` with fallback to `JarvisAccessibilityService.dispatchGestureAction`.

**Step 4: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.GenericNodeExecutorTest"`
Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Connect `ComputerUseEngine` to `CurrentUiStateStore`

**Files:**
- Modify: `app/src/main/java/com/example/automation/computeruse/ComputerUseEngine.kt`
- Modify: `app/src/main/java/com/example/automation/computeruse/ActionVerifier.kt`
- Test: `app/src/test/java/com/example/automation/ComputerUseCurrentStateLoopTest.kt`

**Step 1: Write the failing test**

```kotlin
// app/src/test/java/com/example/automation/ComputerUseCurrentStateLoopTest.kt
package com.example.automation

import com.example.automation.computeruse.ComputerUseEngine
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComputerUseCurrentStateLoopTest {

    @Test
    fun testComputerUseUsesCurrentUiStateWithoutUnnecessaryScreenshot() = runBlocking {
        CurrentUiStateStore.updateState(
            UiScreenState(
                packageName = "com.whatsapp",
                nodes = listOf(
                    SemanticNode(text = "Create a new sticker", isClickable = true)
                )
            )
        )

        val state = CurrentUiStateStore.getCurrentState()
        assertEquals("com.whatsapp", state.packageName)
        val node = CurrentUiStateStore.findFirstMatching("Create a new sticker")
        assertNotNull(node)
    }
}
```

**Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.ComputerUseCurrentStateLoopTest"`
Expected: PASS/FAIL depending on wiring.

**Step 3: Refactor `ComputerUseEngine`**
- In `ComputerUseEngine.executeLoop`:
  - Check `CurrentUiStateStore.getCurrentState()` first.
  - If `currentState.nodes.isNotEmpty()`, resolve target and plan action immediately (< 1ms).
  - Only invoke `ScreenObservationProvider.captureObservation(captureScreenshot = true)` if `currentState.isSparseOrObscured` (fewer than 3 nodes or canvas/web surface).
  - After action execution, call `CurrentUiStateStore.waitForChange(timeoutMs = 600L)` instead of hardcoded `delay(350L)`.

**Step 4: Run test to verify it passes**
Run: `.\gradlew.bat testDebugUnitTest --tests "com.example.automation.ComputerUseCurrentStateLoopTest"`
Expected: `BUILD SUCCESSFUL`.

---

### Task 5: End-to-End Real-World Scenario Verifications

**Scenarios Tested:**
1. **WhatsApp Arbitrary Click:** `"Create a new sticker"` found in tree $\rightarrow$ clicked $\rightarrow$ verified.
2. **YouTube Search Flow:** Search node $\rightarrow$ `ACTION_CLICK` $\rightarrow$ type query $\rightarrow$ `PRESS_ENTER` $\rightarrow$ verify results.
3. **Tab Navigation:** WhatsApp Calls tab $\rightarrow$ Chats tab $\rightarrow$ verify screen changed to Chats.

---

## Acceptance Checklist

- [ ] `CurrentUiStateStore` is the single source of truth for the active UI tree.
- [ ] `JarvisAccessibilityService` has zero app-specific workflows; it is strictly a sensor/executor.
- [ ] `ComputerUseEngine` reads state in < 1ms from in-memory store without unconditional `screencap`.
- [ ] Arbitrary clicks ("Create a new sticker") execute via semantic node matching without hardcoded state machines.
- [ ] All unit tests under `com.example.automation.*` pass with 100% success.
