package com.example.automation.habits

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * P1.4 — Rolling 14-day store of foreground habit events.
 *
 * Purpose-built local storage (SharedPreferences JSON, matching the app's
 * routine persistence style) so no Room schema/migration is required. Only
 * package + timestamp pairs are persisted — nothing else.
 */
class HabitEventStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("max_habit_events", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_EVENTS = "foreground_events"
        const val KEY_SUGGESTED = "suggested_patterns" // JSON map patternKey -> lastSuggestedMs
        const val MAX_EVENTS = 6000
        val WINDOW_MS = HabitDetector.OBSERVATION_WINDOW_MS
        private const val TAG = "HabitEventStore"
    }

    @Synchronized
    fun append(packageName: String, timestampMs: Long = System.currentTimeMillis()) {
        val events = load()
        events.add(ForegroundEvent(packageName = packageName, timestampMs = timestampMs))
        persist(events)
    }

    @Synchronized
    fun load(): MutableList<ForegroundEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return mutableListOf()
        val list = mutableListOf<ForegroundEvent>()
        return try {
            val array = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val ts = obj.optLong("ts", 0L)
                if (ts >= now - WINDOW_MS) { // prune old entries while loading
                    list.add(ForegroundEvent(packageName = obj.optString("pkg", ""), timestampMs = ts))
                }
            }
            list.sortedBy { it.timestampMs }.toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse habit events; starting fresh", e)
            mutableListOf()
        }
    }

    @Synchronized
    private fun persist(events: List<ForegroundEvent>) {
        val array = JSONArray()
        events.takeLast(MAX_EVENTS).forEach { event ->
            array.put(JSONObject().apply {
                put("pkg", event.packageName)
                put("ts", event.timestampMs)
            })
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
    }

    /** Timestamp (ms) the pattern was last suggested to the user, or null. */
    @Synchronized
    fun lastSuggestedAt(patternKey: String): Long? {
        val raw = prefs.getString(KEY_SUGGESTED, null) ?: return null
        return try {
            JSONObject(raw).optLong(patternKey, -1L).takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun markSuggested(patternKey: String, atMs: Long = System.currentTimeMillis()) {
        val raw = prefs.getString(KEY_SUGGESTED, null) ?: "{}"
        try {
            val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            json.put(patternKey, atMs)
            prefs.edit().putString(KEY_SUGGESTED, json.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark pattern as suggested", e)
        }
    }
}
