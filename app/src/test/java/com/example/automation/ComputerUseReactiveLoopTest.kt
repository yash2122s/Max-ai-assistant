package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.engine.ExecutionTier
import com.example.automation.engine.GenericAction
import com.example.automation.engine.GenericActionExecutor
import com.example.automation.engine.LiveNodeResolver
import com.example.automation.grounding.ScoredTargetGroundingEngine
import com.example.automation.state.CurrentUiStateStore
import com.example.automation.state.ExpectedPredicate
import com.example.automation.state.SemanticNode
import com.example.automation.state.UiScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
class ComputerUseReactiveLoopTest {

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
        GenericActionExecutor.testGestureDispatcher = null
        GenericActionExecutor.testGlobalActionDispatcher = null
    }

    @Test
    fun testExpectedPredicate_PackageForegroundFastExit() = runBlocking {
        CurrentUiStateStore.updateSnapshot(
            UiScreenSnapshot(packageName = "com.google.android.youtube")
        )

        launch {
            delay(50)
            CurrentUiStateStore.updateSnapshot(
                UiScreenSnapshot(packageName = "com.whatsapp", activityName = "HomeActivity")
            )
        }

        val satisfied = CurrentUiStateStore.waitForPredicate(
            ExpectedPredicate.PackageForeground("com.whatsapp"),
            timeoutMs = 500L
        )

        assertTrue("PackageForeground predicate must be satisfied", satisfied)
        assertEquals("com.whatsapp", CurrentUiStateStore.getCurrentSnapshot().packageName)
    }

    @Test
    fun testExpectedPredicate_TextAppearedAndFocusEquals() = runBlocking {
        val entryNode = SemanticNode(
            id = "com.whatsapp:id/entry",
            text = "Hello world",
            resourceId = "com.whatsapp:id/entry",
            isEditable = true,
            isFocused = true
        )

        val snapshot = UiScreenSnapshot(
            packageName = "com.whatsapp",
            nodes = listOf(entryNode),
            focusedNode = entryNode,
            allVisibleTexts = listOf("Hello world", "Send")
        )

        CurrentUiStateStore.updateSnapshot(snapshot)

        assertTrue(
            "TextAppeared must be satisfied",
            ExpectedPredicate.TextAppeared("Hello world").evaluate(snapshot)
        )
        assertTrue(
            "FocusEquals must be satisfied for entry node",
            ExpectedPredicate.FocusEquals("entry").evaluate(snapshot)
        )
    }

    @Test
    fun testEndToEnd_WhatsAppStickerClickScenario() = runBlocking {
        // 1. WhatsApp conversation screen state
        val stickerBtnNode = createNode(
            text = "Create a new sticker",
            resId = "com.whatsapp:id/create_custom_sticker",
            isClickable = true,
            bounds = Rect(40, 120, 240, 180)
        )
        LiveNodeResolver.rootProvider = { stickerBtnNode }

        val stickerSemantic = SemanticNode(
            id = "com.whatsapp:id/create_custom_sticker",
            text = "Create a new sticker",
            resourceId = "com.whatsapp:id/create_custom_sticker",
            bounds = Rect(40, 120, 240, 180),
            isClickable = true
        )

        val currentScreen = UiScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "ConversationActivity",
            nodes = listOf(stickerSemantic)
        )
        CurrentUiStateStore.updateSnapshot(currentScreen)

        // 2. Goal: "Create a new sticker"
        val grounding = ScoredTargetGroundingEngine.groundTarget(currentScreen, "Create a new sticker")
        assertNotNull("Target must be grounded", grounding.bestCandidate)
        assertEquals("com.whatsapp:id/create_custom_sticker", grounding.bestCandidate?.id)
        assertTrue("Confidence must be safe for direct action", grounding.confidence >= 0.85f)

        // 3. Execution: GenericAction.ClickNode
        val execResult = GenericActionExecutor.execute(GenericAction.ClickNode(grounding.bestCandidate!!))
        assertTrue("Click must succeed", execResult.success)
        assertEquals(ExecutionTier.TIER_1_ACCESSIBILITY_API, execResult.tier)

        // 4. Verification: State changes to sticker creator
        launch {
            delay(40)
            CurrentUiStateStore.updateSnapshot(
                UiScreenSnapshot(
                    packageName = "com.whatsapp",
                    activityName = "StickerMakerActivity",
                    allVisibleTexts = listOf("Choose a photo to make a sticker", "Gallery")
                )
            )
        }

        val verified = CurrentUiStateStore.waitForPredicate(
            ExpectedPredicate.TextAppeared("Choose a photo"),
            timeoutMs = 500L
        )
        assertTrue("Sticker maker screen must be verified", verified)
    }

    @Test
    fun testEndToEnd_WhatsAppCallsToChatsTabSwitchScenario() = runBlocking {
        // 1. Start in Calls tab
        val callsTabNode = createNode(
            text = "Calls",
            resId = "com.whatsapp:id/tab_calls",
            isClickable = true,
            bounds = Rect(300, 50, 450, 100)
        )
        val chatsTabNode = createNode(
            text = "Chats",
            resId = "com.whatsapp:id/tab_chats",
            isClickable = true,
            bounds = Rect(50, 50, 200, 100)
        )
        val root = createNode(resId = "com.whatsapp:id/tabs")
        org.robolectric.Shadows.shadowOf(root).addChild(callsTabNode)
        org.robolectric.Shadows.shadowOf(root).addChild(chatsTabNode)
        LiveNodeResolver.rootProvider = { root }

        val callsSemantic = SemanticNode(
            id = "tab_calls",
            text = "Calls",
            resourceId = "com.whatsapp:id/tab_calls",
            bounds = Rect(300, 50, 450, 100),
            isClickable = true
        )
        val chatsSemantic = SemanticNode(
            id = "tab_chats",
            text = "Chats",
            resourceId = "com.whatsapp:id/tab_chats",
            bounds = Rect(50, 50, 200, 100),
            isClickable = true
        )

        CurrentUiStateStore.updateSnapshot(
            UiScreenSnapshot(
                packageName = "com.whatsapp",
                nodes = listOf(callsSemantic, chatsSemantic),
                allVisibleTexts = listOf("Calls", "Chats", "Start a call")
            )
        )

        // 2. Goal: Switch to "Chats"
        val grounding = ScoredTargetGroundingEngine.groundTarget(CurrentUiStateStore.getCurrentSnapshot(), "Chats")
        assertNotNull(grounding.bestCandidate)
        assertEquals("tab_chats", grounding.bestCandidate?.id)

        // 3. Execute click on Chats tab
        val result = GenericActionExecutor.execute(GenericAction.ClickNode(grounding.bestCandidate!!))
        assertTrue(result.success)

        // 4. Verify Chats list appears
        launch {
            delay(40)
            CurrentUiStateStore.updateSnapshot(
                UiScreenSnapshot(
                    packageName = "com.whatsapp",
                    allVisibleTexts = listOf("Chats", "Mom", "Work Group", "Archived")
                )
            )
        }

        val verified = CurrentUiStateStore.waitForPredicate(
            ExpectedPredicate.TextAppeared("Archived"),
            timeoutMs = 500L
        )
        assertTrue("Chats list view must be verified", verified)
    }

    @Test
    fun testComputerActionExecutor_RoutesThroughGenericActionExecutorAndLiveNodeResolver() = runBlocking {
        // Set up live node in LiveNodeResolver
        val stickerBtnNode = createNode(
            text = "Create a new sticker",
            resId = "com.whatsapp:id/create_custom_sticker",
            isClickable = true,
            bounds = Rect(40, 120, 240, 180)
        )
        LiveNodeResolver.rootProvider = { stickerBtnNode }

        // Populate CurrentUiStateStore
        val stickerSemantic = SemanticNode(
            id = "com.whatsapp:id/create_custom_sticker",
            text = "Create a new sticker",
            resourceId = "com.whatsapp:id/create_custom_sticker",
            bounds = Rect(40, 120, 240, 180),
            isClickable = true
        )
        CurrentUiStateStore.updateSnapshot(
            UiScreenSnapshot(
                packageName = "com.whatsapp",
                nodes = listOf(stickerSemantic)
            )
        )

        // Execute action via ComputerActionExecutor (as called by ComputerUseEngine)
        val action = com.example.automation.computeruse.ComputerAction.TapElement(
            targetText = "Create a new sticker",
            resourceId = "com.whatsapp:id/create_custom_sticker"
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = com.example.automation.computeruse.ComputerActionExecutor.execute(context, action)

        assertTrue("Execution must succeed", result.success)
        assertEquals("TIER_1_ACCESSIBILITY_API", result.methodUsed)
        assertTrue(result.message?.contains("Clicked") == true)
    }
}
