package com.example.automation

import com.example.automation.computeruse.InteractiveElement
import com.example.automation.computeruse.ScreenObservation
import com.example.automation.engine.AutonomousTaskCoordinator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AutonomousTaskCoordinatorTest {

    private lateinit var coordinator: AutonomousTaskCoordinator

    @Before
    fun setUp() {
        coordinator = AutonomousTaskCoordinator()
    }

    @Test
    fun testStartAutonomousTaskOnAutomationTool() {
        assertFalse(coordinator.isRunning)
        assertEquals(0, coordinator.stepCount)

        coordinator.onAutomationToolInvoked("open_app", "Open YouTube and search Telugu songs")

        assertTrue(coordinator.isRunning)
        assertEquals("Open YouTube and search Telugu songs", coordinator.currentGoal)
        assertEquals(1, coordinator.stepCount)
        assertEquals(0, coordinator.continuationAttempts)
    }

    @Test
    fun testStepAdvancementOnSubsequentTools() {
        coordinator.onAutomationToolInvoked("open_app", "Open Settings")
        assertEquals(1, coordinator.stepCount)

        coordinator.onAutomationToolInvoked("click_element", "Open Settings")
        assertEquals(2, coordinator.stepCount)

        coordinator.onAutomationToolInvoked("type_text", "Open Settings")
        assertEquals(3, coordinator.stepCount)
    }

    @Test
    fun testNonAutomationToolDoesNotStartAutonomousMode() {
        coordinator.onAutomationToolInvoked("flashlight", "Turn on flashlight")
        assertFalse(coordinator.isRunning)
        assertEquals(0, coordinator.stepCount)
    }

    @Test
    fun testEnrichToolResultOnSuccess() {
        coordinator.onAutomationToolInvoked("open_app", "Open YouTube")

        val rawResult = """{"status":"success","app":"YouTube"}"""

        val enriched = coordinator.enrichToolResult(rawResult)
        val json = JSONObject(enriched)

        assertTrue(json.has("system_directive"))
        val directive = json.getString("system_directive")
        assertTrue(directive.contains("AUTONOMOUS STEP 1 EXECUTED"))
        assertTrue(directive.contains("Open YouTube"))
        assertTrue(directive.contains("NEVER ABANDON THE TASK MID-WAY!"))
    }

    @Test
    fun testEnrichToolResultOnFailure() {
        coordinator.onAutomationToolInvoked("click_element", "Open YouTube")

        val rawResult = """{"status":"error","reason":"Element not found"}"""

        val enriched = coordinator.enrichToolResult(rawResult)
        val json = JSONObject(enriched)

        assertTrue(json.has("system_directive"))
        val directive = json.getString("system_directive")
        assertTrue(directive.contains("CRITICAL: STEP 1 FAILED (Element not found)"))
        assertTrue(directive.contains("Do NOT claim success"))
        assertEquals(1, coordinator.consecutiveFailures)
    }

    @Test
    fun testTaskCompletedTerminatesCleanly() {
        coordinator.onAutomationToolInvoked("open_app", "Open YouTube")
        assertTrue(coordinator.isRunning)

        coordinator.onTaskCompleted("Played Telugu songs on YouTube")
        assertFalse(coordinator.isRunning)
        assertEquals("", coordinator.currentGoal)
    }

    @Test
    fun testTurnBoundaryDoesNotAutoClaimSingleStepCompletion() {
        coordinator.onAutomationToolInvoked("open_app", "Open YouTube")
        val connection = com.example.voice.assistant.ConnectionManager(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            onMessageReceived = {},
            onAudioReceived = {},
            onConnectionStateChanged = {},
            onExecuteAutomation = { "{}" }
        )
        val audioPlayer = com.example.voice.audio.AudioPlayer(
            com.example.voice.audio.AudioFocusManager(org.robolectric.RuntimeEnvironment.getApplication())
        )

        coordinator.onTurnComplete(connection, audioPlayer, org.robolectric.RuntimeEnvironment.getApplication())

        assertFalse("A turn boundary must not claim unverified completion", coordinator.isRunning)
        assertEquals("", coordinator.currentGoal)
    }

    @Test
    fun testUserInterruptionWithStopWord() {
        coordinator.onAutomationToolInvoked("open_app", "Open YouTube")
        assertTrue(coordinator.isRunning)

        // Telugu stop word "aagu"
        val stopped = coordinator.handleUserInterruption("aagu MAX")
        assertTrue(stopped)
        assertFalse(coordinator.isRunning)

        // English stop word "cancel"
        coordinator.onAutomationToolInvoked("open_app", "Open YouTube")
        val stoppedEng = coordinator.handleUserInterruption("please stop now")
        assertTrue(stoppedEng)
        assertFalse(coordinator.isRunning)
    }

    @Test
    fun testUserInterruptionWithNewCommandResetsGoal() {
        coordinator.onAutomationToolInvoked("open_app", "Open YouTube")
        assertTrue(coordinator.isRunning)

        // New command that is not a stop word
        val stopped = coordinator.handleUserInterruption("What is the weather today?")
        assertFalse(stopped)
        assertFalse(coordinator.isRunning) // Old task cancelled so new query takes precedence
    }

    @Test
    fun testFormatObservationSummary() {
        val elements = listOf(
            InteractiveElement(
                id = "com.google.android.youtube:id/search_button",
                text = "Search",
                isClickable = true
            ),
            InteractiveElement(
                id = "com.google.android.youtube:id/search_edit_text",
                text = "Search YouTube",
                isEditable = true
            )
        )

        val obs = ScreenObservation(
            foregroundPackage = "com.google.android.youtube",
            currentActivity = "com.google.android.youtube.HomeActivity",
            interactiveElements = elements
        )

        val summary = coordinator.formatObservationSummary(obs)
        assertTrue(summary.contains("Foreground Package: com.google.android.youtube"))
        assertTrue(summary.contains("[Button] \"Search\" [id: search_button]"))
        assertTrue(summary.contains("[EditText] \"Search YouTube\" [id: search_edit_text]"))
    }

    @Test
    fun testBuildContinuationPrompt() {
        val prompt = coordinator.buildContinuationPrompt(
            goal = "Search Telugu songs",
            foregroundPkg = "com.google.android.youtube",
            screenSummary = "Elements on screen",
            step = 2
        )

        assertTrue(prompt.contains("Active Goal: 'Search Telugu songs'"))
        assertTrue(prompt.contains("Foreground Application: 'com.google.android.youtube'"))
        assertTrue(prompt.contains("call task_completed(summary=...)"))
        assertTrue(prompt.contains("Keep the phone quiet while continuing"))
    }

    @Test
    fun testIsCompoundGoalDetection() {
        // Single-action tasks must NOT be treated as compound tasks
        assertFalse(coordinator.isCompoundGoal("Open YouTube"))
        assertFalse(coordinator.isCompoundGoal("YouTube open chey"))
        assertFalse(coordinator.isCompoundGoal("Play Believer song"))
        assertFalse(coordinator.isCompoundGoal("Turn on flashlight"))
        assertFalse(coordinator.isCompoundGoal("Volume up"))
        assertFalse(coordinator.isCompoundGoal("Click search"))

        // Multi-step compound tasks must be identified
        assertTrue(coordinator.isCompoundGoal("Open YouTube and search Telugu songs"))
        assertTrue(coordinator.isCompoundGoal("WhatsApp open chesi Yash ki message pettu"))
        assertTrue(coordinator.isCompoundGoal("Go to Settings, then click Wi-Fi"))
        assertTrue(coordinator.isCompoundGoal("YouTube nundi library ki vellu"))
    }

    @Test
    fun testGoalCompletedOnScreenVerification() {
        val obsYouTube = ScreenObservation(
            foregroundPackage = "com.google.android.youtube",
            currentActivity = "com.google.android.youtube.HomeActivity"
        )
        // For single-step "Open YouTube", having YouTube in foreground completes the goal
        assertTrue(coordinator.isGoalCompletedOnScreen("Open YouTube", obsYouTube))
        assertTrue(coordinator.isGoalCompletedOnScreen("YouTube open chey", obsYouTube))

        // Different package does not complete
        val obsSettings = ScreenObservation(
            foregroundPackage = "com.android.settings",
            currentActivity = "com.android.settings.SettingsActivity"
        )
        assertFalse(coordinator.isGoalCompletedOnScreen("Open YouTube", obsSettings))
    }
}
