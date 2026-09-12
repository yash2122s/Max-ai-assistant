package com.example.automation.habits

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * P1.4 — Foreground habit event recorder.
 *
 * Called from the AccessibilityService's TYPE_WINDOW_STATE_CHANGED callback;
 * ALL work is dispatched to a single background thread so the accessibility
 * callback is never blocked. Events are debounced, noise-filtered and only the
 * minimal package+timestamp payload is stored. Detection is throttled.
 */
object HabitRecorder {
    private const val TAG = "HabitRecorder"
    private const val DETECTION_THROTTLE_MS = 30L * 60 * 1000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "max-habit-recorder").apply { isDaemon = true }
    }

    @Volatile
    private var selfPackage: String? = null

    @Volatile
    private var lastPackage: String? = null

    @Volatile
    private var lastRecordedTs: Long = 0L

    @Volatile
    private var lastDetectionTs: Long = 0L

    fun initialize(context: Context) {
        if (selfPackage == null) {
            selfPackage = runCatching { context.packageName }.getOrNull()
            Log.d(TAG, "Habit recorder initialized (selfPackage=$selfPackage)")
        }
    }

    /**
     * Foreground window transition hook. Safe to call from any thread; heavy
     * work happens on the background executor.
     */
    fun onForegroundWindow(context: Context, packageName: String?) {
        val pkg = packageName ?: return
        val now = System.currentTimeMillis()
        if (pkg.isBlank()) return

        // Debounce + noise checks on the caller thread (cheap, no IO).
        if (!HabitDetector.shouldRecord(lastPackage, now, lastRecordedTs, pkg)) return
        if (HabitDetector.isNoise(pkg, selfPackage)) return

        lastPackage = pkg
        lastRecordedTs = now
        val appContext = context.applicationContext

        executor.execute {
            try {
                val store = HabitEventStore(appContext)
                store.append(pkg, now)
                maybeSuggest(appContext, store)
            } catch (e: Exception) {
                Log.w(TAG, "Habit recording failed: ${e.message}")
            }
        }
    }

    private fun maybeSuggest(context: Context, store: HabitEventStore) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionTs < DETECTION_THROTTLE_MS) return

        val events = store.load()
        if (events.isEmpty()) return
        val distinctDays = events.map { it.timestampMs / 86_400_000L }.distinct()
        if (distinctDays.size < 2) return // need a multi-day signal before ever suggesting

        val candidates = HabitDetector.detect(events, selfPackage)
        val best = candidates.firstOrNull() ?: return
        if (best.occurrenceCount < HabitDetector.MIN_OCCURRENCES) return

        val suggestion = HabitDetector.suggest(best)
        if (HabitSuggestionCoordinator.suggest(context, suggestion, store)) {
            lastDetectionTs = now
            Log.d(TAG, "Emitted habit suggestion: ${suggestion.routineName}")
        } else {
            // Suggestion suppressed (already asked / another pending prompt).
            lastDetectionTs = now
        }
    }
}
