package com.example.emotion.engine

import com.example.emotion.model.EmotionState
import kotlin.math.pow

/**
 * Calculates emotional homeostasis and exponential decay back toward baseline states over time.
 */
object EmotionDecay {

    const val DEFAULT_HALF_LIFE_MINUTES = 60.0f
    const val STABLE_HALF_LIFE_MINUTES = 180.0f

    /**
     * Applies time decay from [fromTimeMs] to [toTimeMs] on the given [state].
     */
    fun applyDecay(
        state: EmotionState,
        fromTimeMs: Long = state.lastUpdatedTimestamp,
        toTimeMs: Long = System.currentTimeMillis(),
        halfLifeMinutes: Float = DEFAULT_HALF_LIFE_MINUTES
    ): EmotionState {
        val elapsedMs = toTimeMs - fromTimeMs
        if (elapsedMs <= 0L) {
            return state
        }

        val elapsedMinutes = elapsedMs / (1000.0f * 60.0f)
        val standardFactor = 2.0.pow((-elapsedMinutes / halfLifeMinutes).toDouble()).toFloat()
        val stableFactor = 2.0.pow((-elapsedMinutes / STABLE_HALF_LIFE_MINUTES).toDouble()).toFloat()

        fun decayDim(current: Float, baseline: Float, factor: Float): Float {
            return baseline + (current - baseline) * factor
        }

        return EmotionState.createClamped(
            happiness = decayDim(state.happiness, EmotionState.BASELINE_HAPPINESS, standardFactor),
            affection = decayDim(state.affection, EmotionState.BASELINE_AFFECTION, stableFactor),
            calmness = decayDim(state.calmness, EmotionState.BASELINE_CALMNESS, standardFactor),
            excitement = decayDim(state.excitement, EmotionState.BASELINE_EXCITEMENT, standardFactor),
            sadness = decayDim(state.sadness, EmotionState.BASELINE_SADNESS, standardFactor),
            concern = decayDim(state.concern, EmotionState.BASELINE_CONCERN, standardFactor),
            frustration = decayDim(state.frustration, EmotionState.BASELINE_FRUSTRATION, standardFactor),
            loneliness = decayDim(state.loneliness, EmotionState.BASELINE_LONELINESS, standardFactor),
            trust = decayDim(state.trust, EmotionState.BASELINE_TRUST, stableFactor),
            attachment = decayDim(state.attachment, EmotionState.BASELINE_ATTACHMENT, stableFactor),
            confidence = decayDim(state.confidence, EmotionState.BASELINE_CONFIDENCE, stableFactor),
            lastUpdatedTimestamp = toTimeMs
        )
    }
}
