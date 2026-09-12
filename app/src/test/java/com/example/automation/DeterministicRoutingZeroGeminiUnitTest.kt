package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.*
import com.example.network.provider.LLMPlanningProvider
import com.example.network.provider.PlanningRequest
import com.example.network.provider.PlanningResponse
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeterministicRoutingZeroGeminiUnitTest {

    private lateinit var context: Context
    private val llmCallCount = AtomicInteger(0)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        llmCallCount.set(0)

        // Inject tracking mock LLM provider into GoalPlanner
        val mockProvider = object : LLMPlanningProvider {
            override val providerId: String = "MockProvider"
            override val defaultModel: String = "MockModel"

            override suspend fun generatePlan(request: PlanningRequest): PlanningResponse {
                llmCallCount.incrementAndGet()
                throw AssertionError("Gemini LLM planning provider was invoked unexpectedly for deterministic command: ${request.goal}")
            }
        }
        GoalPlanner.setPlanningProvider(mockProvider)

        val testApps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.apps.maps", appName = "Google Maps"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator"),
            com.example.data.local.InstalledApp(packageName = "com.android.chrome", appName = "Google Chrome"),
            com.example.data.local.InstalledApp(packageName = "com.whatsapp", appName = "WhatsApp"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube"),
            com.example.data.local.InstalledApp(packageName = "com.android.settings", appName = "Settings")
        )
        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(testApps)
        kotlinx.coroutines.runBlocking {
            val db = com.example.data.local.AppDatabase.getDatabase(context)
            db.installedAppDao().insertApps(testApps)
        }
    }

    // =================================================================
    // 1. TYPE TEXT ROUTING (English, Tanglish, Telugu) -> ZERO Gemini
    // =================================================================

    @Test
    fun testTypeBhimavaram_DeterministicZeroGemini() {
        val command = "Type Bhimavaram"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("TYPE", parsed?.action)
        assertEquals("Bhimavaram", parsed?.target)
        assertEquals("Bhimavaram", parsed?.textToType)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Bhimavaram", plan?.get(0)?.targetText)
        assertEquals("Bhimavaram", plan?.get(0)?.params?.get("text"))

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testBhimavaramTypeChey_DeterministicZeroGemini() {
        val command = "Bhimavaram type chey"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("TYPE", parsed?.action)
        assertEquals("Bhimavaram", parsed?.target)
        assertEquals("Bhimavaram", parsed?.textToType)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Bhimavaram", plan?.get(0)?.targetText)
        assertEquals("Bhimavaram", plan?.get(0)?.params?.get("text"))

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testBhimavaramAniTypeChey_DeterministicZeroGemini() {
        val command = "Bhimavaram ani type chey"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("TYPE", parsed?.action)
        assertEquals("Bhimavaram", parsed?.target)
        assertEquals("Bhimavaram", parsed?.textToType)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Bhimavaram", plan?.get(0)?.targetText)
        assertEquals("Bhimavaram", plan?.get(0)?.params?.get("text"))

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testTeluguTypeBhimavaram_DeterministicZeroGemini() {
        val command = "భీమవరం టైప్ చేయి"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("TYPE", parsed?.action)
        assertEquals("భీమవరం", parsed?.target)
        assertEquals("భీమవరం", parsed?.textToType)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("భీమవరం", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    // =================================================================
    // 2. CLICK / TAP ROUTING -> ZERO Gemini
    // =================================================================

    @Test
    fun testClickSearch_DeterministicZeroGemini() {
        val command = "Click Search"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Search", parsed?.target)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("CLICK_TEXT", plan?.get(0)?.actionType)
        assertEquals("Search", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testSearchMeedaTapChey_DeterministicZeroGemini() {
        val command = "Search meeda tap chey"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Search", parsed?.target)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("CLICK_TEXT", plan?.get(0)?.actionType)
        assertEquals("Search", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    // =================================================================
    // 3. PRESS ENTER ROUTING -> ZERO Gemini
    // =================================================================

    @Test
    fun testPressEnter_DeterministicZeroGemini() {
        val command = "Press Enter"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("PRESS_ENTER", parsed?.action)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("PRESS_ENTER", plan?.get(0)?.actionType)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testEnterChey_DeterministicZeroGemini() {
        val command = "Enter chey"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("PRESS_ENTER", parsed?.action)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("PRESS_ENTER", plan?.get(0)?.actionType)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testEnterKottu_DeterministicZeroGemini() {
        val command = "Enter kottu"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("PRESS_ENTER", parsed?.action)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("PRESS_ENTER", plan?.get(0)?.actionType)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    // =================================================================
    // 4. SYSTEM BACK NAVIGATION -> ZERO Gemini
    // =================================================================

    @Test
    fun testGoBack_DeterministicZeroGemini() {
        val command = "Go back"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("SYSTEM", parsed?.action)
        assertEquals("back", parsed?.target)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("SYSTEM_ACTION", plan?.get(0)?.actionType)
        assertEquals("back", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testVenakkiVellu_DeterministicZeroGemini() {
        val command = "Venakki vellu"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("SYSTEM", parsed?.action)
        assertEquals("back", parsed?.target)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("SYSTEM_ACTION", plan?.get(0)?.actionType)
        assertEquals("back", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    // =================================================================
    // 5. OPEN APP -> ZERO Gemini
    // =================================================================

    @Test
    fun testOpenGoogleMaps_DeterministicZeroGemini() {
        val command = "Open Google Maps"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("OPEN_APP", parsed?.action)
        assertEquals("Google Maps", parsed?.target)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("OPEN_APP", plan?.get(0)?.actionType)
        assertEquals("com.google.android.apps.maps", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    @Test
    fun testGoogleMapsOpenChey_DeterministicZeroGemini() {
        val command = "Google Maps open chey"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val parsed = SemanticUiCommandParser.parse(command)
        assertNotNull(parsed)
        assertEquals("OPEN_APP", parsed?.action)
        assertEquals("Google Maps", parsed?.target)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("OPEN_APP", plan?.get(0)?.actionType)
        assertEquals("com.google.android.apps.maps", plan?.get(0)?.targetText)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }

    // =================================================================
    // 6. COMPOUND WORKFLOW: Open -> Search -> Type -> Enter
    // =================================================================

    @Test
    fun testCompoundMapsSearchFlow_DeterministicZeroGemini() {
        val command = "Open Google Maps, click Search, type Bhimavaram, press Enter"
        assertTrue(SemanticUiCommandParser.isDeterministicPrimitive(command))

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull(plan)
        assertEquals(4, plan?.size)

        assertEquals("OPEN_APP", plan?.get(0)?.actionType)
        assertEquals("com.google.android.apps.maps", plan?.get(0)?.targetText)

        assertEquals("CLICK_TEXT", plan?.get(1)?.actionType)
        assertEquals("Search", plan?.get(1)?.targetText)

        assertEquals("TYPE_TEXT", plan?.get(2)?.actionType)
        assertEquals("Bhimavaram", plan?.get(2)?.targetText)

        assertEquals("PRESS_ENTER", plan?.get(3)?.actionType)

        val validationError = GoalPlanner.validatePlan(plan!!, command)
        assertNull(validationError)
        assertEquals(0, llmCallCount.get())
    }
}
