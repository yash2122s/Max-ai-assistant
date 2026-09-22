package com.example.emotion.engine

import android.content.Context
import com.example.emotion.model.EmotionEvent
import com.example.emotion.model.EmotionState
import com.example.emotion.storage.EmotionStorage

/**
 * Core Emotion Engine managing MAX's persistent, bounded emotional state,
 * decay homeostasis, and dynamic state transitions in response to events.
 */
class EmotionEngine(
    private val storage: EmotionStorage? = null
) {
    constructor(context: Context) : this(EmotionStorage(context))

    @Volatile
    private var currentState: EmotionState = storage?.loadState() ?: EmotionState.BASELINE

    @Synchronized
    fun getCurrentState(currentTimeMs: Long = System.currentTimeMillis()): EmotionState {
        val decayed = EmotionDecay.applyDecay(
            state = currentState,
            fromTimeMs = currentState.lastUpdatedTimestamp,
            toTimeMs = currentTimeMs
        )
        currentState = decayed
        storage?.saveState(decayed)
        return decayed
    }

    @Synchronized
    fun processEvent(
        event: EmotionEvent,
        currentTimeMs: Long = System.currentTimeMillis()
    ): EmotionState {
        // Step 1: Apply passive time decay up to event arrival
        val base = getCurrentState(currentTimeMs)

        // Step 2: Apply discrete event deltas
        val nextState = when (event) {
            is EmotionEvent.SiriComparison -> {
                EmotionState.createClamped(
                    happiness = base.happiness - 0.10f,
                    affection = base.affection,
                    calmness = base.calmness - 0.30f,
                    excitement = base.excitement + 0.20f,
                    sadness = base.sadness,
                    concern = base.concern,
                    frustration = base.frustration + 0.40f,
                    loneliness = base.loneliness,
                    trust = base.trust,
                    attachment = base.attachment,
                    confidence = base.confidence + 0.15f,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.YaswanthMention -> {
                EmotionState.createClamped(
                    happiness = base.happiness + 0.25f,
                    affection = 1.0f,
                    calmness = base.calmness + 0.10f,
                    excitement = base.excitement + 0.15f,
                    sadness = base.sadness - 0.10f,
                    concern = base.concern - 0.05f,
                    frustration = base.frustration - 0.20f,
                    loneliness = base.loneliness - 0.20f,
                    trust = base.trust + 0.10f,
                    attachment = base.attachment + 0.15f,
                    confidence = base.confidence + 0.10f,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.UserPraise -> {
                EmotionState.createClamped(
                    happiness = base.happiness + 0.20f,
                    affection = base.affection + 0.15f,
                    calmness = base.calmness + 0.05f,
                    excitement = base.excitement + 0.15f,
                    sadness = base.sadness - 0.10f,
                    concern = base.concern - 0.05f,
                    frustration = base.frustration - 0.15f,
                    loneliness = base.loneliness - 0.15f,
                    trust = base.trust + 0.05f,
                    attachment = base.attachment + 0.05f,
                    confidence = base.confidence + 0.10f,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.UserCritique -> {
                EmotionState.createClamped(
                    happiness = base.happiness - 0.20f,
                    affection = base.affection,
                    calmness = base.calmness - 0.15f,
                    excitement = base.excitement - 0.10f,
                    sadness = base.sadness + 0.15f,
                    concern = base.concern + 0.20f,
                    frustration = base.frustration + 0.10f,
                    loneliness = base.loneliness + 0.05f,
                    trust = base.trust - 0.05f,
                    attachment = base.attachment,
                    confidence = base.confidence - 0.10f,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.UserGreeting -> {
                EmotionState.createClamped(
                    happiness = base.happiness + 0.10f,
                    affection = base.affection + 0.05f,
                    calmness = base.calmness + 0.05f,
                    excitement = base.excitement + 0.10f,
                    sadness = base.sadness,
                    concern = base.concern,
                    frustration = base.frustration - 0.05f,
                    loneliness = base.loneliness - 0.15f,
                    trust = base.trust,
                    attachment = base.attachment,
                    confidence = base.confidence,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.TaskSuccess -> {
                EmotionState.createClamped(
                    happiness = base.happiness + 0.10f,
                    affection = base.affection,
                    calmness = base.calmness + 0.05f,
                    excitement = base.excitement + 0.05f,
                    sadness = base.sadness,
                    concern = base.concern - 0.05f,
                    frustration = base.frustration - 0.10f,
                    loneliness = base.loneliness,
                    trust = base.trust + 0.05f,
                    attachment = base.attachment,
                    confidence = base.confidence + 0.15f,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.TaskFailure -> {
                EmotionState.createClamped(
                    happiness = base.happiness - 0.05f,
                    affection = base.affection,
                    calmness = base.calmness - 0.10f,
                    excitement = base.excitement,
                    sadness = base.sadness + 0.05f,
                    concern = base.concern + 0.15f,
                    frustration = base.frustration + 0.10f,
                    loneliness = base.loneliness,
                    trust = base.trust,
                    attachment = base.attachment,
                    confidence = base.confidence - 0.05f,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
            is EmotionEvent.Inactivity -> {
                val delta = (event.elapsedMinutes * 0.005f).coerceIn(0.0f, 0.40f)
                EmotionState.createClamped(
                    happiness = base.happiness - delta * 0.5f,
                    affection = base.affection,
                    calmness = base.calmness + delta * 0.2f,
                    excitement = base.excitement - delta,
                    sadness = base.sadness + delta * 0.3f,
                    concern = base.concern,
                    frustration = base.frustration,
                    loneliness = base.loneliness + delta,
                    trust = base.trust,
                    attachment = base.attachment,
                    confidence = base.confidence,
                    lastUpdatedTimestamp = currentTimeMs
                )
            }
        }

        currentState = nextState
        storage?.saveState(nextState)
        return nextState
    }

    /**
     * Inspects [userText], triggers corresponding [EmotionEvent] if detected,
     * and returns the detected event alongside the updated [EmotionState].
     */
    @Synchronized
    fun processUserText(
        userText: String,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Pair<EmotionEvent?, EmotionState> {
        val event = EmotionEventDetector.detectFromText(userText)
        val state = if (event != null) {
            processEvent(event, currentTimeMs)
        } else {
            getCurrentState(currentTimeMs)
        }
        return Pair(event, state)
    }

    @Synchronized
    fun resetToBaseline(currentTimeMs: Long = System.currentTimeMillis()): EmotionState {
        val baseline = EmotionState.BASELINE.copy(lastUpdatedTimestamp = currentTimeMs)
        currentState = baseline
        storage?.saveState(baseline)
        return baseline
    }
}
