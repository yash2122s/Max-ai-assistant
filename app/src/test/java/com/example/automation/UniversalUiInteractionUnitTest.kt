package com.example.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.automation.core.actions.GenericAction
import com.example.automation.core.actions.GenericActionType
import com.example.automation.engine.*
import com.example.automation.event.AutomationEvent
import com.example.automation.event.AutomationEventBus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniversalUiInteractionUnitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testApps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.apps.maps", appName = "Maps"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.keep", appName = "Notes"),
            com.example.data.local.InstalledApp(packageName = "com.android.settings", appName = "Settings"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.youtube", appName = "YouTube")
        )
        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(testApps)
        com.example.ui.overlay.MaxVisualFeedbackManager.isTesting = true
        com.example.ui.overlay.ActionCursorOverlayView.isTesting = true
        runBlocking {
            val db = com.example.data.local.AppDatabase.getDatabase(context)
            db.installedAppDao().insertApps(testApps)
        }
    }

    // ==========================================
    // 1. GENERIC ACTION CONVERSIONS
    // ==========================================

    @Test
    fun testGenericActionPressEnterConversion() {
        val action = GenericAction(type = GenericActionType.PRESS_ENTER)
        val step = action.toActionStep("step_enter")
        assertEquals("PRESS_ENTER", step.actionType)
        assertEquals("enter", step.targetText)

        val restored = GenericAction.fromActionStep(step)
        assertEquals(GenericActionType.PRESS_ENTER, restored.type)
    }

    @Test
    fun testGenericActionBackConversion() {
        val action = GenericAction(type = GenericActionType.BACK)
        val step = action.toActionStep("step_back")
        assertEquals("SYSTEM_ACTION", step.actionType)
        assertEquals("back", step.targetText)

        val restored = GenericAction.fromActionStep(step)
        assertEquals(GenericActionType.BACK, restored.type)
    }

    // ==========================================
    // 2. PARSER CANONICAL SESSION 1.9 TEST CASES
    // ==========================================

    @Test
    fun testParseTestA_OpenNotesAndType() {
        val parsed = SemanticUiCommandParser.parse("Open Notes and type Palakollu")
        assertNotNull(parsed)
        assertEquals("Notes", parsed?.app)
        assertEquals(2, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("TYPE", parsed?.actions?.get(1)?.action)
        assertEquals("Palakollu", parsed?.actions?.get(1)?.text)
    }

    @Test
    fun testParseTestB_OpenSettingsSearchWiFiEnter() {
        val parsed = SemanticUiCommandParser.parse("Open Settings, tap Search, type WiFi, press Enter")
        assertNotNull(parsed)
        assertEquals("Settings", parsed?.app)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("Search", parsed?.actions?.get(1)?.target)
        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("WiFi", parsed?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(3)?.action)
    }

    @Test
    fun testParseTestC_CalculatorSequentialCalculation() {
        val parsed = SemanticUiCommandParser.parse("Open Calculator, enter 25, press +, enter 10")
        assertNotNull(parsed)
        assertEquals("Calculator", parsed?.app)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("TYPE", parsed?.actions?.get(1)?.action)
        assertEquals("25", parsed?.actions?.get(1)?.text)
        assertEquals("CLICK", parsed?.actions?.get(2)?.action)
        assertEquals("+", parsed?.actions?.get(2)?.target)
        assertEquals("TYPE", parsed?.actions?.get(3)?.action)
        assertEquals("10", parsed?.actions?.get(3)?.text)
    }

    @Test
    fun testParseTestD_NotesCreateTypeEnterBack() {
        val parsed = SemanticUiCommandParser.parse("Open Notes, create a note, type tomorrow meeting at 10, press Enter, then go back")
        assertNotNull(parsed)
        assertEquals("Notes", parsed?.app)
        assertEquals(5, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("tomorrow meeting at 10", parsed?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(3)?.action)
        assertEquals("SYSTEM", parsed?.actions?.get(4)?.action)
        assertEquals("back", parsed?.actions?.get(4)?.target)
    }

    // ==========================================
    // 3. GOAL PLANNER DAG RESOLUTION
    // ==========================================

    @Test
    fun testFastPathPlanCalculationReplaceFlags() {
        val steps = GoalPlanner.resolveFastPathPlan("Open Calculator, enter 25, press +, enter 10", context)
        assertNotNull(steps)
        assertEquals(4, steps?.size)

        // Step 1: OPEN_APP Calculator
        assertEquals("OPEN_APP", steps?.get(0)?.actionType)
        assertEquals("com.google.android.calculator", steps?.get(0)?.targetText)

        // Step 2: TYPE_TEXT 25 (first type -> replace=true)
        assertEquals("TYPE_TEXT", steps?.get(1)?.actionType)
        assertEquals("25", steps?.get(1)?.params?.get("text"))
        assertEquals("true", steps?.get(1)?.params?.get("replace"))

        // Step 3: CLICK_TEXT +
        assertEquals("CLICK_TEXT", steps?.get(2)?.actionType)
        assertEquals("+", steps?.get(2)?.targetText)

        // Step 4: TYPE_TEXT 10 (subsequent type -> replace=false)
        assertEquals("TYPE_TEXT", steps?.get(3)?.actionType)
        assertEquals("10", steps?.get(3)?.params?.get("text"))
        assertEquals("false", steps?.get(3)?.params?.get("replace"))
    }

    @Test
    fun testFastPathPlanTestD_StepsAndDependencies() {
        val steps = GoalPlanner.resolveFastPathPlan("Open Notes, create a note, type tomorrow meeting at 10, press Enter, then go back", context)
        assertNotNull(steps)
        assertEquals(5, steps?.size)

        assertEquals("OPEN_APP", steps?.get(0)?.actionType)
        assertEquals("com.google.android.keep", steps?.get(0)?.targetText)

        assertEquals("CLICK_TEXT", steps?.get(1)?.actionType)
        assertEquals("note", steps?.get(1)?.targetText)

        assertEquals("TYPE_TEXT", steps?.get(2)?.actionType)
        assertEquals("tomorrow meeting at 10", steps?.get(2)?.params?.get("text"))

        assertEquals("PRESS_ENTER", steps?.get(3)?.actionType)
        assertEquals("enter", steps?.get(3)?.targetText)

        assertEquals("SYSTEM_ACTION", steps?.get(4)?.actionType)
        assertEquals("back", steps?.get(4)?.targetText)

        // Verify DAG chain
        assertEquals(listOf("step_1"), steps?.get(1)?.dependsOn)
        assertEquals(listOf("step_2"), steps?.get(2)?.dependsOn)
        assertEquals(listOf("step_3"), steps?.get(3)?.dependsOn)
        assertEquals(listOf("step_4"), steps?.get(4)?.dependsOn)
    }

    // ==========================================
    // 4. MID-PLAN INTERRUPTION & CANCELLATION TEST (TEST E)
    // ==========================================

    @Test
    fun testMidPlanInterruptionStopsExecutionAndEmitsCancelled() = runBlocking {
        val cancellationToken = CancellationToken()

        // Prepare a 5-step plan
        val steps = GoalPlanner.resolveFastPathPlan("Open Notes, create a note, type tomorrow meeting at 10, press Enter, then go back", context)
        assertNotNull(steps)
        GoalPlanner.initializeGoalPlan("Test goal", steps!!)

        // Trigger cancellation before step execution finishes
        cancellationToken.cancel()

        val capturedEvents = mutableListOf<AutomationEvent>()
        val collectJob = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            AutomationEventBus.events.collect { capturedEvents.add(it) }
        }

        val result = GoalPlanner.executePlan(context, cancellationToken)

        collectJob.cancel()

        // Verify that plan stopped with isCancelled = true
        assertTrue("Plan execution should be marked as cancelled", result.isCancelled)
        assertFalse("Cancelled plan must NOT report success", result.success)

        // Verify that ExecutionCancelled event was emitted
        val hasCancelledEvent = capturedEvents.any { it is AutomationEvent.ExecutionCancelled }
        assertTrue("ExecutionCancelled event must be emitted on cancellation", hasCancelledEvent)

        // Verify that no SUCCESS event was emitted
        val hasSuccessEvent = capturedEvents.any { it is AutomationEvent.ExecutionFinished && it.success }
        assertFalse("ExecutionFinished(success=true) must NOT be emitted on cancelled plan", hasSuccessEvent)
    }

    // ==========================================
    // 5. MAPS APP ALIAS & MULTI-ACTION WORKFLOWS
    // ==========================================

    @Test
    fun testGoogleMapsAndMapsAppAliasResolution() {
        val testAppsWithMaps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.apps.maps", appName = "Maps"),
            com.example.data.local.InstalledApp(packageName = "com.google.android.calculator", appName = "Calculator")
        )
        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(testAppsWithMaps)

        // Query with "Google Maps"
        val resolvedGoogleMaps = com.example.knowledge.apps.InstalledAppsRepository.findApp(context, "Google Maps")
        assertEquals("com.google.android.apps.maps", resolvedGoogleMaps)

        // Query with "Maps"
        val resolvedMaps = com.example.knowledge.apps.InstalledAppsRepository.findApp(context, "Maps")
        assertEquals("com.google.android.apps.maps", resolvedMaps)

        // Query with "gmaps"
        val resolvedGmaps = com.example.knowledge.apps.InstalledAppsRepository.findApp(context, "gmaps")
        assertEquals("com.google.android.apps.maps", resolvedGmaps)
    }

    @Test
    fun testMultiActionGoogleMapsWorkflowParsingAndPlanning() {
        val testAppsWithMaps = listOf(
            com.example.data.local.InstalledApp(packageName = "com.google.android.apps.maps", appName = "Maps")
        )
        com.example.knowledge.apps.InstalledAppsRepository.setCachedAppsForTesting(testAppsWithMaps)

        // Case 1: "Open Google Maps and tap Search"
        val parsedA = SemanticUiCommandParser.parse("Open Google Maps and tap Search")
        assertNotNull(parsedA)
        assertEquals("Google Maps", parsedA?.app)
        assertEquals(2, parsedA?.actions?.size)
        assertEquals("OPEN_APP", parsedA?.actions?.get(0)?.action)
        assertEquals("CLICK", parsedA?.actions?.get(1)?.action)
        assertEquals("Search", parsedA?.actions?.get(1)?.target)

        val planA = GoalPlanner.resolveFastPathPlan("Open Google Maps and tap Search", context)
        assertNotNull(planA)
        assertEquals(2, planA?.size)
        assertEquals("com.google.android.apps.maps", planA?.get(0)?.targetText)
        assertEquals("CLICK_TEXT", planA?.get(1)?.actionType)
        assertEquals("Search", planA?.get(1)?.targetText)

        // Case 2: "Open Maps and tap Search"
        val planB = GoalPlanner.resolveFastPathPlan("Open Maps and tap Search", context)
        assertNotNull(planB)
        assertEquals(2, planB?.size)
        assertEquals("com.google.android.apps.maps", planB?.get(0)?.targetText)

        // Case 3: "Open Google Maps, tap Search, type Palakollu, press Enter"
        val parsedC = SemanticUiCommandParser.parse("Open Google Maps, tap Search, type Palakollu, press Enter")
        assertNotNull(parsedC)
        assertEquals(4, parsedC?.actions?.size)
        assertEquals("OPEN_APP", parsedC?.actions?.get(0)?.action)
        assertEquals("CLICK", parsedC?.actions?.get(1)?.action)
        assertEquals("Search", parsedC?.actions?.get(1)?.target)
        assertEquals("TYPE", parsedC?.actions?.get(2)?.action)
        assertEquals("Palakollu", parsedC?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsedC?.actions?.get(3)?.action)

        val planC = GoalPlanner.resolveFastPathPlan("Open Google Maps, tap Search, type Palakollu, press Enter", context)
        assertNotNull(planC)
        assertEquals(4, planC?.size)
        assertEquals("com.google.android.apps.maps", planC?.get(0)?.targetText)
        assertEquals("CLICK_TEXT", planC?.get(1)?.actionType)
        assertEquals("Search", planC?.get(1)?.targetText)
        assertEquals("TYPE_TEXT", planC?.get(2)?.actionType)
        assertEquals("Palakollu", planC?.get(2)?.params?.get("text"))
        assertEquals("PRESS_ENTER", planC?.get(3)?.actionType)
    }

    // ==========================================
    // 6. VISUAL OVERLAY TOUCH REJECTION & LIFECYCLE
    // ==========================================

    @Test
    fun testOverlayViewsRejectAllTouchEvents() {
        val edgeView = com.example.ui.overlay.EdgeLightingOverlayView(context)
        val cursorView = com.example.ui.overlay.ActionCursorOverlayView(context)

        assertFalse("EdgeLightingOverlayView must not be clickable", edgeView.isClickable)
        assertFalse("EdgeLightingOverlayView must not be focusable", edgeView.isFocusable)
        assertFalse("EdgeLightingOverlayView dispatchTouchEvent must return false", edgeView.dispatchTouchEvent(null))
        assertFalse("EdgeLightingOverlayView onTouchEvent must return false", edgeView.onTouchEvent(null))

        assertFalse("ActionCursorOverlayView must not be clickable", cursorView.isClickable)
        assertFalse("ActionCursorOverlayView must not be focusable", cursorView.isFocusable)
        assertFalse("ActionCursorOverlayView dispatchTouchEvent must return false", cursorView.dispatchTouchEvent(null))
        assertFalse("ActionCursorOverlayView onTouchEvent must return false", cursorView.onTouchEvent(null))
    }

    @Test
    fun testOverlayAttachOnActiveAndDetachOnCancel() {
        com.example.ui.overlay.MaxVisualFeedbackManager.isTesting = true
        val feedbackManager = com.example.ui.overlay.MaxVisualFeedbackManager(context)
        val execId = java.util.UUID.randomUUID()

        // Send active event -> overlays attach
        feedbackManager.handleAutomationEvent(AutomationEvent.PlanningStarted(execId, "test goal"))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
        assertTrue("Overlays must be attached when automation starts", feedbackManager.isAttached)

        // Send cancellation -> overlays detach immediately
        feedbackManager.handleAutomationEvent(AutomationEvent.ExecutionCancelled(execId, "User stop"))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
        assertFalse("Overlays must detach immediately on cancellation", feedbackManager.isAttached)

        feedbackManager.destroy()
        com.example.ui.overlay.MaxVisualFeedbackManager.isTesting = false
    }

    @Test
    fun testMacBookStyleArrowCursorRenderingAndBounds() {
        val cursorView = com.example.ui.overlay.ActionCursorOverlayView(context)
        val bounds = android.graphics.Rect(100, 200, 300, 260)

        // Animate to target
        cursorView.animateToTarget(bounds)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()

        assertTrue("Cursor must be visible after target resolution", cursorView.isVisibleState)
        assertNotNull(cursorView.targetPositionState)

        // Trigger action pulse
        cursorView.pulseAction()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
        assertEquals(com.example.ui.overlay.AgentCursorState.CLICKING, cursorView.cursorState)

        // Trigger dragging
        cursorView.showDragging()
        assertEquals(com.example.ui.overlay.AgentCursorState.DRAGGING, cursorView.cursorState)

        // Trigger thinking
        cursorView.showThinking()
        assertEquals(com.example.ui.overlay.AgentCursorState.THINKING, cursorView.cursorState)

        // Hide immediately
        cursorView.hideImmediately()
        org.robolectric.shadows.ShadowLooper.runUiThreadTasks()
        assertEquals(com.example.ui.overlay.AgentCursorState.IDLE, cursorView.cursorState)
        assertFalse("Cursor must be hidden after hideImmediately", cursorView.isVisibleState)

        assertFalse("ActionCursorOverlayView must reject touch events", cursorView.dispatchTouchEvent(null))
        assertFalse("ActionCursorOverlayView must reject onTouchEvent", cursorView.onTouchEvent(null))

        cursorView.destroy()
    }

    @Test
    fun testAgentCursorCoordinateConversion() {
        val cursorView = com.example.ui.overlay.ActionCursorOverlayView(context)
        val bounds = android.graphics.Rect(200, 400, 600, 800)

        cursorView.animateToTarget(bounds)
        val targetPos = cursorView.targetPositionState

        val density = context.resources.displayMetrics.density
        val cursorSizePx = 26f * density
        val tipOffsetX = cursorSizePx * 0.15f
        val tipOffsetY = cursorSizePx * 1.25f * 0.08f

        // Target center should match bounds exact center after adding tip offset
        assertEquals(bounds.exactCenterX() - tipOffsetX, targetPos.x, 0.01f)
        assertEquals(bounds.exactCenterY() - tipOffsetY, targetPos.y, 0.01f)

        cursorView.destroy()
    }

    // ==========================================
    // 7. TANGLISH & NATURAL SEARCH INTENT PARSING
    // ==========================================

    @Test
    fun testTanglishSearchIntent_PalakolluAneSearchChey() {
        val parsed = SemanticUiCommandParser.parse("Palakollu ane search chey")
        assertNotNull(parsed)
        assertEquals(2, parsed?.actions?.size)
        assertEquals("TYPE", parsed?.actions?.get(0)?.action)
        assertEquals("Palakollu", parsed?.actions?.get(0)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(1)?.action)

        val plan = GoalPlanner.resolveFastPathPlan("Palakollu ane search chey", context)
        assertNotNull(plan)
        assertEquals(2, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Palakollu", plan?.get(0)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(1)?.actionType)
    }

    @Test
    fun testTanglishSearchIntent_GoogleMapsOpenChesiPalakolluAneSearchChey() {
        val parsed = SemanticUiCommandParser.parse("Google maps open chesi Palakollu ane search chey")
        assertNotNull(parsed)
        assertEquals("Google maps", parsed?.app)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("Search", parsed?.actions?.get(1)?.target)
        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("Palakollu", parsed?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(3)?.action)

        val plan = GoalPlanner.resolveFastPathPlan("Google maps open chesi Palakollu ane search chey", context)
        assertNotNull(plan)
        assertEquals(4, plan?.size)
        assertEquals("OPEN_APP", plan?.get(0)?.actionType)
        assertEquals("com.google.android.apps.maps", plan?.get(0)?.targetText)
        assertEquals("CLICK_TEXT", plan?.get(1)?.actionType)
        assertEquals("Search", plan?.get(1)?.targetText)
        assertEquals("TYPE_TEXT", plan?.get(2)?.actionType)
        assertEquals("Palakollu", plan?.get(2)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(3)?.actionType)
    }

    @Test
    fun testTanglishSearchIntent_BhimavaramSearchChey() {
        val parsed = SemanticUiCommandParser.parse("Bhimavaram search chey")
        assertNotNull(parsed)
        assertEquals(2, parsed?.actions?.size)
        assertEquals("TYPE", parsed?.actions?.get(0)?.action)
        assertEquals("Bhimavaram", parsed?.actions?.get(0)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(1)?.action)

        val plan = GoalPlanner.resolveFastPathPlan("Bhimavaram search chey", context)
        assertNotNull(plan)
        assertEquals(2, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Bhimavaram", plan?.get(0)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(1)?.actionType)
    }

    @Test
    fun testTanglishSearchIntent_HyderabadAniSearchChey() {
        val parsed = SemanticUiCommandParser.parse("Hyderabad ani search chey")
        assertNotNull(parsed)
        assertEquals(2, parsed?.actions?.size)
        assertEquals("TYPE", parsed?.actions?.get(0)?.action)
        assertEquals("Hyderabad", parsed?.actions?.get(0)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(1)?.action)

        val plan = GoalPlanner.resolveFastPathPlan("Hyderabad ani search chey", context)
        assertNotNull(plan)
        assertEquals(2, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Hyderabad", plan?.get(0)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(1)?.actionType)
    }

    @Test
    fun testTanglishSearchIntent_YouTubeOpenChesiSongsAniSearchChey() {
        val parsed = SemanticUiCommandParser.parse("YouTube open chesi songs ani search chey")
        assertNotNull(parsed)
        assertEquals("YouTube", parsed?.app)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("Search", parsed?.actions?.get(1)?.target)
        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("songs", parsed?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(3)?.action)

        val plan = GoalPlanner.resolveFastPathPlan("YouTube open chesi songs ani search chey", context)
        assertNotNull(plan)
        assertEquals(4, plan?.size)
        assertEquals("com.google.android.youtube", plan?.get(0)?.targetText)
        assertEquals("CLICK_TEXT", plan?.get(1)?.actionType)
        assertEquals("Search", plan?.get(1)?.targetText)
        assertEquals("TYPE_TEXT", plan?.get(2)?.actionType)
        assertEquals("songs", plan?.get(2)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(3)?.actionType)
    }

    @Test
    fun testTanglishSearchIntent_SettingsOpenChesiWiFiSearchChey() {
        val parsed = SemanticUiCommandParser.parse("Settings open chesi WiFi search chey")
        assertNotNull(parsed)
        assertEquals(4, parsed?.actions?.size)
        assertEquals("OPEN_APP", parsed?.actions?.get(0)?.action)
        assertEquals("CLICK", parsed?.actions?.get(1)?.action)
        assertEquals("Search", parsed?.actions?.get(1)?.target)
        assertEquals("TYPE", parsed?.actions?.get(2)?.action)
        assertEquals("WiFi", parsed?.actions?.get(2)?.text)
        assertEquals("PRESS_ENTER", parsed?.actions?.get(3)?.action)

        val plan = GoalPlanner.resolveFastPathPlan("Settings open chesi WiFi search chey", context)
        assertNotNull(plan)
        assertEquals(4, plan?.size)
        assertEquals("com.android.settings", plan?.get(0)?.targetText)
        assertEquals("CLICK_TEXT", plan?.get(1)?.actionType)
        assertEquals("Search", plan?.get(1)?.targetText)
        assertEquals("TYPE_TEXT", plan?.get(2)?.actionType)
        assertEquals("WiFi", plan?.get(2)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(3)?.actionType)
    }

    @Test
    fun testTanglishSearchIntent_GoogleMapsLoPalakolluSearchChey() {
        val plan = GoalPlanner.resolveFastPathPlan("Google maps lo Palakollu search chey", context)
        assertNotNull(plan)
        assertEquals(4, plan?.size)
        assertEquals("com.google.android.apps.maps", plan?.get(0)?.targetText)
        assertEquals("CLICK_TEXT", plan?.get(1)?.actionType)
        assertEquals("Search", plan?.get(1)?.targetText)
        assertEquals("TYPE_TEXT", plan?.get(2)?.actionType)
        assertEquals("Palakollu", plan?.get(2)?.params?.get("text"))
        assertEquals("PRESS_ENTER", plan?.get(3)?.actionType)
    }

    // ==========================================
    // 8. SINGLE-TURN INTERACTIVE UI ACTIONS
    // ==========================================

    @Test
    fun testSingleTurnContextualTyping_TypePalakollu() {
        val parsed = SemanticUiCommandParser.parse("Type Palakollu")
        assertNotNull(parsed)
        assertEquals("TYPE", parsed?.actions?.firstOrNull()?.action)
        assertEquals("Palakollu", parsed?.actions?.firstOrNull()?.text)

        val plan = GoalPlanner.resolveFastPathPlan("Type Palakollu", context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Palakollu", plan?.get(0)?.params?.get("text"))
    }

    @Test
    fun testSingleTurnPressEnter() {
        val parsed = SemanticUiCommandParser.parse("Press enter")
        assertNotNull(parsed)
        assertEquals("PRESS_ENTER", parsed?.actions?.firstOrNull()?.action)

        val plan = GoalPlanner.resolveFastPathPlan("Press enter", context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("PRESS_ENTER", plan?.get(0)?.actionType)
    }

    @Test
    fun testSingleTurnClick_ClickSearch() {
        val parsed = SemanticUiCommandParser.parse("Click Search")
        assertNotNull(parsed)
        assertEquals("CLICK", parsed?.actions?.firstOrNull()?.action)
        assertEquals("Search", parsed?.actions?.firstOrNull()?.target)

        val plan = GoalPlanner.resolveFastPathPlan("Click Search", context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("CLICK_TEXT", plan?.get(0)?.actionType)
        assertEquals("Search", plan?.get(0)?.targetText)
    }

    @Test
    fun testSingleTurnGoBack() {
        val parsed = SemanticUiCommandParser.parse("Go back")
        assertNotNull(parsed)
        assertEquals("SYSTEM", parsed?.actions?.firstOrNull()?.action)
        assertEquals("back", parsed?.actions?.firstOrNull()?.target)

        val plan = GoalPlanner.resolveFastPathPlan("Go back", context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("SYSTEM_ACTION", plan?.get(0)?.actionType)
        assertEquals("back", plan?.get(0)?.targetText)
    }

    @Test
    fun testSingleTurnContextualTyping_Tanglish() {
        val parsed = SemanticUiCommandParser.parse("Palakollu ani type chey")
        assertNotNull(parsed)
        assertEquals("TYPE", parsed?.actions?.firstOrNull()?.action)
        assertEquals("Palakollu", parsed?.actions?.firstOrNull()?.text)

        val plan = GoalPlanner.resolveFastPathPlan("Palakollu ani type chey", context)
        assertNotNull(plan)
        assertEquals(1, plan?.size)
        assertEquals("TYPE_TEXT", plan?.get(0)?.actionType)
        assertEquals("Palakollu", plan?.get(0)?.params?.get("text"))
    }

    @Test
    fun testVoiceTextParity_CompoundMapsSearch() {
        val typedGoal = "Google Maps open chesi Palakollu ani search chey"
        val voiceGoal = "Google Maps open chesi Palakollu ani search chey"

        val typedPlan = GoalPlanner.resolveFastPathPlan(typedGoal, context)
        val voicePlan = GoalPlanner.resolveFastPathPlan(voiceGoal, context)

        assertNotNull(typedPlan)
        assertNotNull(voicePlan)
        assertEquals(typedPlan?.size, voicePlan?.size)
        for (i in typedPlan!!.indices) {
            assertEquals(typedPlan[i].actionType, voicePlan!![i].actionType)
            assertEquals(typedPlan[i].targetText, voicePlan[i].targetText)
        }
    }
}
