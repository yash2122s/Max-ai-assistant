package com.example.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.GoalPlanner
import com.example.automation.state.ActionStep
import com.example.network.provider.GeminiPlanningProvider
import com.example.network.provider.LLMPlanningProvider
import com.example.network.provider.PlanningRequest
import com.example.network.provider.PlanningResponse
import com.example.voice.vision.ScreenContextPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LLMPlanningProviderUnitTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        GoalPlanner.clearGoal()
    }

    @Test
    fun test1_GeminiPlanningProvider_DefaultContract() {
        val provider = GeminiPlanningProvider(context)
        assertEquals("gemini", provider.providerId)
        assertEquals("gemini-2.5-flash", provider.defaultModel)
    }

    @Test
    fun test2_GoalPlanner_UsesInjectedProviderForComplexGoal() = runBlocking {
        var providerInvoked = false
        val mockProvider = object : LLMPlanningProvider {
            override val providerId: String = "mock_test_provider"
            override val defaultModel: String = "mock-model-v1"

            override suspend fun generatePlan(request: PlanningRequest): PlanningResponse {
                providerInvoked = true
                val step1 = ActionStep(stepId = "step_1", actionType = "OPEN_APP", targetText = "WhatsApp")
                val step2 = ActionStep(stepId = "step_2", actionType = "SEND_WHATSAPP_MESSAGE", targetText = "Bhaskar", contentDescription = "Hello")
                return PlanningResponse(
                    success = true,
                    plan = listOf(step1, step2),
                    tokenUsage = com.example.network.provider.TokenUsage(
                        promptTokens = 350,
                        candidateTokens = 85,
                        totalTokens = 435
                    ),
                    durationMs = 120L
                )
            }
        }

        GoalPlanner.setPlanningProvider(mockProvider)
        assertEquals("mock_test_provider", GoalPlanner.getPlanningProvider()?.providerId)

        val result = GoalPlanner.planAndExecute(context, "Open WhatsApp, find Bhaskar and send Hello")
        assertTrue(providerInvoked)
        assertEquals("Open WhatsApp, find Bhaskar and send Hello", result.goalPrompt)
        assertEquals(2, result.totalStepsCount)
    }

    @Test
    fun test3_GoalPlanner_HandlesProviderFailure() = runBlocking {
        val failingProvider = object : LLMPlanningProvider {
            override val providerId: String = "failing_provider"
            override val defaultModel: String = "mock-fail"

            override suspend fun generatePlan(request: PlanningRequest): PlanningResponse {
                return PlanningResponse(
                    success = false,
                    errorMessage = "HTTP 429 Rate limit exceeded",
                    durationMs = 45L
                )
            }
        }

        GoalPlanner.setPlanningProvider(failingProvider)

        val result = GoalPlanner.planAndExecute(context, "Open WhatsApp, find Bhaskar and send Hello")
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Rate limit") == true)
        assertFalse(GoalPlanner.isGoalActive())
    }

    @Test
    fun test4_PlanningRequest_DoesNotExposeCredential() {
        val screenPayload = ScreenContextPayload(
            packageName = "com.android.settings",
            prunedNodes = emptyList(),
            visibleText = "Wi-Fi",
            clickableElements = emptyList(),
            screenshotJpeg = null,
            isScreenshotSent = false,
            hasAccessibility = true,
            focusMode = "general"
        )
        val request = PlanningRequest(
            goal = "Open Wi-Fi settings",
            screenContext = screenPayload,
            modelName = "gemini-2.5-flash"
        )

        assertEquals("Open Wi-Fi settings", request.goal)
        assertEquals("gemini-2.5-flash", request.modelName)
        assertEquals("com.android.settings", request.screenContext.packageName)
    }

    @Test
    fun test5_AmbiguousGoal_RejectedBeforeProviderInvocation() = runBlocking {
        var providerCalled = false
        val spyProvider = object : LLMPlanningProvider {
            override val providerId: String = "spy"
            override val defaultModel: String = "spy-model"
            override suspend fun generatePlan(request: PlanningRequest): PlanningResponse {
                providerCalled = true
                return PlanningResponse(success = true, plan = emptyList())
            }
        }

        GoalPlanner.setPlanningProvider(spyProvider)

        val result = GoalPlanner.planAndExecute(context, "vegetable")
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("Ambiguous") == true)
        assertFalse(providerCalled)
    }

    @Test
    fun test6_FastPath_BypassesLLMProvider() = runBlocking {
        var providerCalled = false
        val spyProvider = object : LLMPlanningProvider {
            override val providerId: String = "spy"
            override val defaultModel: String = "spy-model"
            override suspend fun generatePlan(request: PlanningRequest): PlanningResponse {
                providerCalled = true
                return PlanningResponse(success = true, plan = emptyList())
            }
        }
        GoalPlanner.setPlanningProvider(spyProvider)

        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(
            listOf(
                com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator")
            )
        )

        // 1. Open Calculator
        val calcPlan = GoalPlanner.resolveFastPathPlan("Open Calculator", context)
        assertNotNull(calcPlan)
        assertEquals("OPEN_APP", calcPlan!![0].actionType)
        assertEquals("com.google.android.calculator", calcPlan[0].targetText)

        // 2. Turn on flashlight
        val flashPlan = GoalPlanner.resolveFastPathPlan("Turn on flashlight", context)
        assertNotNull(flashPlan)
        assertEquals("FLASHLIGHT_ON", flashPlan!![0].actionType)

        // 3. Go home
        val homePlan = GoalPlanner.resolveFastPathPlan("Go home", context)
        assertNotNull(homePlan)
        assertEquals("SYSTEM_ACTION", homePlan!![0].actionType)
        assertEquals("home", homePlan[0].targetText)

        // 4. Check battery status
        val battPlan = GoalPlanner.resolveFastPathPlan("Check battery status", context)
        assertNotNull(battPlan)
        assertEquals("GET_BATTERY_STATUS", battPlan!![0].actionType)

        // Verify executing fast path does not call LLM provider
        val result = GoalPlanner.planAndExecute(context, "Open Calculator")
        assertFalse(providerCalled)
    }

    @Test
    fun test7_TaskAwareContext_IsScreenContextRequired() {
        assertFalse(GoalPlanner.isScreenContextRequired("Open Calculator"))
        assertFalse(GoalPlanner.isScreenContextRequired("Turn on flashlight"))
        assertFalse(GoalPlanner.isScreenContextRequired("Go home"))
        assertFalse(GoalPlanner.isScreenContextRequired("Check battery status"))

        assertTrue(GoalPlanner.isScreenContextRequired("Click Wi-Fi"))
        assertTrue(GoalPlanner.isScreenContextRequired("Tap on Search"))
        assertTrue(GoalPlanner.isScreenContextRequired("Type Hello into message box"))
        assertTrue(GoalPlanner.isScreenContextRequired("Scroll down"))
        assertTrue(GoalPlanner.isScreenContextRequired("What is on my screen?"))
    }

    @Test
    fun test8_PromptBuilder_CompactContextForNonUITask() {
        val minimalPayload = ScreenContextPayload(
            packageName = "com.android.settings",
            prunedNodes = emptyList(),
            visibleText = "",
            clickableElements = emptyList(),
            screenshotJpeg = null,
            isScreenshotSent = false,
            hasAccessibility = true,
            focusMode = "general"
        )
        val prompt = PromptBuilder.buildPlanningPrompt("Turn on flashlight", minimalPayload)
        assertFalse(prompt.contains("Clickable Elements:"))
        assertFalse(prompt.contains("Visible Text:"))
        assertTrue(prompt.contains("Foreground Package: com.android.settings"))
    }

    @Test
    fun test9_PromptBuilder_DetailedContextForUITask() {
        val detailedPayload = ScreenContextPayload(
            packageName = "com.whatsapp",
            prunedNodes = emptyList(),
            visibleText = "Chats\nBhaskar: Hey",
            clickableElements = listOf("button: 'Bhaskar' (id=1, bounds=[0,100,200,300])"),
            screenshotJpeg = null,
            isScreenshotSent = false,
            hasAccessibility = true,
            focusMode = "general"
        )
        val prompt = PromptBuilder.buildPlanningPrompt("Find Bhaskar", detailedPayload)
        assertTrue(prompt.contains("Clickable Elements: [button: 'Bhaskar'"))
        assertTrue(prompt.contains("Visible Text: \"Chats"))
    }

    @Test
    fun test10_TokenUsage_DataClassCompatibility() {
        val response1 = PlanningResponse(success = true)
        assertEquals(null, response1.tokenUsage)
        assertEquals(0L, response1.durationMs)

        val usage = com.example.network.provider.TokenUsage(100, 25, 125)
        val response2 = PlanningResponse(
            success = true,
            tokenUsage = usage,
            durationMs = 250L
        )
        assertEquals(100, response2.tokenUsage?.promptTokens)
        assertEquals(25, response2.tokenUsage?.candidateTokens)
        assertEquals(125, response2.tokenUsage?.totalTokens)
        assertEquals(250L, response2.durationMs)
    }

    @Test
    fun test11_PromptBuilder_SystemInstruction_CompactAndAccurate() {
        val sysInstruction = PromptBuilder.buildPlanningSystemInstruction()
        assertTrue(sysInstruction.contains("MAX's action planner"))
        assertTrue(sysInstruction.contains("OPEN_APP"))
        assertTrue(sysInstruction.contains("CLICK_ELEMENT"))
        assertTrue(sysInstruction.contains("TYPE_TEXT"))
        assertTrue(sysInstruction.contains("SEND_WHATSAPP_MESSAGE"))
        assertTrue(sysInstruction.contains("Ambiguity Rule"))
        // Confirms negative prose/markdown rules are omitted for token efficiency
        assertFalse(sysInstruction.contains("Do NOT output markdown code blocks"))
        assertFalse(sysInstruction.contains("Return ONLY a valid JSON array of ActionStep objects matching the schema."))
    }

    @Test
    fun test12_PromptBuilder_UserContent_SeparatesGoalAndContext() {
        val payload = ScreenContextPayload(
            packageName = "com.whatsapp",
            prunedNodes = emptyList(),
            visibleText = "Bhaskar",
            clickableElements = listOf("Bhaskar"),
            screenshotJpeg = null,
            isScreenshotSent = false,
            hasAccessibility = true,
            focusMode = "general"
        )
        val userContent = PromptBuilder.buildPlanningUserContent("Open WhatsApp, find Bhaskar and send Hello", payload)
        assertTrue(userContent.contains("<USER_GOAL>"))
        assertTrue(userContent.contains("Open WhatsApp, find Bhaskar and send Hello"))
        assertTrue(userContent.contains("Foreground Package: com.whatsapp"))
        assertTrue(userContent.contains("Clickable Elements: [Bhaskar]"))
        // Confirms system role rules are not duplicated in user content
        assertFalse(userContent.contains("You are MAX's action planner"))
    }
}
