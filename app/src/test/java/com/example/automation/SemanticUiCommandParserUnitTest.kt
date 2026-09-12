package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SemanticUiCommandParserUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testApps = listOf(
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

    // ==========================================
    // 1. ENGLISH COMPOUND UI COMMANDS
    // ==========================================

    @Test
    fun testParseOpenYouTubeAndTapSearch() {
        val parsed = SemanticUiCommandParser.parse("Open YouTube and tap Search")
        assertNotNull(parsed)
        assertEquals("UI_AUTOMATION", parsed?.intent)
        assertEquals("YouTube", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Search", parsed?.target)

        val json = parsed?.toJson()
        assertNotNull(json)
        assertEquals("UI_AUTOMATION", json?.getString("intent"))
        assertEquals("YouTube", json?.getString("app"))
        assertEquals("CLICK", json?.getString("action"))
        assertEquals("Search", json?.getString("target"))
    }

    @Test
    fun testParseOpenSettingsAndFindWiFi() {
        val parsed = SemanticUiCommandParser.parse("Open Settings and find WiFi")
        assertNotNull(parsed)
        assertEquals("UI_AUTOMATION", parsed?.intent)
        assertEquals("Settings", parsed?.app)
        assertEquals("FIND", parsed?.action)
        assertEquals("WiFi", parsed?.target)
    }

    @Test
    fun testParseOpenCalculatorAndTap5() {
        val parsed = SemanticUiCommandParser.parse("Open Calculator and tap 5")
        assertNotNull(parsed)
        assertEquals("UI_AUTOMATION", parsed?.intent)
        assertEquals("Calculator", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("5", parsed?.target)
    }

    @Test
    fun testParseOpenSettingsAndTapDisplay() {
        val parsed = SemanticUiCommandParser.parse("Open Settings and tap Display")
        assertNotNull(parsed)
        assertEquals("UI_AUTOMATION", parsed?.intent)
        assertEquals("Settings", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Display", parsed?.target)
    }

    @Test
    fun testParseLaunchSettingsThenClickBattery() {
        val parsed = SemanticUiCommandParser.parse("Launch Settings then click Battery")
        assertNotNull(parsed)
        assertEquals("UI_AUTOMATION", parsed?.intent)
        assertEquals("Settings", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Battery", parsed?.target)
    }

    @Test
    fun testParseCommaCompound() {
        val parsed = SemanticUiCommandParser.parse("Open YouTube, tap Search")
        assertNotNull(parsed)
        assertEquals("YouTube", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Search", parsed?.target)
    }

    @Test
    fun testParseInAppTargeting() {
        val parsed = SemanticUiCommandParser.parse("In Settings tap Display")
        assertNotNull(parsed)
        assertEquals("Settings", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Display", parsed?.target)
    }

    // ==========================================
    // 2. TELUGU & TANGLISH COMPOUND COMMANDS
    // ==========================================

    @Test
    fun testParseTanglishYouTubeSearch() {
        val parsed = SemanticUiCommandParser.parse("YouTube open chesi Search meeda tap chey")
        assertNotNull(parsed)
        assertEquals("YouTube", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Search", parsed?.target)
    }

    @Test
    fun testParseTanglishSettingsWiFi() {
        val parsed = SemanticUiCommandParser.parse("Settings open chesi WiFi వెతుకు")
        assertNotNull(parsed)
        assertEquals("Settings", parsed?.app)
        assertEquals("FIND", parsed?.action)
        assertEquals("WiFi", parsed?.target)
    }

    @Test
    fun testParseTanglishCalculator5() {
        val parsed = SemanticUiCommandParser.parse("Calculator open chesi 5 నొక్కు")
        assertNotNull(parsed)
        assertEquals("Calculator", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("5", parsed?.target)
    }

    @Test
    fun testParseTeluguSettingsDisplay() {
        val parsed = SemanticUiCommandParser.parse("Settings ఓపెన్ చేసి Display ట్యాప్ చేయి")
        assertNotNull(parsed)
        assertEquals("Settings", parsed?.app)
        assertEquals("CLICK", parsed?.action)
        assertEquals("Display", parsed?.target)
    }

    // ==========================================
    // 3. NATURAL COMMAND NORMALIZER INTEGRATION
    // ==========================================

    @Test
    fun testNormalizerRecognizesUiAutomation() {
        val normalized = NaturalCommandNormalizer.normalize("Open YouTube and tap Search")
        assertEquals(FastPathIntent.UI_AUTOMATION, normalized.intent)
        assertEquals("YouTube", normalized.target)
        assertEquals("CLICK", normalized.secondaryAction)
        assertEquals("Search", normalized.secondaryTarget)
        assertTrue(normalized.isCompound)
    }

    // ==========================================
    // 4. GOAL PLANNER FAST-PATH & DAG GENERATION
    // ==========================================

    @Test
    fun testGoalPlannerPlanGenerationForYouTubeSearch() {
        val goal = "Open YouTube and tap Search"
        val steps = GoalPlanner.resolveFastPathPlan(goal, context)
        assertNotNull(steps)
        assertEquals(2, steps?.size)

        val step1 = steps!![0]
        assertEquals("step_1", step1.stepId)
        assertEquals("OPEN_APP", step1.actionType)
        assertTrue(step1.targetText?.contains("youtube", ignoreCase = true) == true)

        val step2 = steps[1]
        assertEquals("step_2", step2.stepId)
        assertEquals("CLICK_TEXT", step2.actionType)
        assertEquals("Search", step2.targetText)
        assertEquals(listOf("step_1"), step2.dependsOn)

        val validationError = GoalPlanner.validatePlan(steps, goal)
        assertNull(validationError)
    }

    @Test
    fun testGoalPlannerPlanGenerationForSettingsWiFi() {
        val goal = "Open Settings and find WiFi"
        val steps = GoalPlanner.resolveFastPathPlan(goal, context)
        assertNotNull(steps)
        assertEquals(2, steps?.size)

        val step1 = steps!![0]
        assertEquals("step_1", step1.stepId)
        assertEquals("OPEN_APP", step1.actionType)
        assertTrue(step1.targetText?.contains("settings", ignoreCase = true) == true)

        val step2 = steps[1]
        assertEquals("step_2", step2.stepId)
        assertEquals("CLICK_TEXT", step2.actionType)
        assertEquals("WiFi", step2.targetText)
        assertEquals("find", step2.params["mode"])
        assertEquals(listOf("step_1"), step2.dependsOn)

        val validationError = GoalPlanner.validatePlan(steps, goal)
        assertNull(validationError)
    }

    @Test
    fun testGoalPlannerPlanGenerationForCalculator5() {
        val goal = "Open Calculator and tap 5"
        val steps = GoalPlanner.resolveFastPathPlan(goal, context)
        assertNotNull(steps)
        assertEquals(2, steps?.size)

        val step1 = steps!![0]
        assertEquals("step_1", step1.stepId)
        assertEquals("OPEN_APP", step1.actionType)
        assertTrue(step1.targetText?.contains("calculator", ignoreCase = true) == true)

        val step2 = steps[1]
        assertEquals("step_2", step2.stepId)
        assertEquals("CLICK_TEXT", step2.actionType)
        assertEquals("5", step2.targetText)
        assertEquals(listOf("step_1"), step2.dependsOn)

        val validationError = GoalPlanner.validatePlan(steps, goal)
        assertNull(validationError)
    }

    @Test
    fun testGoalPlannerPlanGenerationForSettingsDisplay() {
        val goal = "Open Settings and tap Display"
        val steps = GoalPlanner.resolveFastPathPlan(goal, context)
        assertNotNull(steps)
        assertEquals(2, steps?.size)

        val step1 = steps!![0]
        assertEquals("step_1", step1.stepId)
        assertEquals("OPEN_APP", step1.actionType)
        assertTrue(step1.targetText?.contains("settings", ignoreCase = true) == true)

        val step2 = steps[1]
        assertEquals("step_2", step2.stepId)
        assertEquals("CLICK_TEXT", step2.actionType)
        assertEquals("Display", step2.targetText)
        assertEquals(listOf("step_1"), step2.dependsOn)

        val validationError = GoalPlanner.validatePlan(steps, goal)
        assertNull(validationError)
    }

    // ==========================================
    // 5. MULTI-ACTION & REGRESSION PRESERVATION
    // ==========================================

    @Test
    fun testParseMultiActionCalculatorSequence() {
        val goal = "Open Calculator, enter 25, press +, enter 10"
        val parsed = SemanticUiCommandParser.parse(goal)
        assertNotNull(parsed)
        assertEquals("Calculator", parsed?.app)
        assertEquals(4, parsed?.actions?.size)

        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("Calculator", parsed?.actions?.get(0)?.target)

        assertEquals("TYPE", parsed?.actions?.get(1)?.action)
        assertEquals("25", parsed?.actions?.get(1)?.target)
        assertEquals("25", parsed?.actions?.get(1)?.text)

        assertEquals("CLICK", parsed?.actions?.get(2)?.action)
        assertEquals("+", parsed?.actions?.get(2)?.target)

        assertEquals("TYPE", parsed?.actions?.get(3)?.action)
        assertEquals("10", parsed?.actions?.get(3)?.target)
        assertEquals("10", parsed?.actions?.get(3)?.text)

        val plan = GoalPlanner.resolveFastPathPlan(goal, context)
        assertNotNull(plan)
        assertEquals(4, plan?.size)
        assertEquals("OPEN_APP", plan?.get(0)?.actionType)
        assertEquals("TYPE_TEXT", plan?.get(1)?.actionType)
        assertEquals("25", plan?.get(1)?.targetText)
        assertEquals("CLICK_TEXT", plan?.get(2)?.actionType)
        assertEquals("+", plan?.get(2)?.targetText)
        assertEquals("TYPE_TEXT", plan?.get(3)?.actionType)
        assertEquals("10", plan?.get(3)?.targetText)
    }

    @Test
    fun testParseMultiActionCalculatorChainedTaps() {
        val goal = "Open Calculator and tap 5 then tap + then tap 7"
        val parsed = SemanticUiCommandParser.parse(goal)
        assertNotNull(parsed)
        assertEquals("Calculator", parsed?.app)
        assertEquals(4, parsed?.actions?.size)

        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("Calculator", parsed?.actions?.get(0)?.target)

        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("5", parsed?.actions?.get(1)?.target)

        assertEquals("CLICK", parsed?.actions?.get(2)?.action)
        assertEquals("+", parsed?.actions?.get(2)?.target)

        assertEquals("CLICK", parsed?.actions?.get(3)?.action)
        assertEquals("7", parsed?.actions?.get(3)?.target)
    }

    @Test
    fun testParseMultiActionYouTubeSearchType() {
        val goal = "Open YouTube, tap Search, type music"
        val parsed = SemanticUiCommandParser.parse(goal)
        assertNotNull(parsed)
        assertEquals("YouTube", parsed?.app)
        assertEquals(3, parsed?.actions?.size)

        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("YouTube", parsed?.actions?.get(0)?.target)

        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("Search", parsed?.actions?.get(1)?.target)

        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("music", parsed?.actions?.get(2)?.target)
        assertEquals("music", parsed?.actions?.get(2)?.text)
    }

    @Test
    fun testParseIdenticalConsecutiveActionsNotDeduplicated() {
        val goal = "tap 5 then tap 5"
        val parsed = SemanticUiCommandParser.parse(goal)
        assertNotNull(parsed)
        assertEquals(2, parsed?.actions?.size)

        assertEquals("CLICK", parsed?.actions?.get(0)?.action)
        assertEquals("5", parsed?.actions?.get(0)?.target)

        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("5", parsed?.actions?.get(1)?.target)
    }

    // ==========================================
    // 6. INVARIANTS: SINGLE-STEP COMMANDS UNCHANGED
    // ==========================================

    @Test
    fun testSingleStepCommandsUnchanged() {
        val openApp = GoalPlanner.resolveFastPathPlan("Open YouTube", context)
        assertNotNull(openApp)
        assertEquals(1, openApp?.size)
        assertEquals("OPEN_APP", openApp?.get(0)?.actionType)

        val flashlight = GoalPlanner.resolveFastPathPlan("Turn on flashlight", context)
        assertNotNull(flashlight)
        assertEquals(1, flashlight?.size)
        assertEquals("FLASHLIGHT_ON", flashlight?.get(0)?.actionType)

        val battery = GoalPlanner.resolveFastPathPlan("battery status", context)
        assertNotNull(battery)
        assertEquals(1, battery?.size)
        assertEquals("GET_BATTERY_STATUS", battery?.get(0)?.actionType)
    }
}
