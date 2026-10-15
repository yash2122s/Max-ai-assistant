package com.example.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.ActionResult
import com.example.automation.engine.ExecutionTier
import com.example.automation.engine.GenericAction
import com.example.automation.engine.GenericActionExecutor
import com.example.automation.engine.LiveNodeResolver
import com.example.automation.state.SemanticNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GenericActionExecutorTest {

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.view.View",
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        bounds: Rect = Rect(10, 10, 100, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isEditable = isEditable
            this.setBoundsInScreen(bounds)
        }
    }

    @Before
    fun setUp() {
        GenericActionExecutor.testGestureDispatcher = null
        GenericActionExecutor.testGlobalActionDispatcher = null
    }

    @Test
    fun testLiveNodeResolver_ResolvesExactIdAndText() {
        val root = createNode(resId = "com.whatsapp:id/container", className = "android.widget.FrameLayout")
        val child = createNode(
            text = "Create a new sticker",
            resId = "com.whatsapp:id/create_sticker",
            isClickable = true,
            bounds = Rect(50, 100, 250, 150)
        )
        org.robolectric.Shadows.shadowOf(root).addChild(child)

        val semantic = SemanticNode(
            id = "com.whatsapp:id/create_sticker",
            text = "Create a new sticker",
            resourceId = "com.whatsapp:id/create_sticker",
            bounds = Rect(50, 100, 250, 150)
        )

        val resolved = LiveNodeResolver.resolveLiveNode(semantic, root)
        assertNotNull(resolved)
        assertEquals("com.whatsapp:id/create_sticker", resolved?.viewIdResourceName)
        assertEquals("Create a new sticker", resolved?.text?.toString())
    }

    @Test
    fun testLiveNodeResolver_WalksUpToClickableAncestor() {
        val parentButton = createNode(
            resId = "com.whatsapp:id/sticker_button",
            className = "android.widget.LinearLayout",
            isClickable = true,
            bounds = Rect(0, 0, 300, 100)
        )
        val childText = createNode(
            text = "Create a new sticker",
            isClickable = false,
            bounds = Rect(20, 20, 280, 80)
        )
        org.robolectric.Shadows.shadowOf(parentButton).addChild(childText)

        val clickableTarget = LiveNodeResolver.resolveClickableTarget(childText)
        assertEquals(parentButton, clickableTarget)
        assertTrue(clickableTarget.isClickable)
    }

    @Test
    fun testExecuteClickNode_Tier1SuccessWhenNodeClickable() = runBlocking {
        val root = createNode(
            resId = "com.whatsapp:id/create_sticker",
            text = "Create a new sticker",
            isClickable = true,
            bounds = Rect(50, 50, 200, 100)
        )
        LiveNodeResolver.rootProvider = { root }

        val semantic = SemanticNode(
            id = "wa_sticker",
            text = "Create a new sticker",
            resourceId = "com.whatsapp:id/create_sticker",
            bounds = Rect(50, 50, 200, 100),
            isClickable = true
        )

        val result = GenericActionExecutor.execute(GenericAction.ClickNode(semantic))
        assertTrue(result.success)
        assertEquals(ExecutionTier.TIER_1_ACCESSIBILITY_API, result.tier)
    }

    @Test
    fun testExecuteClickNode_FallsBackToTier2GestureWhenSemanticFails() = runBlocking {
        // Root is not clickable and doesn't support performAction(CLICK)
        val root = createNode(
            resId = "custom:id/canvas_view",
            isClickable = false,
            bounds = Rect(100, 200, 300, 400)
        )
        LiveNodeResolver.rootProvider = { root }

        var gestureX = 0
        var gestureY = 0
        GenericActionExecutor.testGestureDispatcher = { x, y ->
            gestureX = x
            gestureY = y
            true
        }

        val semantic = SemanticNode(
            id = "canvas_target",
            bounds = Rect(100, 200, 300, 400),
            isClickable = false
        )

        val result = GenericActionExecutor.execute(GenericAction.ClickNode(semantic))
        assertTrue(result.success)
        assertEquals(ExecutionTier.TIER_2_GESTURE, result.tier)
        // Center of (100, 200, 300, 400) is (200, 300)
        assertEquals(200, gestureX)
        assertEquals(300, gestureY)
    }

    @Test
    fun testExecuteGlobalSystemAction_DispatchesBackAndHome() = runBlocking {
        var dispatchedActionId = -1
        GenericActionExecutor.testGlobalActionDispatcher = { actionId ->
            dispatchedActionId = actionId
            true
        }

        val backResult = GenericActionExecutor.execute(GenericAction.GlobalSystemAction.Back)
        assertTrue(backResult.success)
        assertEquals(AccessibilityService.GLOBAL_ACTION_BACK, dispatchedActionId)

        val homeResult = GenericActionExecutor.execute(GenericAction.GlobalSystemAction.Home)
        assertTrue(homeResult.success)
        assertEquals(AccessibilityService.GLOBAL_ACTION_HOME, dispatchedActionId)
    }

    @Test
    fun testLiveNodeResolver_ResolvesByHierarchyPath() {
        val root = createNode(className = "android.widget.FrameLayout")
        val container = createNode(className = "android.widget.LinearLayout")
        val leafTarget = createNode(
            className = "android.widget.ImageView",
            bounds = Rect(20, 20, 80, 80)
        )

        org.robolectric.Shadows.shadowOf(container).addChild(leafTarget)
        org.robolectric.Shadows.shadowOf(root).addChild(container)

        // Target has no text or resourceId, only hierarchyPath "root/0/0"
        val target = SemanticNode(
            id = "unknown_icon",
            hierarchyPath = "root/0/0",
            className = "android.widget.ImageView",
            bounds = Rect(20, 20, 80, 80)
        )

        val resolved = LiveNodeResolver.resolveLiveNode(target, root)
        assertNotNull("Must resolve by hierarchyPath", resolved)
        assertEquals("android.widget.ImageView", resolved?.className?.toString())
    }

    @Test
    fun testLiveNodeResolver_RejectsInvalidHierarchyPathWhenClassMismatches() {
        val root = createNode(className = "android.widget.FrameLayout")
        val container = createNode(className = "android.widget.LinearLayout")
        val shiftedNode = createNode(
            className = "android.widget.TextView",
            text = "Unrelated",
            bounds = Rect(20, 20, 80, 80)
        )

        org.robolectric.Shadows.shadowOf(container).addChild(shiftedNode)
        org.robolectric.Shadows.shadowOf(root).addChild(container)

        val target = SemanticNode(
            id = "expected_image",
            hierarchyPath = "root/0/0",
            className = "android.widget.ImageView",
            bounds = Rect(20, 20, 80, 80)
        )

        val isValid = LiveNodeResolver.validatePathCandidate(shiftedNode, target)
        org.junit.Assert.assertFalse("Mismatched class must be rejected", isValid)
    }
}
