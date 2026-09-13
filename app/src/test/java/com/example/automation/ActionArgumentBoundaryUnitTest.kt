package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.engine.GoalPlanner
import com.example.automation.engine.NaturalCommandNormalizer
import com.example.automation.engine.SemanticUiCommandParser
import com.example.automation.engine.planning.ActionArgumentBoundaryNormalizer
import com.example.automation.state.ActionStep
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActionArgumentBoundaryUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testApps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator"),
            com.example.data.local.InstalledApp(packageName = "com.android.chrome", appName = "Google Chrome"),
            com.example.data.local.InstalledApp(packageName = "com.whatsapp", appName = "WhatsApp"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.apps.maps", appName = "Google Maps"),
            com.example.data.local.InstalledApp(packageName = "com.android.settings", appName = "Settings")
        )
        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(testApps)
        kotlinx.coroutines.runBlocking {
            val db = com.example.data.local.AppDatabase.getDatabase(context)
            db.installedAppDao().insertApps(testApps)
        }
    }

    // =========================================================================
    // REQUIRED TEST 1: Chrome -> Search YouTube -> Open 1st Result -> Play Video
    // =========================================================================
    @Test
    fun testChromeSearchYouTubeOpenFirstResultAndPlayVideo() {
        val command = "Open Chrome, search YouTube, open the first result and play the video."
        val plan = GoalPlanner.resolveFastPathPlan(command, context)

        assertNotNull("Plan must not be null for valid command", plan)
        assertTrue("Plan must have at least 4 steps", plan!!.size >= 4)

        // 1. Assert OPEN_APP
        assertEquals("OPEN_APP", plan[0].actionType)
        assertEquals("com.android.chrome", plan[0].targetText)

        // 2. Assert TAP/CLICK_TEXT Search
        assertTrue("Action must be TAP or CLICK_TEXT", plan[1].actionType in setOf("TAP", "CLICK_TEXT"))
        assertEquals("Search", plan[1].targetText)

        // 3. Assert TYPE_TEXT ONLY contains "YouTube" (NO subsequent action text)
        assertEquals("TYPE_TEXT", plan[2].actionType)
        val typedText = plan[2].targetText ?: plan[2].params["text"]
        assertEquals("YouTube", typedText)

        // 4. Assert PRESS_ENTER
        assertEquals("PRESS_ENTER", plan[3].actionType)

        // 5. Assert subsequent decomposed actions
        val remainingActions = plan.drop(4)
        assertTrue("Must contain subsequent decomposed steps", remainingActions.isNotEmpty())
        assertEquals("first result", remainingActions[0].targetText)
        if (remainingActions.size > 1) {
            assertEquals("Play", remainingActions[1].targetText)
        }
    }

    // =========================================================================
    // REQUIRED TEST 2: Maps -> Search Palakollu to Bhimavaram -> Open Directions
    // =========================================================================
    @Test
    fun testMapsSearchPalakolluToBhimavaramOpenDirections() {
        val command = "Open Google Maps, search Palakollu to Bhimavaram, open directions."
        val plan = GoalPlanner.resolveFastPathPlan(command, context)

        assertNotNull("Plan must not be null for valid command", plan)
        assertTrue("Plan must have at least 4 steps", plan!!.size >= 4)

        // 1. Assert OPEN_APP
        assertEquals("OPEN_APP", plan[0].actionType)
        assertEquals("com.google.android.apps.maps", plan[0].targetText)

        // 2. Assert TAP/CLICK_TEXT Search
        assertTrue("Action must be TAP or CLICK_TEXT", plan[1].actionType in setOf("TAP", "CLICK_TEXT"))
        assertEquals("Search", plan[1].targetText)

        // 3. Assert TYPE_TEXT ONLY contains "Palakollu to Bhimavaram"
        assertEquals("TYPE_TEXT", plan[2].actionType)
        val typedText = plan[2].targetText ?: plan[2].params["text"]
        assertEquals("Palakollu to Bhimavaram", typedText)

        // 4. Assert PRESS_ENTER
        assertEquals("PRESS_ENTER", plan[3].actionType)

        // 5. Assert TAP Directions
        val remainingActions = plan.drop(4)
        assertTrue("Must contain Directions action", remainingActions.isNotEmpty())
        assertEquals("Directions", remainingActions[0].targetText)
    }

    // =========================================================================
    // REQUIRED TEST 3: Open Chrome and search OpenAI
    // =========================================================================
    @Test
    fun testOpenChromeAndSearchOpenAI() {
        val command = "Open Chrome and search OpenAI"
        val plan = GoalPlanner.resolveFastPathPlan(command, context)

        assertNotNull("Plan must not be null for valid command", plan)
        assertTrue("Plan must have at least 3 steps", plan!!.size >= 3)

        assertEquals("OPEN_APP", plan[0].actionType)
        assertEquals("com.android.chrome", plan[0].targetText)

        val typeStep = plan.firstOrNull { it.actionType == "TYPE_TEXT" }
        assertNotNull("Must contain TYPE_TEXT step", typeStep)
        val typedText = typeStep!!.targetText ?: typeStep.params["text"]
        assertEquals("OpenAI", typedText)
    }

    // =========================================================================
    // REQUIRED TEST 4: Open YouTube, search music videos, open the first video and play it
    // =========================================================================
    @Test
    fun testOpenYouTubeSearchMusicVideosOpenFirstVideoAndPlayIt() {
        val command = "Open YouTube, search music videos, open the first video and play it."
        val plan = GoalPlanner.resolveFastPathPlan(command, context)

        assertNotNull("Plan must not be null", plan)

        assertEquals("OPEN_APP", plan!![0].actionType)
        assertEquals("com.google.android.youtube", plan[0].targetText)

        val typeStep = plan.firstOrNull { it.actionType == "TYPE_TEXT" }
        assertNotNull("Must contain TYPE_TEXT step", typeStep)
        val typedText = typeStep!!.targetText ?: typeStep.params["text"]
        assertEquals("music videos", typedText)

        val remaining = plan.dropWhile { it.actionType != "PRESS_ENTER" }.drop(1)
        assertTrue("Must have decomposed trailing steps", remaining.isNotEmpty())
        assertEquals("first video", remaining[0].targetText)
        if (remaining.size > 1) {
            assertEquals("Play", remaining[1].targetText)
        }
    }

    // =========================================================================
    // REQUIRED TEST 5: Search New York, New York (Do NOT split comma in location)
    // =========================================================================
    @Test
    fun testSearchNewYorkNewYorkPreservesCommaInLocation() {
        val command = "Search New York, New York"
        val parsed = SemanticUiCommandParser.parse(command)

        assertNotNull("Parsed command must not be null", parsed)
        val typeAction = parsed!!.actions.firstOrNull { it.action == "TYPE" }
        assertNotNull("Must contain TYPE action", typeAction)
        assertEquals("New York, New York", typeAction!!.text)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull("Plan must not be null", plan)
        val typeStep = plan!!.firstOrNull { it.actionType == "TYPE_TEXT" }
        assertNotNull("Must contain TYPE_TEXT step", typeStep)
        val typedText = typeStep!!.targetText ?: typeStep.params["text"]
        assertEquals("New York, New York", typedText)
    }

    // =========================================================================
    // REQUIRED TEST 6: Type Hello, how are you? (Preserves full typed sentence)
    // =========================================================================
    @Test
    fun testTypeHelloHowAreYouPreservesSentence() {
        val command = "Type Hello, how are you?"
        val parsed = SemanticUiCommandParser.parse(command)

        assertNotNull("Parsed command must not be null", parsed)
        val typeAction = parsed!!.actions.firstOrNull { it.action == "TYPE" }
        assertNotNull("Must contain TYPE action", typeAction)
        assertEquals("Hello, how are you?", typeAction!!.text)

        val plan = GoalPlanner.resolveFastPathPlan(command, context)
        assertNotNull("Plan must not be null", plan)
        val typeStep = plan!!.firstOrNull { it.actionType == "TYPE_TEXT" }
        assertNotNull("Must contain TYPE_TEXT step", typeStep)
        val typedText = typeStep!!.targetText ?: typeStep.params["text"]
        assertEquals("Hello, how are you?", typedText)
    }

    // =========================================================================
    // REQUIRED TEST 7: WhatsApp collision guard (Must remain SEND_WHATSAPP, never CALL_PHONE)
    // =========================================================================
    @Test
    fun testSendWhatsAppMessagePreservesPayloadAndNeverBecomesCall() {
        val command = "Send WhatsApp message to Yaswanth that I am in a meeting and will call later."
        val parsed = com.example.utils.IntentParser.parse(command)

        // Must remain SEND_MESSAGE
        assertEquals("SEND_MESSAGE", parsed.intent)
        assertEquals("yaswanth", parsed.contact?.lowercase())
        // Message payload must remain exactly opaque without trimming "will call later"
        assertEquals("I am in a meeting and will call later.", parsed.message)

        // Normalizer boundary check must confirm this is message dictation (never decomposed)
        assertTrue(ActionArgumentBoundaryNormalizer.isMessageOrDictation(actionType = "SEND_WHATSAPP", goal = command))

        // Authorizer must authorize SEND_WHATSAPP and explicitly REJECT CALL_PHONE
        val waAuth = com.example.automation.engine.ActionAuthorizer.authorizeAction(command, "SEND_WHATSAPP", "Yaswanth")
        assertTrue("SEND_WHATSAPP must be authorized", waAuth.authorized)

        val callAuth = com.example.automation.engine.ActionAuthorizer.authorizeAction(command, "CALL_PHONE", "Yaswanth")
        assertFalse("CALL_PHONE must be denied for WhatsApp message command", callAuth.authorized)
    }

    // =========================================================================
    // REQUIRED TEST 8: Call Yaswanth -> CALL_PHONE
    // =========================================================================
    @Test
    fun testCallYaswanthResolvesToCallPhone() {
        val command = "Call Yaswanth"
        val parsed = com.example.utils.IntentParser.parse(command)

        assertEquals("CALL_PHONE", parsed.intent)
        assertEquals("yaswanth", parsed.contact?.lowercase())

        val callAuth = com.example.automation.engine.ActionAuthorizer.authorizeAction(command, "CALL_PHONE", "Yaswanth")
        assertTrue("CALL_PHONE must be authorized for Call command", callAuth.authorized)
    }

    // =========================================================================
    // ADDITIONAL TEST 9: Multi-comma Location + Directions
    // "Open Maps, search Palakollu, Andhra Pradesh, open directions."
    // =========================================================================
    @Test
    fun testMultiCommaLocationWithDirections() {
        val command = "Open Maps, search Palakollu, Andhra Pradesh, open directions."
        val plan = GoalPlanner.resolveFastPathPlan(command, context)

        assertNotNull("Plan must not be null", plan)
        val typeStep = plan!!.firstOrNull { it.actionType == "TYPE_TEXT" }
        assertNotNull("Must contain TYPE_TEXT step", typeStep)
        val typedText = typeStep!!.targetText ?: typeStep.params["text"]
        assertEquals("Palakollu, Andhra Pradesh", typedText)

        val lastStep = plan.last()
        assertTrue("Last step must be TAP or CLICK_TEXT", lastStep.actionType in setOf("TAP", "CLICK_TEXT"))
        assertEquals("Directions", lastStep.targetText)
    }

    // =========================================================================
    // ADDITIONAL TEST 10: Direct Plan Normalization of un-split LLM response
    // =========================================================================
    @Test
    fun testDirectPlanNormalizationOfLlmCompoundStep() {
        val unNormalizedSteps = listOf(
            ActionStep(stepId = "step_1", actionType = "OPEN_APP", targetText = "com.android.chrome"),
            ActionStep(stepId = "step_2", actionType = "TAP", targetText = "Search"),
            ActionStep(
                stepId = "step_3",
                actionType = "TYPE_TEXT",
                targetText = "YouTube, open the first result and play the video",
                params = mapOf("text" to "YouTube, open the first result and play the video")
            )
        )

        val normalized = ActionArgumentBoundaryNormalizer.normalizePlan(unNormalizedSteps, "Open Chrome, search YouTube, open the first result and play the video.")

        assertEquals("com.android.chrome", normalized[0].targetText)
        assertEquals("Search", normalized[1].targetText)

        // step 3 must be cleaned to "YouTube"
        assertEquals("TYPE_TEXT", normalized[2].actionType)
        assertEquals("YouTube", normalized[2].targetText)

        // step 4 must be PRESS_ENTER
        assertEquals("PRESS_ENTER", normalized[3].actionType)

        // step 5 must be first result
        assertEquals("TAP", normalized[4].actionType)
        assertEquals("first result", normalized[4].targetText)

        // step 6 must be Play
        assertEquals("TAP", normalized[5].actionType)
        assertEquals("Play", normalized[5].targetText)
    }
}
