package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.monitor.AccessibilityStateMonitor
import com.example.automation.state.CurrentUiStateStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityStateMonitorTest {

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.view.View",
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isFocused: Boolean = false,
        bounds: Rect = Rect(10, 10, 100, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isEditable = isEditable
            this.isFocused = isFocused
            this.setBoundsInScreen(bounds)
        }
    }

    @Before
    fun setUp() {
        CurrentUiStateStore.reset()
        AccessibilityStateMonitor.reset()
    }

    @Test
    fun testExtractSnapshot_ConvertsTreeToSemanticNodes() {
        val root = createNode(
            resId = "com.whatsapp:id/main_layout",
            className = "android.widget.FrameLayout"
        ).apply {
            packageName = "com.whatsapp"
        }

        val child1 = createNode(
            text = "Create a new sticker",
            resId = "com.whatsapp:id/sticker_btn",
            className = "android.widget.Button",
            isClickable = true,
            bounds = Rect(20, 20, 120, 80)
        )
        val child2 = createNode(
            text = "Type a message",
            resId = "com.whatsapp:id/entry",
            className = "android.widget.EditText",
            isEditable = true,
            isFocused = true,
            bounds = Rect(20, 100, 300, 160)
        )

        org.robolectric.Shadows.shadowOf(root).addChild(child1)
        org.robolectric.Shadows.shadowOf(root).addChild(child2)

        val snapshot = AccessibilityStateMonitor.extractSnapshot(root)

        assertEquals("com.whatsapp", snapshot.packageName)
        assertEquals(3, snapshot.nodes.size) // root + 2 children

        val stickerNode = snapshot.nodes.firstOrNull { it.text == "Create a new sticker" }
        assertNotNull(stickerNode)
        assertTrue(stickerNode!!.isClickable)
        assertEquals("com.whatsapp:id/sticker_btn", stickerNode.resourceId)

        val entryNode = snapshot.nodes.firstOrNull { it.isEditable }
        assertNotNull(entryNode)
        assertEquals(entryNode, snapshot.focusedNode)
    }

    @Test
    fun testEventProcessing_WindowChangeUpdatesStore() = runTest {
        val testScope = this
        AccessibilityStateMonitor.monitorScope = testScope

        val root = createNode(
            text = "Chats",
            resId = "com.whatsapp:id/tab_chats",
            className = "android.widget.TextView",
            isClickable = true
        ).apply {
            packageName = "com.whatsapp"
        }

        AccessibilityStateMonitor.rootProvider = { root }

        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "com.whatsapp"
            className = "com.whatsapp.HomeActivity"
        }

        AccessibilityStateMonitor.onAccessibilityEvent(event)
        testScope.advanceTimeBy(10)

        val snapshot = CurrentUiStateStore.getCurrentSnapshot()
        assertEquals("com.whatsapp", snapshot.packageName)
        assertTrue(snapshot.nodes.isNotEmpty())
    }

    @Test
    fun testEventProcessing_ViewFocusedTriggersImmediateUpdate() = runTest {
        val testScope = this
        AccessibilityStateMonitor.monitorScope = testScope

        val focusedNode = createNode(
            text = "Search",
            resId = "com.whatsapp:id/search_src_text",
            isFocused = true
        ).apply {
            packageName = "com.whatsapp"
        }
        AccessibilityStateMonitor.rootProvider = { focusedNode }

        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_VIEW_FOCUSED
            packageName = "com.whatsapp"
        }

        AccessibilityStateMonitor.onAccessibilityEvent(event)
        testScope.advanceTimeBy(10)

        val snapshot = CurrentUiStateStore.getCurrentSnapshot()
        assertEquals("com.whatsapp", snapshot.packageName)
        assertEquals(true, snapshot.focusedNode?.isFocused)
    }

    @Test
    fun testEventProcessing_ContentChangeDebounced() = runTest {
        val testScope = this
        AccessibilityStateMonitor.monitorScope = testScope

        var callCount = 0
        val root = createNode(
            text = "Initial",
            resId = "com.whatsapp:id/chat_title"
        ).apply {
            packageName = "com.whatsapp"
        }

        AccessibilityStateMonitor.rootProvider = {
            callCount++
            root
        }

        // Fire 5 content changed events rapidly
        for (i in 1..5) {
            val event = AccessibilityEvent.obtain().apply {
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                packageName = "com.whatsapp"
            }
            AccessibilityStateMonitor.onAccessibilityEvent(event)
            testScope.advanceTimeBy(20) // total 100ms
        }

        // Initially within debounce window, should not have fired yet
        testScope.advanceTimeBy(100) // advance past 100ms debounce
        // Verify debounced coalescing
        assertTrue("Expected coalesced calls, actual: $callCount", callCount <= 2)
    }

    @Test
    fun testFingerprintDetectsFocusStateChange_PublishesNewState() = runTest {
        val testScope = this
        AccessibilityStateMonitor.monitorScope = testScope

        // Step 1: Initial state where search box is NOT focused
        val unfocusedNode = createNode(
            text = "Search here",
            resId = "com.whatsapp:id/search_src_text",
            isFocused = false
        ).apply {
            packageName = "com.whatsapp"
        }
        AccessibilityStateMonitor.rootProvider = { unfocusedNode }

        AccessibilityStateMonitor.refreshNow("com.whatsapp", "HomeActivity")
        val initialSnapshot = CurrentUiStateStore.getCurrentSnapshot()
        assertNotNull(initialSnapshot)
        assertEquals("com.whatsapp", initialSnapshot.packageName)
        val initialVersion = initialSnapshot.stateVersion
        assertEquals(false, initialSnapshot.nodes.firstOrNull()?.isFocused)

        // Step 2: User taps search box -> becomes FOCUSED
        val focusedNode = createNode(
            text = "Search here",
            resId = "com.whatsapp:id/search_src_text",
            isFocused = true
        ).apply {
            packageName = "com.whatsapp"
        }
        AccessibilityStateMonitor.rootProvider = { focusedNode }

        AccessibilityStateMonitor.refreshNow("com.whatsapp", "HomeActivity")
        val updatedSnapshot = CurrentUiStateStore.getCurrentSnapshot()

        assertTrue(
            "State version must increment on focus change",
            updatedSnapshot.stateVersion > initialVersion
        )
        assertTrue(
            "Fingerprint must differ on focus change",
            updatedSnapshot.fingerprint != initialSnapshot.fingerprint
        )
        assertEquals(true, updatedSnapshot.nodes.firstOrNull()?.isFocused)
        assertNotNull(updatedSnapshot.focusedNode)
        assertEquals("com.whatsapp:id/search_src_text", updatedSnapshot.focusedNode?.id)
    }

    @Test
    fun testSparseDetection_IsSemanticRatherThanRawNodeCount() {
        val searchNode = com.example.automation.state.SemanticNode(
            id = "search",
            text = "Search",
            isClickable = true,
            isFocusable = true,
            isVisibleToUser = true
        )

        val backNode = com.example.automation.state.SemanticNode(
            id = "back",
            contentDescription = "Back",
            isClickable = true,
            isVisibleToUser = true
        )

        val snapshot = com.example.automation.state.UiScreenSnapshot(
            packageName = "com.google.android.youtube",
            nodes = listOf(searchNode, backNode)
        )

        assertFalse(
            "Two meaningful interactive nodes should not be treated as sparse",
            snapshot.isSparseOrObscured
        )
    }

    @Test
    fun testRefreshSequence_PreventsOlderRequestFromOverwritingNewerState() = runTest {
        val oldNode = createNode(
            text = "Old Screen",
            resId = "test:id/old"
        ).apply {
            packageName = "com.test"
        }

        val newNode = createNode(
            text = "New Screen",
            resId = "test:id/new"
        ).apply {
            packageName = "com.test"
        }

        var currentRoot: AccessibilityNodeInfo = oldNode

        AccessibilityStateMonitor.rootProvider = {
            currentRoot
        }

        AccessibilityStateMonitor.refreshNow("com.test", "OldActivity")
        val oldVersion =
            CurrentUiStateStore.getCurrentSnapshot().stateVersion

        currentRoot = newNode

        AccessibilityStateMonitor.refreshNow("com.test", "NewActivity")
        val newSnapshot =
            CurrentUiStateStore.getCurrentSnapshot()

        assertTrue(newSnapshot.stateVersion > oldVersion)
        assertEquals("NewActivity", newSnapshot.activityName)
        assertEquals("New Screen", newSnapshot.nodes.first().text)
    }
}
