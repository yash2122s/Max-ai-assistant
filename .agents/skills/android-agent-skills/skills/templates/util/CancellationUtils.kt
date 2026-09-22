package com.agentcore.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Utilities for proper cancellation support in skills.
 * 
 * Following the Android Kotlin Agent Skills Guide patterns for
 * coroutine cancellation handling.
 */

/**
 * Checks if the current coroutine is active and throws CancellationException if not.
 * Use this at key points in long-running operations.
 * 
 * Usage:
 * ```kotlin
 * override suspend fun doExecute(...): SkillResult<Output> {
 *     checkCancellation()
 *     
 *     items.forEach { item ->
 *         checkCancellation()
 *         process(item)
 *     }
 * }
 * ```
 */
suspend inline fun checkCancellation() {
    currentCoroutineContext().ensureActive()
}

/**
 * Checks if the current coroutine is still active.
 * Returns false if cancelled.
 */
suspend inline fun isStillActive(): Boolean {
    return currentCoroutineContext().isActive
}

/**
 * Processes a collection with periodic cancellation checks.
 * 
 * @param checkInterval How often to check for cancellation (every N items)
 * @param action The action to perform on each item
 */
suspend inline fun <T> Collection<T>.forEachWithCancellation(
    checkInterval: Int = 100,
    action: (T) -> Unit,
) {
    for ((index, item) in this.withIndex()) {
        if (index % checkInterval == 0) {
            checkCancellation()
        }
        action(item)
    }
}

/**
 * Maps a collection with periodic cancellation checks.
 * 
 * @param checkInterval How often to check for cancellation (every N items)
 * @param transform The transformation to apply to each item
 */
suspend inline fun <T, R> Collection<T>.mapWithCancellation(
    checkInterval: Int = 100,
    transform: (T) -> R,
): List<R> {
    val result = mutableListOf<R>()
    for ((index, item) in this.withIndex()) {
        if (index % checkInterval == 0) {
            checkCancellation()
        }
        result.add(transform(item))
    }
    return result
}

/**
 * Filters a collection with periodic cancellation checks.
 * 
 * @param checkInterval How often to check for cancellation (every N items)
 * @param predicate The filter predicate
 */
suspend inline fun <T> Collection<T>.filterWithCancellation(
    checkInterval: Int = 100,
    predicate: (T) -> Boolean,
): List<T> {
    val result = mutableListOf<T>()
    for ((index, item) in this.withIndex()) {
        if (index % checkInterval == 0) {
            checkCancellation()
        }
        if (predicate(item)) {
            result.add(item)
        }
    }
    return result
}

/**
 * Finds the first element matching the predicate with cancellation checks.
 * 
 * @param checkInterval How often to check for cancellation (every N items)
 * @param predicate The predicate to match
 */
suspend inline fun <T> Collection<T>.findWithCancellation(
    checkInterval: Int = 100,
    predicate: (T) -> Boolean,
): T? {
    for ((index, item) in this.withIndex()) {
        if (index % checkInterval == 0) {
            checkCancellation()
        }
        if (predicate(item)) {
            return item
        }
    }
    return null
}

/**
 * Runs a block and catches CancellationException properly.
 * CancellationException is always re-thrown as per Kotlin coroutines best practice.
 * 
 * Usage:
 * ```kotlin
 * val result = runCatchingWithCancellation {
 *     performOperation()
 * }
 * ```
 */
suspend inline fun <T> runCatchingWithCancellation(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        // ALWAYS re-throw CancellationException
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Wraps a block to handle cancellation gracefully.
 * Executes cleanup if cancelled.
 * 
 * Usage:
 * ```kotlin
 * withCancellationCleanup(
 *     onCancel = { cleanupResources() }
 * ) {
 *     performOperation()
 * }
 * ```
 */
suspend inline fun <T> withCancellationCleanup(
    onCancel: () -> Unit,
    block: suspend () -> T,
): T {
    return try {
        block()
    } catch (e: CancellationException) {
        onCancel()
        throw e
    }
}

/**
 * Progress reporter for long-running operations with cancellation support.
 */
class CancellableProgress(
    private val totalItems: Int,
    private val checkInterval: Int = 100,
    private val onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> },
) {
    private var processed = 0
    
    /**
     * Reports progress for one item processed.
     * Checks cancellation at specified intervals.
     */
    suspend fun reportProgress() {
        processed++
        if (processed % checkInterval == 0) {
            checkCancellation()
            onProgress(processed, totalItems)
        }
    }
    
    /**
     * Gets the current progress as a percentage (0.0 to 1.0).
     */
    val progress: Float
        get() = if (totalItems > 0) processed.toFloat() / totalItems else 0f
}

/**
 * Executes a list of suspending operations with cancellation awareness.
 * Stops processing if cancelled.
 * 
 * @param items Items to process
 * @param checkInterval How often to check for cancellation
 * @param operation The operation to perform on each item
 */
suspend inline fun <T, R> processWithCancellation(
    items: List<T>,
    checkInterval: Int = 100,
    crossinline operation: suspend (T) -> R,
): List<R> {
    val results = mutableListOf<R>()
    for ((index, item) in items.withIndex()) {
        if (index % checkInterval == 0) {
            checkCancellation()
        }
        results.add(operation(item))
    }
    return results
}
