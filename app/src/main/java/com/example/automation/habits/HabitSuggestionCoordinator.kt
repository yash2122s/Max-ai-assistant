package com.example.automation.habits

import android.content.Context
import android.util.Log
import com.example.automation.approval.ApprovalCoordinator
import com.example.voice.assistant.AssistantEvent
import com.example.voice.assistant.AssistantEventBus
import org.json.JSONArray
import org.json.JSONObject

/**
 * P1.4 — Habit suggestion coordinator.
 *
 * Habit discovery may SUGGEST routines; it NEVER activates them silently.
 * Flow:
 *   observed pattern → candidate → confidence check → user suggestion
 *   → user approval → converted to an existing routine (RoutineTool) and
 *   executed through the standard ExecutionEngine path.
 */
object HabitSuggestionCoordinator {
    private const val TAG = "HabitSuggestion"

    @Volatile
    private var pending: HabitSuggestion? = null

    @Volatile
    private var lastContext: Context? = null

    private val RE_SUGGEST_THROTTLE_MS = 24L * 60 * 60 * 1000 // one ask per pattern per day

    fun hasPending(): Boolean = pending != null

    fun currentPending(): HabitSuggestion? = pending

    fun context(): Context? = lastContext

    /**
     * Surfaces a suggestion conversationally (Allow = create routine).
     * Returns false when a suggestion was already shown recently or another
     * suggestion/approval is pending.
     */
    fun suggest(context: Context, suggestion: HabitSuggestion, store: HabitEventStore): Boolean {
        if (pending != null) return false
        val lastShown = store.lastSuggestedAt(suggestion.candidate.key())
        if (lastShown != null && System.currentTimeMillis() - lastShown < RE_SUGGEST_THROTTLE_MS) return false
        if (ApprovalCoordinator.hasPending()) return false

        pending = suggestion
        lastContext = context.applicationContext
        store.markSuggested(suggestion.candidate.key())

        val question = "MAX noticed a habit: ${suggestion.description}"
        Log.d(TAG, "Habit suggestion: $question")
        AssistantEventBus.emit(
            AssistantEvent.NeedsUserInput(
                stepId = "habit_${suggestion.routineName}",
                question = question,
                options = listOf("Create routine", "Cancel")
            )
        )
        return true
    }

    /**
     * Approves the pending suggestion: creates the routine through RoutineTool
     * and runs it once through ExecutionEngine (same path the user's explicit
     * "run routine" command takes). Idempotent CREATE (upsert) semantics.
     */
    suspend fun approveAndRun(context: Context): String {
        val suggestion = pending
        if (suggestion == null) return "No pending habit suggestion."
        pending = null
        lastContext = null

        val stepsJson = JSONArray()
        suggestion.candidate.sequence.forEach { pkg ->
            stepsJson.put(JSONObject().apply {
                put("action", "OPEN_APP")
                put("arguments", JSONObject().put("app_name", HabitDetector.readableLabel(pkg)))
            })
        }

        val createArgs = org.json.JSONObject()
        createArgs.put("routineName", suggestion.routineName)
        createArgs.put("steps_json", stepsJson.toString())

        val routineTool = com.example.automation.tools.RoutineTool()
        val request = com.example.automation.engine.ExecutionRequest(
            action = "CREATE_ROUTINE",
            arguments = com.google.gson.JsonParser.parseString(createArgs.toString()).asJsonObject,
            source = com.example.automation.engine.ExecutionSource.PLANNER
        )
        val createResult = routineTool.execute(context, request)
        Log.d(TAG, "Routine creation result: success=${createResult.success} ${createResult.message}")

        // Run the newly created routine once (explicitly approved by the user).
        val runArgs = org.json.JSONObject()
        runArgs.put("routineName", suggestion.routineName)
        val runRequest = com.example.automation.engine.ExecutionRequest(
            action = "RUN_ROUTINE",
            arguments = com.google.gson.JsonParser.parseString(runArgs.toString()).asJsonObject,
            source = com.example.automation.engine.ExecutionSource.PLANNER
        )
        val runResult = routineTool.execute(context, runRequest)

        return if (createResult.success && runResult.success) {
            "Routine '${suggestion.routineName}' created and started."
        } else if (createResult.success) {
            "Routine '${suggestion.routineName}' created (run reported issues)."
        } else {
            "Could not create routine: ${createResult.message ?: runResult.message ?: "unknown error"}"
        }
    }

    fun cancel() {
        val s = pending
        if (s != null) {
            Log.d(TAG, "Habit suggestion dismissed: ${s.routineName}")
            AssistantEventBus.emit(
                AssistantEvent.TextTranscribed("MAX", "Okay, I won't create that routine.")
            )
        }
        pending = null
        lastContext = null
    }
}
