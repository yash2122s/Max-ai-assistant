package com.example.emotion.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.emotion.model.EmotionState

/**
 * Thread-safe persistent storage for MAX's [EmotionState].
 * Backed by lightweight Android SharedPreferences with in-memory caching.
 */
class EmotionStorage(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedState: EmotionState? = null

    companion object {
        private const val PREFS_NAME = "max_emotion_prefs"
        private const val KEY_HAPPINESS = "emotion_happiness"
        private const val KEY_AFFECTION = "emotion_affection"
        private const val KEY_CALMNESS = "emotion_calmness"
        private const val KEY_EXCITEMENT = "emotion_excitement"
        private const val KEY_SADNESS = "emotion_sadness"
        private const val KEY_CONCERN = "emotion_concern"
        private const val KEY_FRUSTRATION = "emotion_frustration"
        private const val KEY_LONELINESS = "emotion_loneliness"
        private const val KEY_TRUST = "emotion_trust"
        private const val KEY_ATTACHMENT = "emotion_attachment"
        private const val KEY_CONFIDENCE = "emotion_confidence"
        private const val KEY_TIMESTAMP = "emotion_timestamp"
    }

    @Synchronized
    fun loadState(): EmotionState {
        cachedState?.let { return it }

        if (!prefs.contains(KEY_TIMESTAMP)) {
            val defaultState = EmotionState.BASELINE
            cachedState = defaultState
            return defaultState
        }

        val state = EmotionState.createClamped(
            happiness = prefs.getFloat(KEY_HAPPINESS, EmotionState.BASELINE_HAPPINESS),
            affection = prefs.getFloat(KEY_AFFECTION, EmotionState.BASELINE_AFFECTION),
            calmness = prefs.getFloat(KEY_CALMNESS, EmotionState.BASELINE_CALMNESS),
            excitement = prefs.getFloat(KEY_EXCITEMENT, EmotionState.BASELINE_EXCITEMENT),
            sadness = prefs.getFloat(KEY_SADNESS, EmotionState.BASELINE_SADNESS),
            concern = prefs.getFloat(KEY_CONCERN, EmotionState.BASELINE_CONCERN),
            frustration = prefs.getFloat(KEY_FRUSTRATION, EmotionState.BASELINE_FRUSTRATION),
            loneliness = prefs.getFloat(KEY_LONELINESS, EmotionState.BASELINE_LONELINESS),
            trust = prefs.getFloat(KEY_TRUST, EmotionState.BASELINE_TRUST),
            attachment = prefs.getFloat(KEY_ATTACHMENT, EmotionState.BASELINE_ATTACHMENT),
            confidence = prefs.getFloat(KEY_CONFIDENCE, EmotionState.BASELINE_CONFIDENCE),
            lastUpdatedTimestamp = prefs.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        )

        cachedState = state
        return state
    }

    @Synchronized
    fun saveState(state: EmotionState) {
        cachedState = state
        prefs.edit()
            .putFloat(KEY_HAPPINESS, state.happiness)
            .putFloat(KEY_AFFECTION, state.affection)
            .putFloat(KEY_CALMNESS, state.calmness)
            .putFloat(KEY_EXCITEMENT, state.excitement)
            .putFloat(KEY_SADNESS, state.sadness)
            .putFloat(KEY_CONCERN, state.concern)
            .putFloat(KEY_FRUSTRATION, state.frustration)
            .putFloat(KEY_LONELINESS, state.loneliness)
            .putFloat(KEY_TRUST, state.trust)
            .putFloat(KEY_ATTACHMENT, state.attachment)
            .putFloat(KEY_CONFIDENCE, state.confidence)
            .putLong(KEY_TIMESTAMP, state.lastUpdatedTimestamp)
            .apply()
    }

    @Synchronized
    fun clear() {
        cachedState = null
        prefs.edit().clear().apply()
    }
}
