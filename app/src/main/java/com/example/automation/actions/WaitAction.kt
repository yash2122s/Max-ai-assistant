package com.example.automation.actions

import android.content.Context
import android.util.Log
import com.example.automation.engine.CancellationToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * First-class time-based wait action.
 * Supports cancellable chunked delays and live countdown updates.
 */
class WaitAction : BaseAction<Long>() {
    override fun execute(context: Context, payload: Long) {
        log("Synchronous execute() called with $payload ms")
    }

    suspend fun executeWait(
        durationMs: Long,
        cancellationToken: CancellationToken? = null,
        onProgress: ((elapsedMs: Long, remainingMs: Long) -> Unit)? = null
    ): Boolean {
        log("Executing wait for ${durationMs}ms")
        if (durationMs <= 0L) return true

        var remainingMs = durationMs
        var elapsedMs = 0L
        val tickIntervalMs = 100L

        while (remainingMs > 0L) {
            currentCoroutineContext().ensureActive()
            if (cancellationToken?.isCancelled == true) {
                log("Wait cancelled via CancellationToken after ${elapsedMs}ms")
                return false
            }
            val sleepChunk = if (remainingMs > tickIntervalMs) tickIntervalMs else remainingMs
            delay(sleepChunk)
            elapsedMs += sleepChunk
            remainingMs -= sleepChunk
            onProgress?.invoke(elapsedMs, remainingMs)
        }
        return true
    }

    companion object {
        fun parseDurationMs(raw: String?, defaultMs: Long = 5000L): Long {
            if (raw.isNullOrBlank()) return defaultMs
            val trimmed = raw.trim().lowercase()

            // 1. Check pure milliseconds "100ms", "5000ms" FIRST so "m" doesn't shadow "ms"
            val msMatch = Regex("""(\d+)\s*(?:ms|millis|milliseconds)""").find(trimmed)
            if (msMatch != null) {
                return msMatch.groupValues[1].toLongOrNull() ?: defaultMs
            }

            // 2. Check if pattern like "10 seconds" or "10s" or "10 sec" or Telugu "10 సెకన్లు"
            val secMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:s|sec|secs|second|seconds|సెకన్లు|సెకండ్లు)""").find(trimmed)
            if (secMatch != null) {
                val num = secMatch.groupValues[1].toDoubleOrNull() ?: 5.0
                return (num * 1000.0).toLong()
            }

            // 3. Check minutes "2 min" or "2m" or Telugu "నిమిషాలు" (negative lookahead ensures "m" doesn't match "ms")
            val minMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:min|mins|minute|minutes|నిమిషాలు|m(?!s))""").find(trimmed)
            if (minMatch != null) {
                val num = minMatch.groupValues[1].toDoubleOrNull() ?: 1.0
                return (num * 60.0 * 1000.0).toLong()
            }

            // Plain number
            val num = trimmed.toLongOrNull()
            if (num != null) {
                // If number is <= 120, user almost certainly meant seconds (e.g. "10" or "5")
                return if (num <= 120L) num * 1000L else num
            }

            return defaultMs
        }
    }
}
