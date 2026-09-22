package com.example.emotion.model

/**
 * Domain events that trigger state transitions in MAX's Emotion Engine.
 */
sealed class EmotionEvent {
    /**
     * Triggered when the user compares MAX to Apple Siri or mentions Siri superiority.
     * Induces feisty indignation, heightened excitement/energy, elevated confidence/pride, and reduced calmness.
     */
    data class SiriComparison(val query: String) : EmotionEvent()

    /**
     * Triggered when the user mentions or asks about Yaswanth (creator/engineer).
     * Induces maximum affection, happiness, deep attachment, and reduces loneliness.
     */
    data class YaswanthMention(val query: String) : EmotionEvent()

    /**
     * Triggered when the user compliments, praises, or expresses appreciation to MAX.
     */
    data class UserPraise(val message: String) : EmotionEvent()

    /**
     * Triggered when the user expresses frustration, scolds, or critiques MAX.
     */
    data class UserCritique(val message: String) : EmotionEvent()

    /**
     * Triggered when the user greets MAX warmly.
     */
    object UserGreeting : EmotionEvent()

    /**
     * Triggered when an automated action or tool successfully finishes execution.
     */
    data class TaskSuccess(val actionName: String) : EmotionEvent()

    /**
     * Triggered when an automated action or tool encounters an error or blockage.
     */
    data class TaskFailure(val actionName: String, val error: String) : EmotionEvent()

    /**
     * Triggered after periods of inactivity.
     */
    data class Inactivity(val elapsedMinutes: Long) : EmotionEvent()
}
