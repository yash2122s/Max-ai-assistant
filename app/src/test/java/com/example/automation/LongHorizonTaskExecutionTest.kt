package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.ActionAuthorizer
import com.example.automation.engine.CancellationToken
import com.example.automation.engine.ExecutionRequest
import com.example.automation.engine.execution.CapabilityRouter
import com.example.automation.engine.execution.LongTaskOrchestrator
import com.example.automation.engine.execution.TaskRecoveryEngine
import com.example.automation.engine.execution.VerificationEngine
import com.example.automation.engine.planning.IntentDeepLinkRouter
import com.example.automation.engine.planning.TaskPlanner
import com.example.automation.engine.state.AgentTask
import com.example.automation.engine.state.ExpectedOutcome
import com.example.automation.engine.state.TaskStatus
import com.example.automation.engine.state.TaskStep
import com.example.automation.keyboard.KeyboardController
import com.example.automation.models.UiStep
import com.example.automation.state.ActionStep
import com.example.automation.state.WorldStateManager
import com.example.automation.tools.ScreenContextTool
import com.example.automation.tools.ToolResult
import com.example.automation.verification.DeviceContext
import com.example.automation.verification.KeyboardActionVerifier
import com.example.core.lifecycle.MaxRuntimeManager
import com.example.service.JarvisAccessibilityService
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LongHorizonTaskExecutionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorldStateManager.reset()
        JarvisAccessibilityService.testRootProvider = null
        JarvisAccessibilityService.testShellExecutor = { /* test mock shell executor */ }
        MaxRuntimeManager.setGlobalEnabled(context, true)
    }

    @After fun clearLiveRoot() {
        JarvisAccessibilityService.testRootProvider = null
        JarvisAccessibilityService.testShellExecutor = null
    }

    // =========================================================================
    // 1. ACTION CONTRACTS & KEYBOARD SAFETY
    // =========================================================================

    @Test
    fun testUnknownKeyRejection() = runBlocking {
        // Unknown key must NEVER silently become ENTER; must return structured UNSUPPORTED_KEY error
        val result = KeyboardController.pressKey(context, "UNKNOWN_NONEXISTENT_KEY")
        assertFalse(result.success)
        assertEquals("UNSUPPORTED_KEY", result.errorCode)
        assertTrue(result.message?.contains("Unsupported key") == true)
    }

    @Test
    fun testKeyboardActionVerifierRegistered() {
        val verifier = KeyboardActionVerifier()
        assertTrue(verifier.supportedTools.contains("press_key"))
        assertTrue(verifier.supportedTools.contains("KEYBOARD_ACTION"))

        val request = ExecutionRequest(action = "PRESS_KEY", arguments = JsonObject())
        val toolResult = ToolResult.success("press_key")
        val deviceContext = DeviceContext.capture(context)
        val verification = verifier.verify(context, request, toolResult, deviceContext)
        assertTrue(verification.success)
    }

    // =========================================================================
    // 2. LONG TASK ORCHESTRATOR & SINGLE OWNER PROGRESSION
    // =========================================================================

    @Test
    fun testOpenAppContinuesToNextStep() = runBlocking {
        val chrome = android.content.ComponentName("com.android.chrome", "com.android.chrome.Main")
        org.robolectric.Shadows.shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(chrome)
            addIntentFilterForActivity(chrome, android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            })
        }
        WorldStateManager.setForegroundApp("com.android.chrome")

        val task = AgentTask(
            originalIntent = "Open Chrome and search",
            goal = "open_app",
            steps = listOf(
                TaskStep(
                    id = "step_1_launch",
                    action = UiStep.OpenApp(appName = "Chrome", packageName = "com.android.chrome"),
                    expectedOutcome = ExpectedOutcome.PackageForeground("com.android.chrome")
                ),
                TaskStep(
                    id = "step_2_wait",
                    action = UiStep.WaitForUi(packageName = "com.android.chrome", timeoutMs = 200L),
                    expectedOutcome = null
                )
            )
        )

        val orchestrator = LongTaskOrchestrator(
            context = context,
            capabilityRouter = CapabilityRouter(context),
            verificationEngine = VerificationEngine(context),
            recoveryEngine = TaskRecoveryEngine(),
            taskPlanner = TaskPlanner()
        )

        val result = orchestrator.executeTask(task)
        assertTrue("Task failed: ${result.message}; steps=${task.steps.map { it.id to it.failureReason }}", result.success)
        assertEquals(2, result.executedSteps)
        assertEquals(TaskStatus.COMPLETED, task.status)
        assertEquals("com.android.chrome", task.context.currentApp)
    }

    @Test
    fun testFailedDispatchCannotAdvanceFromCachedForeground() = runBlocking {
        WorldStateManager.setForegroundApp("example.missing")
        val task = AgentTask(originalIntent = "Open missing app", goal = "open_app", steps = listOf(
            TaskStep("missing", UiStep.OpenApp("Missing", "example.missing"), ExpectedOutcome.PackageForeground("example.missing"))
        ))
        val result = LongTaskOrchestrator(context = context).executeTask(task)
        assertFalse(result.success)
        assertEquals(0, result.executedSteps)
    }

    @Test
    fun testTaskCancellationStopsExecution() = runBlocking {
        val token = CancellationToken().apply { cancel() }

        val task = AgentTask(
            originalIntent = "Cancelled task test",
            goal = "test_goal",
            steps = listOf(
                TaskStep("s1", UiStep.WaitForUi(timeoutMs = 100L), null),
                TaskStep("s2", UiStep.WaitForUi(timeoutMs = 100L), null)
            )
        )

        val orchestrator = LongTaskOrchestrator(
            context = context,
            capabilityRouter = CapabilityRouter(context),
            verificationEngine = VerificationEngine(context),
            recoveryEngine = TaskRecoveryEngine(),
            taskPlanner = TaskPlanner()
        )

        val result = orchestrator.executeTask(task, cancellationToken = token)
        assertFalse(result.success)
        assertTrue(result.isCancelled)
        assertEquals(0, result.executedSteps)
    }

    @Test
    fun testPlanExecutionThroughOrchestrator() = runBlocking {
        WorldStateManager.setForegroundApp("com.android.chrome")
        val root = android.view.accessibility.AccessibilityNodeInfo.obtain().apply { packageName = "com.android.chrome" }
        JarvisAccessibilityService.testRootProvider = { root }

        val steps = listOf(
            ActionStep(stepId = "s1", actionType = "OPEN_APP", targetText = "Chrome"),
            ActionStep(stepId = "s2", actionType = "PRESS_KEY", targetText = "ENTER")
        )

        val orchestrator = LongTaskOrchestrator(
            context = context,
            capabilityRouter = CapabilityRouter(context),
            verificationEngine = VerificationEngine(context),
            recoveryEngine = TaskRecoveryEngine(),
            taskPlanner = TaskPlanner()
        )

        val result = orchestrator.executePlan(
            context = context,
            goalPrompt = "Open Chrome and enter",
            steps = steps
        )

        assertTrue(
            "Plan failed: error=${result.errorMessage}, steps=${result.executedStepsCount}, results=${result.stepResults.map { "${it.toolName}:${it.success}:${it.errorCode}:${it.message}" }}",
            result.success
        )
        assertEquals(2, result.executedStepsCount)
    }

    // =========================================================================
    // 3. CROSS-APP CONTEXT & OUTPUT_DATA
    // =========================================================================

    @Test
    fun testScreenContextToolPopulatesOutputData() = runBlocking {
        val tool = ScreenContextTool()
        val request = ExecutionRequest(action = "GET_SCREEN_CONTEXT", arguments = JsonObject())
        val result = tool.execute(context, request)

        // ScreenContextTool outputData must contain primary text
        assertNotNull(result.outputData)
        assertTrue(result.outputData.containsKey("text"))
        assertTrue(result.outputData.containsKey("packageName"))
        assertTrue(result.outputData.containsKey("elements"))
    }

    // =========================================================================
    // 4. INTENT ACCELERATION & CAPABILITY ROUTING (Tier 2 Optimization)
    // =========================================================================

    @Test
    fun testIntentDeepLinkRouterDetection() {
        assertTrue(IntentDeepLinkRouter.canAccelerate("Open https://openai.com"))
        assertTrue(IntentDeepLinkRouter.canAccelerate("Navigate to Bhimavaram"))
        assertTrue(IntentDeepLinkRouter.canAccelerate("Directions to Palakollu"))
        assertTrue(IntentDeepLinkRouter.canAccelerate("Search YouTube for tech reviews"))

        // Generic in-app click must NOT be replaced by deep link
        assertFalse(IntentDeepLinkRouter.canAccelerate("Click the first result"))
        assertFalse(IntentDeepLinkRouter.canAccelerate("Tap on subscribe"))
    }

    // =========================================================================
    // 5. SAFETY & WHATSAPP COLLISION INVARIANT
    // =========================================================================

    @Test
    fun testWhatsAppMessageContainingCallNeverBecomesPhoneCall() {
        val utterance = "Send WhatsApp message to Yash that I am in a meeting and will call later"

        // ActionAuthorizer must reject CALL_PHONE
        val authCall = ActionAuthorizer.authorizeAction(utterance, "CALL_PHONE", "Yash")
        assertFalse("CALL_PHONE must be strictly rejected on messaging utterance", authCall.authorized)

        // SEND_WHATSAPP must be authorized
        val authWa = ActionAuthorizer.authorizeAction(utterance, "SEND_WHATSAPP", "Yash")
        assertTrue("SEND_WHATSAPP must be authorized", authWa.authorized)

        // SEND_WHATSAPP_MESSAGE must be authorized
        val authWaMsg = ActionAuthorizer.authorizeAction(utterance, "SEND_WHATSAPP_MESSAGE", "Yash")
        assertTrue("SEND_WHATSAPP_MESSAGE must be authorized", authWaMsg.authorized)
    }

    @Test
    fun testExplicitCallPhoneAuthorized() {
        val genuineCall = "Call Yash"
        val auth = ActionAuthorizer.authorizeAction(genuineCall, "CALL_PHONE", "Yash")
        assertTrue("Genuine Call Yash must be authorized", auth.authorized)
    }
}
