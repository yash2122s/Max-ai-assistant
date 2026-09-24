package com.example.network

import com.example.network.model.GeminiLiveModels
import com.example.network.model.GeminiModelMode
import com.example.network.model.TaskContext
import com.example.network.router.DefaultModelRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiDualModelSessionTest {

    private lateinit var router: DefaultModelRouter

    @Before
    fun setUp() {
        router = DefaultModelRouter()
    }

    @Test
    fun testGeminiLiveModels_ConstantsCorrect() {
        assertEquals("gemini-3.8-live", GeminiLiveModels.FAST)
        assertEquals("gemini-3.8-live-extended-thinking", GeminiLiveModels.DEEP)
        assertEquals("gemini-3.1-flash-live-preview", GeminiLiveModels.FALLBACK)
    }

    @Test
    fun testGeminiLiveModels_IsDeepModel() {
        assertTrue(GeminiLiveModels.isDeepModel("gemini-3.8-live-extended-thinking"))
        assertTrue(GeminiLiveModels.isDeepModel("models/gemini-3.8-live-extended-thinking"))
        assertTrue(GeminiLiveModels.isDeepModel("gemini-1.5-pro-extended-thinking"))

        assertFalse(GeminiLiveModels.isDeepModel("gemini-3.8-live"))
        assertFalse(GeminiLiveModels.isDeepModel("models/gemini-3.8-live"))
        assertFalse(GeminiLiveModels.isDeepModel("gemini-3.1-flash-live-preview"))
    }

    @Test
    fun testGeminiLiveModels_ModelForMode() {
        assertEquals("gemini-3.8-live", GeminiLiveModels.modelFor(GeminiModelMode.FAST))
        assertEquals("gemini-3.8-live-extended-thinking", GeminiLiveModels.modelFor(GeminiModelMode.DEEP))
        assertEquals("gemini-3.8-live", GeminiLiveModels.modelFor(GeminiModelMode.AUTO))
    }

    @Test
    fun testGeminiModelMode_FromString() {
        assertEquals(GeminiModelMode.FAST, GeminiModelMode.fromString("FAST"))
        assertEquals(GeminiModelMode.FAST, GeminiModelMode.fromString("fast"))
        assertEquals(GeminiModelMode.DEEP, GeminiModelMode.fromString("DEEP"))
        assertEquals(GeminiModelMode.DEEP, GeminiModelMode.fromString("deep"))
        assertEquals(GeminiModelMode.AUTO, GeminiModelMode.fromString("AUTO"))
        assertEquals(GeminiModelMode.AUTO, GeminiModelMode.fromString("unknown"))
        assertEquals(GeminiModelMode.AUTO, GeminiModelMode.fromString(null))
    }

    @Test
    fun testDualModelRouting_FastTaskExecution() {
        val quickVoiceTurn = TaskContext(query = "Turn on the flashlight")
        val mode = router.selectMode(quickVoiceTurn)
        assertEquals(GeminiModelMode.FAST, mode)
        val model = router.modelFor(mode)
        assertEquals("gemini-3.8-live", model)
    }

    @Test
    fun testDualModelRouting_DeepTaskExecution() {
        val deepVoiceTurn = TaskContext(query = "Open VS Code, debug the gradle build error, and fix it")
        val mode = router.selectMode(deepVoiceTurn)
        assertEquals(GeminiModelMode.DEEP, mode)
        val model = router.modelFor(mode)
        assertEquals("gemini-3.8-live-extended-thinking", model)
    }

    @Test
    fun testDualModelSession_ExplicitModeSwitch() {
        val switchTurn = TaskContext(query = "Switch to deep mode please")
        val override = router.detectExplicitModeOverride(switchTurn.query)
        assertEquals(GeminiModelMode.DEEP, override)

        val resolved = router.resolveModel(GeminiModelMode.AUTO, switchTurn.query, null)
        assertEquals("gemini-3.8-live-extended-thinking", resolved)
    }
}
