package com.example.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import com.example.automation.agent.UiAction
import com.example.automation.agent.UiActionExecutor
import com.example.automation.computeruse.ActionExpectation
import com.example.automation.computeruse.ActionResult
import com.example.automation.computeruse.ActionVerifier
import com.example.automation.computeruse.ComputerAction
import com.example.automation.computeruse.InteractiveElement
import com.example.automation.computeruse.ScreenObservation
import com.example.automation.computeruse.ShellCommandPolicy
import com.example.automation.engine.ExecutionEngine
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.GenericAction
import com.example.automation.engine.GenericActionExecutor
import com.example.automation.state.SemanticNode
import com.example.automation.tools.ToolResult
import com.example.automation.tools.ToolResultStatus
import com.example.automation.verification.ClickUiVerifier
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.VerificationResult
import com.example.service.ClickEscalationResult
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase1ReliabilityUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GenericActionExecutor.testGestureDispatcher = null
        GenericActionExecutor.testGlobalActionDispatcher = null
        JarvisAccessibilityService.testRootProvider = null
        JarvisAccessibilityService.testWindowsProvider = null
        JarvisAccessibilityService.testGestureDispatcher = null
        JarvisAccessibilityService.testShellExecutor = null
    }

    @After
    fun tearDown() {
        GenericActionExecutor.testGestureDispatcher = null
        GenericActionExecutor.testGlobalActionDispatcher = null
        JarvisAccessibilityService.testRootProvider = null
        JarvisAccessibilityService.testWindowsProvider = null
        JarvisAccessibilityService.testGestureDispatcher = null
        JarvisAccessibilityService.testShellExecutor = null
    }

    private fun createObservation(
        id: String,
        text: String = "",
        pkg: String = "com.example.app",
        selected: Boolean = false,
        focused: Boolean = false
    ): ScreenObservation = ScreenObservation(
        observationId = id,
        foregroundPackage = pkg,
        interactiveElements = if (text.isBlank()) emptyList() else listOf(
            InteractiveElement(
                text = text,
                bounds = Rect(0, 0, 100, 50),
                isClickable = true,
                isSelected = selected,
                isFocused = focused
            )
        ),
        visibleTexts = listOfNotNull(text.takeIf { it.isNotBlank() }),
        timestamp = System.currentTimeMillis()
    )

    // =========================================================================
    // 1. Accessibility click accepted but expected UI state unchanged
    // =========================================================================
    @Test
    fun testAccessibilityClickAccepted_UIUnchanged_NotVerified() {
        val acceptedResult = ToolResult.accepted("click_element", "Dispatched to OS")
        val verifier = ClickUiVerifier()
        val request = ExecutionRequest(action = "click_element", arguments = com.google.gson.JsonObject().apply {
            addProperty("target", "Settings")
        })
        val snapshot = DeviceContext.capture(context)

        val verification = verifier.verify(context, request, acceptedResult, snapshot)

        // Acceptance alone MUST NOT count as verified postcondition
        assertFalse(verification.success)
        assertTrue(verification.reason?.contains("no verified postcondition") == true)
        assertFalse(acceptedResult.isVerifiedSuccess)
    }

    // =========================================================================
    // 2. Target disappears before execution
    // =========================================================================
    @Test
    fun testTargetDisappearsBeforeExecution_FailsTruthfully() = runBlocking {
        // Semantic node with empty bounds representing a target that vanished
        val emptyNode = SemanticNode(
            id = "vanished_btn",
            text = "Submit",
            bounds = Rect(), // empty bounds
            isClickable = true
        )

        val action = GenericAction.ClickNode(emptyNode)
        val result = GenericActionExecutor.execute(action)

        assertFalse(result.success)
        assertEquals(com.example.automation.engine.ExecutionTier.NONE, result.tier)
    }

    // =========================================================================
    // 3. Action outcome remains unknown
    // =========================================================================
    @Test
    fun testActionOutcomeUnknown_IsNeverVerifiedSuccess() {
        val unknownResult = ToolResult.outcomeUnknown("custom_action", "Evidence unavailable")

        assertEquals(ToolResultStatus.OUTCOME_UNKNOWN, unknownResult.status)
        assertFalse(unknownResult.success)
        assertFalse(unknownResult.isVerifiedSuccess)
    }

    // =========================================================================
    // 4. A click must not trigger a blind second tap
    // =========================================================================
    @Test
    fun testClickAccepted_DoesNotBlindlyDispatchSecondTap() {
        var gestureTapCount = 0
        JarvisAccessibilityService.testGestureDispatcher = {
            gestureTapCount++
            true
        }

        // Mock accessibility node that accepts ACTION_CLICK
        val mockNode = AccessibilityNodeInfo.obtain()
        mockNode.isClickable = true

        // Create a test instance of service
        val service = JarvisAccessibilityService()
        val result = service.performClickWithEscalation(mockNode)

        // When ACTION_CLICK is accepted, gesture fallback MUST NOT fire
        assertEquals(0, gestureTapCount)
        // If UI signature did not change, result is truthfully UNCONFIRMED
        assertEquals(ClickEscalationResult.UNCONFIRMED, result)
    }

    // =========================================================================
    // 5. A successful action is verified against its postcondition
    // =========================================================================
    @Test
    fun testSuccessfulActionVerifiedAgainstPostcondition() {
        val before = createObservation("before", text = "Submit", pkg = "com.example.app")
        val after = createObservation("after", text = "Success Screen", pkg = "com.example.app")

        val action = ComputerAction.TapElement("Submit")
        val executionResult = ActionResult(true, "TAP")

        val (verified, reason) = ActionVerifier.verify(action, executionResult, before, after)

        assertTrue(verified)
        assertTrue(reason.contains("Screen transitioned"))
    }

    // =========================================================================
    // 6. A failed verifier cannot be overridden by a successful dispatch result
    // =========================================================================
    @Test
    fun testFailedVerifierCannotBeOverriddenBySuccessfulDispatch() {
        val dispatchResult = ToolResult.success("test_tool", "Dispatched successfully")
        val failedVerification = VerificationResult(
            success = false,
            reason = "Expected element not present on screen",
            retryRecommended = false,
            snapshot = DeviceContext.capture(context)
        )

        val finalResult = dispatchResult.copy(
            verification = failedVerification
        )

        assertFalse(finalResult.isVerifiedSuccess)
    }

    // =========================================================================
    // 7. Ambiguous or rejected shell input is not executed
    // =========================================================================
    @Test
    fun testRejectedShellInput_BlockedByPolicy() {
        val dangerousCommands = listOf(
            "input text \"test; rm -rf /\"",
            "input text $(whoami)",
            "input text `id`",
            "rm -rf /data/local/tmp",
            "reboot",
            "chmod 777 /data",
            "input text hello<script>"
        )

        for (cmd in dangerousCommands) {
            assertFalse("Command should be blocked by policy: $cmd", ShellCommandPolicy.isCommandAllowed(cmd))
            val pluginResult = com.example.automation.ShizukuShellPlugin.runCommand(cmd)
            assertTrue("Plugin must report rejection: $cmd", pluginResult.contains("rejected by security policy") || pluginResult.contains("Error"))
        }
    }

    // =========================================================================
    // 8. Typed, permitted commands still work
    // =========================================================================
    @Test
    fun testPermittedCommandsAllowedByPolicy() {
        val safeCommands = listOf(
            "input tap 100 200",
            "input keyevent 66",
            "input swipe 10 20 30 40 300",
            "input text hello%sworld",
            "wm dismiss-keyguard"
        )

        for (cmd in safeCommands) {
            assertTrue("Command should be allowed by policy: $cmd", ShellCommandPolicy.isCommandAllowed(cmd))
        }
    }

    // =========================================================================
    // 9. Command failures and nonzero exit codes are reported truthfully
    // =========================================================================
    @Test
    fun testCommandFailuresReportedTruthfully() = runBlocking {
        // When Shizuku is not running / unbound, UiActionExecutor must return false
        val action = UiAction(type = UiAction.Type.KEY_ENTER, reason = "Press enter")
        val executed = UiActionExecutor.executeAction(context, action)

        // Without active AccessibilityService or Shizuku, enter execution must report false
        assertFalse(executed)
    }

    // =========================================================================
    // 10. Permission denial and unavailable accessibility execution do not produce false success
    // =========================================================================
    @Test
    fun testUnavailableAccessibilityExecution_DoesNotProduceFalseSuccess() = runBlocking {
        val action = UiAction(type = UiAction.Type.TAP, x = 500, y = 500, reason = "Tap button")
        val executed = UiActionExecutor.executeAction(context, action)

        assertFalse(executed)
    }

    // =========================================================================
    // 11. Unrelated text fails input readback verification
    // =========================================================================
    @Test
    fun testInputVerification_UnrelatedTextFails() {
        val isMatch = com.example.automation.verification.InputVerifier.isTextMatch(
            actual = "Random Unrelated Text",
            expectedNormalized = "target text",
            replace = true,
            originalText = ""
        )
        assertFalse(isMatch)
    }

    // =========================================================================
    // 12. Matching substring in wrong field / decoy is rejected
    // =========================================================================
    @Test
    fun testInputVerification_DecoySubstringRejected() {
        // When user types "shoes", a field containing "Search suggestions for shoes" must NOT match
        val isMatch = com.example.automation.verification.InputVerifier.isTextMatch(
            actual = "Search suggestions for shoes",
            expectedNormalized = "shoes",
            replace = true,
            originalText = ""
        )
        assertFalse("Decoy substring match must be rejected in replacement mode", isMatch)
    }

    // =========================================================================
    // 13. Exact matching and legitimate normalization succeed
    // =========================================================================
    @Test
    fun testInputVerification_ExactMatchAndWhitespaceNormalization() {
        val isMatch = com.example.automation.verification.InputVerifier.isTextMatch(
            actual = "  Test   Query  ",
            expectedNormalized = "test query",
            replace = true,
            originalText = ""
        )
        assertTrue("Exact match with whitespace normalization must succeed", isMatch)
    }

    // =========================================================================
    // 14. Sensitive text is masked in logs
    // =========================================================================
    @Test
    fun testInputVerification_SensitiveTextMasked() {
        val shortMasked = com.example.automation.verification.InputVerifier.maskSensitive("1234")
        assertEquals("***", shortMasked)

        val longMasked = com.example.automation.verification.InputVerifier.maskSensitive("password123")
        assertEquals("p***3", longMasked)
        assertFalse("Raw password must never be present in masked output", longMasked.contains("password"))
    }

    // =========================================================================
    // 15. Keyboard transition timeout fails truthfully
    // =========================================================================
    @Test
    fun testInputVerification_KeyboardTimeoutFails() = runBlocking {
        val service = JarvisAccessibilityService()
        // With no screen transition, keyboard action verification must return false on timeout
        val verified = com.example.automation.verification.InputVerifier.verifyKeyboardActionExecuted(
            initialSignature = "sig_screen_A",
            service = service,
            timeoutMs = 150L
        )
        assertFalse("Keyboard action with unchanged signature must report failure on timeout", verified)
    }

    // =========================================================================
    // 16. KeyboardController enter failure reported truthfully
    // =========================================================================
    @Test
    fun testKeyboardController_PressEnterFailureReportedTruthfully() = runBlocking {
        // Without active AccessibilityService or Shizuku, pressEnter must return success = false
        val result = com.example.automation.keyboard.KeyboardController.pressEnter(context, null)
        assertFalse(result.success)
        assertEquals("ENTER_DISPATCH_FAILED", result.errorCode)
    }

    // =========================================================================
    // 17. KeyboardController key failure reported truthfully
    // =========================================================================
    @Test
    fun testKeyboardController_PressKeyFailureReportedTruthfully() = runBlocking {
        val result = com.example.automation.keyboard.KeyboardController.pressKey(context, "ESCAPE")
        assertFalse(result.success)
        assertEquals("KEY_DISPATCH_FAILED", result.errorCode)
    }

    // =========================================================================
    // 18. Inert button already present on screen fails TargetVerified
    // =========================================================================
    @Test
    fun testTargetVerified_InertPreExistingButtonRejected() {
        // Target button was already visible before, and is still visible after
        val before = createObservation("obs1", text = "Settings", pkg = "com.android.settings")
        // Screen text changed elsewhere (e.g. clock/animation) but target was not selected or newly opened
        val after = ScreenObservation(
            observationId = "obs2",
            foregroundPackage = "com.android.settings",
            interactiveElements = listOf(
                InteractiveElement(
                    text = "Settings",
                    bounds = Rect(0, 0, 100, 50),
                    isClickable = true,
                    isSelected = false,
                    isFocused = false
                )
            ),
            visibleTexts = listOf("Settings", "12:01 PM"),
            timestamp = System.currentTimeMillis()
        )

        val expectation = ActionExpectation.TargetVerified("Settings")
        val (verified, reason) = ActionVerifier.verify(
            action = ComputerAction.TapElement("Settings", expectation = expectation),
            executionResult = ActionResult(true, "TAP"),
            before = before,
            after = after
        )

        assertFalse("Inert pre-existing button without selection or destination change must not be verified", verified)
        assertTrue(reason.contains("already present") || reason.contains("no destination transition"))
    }

    // =========================================================================
    // 19. ComputerUseTool propagates cancellation
    // =========================================================================
    @Test
    fun testComputerUseTool_PropagatesCancellation() = runBlocking {
        val tool = com.example.automation.tools.ComputerUseTool()
        val cancellationToken = com.example.automation.engine.CancellationToken()
        cancellationToken.cancel()

        val request = ExecutionRequest(
            action = "COMPUTER_USE",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("goal", "Open settings and toggle Wi-Fi")
            },
            cancellationToken = cancellationToken
        )

        assertFalse("Cancelled request should fail validation", tool.validate(request))

        val result = tool.execute(context, request)
        assertFalse(result.success)
        assertEquals("CANCELLED", result.errorCode)
    }

    // =========================================================================
    // 20. ScrollTool honors cancellation
    // =========================================================================
    @Test
    fun testScrollTool_HonorsCancellation() = runBlocking {
        val tool = com.example.automation.tools.ScrollTool()
        val cancellationToken = com.example.automation.engine.CancellationToken()
        cancellationToken.cancel()

        val request = ExecutionRequest(
            action = "scroll_until",
            arguments = com.google.gson.JsonObject().apply {
                addProperty("direction", "DOWN")
                addProperty("target", "Nonexistent target")
                addProperty("max_steps", 5)
            },
            cancellationToken = cancellationToken
        )

        val result = tool.execute(context, request)
        assertFalse(result.success)
        assertEquals("CANCELLED", result.errorCode)
    }
}
