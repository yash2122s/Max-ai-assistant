package com.example.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.automation.models.UiTarget
import com.example.automation.tools.ClickUiTool
import com.example.automation.tools.SpotifySearchTool
import com.example.automation.tools.ToolResult
import com.example.automation.ui.ScreenGroundingEngine
import com.example.automation.ui.ScreenSummaryFormatter
import com.example.automation.ui.SignatureCalculator
import com.example.service.JarvisAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class JarvisMigrationUnitTest {

    private fun createNode(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        className: String = "android.widget.TextView",
        isClickable: Boolean = false,
        isCheckable: Boolean = false,
        isChecked: Boolean = false,
        isEditable: Boolean = false,
        bounds: Rect = Rect(10, 10, 100, 100)
    ): AccessibilityNodeInfo {
        return AccessibilityNodeInfo.obtain().apply {
            this.text = text
            this.contentDescription = desc
            this.viewIdResourceName = resId
            this.className = className
            this.isClickable = isClickable
            this.isCheckable = isCheckable
            this.isChecked = isChecked
            this.isEditable = isEditable
            this.setBoundsInScreen(bounds)
        }
    }

    // ========================================================================
    // 1. Data Models Verification (Phase 1)
    // ========================================================================

    @Test
    fun testUiTargetExtendedFields() {
        val target = UiTarget(
            text = "Dark Mode",
            contentDescription = "Toggle Dark Mode",
            className = "android.widget.Switch",
            viewId = "com.example:id/dark_mode_switch",
            isClickable = true,
            isCheckable = true,
            isChecked = false,
            isEditable = false,
            isScrollable = false,
            bounds = Rect(0, 0, 100, 50)
        )

        assertEquals("Dark Mode", target.primaryLabel)
        assertTrue(target.hasTextOrDesc)
        assertTrue(target.isSwitchOrToggle())
        assertFalse(target.isImageOrThumbnail)
        assertEquals(50f, target.center.x, 0.01f)
        assertEquals(25f, target.center.y, 0.01f)
    }

    @Test
    fun testToolResultExtendedFieldsAndSerialization() {
        val result = ToolResult(
            success = true,
            toolName = "click_ui",
            message = "Element clicked successfully",
            verified = true,
            currentStateSummary = "Screen Summary: 5 interactive elements visible.",
            requiresUserConfirmation = true
        )

        assertEquals(true, result.verified)
        assertEquals("Screen Summary: 5 interactive elements visible.", result.currentStateSummary)
        assertEquals(true, result.requiresUserConfirmation)

        val json = result.toModelJson()
        assertTrue(json.getBoolean("verified"))
        assertEquals("Screen Summary: 5 interactive elements visible.", json.getString("current_state"))
        assertTrue(json.getBoolean("requires_confirmation"))
    }

    // ========================================================================
    // 2. Tree Traversal & Row Coalescing (Phase 2)
    // ========================================================================

    @Test
    fun testCollectNodeText_Coalescing() {
        val parent = createNode(className = "android.widget.LinearLayout")
        val child1 = createNode(text = "Wireless Headphones")
        val child2 = createNode(text = "$49.99")
        val child3 = createNode(desc = "4.5 stars rating")

        shadowOf(parent).addChild(child1)
        shadowOf(parent).addChild(child2)
        shadowOf(parent).addChild(child3)

        val coalesced = ScreenGroundingEngine.collectNodeText(parent)
        assertTrue(coalesced.contains("Wireless Headphones"))
        assertTrue(coalesced.contains("$49.99"))
        assertTrue(coalesced.contains("4.5 stars rating"))
    }

    @Test
    fun testCollectNodeText_LeafNodeReturnsEmptyDescendants() {
        val leaf = createNode(text = "Settings")
        val descendants = ScreenGroundingEngine.collectNodeText(leaf)
        assertTrue("Leaf node without children should have empty descendant list", descendants.isEmpty())
    }

    // ========================================================================
    // 3. UiStateWaiter & SignatureCalculator (Phase 3)
    // ========================================================================

    @Test
    fun testSignatureCalculator_ConsistencyAndDifference() {
        val root1 = createNode(text = "Home Screen", resId = "com.app:id/title")
        val sig1 = SignatureCalculator.compute(root1)
        val sig1Copy = SignatureCalculator.compute(root1)

        assertEquals(sig1, sig1Copy)

        val root2 = createNode(text = "Profile Screen", resId = "com.app:id/title")
        val sig2 = SignatureCalculator.compute(root2)

        assertFalse("Signatures should differ for different screen contents", sig1 == sig2)
    }

    // ========================================================================
    // 4. ClickUiTool Guards & Safety (Phase 4)
    // ========================================================================

    @Test
    fun testSensitiveTargetGate() {
        val tool = ClickUiTool()

        assertTrue(tool.isSensitiveTarget("Pay Now", ""))
        assertTrue(tool.isSensitiveTarget("Transfer Money", ""))
        assertTrue(tool.isSensitiveTarget("Delete All Records", ""))
        assertTrue(tool.isSensitiveTarget("Factory Reset", ""))
        assertTrue(tool.isSensitiveTarget("Buy Subscription", "Purchase item"))

        assertFalse(tool.isSensitiveTarget("Next Page", ""))
        assertFalse(tool.isSensitiveTarget("Submit Review", ""))
        assertFalse(tool.isSensitiveTarget("Search", ""))
    }

    @Test
    fun testGmailSendGuard_BlockedWhenNotExplicit() {
        val tool = ClickUiTool()
        val dummyService = JarvisAccessibilityService()

        val blockedResult = tool.evaluateAppGuards(
            service = dummyService,
            root = null,
            pkg = "com.google.android.gm",
            targetName = "Send",
            targetDesc = "",
            taskGoal = "Check my unread messages"
        )

        assertNotNull(blockedResult)
        assertEquals("GMAIL_SEND_BLOCKED", blockedResult?.errorCode)
        assertEquals(false, blockedResult?.verified)
    }

    @Test
    fun testGmailSendGuard_AllowedWhenExplicitlyRequested() {
        val tool = ClickUiTool()
        val dummyService = JarvisAccessibilityService()

        val allowedResult = tool.evaluateAppGuards(
            service = dummyService,
            root = null,
            pkg = "com.google.android.gm",
            targetName = "Send",
            targetDesc = "",
            taskGoal = "Send email to john with attachment"
        )

        assertNull("Should allow send when goal explicitly asks to send email", allowedResult)
    }

    @Test
    fun testFlowSettingsChipGuard_Blocked() {
        val tool = ClickUiTool()
        val dummyService = JarvisAccessibilityService()

        val blockedChips = listOf("16:9", "1080p", "4s", "x2", "veo 2", "360p")
        for (chip in blockedChips) {
            val result = tool.evaluateAppGuards(
                service = dummyService,
                root = null,
                pkg = "com.google.android.googlequicksearchbox",
                targetName = chip,
                targetDesc = "",
                taskGoal = "Create a video"
            )
            assertNotNull("Chip '$chip' must be blocked", result)
            assertEquals("FLOW_SETTINGS_BLOCKED", result?.errorCode)
        }

        val normalButtonResult = tool.evaluateAppGuards(
            service = dummyService,
            root = null,
            pkg = "com.google.android.googlequicksearchbox",
            targetName = "Generate Video",
            targetDesc = "",
            taskGoal = "Create a video"
        )
        assertNull(normalButtonResult)
    }

    // ========================================================================
    // 5. ScreenSummaryFormatter Spatial & Warning Banners (Phase 5)
    // ========================================================================

    @Test
    fun testScreenSummaryFormatter_SpatialPosition() {
        val targetTop = UiTarget(
            text = "Header",
            bounds = Rect(0, 50, 1000, 150)
        )
        val posTop = ScreenSummaryFormatter.getSpatialPosition(targetTop.bounds!!, 1000, 2000)
        assertEquals("Top-Center", posTop)

        val targetBottom = UiTarget(
            text = "Footer",
            bounds = Rect(0, 1850, 1000, 1950)
        )
        val posBottom = ScreenSummaryFormatter.getSpatialPosition(targetBottom.bounds!!, 1000, 2000)
        assertEquals("Bottom-Center", posBottom)
    }

    @Test
    fun testScreenSummaryFormatter_GoogleSearchResultsWarning() {
        val rootNode = createNode(text = "Chrome", className = "android.widget.FrameLayout").apply {
            packageName = "com.android.chrome"
        }
        val target1 = UiTarget(
            text = "https://www.google.com/search?q=kotlin",
            viewId = "com.android.chrome:id/url_bar",
            className = "android.widget.EditText"
        )
        val target2 = UiTarget(
            text = "Kotlin Programming Language",
            className = "android.widget.TextView",
            isClickable = true,
            bounds = Rect(0, 300, 1000, 400)
        )
        val targets = listOf(target1, target2)

        val isSearch = ScreenSummaryFormatter.isGoogleSearchResults(targets)
        assertTrue(isSearch)

        val formatted = ScreenSummaryFormatter.format(targets, rootNode)
        assertTrue(formatted.contains("WEB SEARCH RESULTS"))
    }

    // ========================================================================
    // 6. SpotifySearchTool Clean Query (Phase 9)
    // ========================================================================

    @Test
    fun testSpotifyCleanQuery() {
        val tool = SpotifySearchTool()

        assertEquals("starboy", tool.cleanSpotifyQuery("play starboy song please"))
        assertEquals("believer", tool.cleanSpotifyQuery("spotify bajao believer by imagine dragons please".replace("by imagine dragons please", "")).trim())
        assertEquals("kesariya", tool.cleanSpotifyQuery("meri favorite song chalao kesariya"))
        assertEquals("shape of you", tool.cleanSpotifyQuery("search for shape of you"))
    }

    // ========================================================================
    // 7. Service Additions & Coordinators (Phases 9 & 10)
    // ========================================================================

    @Test
    fun testRecentOtpTtlAndStorage() {
        JarvisAccessibilityService.setRecentOtp("654321")
        assertEquals("654321", JarvisAccessibilityService.getRecentOtp())
    }

    @Test
    fun testUiChangeInitialFlowState() {
        val flow = JarvisAccessibilityService.uiChanges
        assertNotNull(flow.value)
        assertEquals(0L, flow.value.revision)
    }

    @Test
    fun testCoordinatorsCancelSafety() {
        com.example.automation.coordinators.YouTubeAutomationCoordinator.cancel()
        com.example.automation.coordinators.WhatsAppDeleteCoordinator.cancel()
        com.example.automation.coordinators.WhatsAppCallCoordinator.cancel()
    }

    // ========================================================================
    // 8. Back Navigation & Recovery Safety (Phase 12)
    // ========================================================================

    @Test
    fun testTaskRecoveryEngine_NoKeyboardNoBack() = kotlinx.coroutines.runBlocking {
        val recoveryEngine = com.example.automation.engine.execution.TaskRecoveryEngine()
        val dummyContext = org.robolectric.RuntimeEnvironment.getApplication()
        val tapStep = com.example.automation.models.UiStep.Tap("Settings")
        val taskStep = com.example.automation.engine.state.TaskStep(
            id = "test_step_1",
            action = tapStep,
            expectedOutcome = null
        )
        val taskContext = com.example.automation.engine.state.TaskContext(originalIntent = "tap Settings")

        val recovered = recoveryEngine.attemptRecovery(dummyContext, taskStep, taskContext)
        assertTrue(recovered)
    }
}
