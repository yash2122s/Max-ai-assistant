package com.example.emotion

import com.example.emotion.engine.EmotionDecay
import com.example.emotion.model.EmotionState
import org.junit.Assert.*
import org.junit.Test

class EmotionDecayUnitTest {

    @Test
    fun testZeroElapsedReturnsSameState() {
        val now = 1000000L
        val state = EmotionState.createClamped(frustration = 0.80f, lastUpdatedTimestamp = now)
        val decayed = EmotionDecay.applyDecay(state, fromTimeMs = now, toTimeMs = now)

        assertEquals(0.80f, decayed.frustration, 0.001f)
        assertEquals(now, decayed.lastUpdatedTimestamp)
    }

    @Test
    fun testDecayTowardsBaselineOverOneHalfLife() {
        val t0 = 1000000L
        // 60 minutes later
        val t1 = t0 + (60 * 60 * 1000L)
        // Spike frustration to 0.85 (baseline is 0.05, delta = 0.80)
        val state = EmotionState.createClamped(frustration = 0.85f, lastUpdatedTimestamp = t0)

        val decayed = EmotionDecay.applyDecay(state, fromTimeMs = t0, toTimeMs = t1, halfLifeMinutes = 60.0f)

        // After 1 half-life, delta should halve: 0.05 + 0.80 / 2 = 0.45
        assertEquals(0.45f, decayed.frustration, 0.01f)
    }

    @Test
    fun testLongDecayApproachesBaseline() {
        val t0 = 1000000L
        // 24 hours later
        val t24 = t0 + (24 * 60 * 60 * 1000L)
        val state = EmotionState.createClamped(
            frustration = 0.90f,
            sadness = 0.80f,
            excitement = 0.95f,
            lastUpdatedTimestamp = t0
        )

        val decayed = EmotionDecay.applyDecay(state, fromTimeMs = t0, toTimeMs = t24)

        // All should be extremely close to baseline
        assertEquals(EmotionState.BASELINE_FRUSTRATION, decayed.frustration, 0.01f)
        assertEquals(EmotionState.BASELINE_SADNESS, decayed.sadness, 0.01f)
        assertEquals(EmotionState.BASELINE_EXCITEMENT, decayed.excitement, 0.01f)
    }
}
