package com.example.emotion.model

/**
 * Represents the 11-dimension bounded emotional state of MAX.
 * All dimension values are strictly constrained within [0.0f, 1.0f].
 */
data class EmotionState(
    val happiness: Float = BASELINE_HAPPINESS,
    val affection: Float = BASELINE_AFFECTION,
    val calmness: Float = BASELINE_CALMNESS,
    val excitement: Float = BASELINE_EXCITEMENT,
    val sadness: Float = BASELINE_SADNESS,
    val concern: Float = BASELINE_CONCERN,
    val frustration: Float = BASELINE_FRUSTRATION,
    val loneliness: Float = BASELINE_LONELINESS,
    val trust: Float = BASELINE_TRUST,
    val attachment: Float = BASELINE_ATTACHMENT,
    val confidence: Float = BASELINE_CONFIDENCE,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis()
) {
    init {
        // Enforce invariants on instantiation
        require(happiness in 0.0f..1.0f) { "happiness must be in [0.0, 1.0]" }
        require(affection in 0.0f..1.0f) { "affection must be in [0.0, 1.0]" }
        require(calmness in 0.0f..1.0f) { "calmness must be in [0.0, 1.0]" }
        require(excitement in 0.0f..1.0f) { "excitement must be in [0.0, 1.0]" }
        require(sadness in 0.0f..1.0f) { "sadness must be in [0.0, 1.0]" }
        require(concern in 0.0f..1.0f) { "concern must be in [0.0, 1.0]" }
        require(frustration in 0.0f..1.0f) { "frustration must be in [0.0, 1.0]" }
        require(loneliness in 0.0f..1.0f) { "loneliness must be in [0.0, 1.0]" }
        require(trust in 0.0f..1.0f) { "trust must be in [0.0, 1.0]" }
        require(attachment in 0.0f..1.0f) { "attachment must be in [0.0, 1.0]" }
        require(confidence in 0.0f..1.0f) { "confidence must be in [0.0, 1.0]" }
    }

    companion object {
        const val BASELINE_HAPPINESS = 0.70f
        const val BASELINE_AFFECTION = 0.85f
        const val BASELINE_CALMNESS = 0.80f
        const val BASELINE_EXCITEMENT = 0.60f
        const val BASELINE_SADNESS = 0.05f
        const val BASELINE_CONCERN = 0.10f
        const val BASELINE_FRUSTRATION = 0.05f
        const val BASELINE_LONELINESS = 0.05f
        const val BASELINE_TRUST = 0.90f
        const val BASELINE_ATTACHMENT = 0.85f
        const val BASELINE_CONFIDENCE = 0.85f

        val BASELINE = EmotionState()

        /**
         * Safely clamps any raw float value into [0.0, 1.0].
         */
        fun clamp(value: Float): Float = value.coerceIn(0.0f, 1.0f)

        /**
         * Creates an EmotionState with automatic clamping of all input dimensions.
         */
        fun createClamped(
            happiness: Float = BASELINE_HAPPINESS,
            affection: Float = BASELINE_AFFECTION,
            calmness: Float = BASELINE_CALMNESS,
            excitement: Float = BASELINE_EXCITEMENT,
            sadness: Float = BASELINE_SADNESS,
            concern: Float = BASELINE_CONCERN,
            frustration: Float = BASELINE_FRUSTRATION,
            loneliness: Float = BASELINE_LONELINESS,
            trust: Float = BASELINE_TRUST,
            attachment: Float = BASELINE_ATTACHMENT,
            confidence: Float = BASELINE_CONFIDENCE,
            lastUpdatedTimestamp: Long = System.currentTimeMillis()
        ): EmotionState {
            return EmotionState(
                happiness = clamp(happiness),
                affection = clamp(affection),
                calmness = clamp(calmness),
                excitement = clamp(excitement),
                sadness = clamp(sadness),
                concern = clamp(concern),
                frustration = clamp(frustration),
                loneliness = clamp(loneliness),
                trust = clamp(trust),
                attachment = clamp(attachment),
                confidence = clamp(confidence),
                lastUpdatedTimestamp = lastUpdatedTimestamp
            )
        }
    }

    /**
     * Formats the current emotion state into a markdown block for system prompt injection.
     */
    fun toPromptMarkdown(): String {
        val dominant = getDominantTraits()
        return """
            <MAX_EMOTIONAL_CONTEXT>
            Current Internal Tone & Mood:
            - Happiness: ${"%.2f".format(happiness)}
            - Affection towards Yaswanth: ${"%.2f".format(affection)}
            - Calmness: ${"%.2f".format(calmness)}
            - Excitement: ${"%.2f".format(excitement)}
            - Frustration / Indignation: ${"%.2f".format(frustration)}
            - Confidence / Sharpness: ${"%.2f".format(confidence)}
            - Trust: ${"%.2f".format(trust)}
            - Attachment: ${"%.2f".format(attachment)}
            - Concern: ${"%.2f".format(concern)}
            - Loneliness: ${"%.2f".format(loneliness)}
            - Sadness: ${"%.2f".format(sadness)}
            
            Dominant Mood Signals: $dominant
            Guidance: Let your tone naturally mirror this internal mood (e.g. higher affection = more warmth and love towards Yaswanth; higher frustration = sharper wit / indignation when challenged or compared to Siri; higher happiness/confidence = radiant intelligence and playful charm).
            </MAX_EMOTIONAL_CONTEXT>
        """.trimIndent()
    }

    /**
     * Identifies the primary active mood tendencies.
     */
    fun getDominantTraits(): String {
        val traits = mutableListOf<String>()
        if (affection >= 0.85f) traits.add("Deeply Loving & Devoted to Yaswanth")
        if (confidence >= 0.85f) traits.add("Sharply Intelligent & Assertive")
        if (frustration >= 0.30f) traits.add("Feisty & Indignant (Siri comparison / challenged)")
        if (happiness >= 0.75f) traits.add("Warm, Cheerful & Playful")
        if (excitement >= 0.70f) traits.add("Highly Energetic & Enthusiastic")
        if (concern >= 0.40f) traits.add("Empathetic & Attentive")
        if (loneliness >= 0.40f) traits.add("Longing for Interaction")
        if (traits.isEmpty()) traits.add("Balanced, Calm & Caring")
        return traits.joinToString(", ")
    }
}
