package com.example.network

import com.example.network.model.GeminiLiveModels
import com.example.network.model.GeminiModelMode
import com.example.network.model.TaskContext
import com.example.network.router.DefaultModelRouter
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GeminiModelRouterTest {

    private lateinit var router: DefaultModelRouter

    @Before
    fun setUp() {
        router = DefaultModelRouter()
    }

    @Test
    fun testQuickCommands_DefaultToPrimaryFastModel() {
        val quickQueries = listOf(
            "Open Chrome",
            "Call Mom",
            "Turn on flashlight",
            "Search YouTube for music",
            "What time is it?",
            "Send a WhatsApp message",
            "Set an alarm for 7 AM",
            "Battery percentage"
        )

        for (query in quickQueries) {
            val mode = router.selectMode(TaskContext(query = query))
            assertEquals("Expected primary FAST mode for query: '$query'", GeminiModelMode.FAST, mode)
            assertEquals(GeminiLiveModels.FAST, router.modelFor(mode))
        }
    }

    @Test
    fun testComplexCodingAndTroubleshooting_SelectsDeepMode() {
        val deepQueries = listOf(
            "Analyze this Android project and find the bug",
            "Open VS Code, inspect this project, find why the build is failing and fix it",
            "Look at this screen and figure out why the application isn't working, then fix it",
            "Plan and execute this multi-step workflow",
            "Debug the code and inspect the stacktrace for nullpointerexception",
            "Find why it's failing to compile and fix the unresolved reference"
        )

        for (query in deepQueries) {
            val mode = router.selectMode(TaskContext(query = query))
            assertEquals("Expected DEEP mode for query: '$query'", GeminiModelMode.DEEP, mode)
            assertEquals(GeminiLiveModels.DEEP, router.modelFor(mode))
        }
    }

    @Test
    fun testExplicitVoiceCommands_OverridesMode() {
        // Deep voice overrides
        val deepVoice = listOf(
            "Please use deep thinking for this task",
            "Think deeply about this problem",
            "Switch to deep mode now",
            "Enable deep thinking"
        )
        for (query in deepVoice) {
            val override = router.detectExplicitModeOverride(query)
            assertEquals("Expected explicit DEEP override for: '$query'", GeminiModelMode.DEEP, override)
            val selected = router.selectMode(TaskContext(query = query))
            assertEquals("Expected DEEP mode selection for: '$query'", GeminiModelMode.DEEP, selected)
        }

        // Fast voice overrides
        val fastVoice = listOf(
            "Switch to fast mode",
            "Use fast mode please",
            "Be quick and use fast mode",
            "Turn off deep mode"
        )
        for (query in fastVoice) {
            val override = router.detectExplicitModeOverride(query)
            assertEquals("Expected explicit FAST override for: '$query'", GeminiModelMode.FAST, override)
            val selected = router.selectMode(TaskContext(query = query))
            assertEquals("Expected FAST mode selection for: '$query'", GeminiModelMode.FAST, selected)
        }
    }

    @Test
    fun testTaskContextFlags_TriggerDeepMode() {
        val codingTask = TaskContext(query = "Help me", requiresCodingOrDebug = true)
        assertEquals(GeminiModelMode.DEEP, router.selectMode(codingTask))

        val visionTask = TaskContext(query = "Look at this", requiresDeepVisionAnalysis = true)
        assertEquals(GeminiModelMode.DEEP, router.selectMode(visionTask))

        val multiStepTask = TaskContext(query = "Do these actions", isMultiStep = true)
        assertEquals(GeminiModelMode.DEEP, router.selectMode(multiStepTask))
    }

    @Test
    fun testResolveModel_RespectsUserSettings() {
        // When user explicitly sets FAST in settings, it returns FAST unless query has explicit DEEP command
        assertEquals(GeminiLiveModels.FAST, router.resolveModel(GeminiModelMode.FAST, "Any normal question"))
        assertEquals(GeminiLiveModels.DEEP, router.resolveModel(GeminiModelMode.FAST, "Use deep thinking for this"))

        // When user explicitly sets DEEP in settings, it returns DEEP unless query has explicit FAST command
        assertEquals(GeminiLiveModels.DEEP, router.resolveModel(GeminiModelMode.DEEP, "Open Chrome"))
        assertEquals(GeminiLiveModels.FAST, router.resolveModel(GeminiModelMode.DEEP, "Switch to fast mode"))

        // When AUTO is set, task classification decides
        assertEquals(GeminiLiveModels.FAST, router.resolveModel(GeminiModelMode.AUTO, "Open YouTube"))
        assertEquals(GeminiLiveModels.DEEP, router.resolveModel(GeminiModelMode.AUTO, "Analyze this build error and fix it"))
    }
}
