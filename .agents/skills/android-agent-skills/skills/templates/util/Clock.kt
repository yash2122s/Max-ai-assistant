package com.agentcore.util

import kotlin.time.Duration

/**
 * Abstraction for time-related operations.
 * Enables testing with controlled time.
 * 
 * Compatible with kotlinx.datetime patterns while remaining
 * pure Kotlin (no Android dependencies).
 */
interface Clock {
    /**
     * Returns the current time in milliseconds since epoch.
     */
    fun currentTimeMillis(): Long
    
    /**
     * Returns the current time in nanoseconds for high-precision measurements.
     */
    fun nanoTime(): Long
    
    /**
     * Returns the current instant as an epoch timestamp.
     * For kotlinx.datetime integration, convert using:
     * `Instant.fromEpochMilliseconds(clock.epochMillis())`
     */
    fun epochMillis(): Long = currentTimeMillis()
    
    /**
     * Measures the execution time of a block.
     */
    fun <T> measureTimeMillis(block: () -> T): Pair<T, Long> {
        val start = currentTimeMillis()
        val result = block()
        val elapsed = currentTimeMillis() - start
        return result to elapsed
    }
    
    /**
     * Measures the execution time of a suspend block.
     */
    suspend fun <T> measureTimeSuspend(block: suspend () -> T): Pair<T, Long> {
        val start = currentTimeMillis()
        val result = block()
        val elapsed = currentTimeMillis() - start
        return result to elapsed
    }
}

/**
 * Default implementation using system time.
 */
class SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * Test implementation with controllable time.
 * 
 * Usage:
 * ```kotlin
 * val clock = TestClock(initialMillis = 1000L)
 * clock.advanceBy(Duration.seconds(5))
 * assertEquals(6000L, clock.currentTimeMillis())
 * ```
 */
class TestClock(
    private var currentMillis: Long = 0L,
    private var currentNanos: Long = 0L,
) : Clock {
    
    override fun currentTimeMillis(): Long = currentMillis
    
    override fun nanoTime(): Long = currentNanos
    
    /**
     * Advances time by the specified milliseconds.
     */
    fun advanceTimeBy(millis: Long) {
        currentMillis += millis
        currentNanos += millis * 1_000_000
    }
    
    /**
     * Advances time by the specified duration.
     */
    fun advanceBy(duration: Duration) {
        advanceTimeBy(duration.inWholeMilliseconds)
    }
    
    /**
     * Sets the current time to the specified value.
     */
    fun setCurrentTimeMillis(millis: Long) {
        currentMillis = millis
    }
    
    /**
     * Sets the current nano time to the specified value.
     */
    fun setNanoTime(nanos: Long) {
        currentNanos = nanos
    }
    
    /**
     * Resets the clock to zero.
     */
    fun reset() {
        currentMillis = 0L
        currentNanos = 0L
    }
    
    /**
     * Creates a snapshot of the current time for later comparison.
     */
    fun snapshot(): ClockSnapshot = ClockSnapshot(currentMillis, currentNanos)
    
    /**
     * Calculates elapsed time since a snapshot.
     */
    fun elapsedSince(snapshot: ClockSnapshot): Long = currentMillis - snapshot.millis
}

/**
 * Snapshot of clock state for comparison.
 */
data class ClockSnapshot(
    val millis: Long,
    val nanos: Long,
)

/**
 * Extension to convert Clock to kotlinx.datetime.Instant.
 * Requires kotlinx-datetime dependency.
 * 
 * Usage:
 * ```kotlin
 * import kotlinx.datetime.Instant
 * 
 * val instant = clock.toInstant()
 * ```
 */
// Uncomment when kotlinx-datetime is available:
// fun Clock.toInstant(): kotlinx.datetime.Instant =
//     kotlinx.datetime.Instant.fromEpochMilliseconds(currentTimeMillis())
