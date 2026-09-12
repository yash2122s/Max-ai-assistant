package com.example.network.agent

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe 60-Second Bounded LRU Idempotency & In-Flight Deduplication Cache.
 * Guarantees that duplicate commands sharing the same command_id:
 * 1. Are never executed twice within 60 seconds.
 * 2. Return the previous execution result.
 * 3. Concurrent requests with the same command_id await the in-flight result rather than executing simultaneously.
 */
class CommandIdempotencyCache(
    private val maxEntries: Int = 256,
    private val ttlMs: Long = 60_000L
) {
    data class CacheEntry(
        val result: WindowsAgentClient.CommandResultPayload,
        val timestamp: Long
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }
    private val lock = Any()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<WindowsAgentClient.CommandResultPayload>>()

    /**
     * Retrieves cached result if present and within TTL.
     */
    fun getCachedResult(commandId: String): WindowsAgentClient.CommandResultPayload? {
        synchronized(lock) {
            val entry = cache[commandId] ?: return null
            if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
                cache.remove(commandId)
                return null
            }
            return entry.result
        }
    }

    /**
     * Stores final command result into the bounded LRU cache with current timestamp.
     */
    fun storeResult(commandId: String, result: WindowsAgentClient.CommandResultPayload) {
        synchronized(lock) {
            cache[commandId] = CacheEntry(result, System.currentTimeMillis())
        }
    }

    private fun safeLog(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (e: Throwable) {
            println("[$tag] $msg")
        }
    }

    /**
     * Executes the provided block if commandId is new, or awaits existing in-flight / returns cached result.
     */
    suspend fun executeOrAwait(
        commandId: String,
        block: suspend () -> WindowsAgentClient.CommandResultPayload
    ): WindowsAgentClient.CommandResultPayload {
        // 1. Check completed cache
        val cached = getCachedResult(commandId)
        if (cached != null) {
            safeLog("CommandIdempotencyCache", "Returning cached idempotent result for command_id='$commandId'")
            return cached
        }

        // 2. Check in-flight concurrent executions
        val deferred = CompletableDeferred<WindowsAgentClient.CommandResultPayload>()
        val existing = inFlight.putIfAbsent(commandId, deferred)
        if (existing != null) {
            safeLog("CommandIdempotencyCache", "Awaiting in-flight execution for duplicate command_id='$commandId'")
            return existing.await()
        }

        // 3. Execute block as primary worker
        try {

            val result = block()
            storeResult(commandId, result)
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            val errResult = WindowsAgentClient.CommandResultPayload(
                commandId = commandId,
                status = "failed",
                action = "UNKNOWN",
                data = emptyMap(),
                error = WindowsAgentClient.CommandError("EXECUTION_FAILED", e.message ?: "Execution failed"),
                executionTimeMs = 0
            )
            storeResult(commandId, errResult)
            deferred.complete(errResult)
            return errResult
        } finally {
            inFlight.remove(commandId)
        }
    }

    /**
     * Returns current number of active cached entries.
     */
    fun size(): Int {
        synchronized(lock) {
            return cache.size
        }
    }

    /**
     * Clears all cache and in-flight maps (useful for unit tests).
     */
    fun clear() {
        synchronized(lock) {
            cache.clear()
        }
        inFlight.clear()
    }
}
