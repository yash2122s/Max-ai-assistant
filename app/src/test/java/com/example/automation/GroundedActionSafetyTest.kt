package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.computeruse.ComputerAction
import com.example.automation.computeruse.ComputerActionExecutor
import com.example.automation.engine.ExecutionTier
import com.example.automation.engine.GenericAction
import com.example.automation.engine.GenericActionExecutor
import com.example.automation.engine.LiveNodeResolver
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.SemanticNode
import com.example.service.JarvisAccessibilityService
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroundedActionSafetyTest {
    private fun node(
        id: String? = null,
        text: String? = null,
        bounds: Rect = Rect(20, 20, 120, 80),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        pkg: String? = null
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        viewIdResourceName = id
        this.text = text
        className = if (editable) "android.widget.EditText" else "android.view.View"
        packageName = pkg
        isClickable = clickable
        isLongClickable = longClickable
        isEditable = editable
        isEnabled = enabled
        isVisibleToUser = visible
        setBoundsInScreen(bounds)
        if (clickable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        if (longClickable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
        if (editable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
    }

    @Before fun setup() {
        CurrentUiStateStore.reset()
        LiveNodeResolver.rootProvider = { null }
        GenericActionExecutor.testGestureDispatcher = null
        JarvisAccessibilityService.testRootProvider = null
        JarvisAccessibilityService.testShellExecutor = null
    }

    @After fun teardown() {
        CurrentUiStateStore.reset()
        LiveNodeResolver.rootProvider = { com.example.service.JarvisAccessibilityService.getActiveRoot() }
        GenericActionExecutor.testGestureDispatcher = null
        JarvisAccessibilityService.testRootProvider = null
        JarvisAccessibilityService.testShellExecutor = null
    }

    @Test fun missingTargetNeverTapsOldCoordinates() = runBlocking {
        val root = node(text = "Other")
        LiveNodeResolver.rootProvider = { root }
        var taps = 0
        GenericActionExecutor.testGestureDispatcher = { _, _ -> taps++; true }
        val target = SemanticNode(id = "old", text = "Gone", bounds = Rect(20, 20, 120, 80))

        assertFalse(GenericActionExecutor.execute(GenericAction.ClickNode(target)).success)
        assertFalse(GenericActionExecutor.execute(GenericAction.LongClickNode(target)).success)
        assertEquals(0, taps)
    }

    @Test fun movedTargetUsesCurrentLiveBounds() = runBlocking {
        val root = node(id = "app:id/label", text = "Continue", bounds = Rect(300, 400, 500, 500))
        LiveNodeResolver.rootProvider = { root }
        val taps = mutableListOf<Pair<Int, Int>>()
        GenericActionExecutor.testGestureDispatcher = { x, y -> taps.add(x to y); true }
        val target = SemanticNode(id = "label", resourceId = "app:id/label", text = "Continue", bounds = Rect(20, 20, 120, 80))

        val result = GenericActionExecutor.execute(GenericAction.ClickNode(target))
        assertTrue(result.success)
        assertEquals(ExecutionTier.TIER_2_GESTURE, result.tier)
        assertEquals(listOf(400 to 450), taps)
    }

    @Test fun sameIdDifferentRowsUseExactTextAndRejectUnlabeledDuplicate() {
        val root = node(bounds = Rect(0, 0, 600, 600))
        val wrong = node(id = "app:id/row", text = "Bob", bounds = Rect(10, 10, 120, 50))
        val right = node(id = "app:id/row", text = "Alice", bounds = Rect(10, 100, 120, 140))
        Shadows.shadowOf(root).addChild(wrong)
        Shadows.shadowOf(root).addChild(right)

        assertEquals(right, LiveNodeResolver.resolveLiveNode(SemanticNode(
            id = "row", resourceId = "app:id/row", text = "Alice", bounds = Rect(10, 10, 120, 50)
        ), root))
        assertNull(LiveNodeResolver.resolveLiveNode(SemanticNode(
            id = "row", resourceId = "app:id/row", bounds = Rect(10, 10, 120, 50)
        ), root))
    }

    @Test fun duplicateExactTextAndPartialTextAreNotTargets() {
        val root = node(bounds = Rect(0, 0, 600, 600))
        Shadows.shadowOf(root).addChild(node(text = "Send", bounds = Rect(10, 10, 120, 50)))
        Shadows.shadowOf(root).addChild(node(text = "Send", bounds = Rect(10, 100, 120, 140)))
        assertNull(LiveNodeResolver.resolveLiveNode(SemanticNode(id = "send", text = "Send"), root))
        assertNull(LiveNodeResolver.resolveLiveNode(SemanticNode(id = "send", text = "Sen"), root))
    }

    @Test fun ambiguousOrChangedControlNeverReceivesGesture() = runBlocking {
        val root = node(bounds = Rect(0, 0, 600, 600))
        Shadows.shadowOf(root).addChild(node(id = "app:id/row", text = "Other", bounds = Rect(10, 10, 120, 50)))
        Shadows.shadowOf(root).addChild(node(id = "app:id/row", text = "Other", bounds = Rect(10, 100, 120, 140)))
        LiveNodeResolver.rootProvider = { root }
        var taps = 0
        GenericActionExecutor.testGestureDispatcher = { _, _ -> taps++; true }
        assertFalse(GenericActionExecutor.execute(GenericAction.ClickNode(SemanticNode(
            id = "row", resourceId = "app:id/row", bounds = Rect(10, 10, 120, 50)
        ))).success)
        assertFalse(GenericActionExecutor.execute(GenericAction.ClickNode(SemanticNode(
            id = "row", resourceId = "app:id/row", text = "Original", bounds = Rect(10, 10, 120, 50)
        ))).success)
        assertEquals(0, taps)
    }

    @Test fun invisibleDisabledMissingSignatureAndWrongPackageAreRejected() {
        val target = SemanticNode(id = "row", resourceId = "app:id/row", text = "Alice")
        assertNull(LiveNodeResolver.resolveLiveNode(target, node(id = "app:id/row", text = "Alice", enabled = false)))
        assertNull(LiveNodeResolver.resolveLiveNode(target, node(id = "app:id/row", text = "Alice", visible = false)))
        val noText = node(id = "app:id/row")
        assertFalse(LiveNodeResolver.validatePathCandidate(noText, target))
        assertNull(LiveNodeResolver.resolveLiveNode(target, noText))
        assertNull(LiveNodeResolver.resolveLiveNode(target, node(id = "app:id/row", text = "Alice", pkg = "other")))
    }

    @Test fun anonymousCanvasRequiresLiveExactBoundsAndPath() = runBlocking {
        val root = node(bounds = Rect(0, 0, 600, 600))
        val canvas = node(bounds = Rect(100, 200, 300, 400))
        Shadows.shadowOf(root).addChild(canvas)
        LiveNodeResolver.rootProvider = { root }
        var tap: Pair<Int, Int>? = null
        GenericActionExecutor.testGestureDispatcher = { x, y -> tap = x to y; true }
        val target = SemanticNode(id = "canvas", hierarchyPath = "root/0", bounds = Rect(100, 200, 300, 400))
        assertTrue(GenericActionExecutor.execute(GenericAction.ClickNode(target)).success)
        assertEquals(200 to 300, tap)
        assertNull(LiveNodeResolver.resolveLiveNode(target.copy(bounds = Rect(101, 200, 300, 400)), root))
        assertNull(LiveNodeResolver.resolveByHierarchyPath(root, "root/0/../0"))
        assertNull(LiveNodeResolver.resolveByHierarchyPath(root, "root/" + List(31) { "0" }.joinToString("/")))
    }

    @Test fun onlyAppropriateAncestorActionIsSelected() {
        val parent = node(bounds = Rect(0, 0, 300, 300), clickable = true, longClickable = true)
        val child = node(text = "Label")
        Shadows.shadowOf(parent).addChild(child)
        assertEquals(parent, LiveNodeResolver.resolveClickableTarget(child))
        assertEquals(parent, LiveNodeResolver.resolveClickableTarget(child, AccessibilityNodeInfo.ACTION_LONG_CLICK))
        val clickOnly = node(bounds = Rect(0, 0, 300, 300), clickable = true)
        val label = node(text = "Label")
        Shadows.shadowOf(clickOnly).addChild(label)
        assertNull(LiveNodeResolver.resolveClickableTarget(label, AccessibilityNodeInfo.ACTION_LONG_CLICK))
    }

    @Test fun syntheticTapElementKeepsResourceIdAndRejectsWrongControl() = runBlocking {
        LiveNodeResolver.rootProvider = { node(id = "app:id/other", text = "Continue", clickable = true) }
        var taps = 0
        GenericActionExecutor.testGestureDispatcher = { _, _ -> taps++; true }
        val result = GenericActionExecutor.executeComputerAction(ComputerAction.TapElement("Continue", resourceId = "app:id/confirm"))
        assertFalse(result.success)
        assertEquals(0, taps)
    }

    @Test fun unresolvedExplicitTypingAndUnknownKeyFailClosed() = runBlocking {
        LiveNodeResolver.rootProvider = { node(id = "app:id/other", text = "Other", editable = true) }
        assertFalse(GenericActionExecutor.executeComputerAction(ComputerAction.TypeText("hello", targetField = "recipient")).success)
        assertFalse(GenericActionExecutor.execute(GenericAction.SetTextNode(SemanticNode(id = "missing", text = "Missing"), "hello")).success)
        assertFalse(GenericActionExecutor.execute(GenericAction.TypeText("hello")).success)
        assertEquals(ExecutionTier.NONE, GenericActionExecutor.execute(GenericAction.KeyPress("UNKNOWN")).tier)
        assertEquals(ExecutionTier.NONE, GenericActionExecutor.executeComputerAction(ComputerAction.PressKey("UNKNOWN")).tier)
    }

    @Test fun targetDisappearingBeforeGestureFallbackDoesNotReceiveTap() = runBlocking {
        var reads = 0
        var taps = 0
        LiveNodeResolver.rootProvider = { node(text = if (reads++ == 0) "Continue" else "Different control") }
        GenericActionExecutor.testGestureDispatcher = { _, _ -> taps++; true }
        val result = GenericActionExecutor.execute(GenericAction.ClickNode(SemanticNode(id = "continue", text = "Continue")))
        assertFalse(result.success)
        assertEquals(0, taps)
    }

    @Test fun computerUseAdapterDoesNotBypassRejectedSemanticTarget() = runBlocking {
        val root = node(id = "app:id/row", text = "Other row", clickable = true)
        LiveNodeResolver.rootProvider = { root }
        JarvisAccessibilityService.testRootProvider = { root }
        var shellCalls = 0
        JarvisAccessibilityService.testShellExecutor = { shellCalls++ }
        val result = ComputerActionExecutor.execute(
            ApplicationProvider.getApplicationContext<Context>(),
            ComputerAction.TapElement("Intended row", resourceId = "app:id/row")
        )
        assertFalse(result.success)
        assertEquals("GROUNDING_REJECTED", result.methodUsed)
        assertEquals(0, shellCalls)
    }

    @Test fun setTextRefusesPasswordField() = runBlocking {
        val password = node(id = "app:id/password", editable = true).apply { isPassword = true }
        LiveNodeResolver.rootProvider = { password }
        assertFalse(GenericActionExecutor.execute(GenericAction.SetTextNode(
            SemanticNode(id = "password", resourceId = "app:id/password"), "not-a-real-secret"
        )).success)
    }
}
