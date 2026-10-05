package com.example.network.router

import android.util.Log
import com.example.network.model.GeminiLiveModels
import com.example.network.model.GeminiModelMode
import com.example.network.model.TaskContext
import java.util.Locale

interface ModelRouter {
    /**
     * Determines whether FAST or DEEP model mode is required based on task context and heuristics.
     */
    fun selectMode(task: TaskContext): GeminiModelMode

    /**
     * Returns the exact model name string for the requested mode.
     */
    fun modelFor(mode: GeminiModelMode): String

    /**
     * Resolves the target model given the user's preferred routing mode (AUTO, FAST, DEEP) and query/context.
     */
    fun resolveModel(userPreferredMode: GeminiModelMode, query: String, context: TaskContext? = null): String

    /**
     * Inspects a user query for explicit mode override commands (e.g., "Use deep thinking", "Switch to fast mode").
     */
    fun detectExplicitModeOverride(query: String): GeminiModelMode?
}

/**
 * Production implementation of ModelRouter that combines explicit overrides,
 * user settings preferences, and lightweight task classification heuristics.
 */
class DefaultModelRouter : ModelRouter {

    companion object {
        private const val TAG = "ModelRouter"

        private val EXPLICIT_FAST_PHRASES = listOf(
            "turn off deep mode",
            "turn off deep thinking",
            "disable deep mode",
            "disable deep thinking",
            "switch to fast mode",
            "use fast mode",
            "fast mode",
            "be quick",
            "quick mode"
        )

        private val EXPLICIT_DEEP_PHRASES = listOf(
            "use deep thinking",
            "enable deep thinking",
            "switch to deep mode",
            "enable deep mode",
            "deep thinking",
            "think deeply",
            "deep analysis",
            "thoroughly analyze",
            "deep think",
            "deep mode"
        )

        private val DEEP_INTENT_PATTERNS = listOf(
            Regex("(?i).*\\b(analyze|inspect|debug|fix)\\b.*\\b(project|code|repo|build|error|failure|crash|bug|stacktrace)\\b.*"),
            Regex("(?i).*\\bwhy\\b.*\\b(failing|broken|crashing|not working|failed)\\b.*"),
            Regex("(?i).*\\bplan and execute\\b.*"),
            Regex("(?i).*\\bmulti-step\\b.*"),
            Regex("(?i).*\\blook at this screen and figure out\\b.*"),
            Regex("(?i).*\\bfind (what's wrong|the bug|why it's failing)\\b.*"),
            Regex("(?i).*\\b(compile error|unresolved reference|nullpointerexception)\\b.*")
        )
    }

    override fun detectExplicitModeOverride(query: String): GeminiModelMode? {
        val lower = query.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return null

        // Check FAST / negative deep phrases first so "turn off deep mode" isn't swallowed by "deep mode"
        for (phrase in EXPLICIT_FAST_PHRASES) {
            if (lower.contains(phrase)) {
                Log.d(TAG, "Explicit FAST mode command detected in query: '$query'")
                return GeminiModelMode.FAST
            }
        }

        for (phrase in EXPLICIT_DEEP_PHRASES) {
            if (lower.contains(phrase)) {
                Log.d(TAG, "Explicit DEEP mode command detected in query: '$query'")
                return GeminiModelMode.DEEP
            }
        }

        return null
    }

    override fun selectMode(task: TaskContext): GeminiModelMode {
        // 1. Explicit voice override takes highest precedence
        task.explicitModeOverride?.let { return it }

        val detectedOverride = detectExplicitModeOverride(task.query)
        if (detectedOverride != null) {
            return detectedOverride
        }

        // 2. Structured Task Context flags
        if (task.requiresCodingOrDebug || task.requiresDeepVisionAnalysis || task.isMultiStep) {
            Log.d(TAG, "TaskContext flags triggered DEEP mode (coding=${task.requiresCodingOrDebug}, vision=${task.requiresDeepVisionAnalysis}, multiStep=${task.isMultiStep})")
            return GeminiModelMode.DEEP
        }

        // 3. Heuristic Query Classification
        val queryLower = task.query.trim().lowercase(Locale.ROOT)
        if (queryLower.isNotBlank()) {
            val matchesDeep = DEEP_INTENT_PATTERNS.any { it.containsMatchIn(queryLower) }
            if (matchesDeep) {
                Log.d(TAG, "Query pattern triggered DEEP mode: '${task.query.take(60)}...'")
                return GeminiModelMode.DEEP
            }
        }

        // gemini-3.8-live (FAST) is the primary real-time conversational model. DEEP is used when complex coding/debug/planning is requested.
        return GeminiModelMode.FAST
    }

    override fun modelFor(mode: GeminiModelMode): String {
        return GeminiLiveModels.modelFor(mode)
    }

    override fun resolveModel(userPreferredMode: GeminiModelMode, query: String, context: TaskContext?): String {
        // Explicit voice commands in query override static setting
        val voiceOverride = detectExplicitModeOverride(query)
        if (voiceOverride != null) {
            return modelFor(voiceOverride)
        }

        return when (userPreferredMode) {
            GeminiModelMode.FAST -> GeminiLiveModels.FAST
            GeminiModelMode.DEEP -> GeminiLiveModels.DEEP
            GeminiModelMode.AUTO -> {
                val taskCtx = context ?: TaskContext(query = query)
                val selected = selectMode(taskCtx)
                modelFor(selected)
            }
        }
    }
}
