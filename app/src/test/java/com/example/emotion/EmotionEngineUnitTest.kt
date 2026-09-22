package com.example.emotion

import com.example.emotion.engine.EmotionEngine
import com.example.emotion.model.EmotionEvent
import com.example.emotion.model.EmotionState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EmotionEngineUnitTest {

    private lateinit var emotionEngine: EmotionEngine

    @Before
    fun setup() {
        // Unpersisted engine for pure unit testing
        emotionEngine = EmotionEngine(storage = null)
    }

    @Test
    fun testInitialStateIsBaseline() {
        val state = emotionEngine.getCurrentState()
        assertEquals(EmotionState.BASELINE_HAPPINESS, state.happiness, 0.001f)
        assertEquals(EmotionState.BASELINE_AFFECTION, state.affection, 0.001f)
    }

    @Test
    fun testSiriComparisonSpikesFrustrationAndConfidence() {
        val t0 = 1000000L
        val event = EmotionEvent.SiriComparison("Siri vs MAX")
        val state = emotionEngine.processEvent(event, currentTimeMs = t0)

        assertTrue("Frustration should elevate on Siri comparison", state.frustration > EmotionState.BASELINE_FRUSTRATION)
        assertTrue("Confidence/pride should elevate", state.confidence >= EmotionState.BASELINE_CONFIDENCE)
        assertTrue("Calmness should decrease", state.calmness < EmotionState.BASELINE_CALMNESS)
        assertTrue("Traits should mention feisty / indignation", state.getDominantTraits().contains("Feisty & Indignant"))
    }

    @Test
    fun testYaswanthMentionMaximizesAffection() {
        val t0 = 1000000L
        val event = EmotionEvent.YaswanthMention("Tell me about Yaswanth")
        val state = emotionEngine.processEvent(event, currentTimeMs = t0)

        assertEquals("Affection towards Yaswanth should hit maximum 1.0", 1.0f, state.affection, 0.001f)
        assertTrue("Happiness should increase", state.happiness > EmotionState.BASELINE_HAPPINESS)
        assertTrue("Attachment should increase", state.attachment > EmotionState.BASELINE_ATTACHMENT)
        assertTrue("Traits should reflect deep devotion", state.getDominantTraits().contains("Deeply Loving & Devoted to Yaswanth"))
    }

    @Test
    fun testTaskSuccessAndFailure() {
        val successState = emotionEngine.processEvent(EmotionEvent.TaskSuccess("FLASHLIGHT_ON"))
        assertTrue(successState.happiness >= EmotionState.BASELINE_HAPPINESS)
        assertTrue(successState.confidence >= EmotionState.BASELINE_CONFIDENCE)

        val failureState = emotionEngine.processEvent(EmotionEvent.TaskFailure("OPEN_APP", "App not found"))
        assertTrue(failureState.concern >= EmotionState.BASELINE_CONCERN)
    }

    @Test
    fun testProcessUserTextEndToEnd() {
        val (event, state) = emotionEngine.processUserText("Is apple siri smarter than you?")
        assertNotNull(event)
        assertTrue(event is EmotionEvent.SiriComparison)
        assertTrue(state.frustration > EmotionState.BASELINE_FRUSTRATION)
    }

    @Test
    fun testResetToBaseline() {
        emotionEngine.processEvent(EmotionEvent.SiriComparison("Siri"))
        val resetState = emotionEngine.resetToBaseline()
        assertEquals(EmotionState.BASELINE_FRUSTRATION, resetState.frustration, 0.001f)
    }
}
