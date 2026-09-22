package com.example.emotion

import com.example.emotion.model.EmotionState
import org.junit.Assert.*
import org.junit.Test

class EmotionStateUnitTest {

    @Test
    fun testBaselineValues() {
        val state = EmotionState.BASELINE
        assertEquals(0.70f, state.happiness, 0.001f)
        assertEquals(0.85f, state.affection, 0.001f)
        assertEquals(0.80f, state.calmness, 0.001f)
        assertEquals(0.60f, state.excitement, 0.001f)
        assertEquals(0.05f, state.sadness, 0.001f)
        assertEquals(0.10f, state.concern, 0.001f)
        assertEquals(0.05f, state.frustration, 0.001f)
        assertEquals(0.05f, state.loneliness, 0.001f)
        assertEquals(0.90f, state.trust, 0.001f)
        assertEquals(0.85f, state.attachment, 0.001f)
        assertEquals(0.85f, state.confidence, 0.001f)
    }

    @Test
    fun testCreateClampedEnforcesBounds() {
        val clampedOver = EmotionState.createClamped(
            happiness = 1.8f,
            affection = 2.5f,
            calmness = 5.0f,
            excitement = 1.1f,
            sadness = 1.05f,
            concern = 3.0f,
            frustration = 9.9f,
            loneliness = 1.5f,
            trust = 2.0f,
            attachment = 1.2f,
            confidence = 10.0f
        )
        assertEquals(1.0f, clampedOver.happiness, 0.001f)
        assertEquals(1.0f, clampedOver.affection, 0.001f)
        assertEquals(1.0f, clampedOver.calmness, 0.001f)
        assertEquals(1.0f, clampedOver.excitement, 0.001f)
        assertEquals(1.0f, clampedOver.sadness, 0.001f)
        assertEquals(1.0f, clampedOver.concern, 0.001f)
        assertEquals(1.0f, clampedOver.frustration, 0.001f)
        assertEquals(1.0f, clampedOver.loneliness, 0.001f)
        assertEquals(1.0f, clampedOver.trust, 0.001f)
        assertEquals(1.0f, clampedOver.attachment, 0.001f)
        assertEquals(1.0f, clampedOver.confidence, 0.001f)

        val clampedUnder = EmotionState.createClamped(
            happiness = -0.5f,
            affection = -1.0f,
            calmness = -0.2f,
            excitement = -0.1f,
            sadness = -0.05f,
            concern = -0.10f,
            frustration = -0.9f,
            loneliness = -0.5f,
            trust = -2.0f,
            attachment = -1.2f,
            confidence = -10.0f
        )
        assertEquals(0.0f, clampedUnder.happiness, 0.001f)
        assertEquals(0.0f, clampedUnder.affection, 0.001f)
        assertEquals(0.0f, clampedUnder.calmness, 0.001f)
        assertEquals(0.0f, clampedUnder.excitement, 0.001f)
        assertEquals(0.0f, clampedUnder.sadness, 0.001f)
        assertEquals(0.0f, clampedUnder.concern, 0.001f)
        assertEquals(0.0f, clampedUnder.frustration, 0.001f)
        assertEquals(0.0f, clampedUnder.loneliness, 0.001f)
        assertEquals(0.0f, clampedUnder.trust, 0.001f)
        assertEquals(0.0f, clampedUnder.attachment, 0.001f)
        assertEquals(0.0f, clampedUnder.confidence, 0.001f)
    }

    @Test
    fun testPromptMarkdownFormatting() {
        val state = EmotionState.BASELINE
        val markdown = state.toPromptMarkdown()

        assertTrue(markdown.contains("<MAX_EMOTIONAL_CONTEXT>"))
        assertTrue(markdown.contains("Happiness:"))
        assertTrue(markdown.contains("Affection towards Yaswanth:"))
        assertTrue(markdown.contains("Frustration / Indignation:"))
        assertTrue(markdown.contains("Confidence / Sharpness:"))
        assertTrue(markdown.contains("Dominant Mood Signals:"))
        assertTrue(markdown.contains("</MAX_EMOTIONAL_CONTEXT>"))
    }

    @Test
    fun testDominantTraitsDetection() {
        val lovingState = EmotionState.createClamped(affection = 0.95f, confidence = 0.90f)
        val traits = lovingState.getDominantTraits()
        assertTrue(traits.contains("Deeply Loving & Devoted to Yaswanth"))
        assertTrue(traits.contains("Sharply Intelligent & Assertive"))

        val feistyState = EmotionState.createClamped(frustration = 0.60f)
        val feistyTraits = feistyState.getDominantTraits()
        assertTrue(feistyTraits.contains("Feisty & Indignant"))
    }
}
