package com.agentcore.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Flow extension utilities following the Android Kotlin Agent Skills Guide patterns.
 */

/**
 * Applies common skill defaults to a Flow:
 * - Debounce to avoid rapid emissions
 * - Distinct until changed to skip duplicates
 * - Flow on specified dispatcher
 * 
 * Usage:
 * ```kotlin
 * memoryStore.observe(key)
 *     .withSkillDefaults()
 *     .collect { ... }
 * ```
 */
fun <T> Flow<T>.withSkillDefaults(
    debounceMs: Long = 300,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<T> = this
    .debounce(debounceMs)
    .distinctUntilChanged()
    .flowOn(dispatcher)

/**
 * Applies common skill defaults with Duration parameter.
 */
fun <T> Flow<T>.withSkillDefaults(
    debounce: Duration = 300.milliseconds,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<T> = withSkillDefaults(debounce.inWholeMilliseconds, dispatcher)

/**
 * Adds timeout to a Flow with a fallback value on timeout.
 */
fun <T> Flow<T>.withTimeoutOrDefault(
    timeout: Duration,
    defaultValue: T,
): Flow<T> = flow {
    try {
        withTimeout(timeout) {
            collect { emit(it) }
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        emit(defaultValue)
    }
}

/**
 * Adds retry with exponential backoff to a Flow.
 */
fun <T> Flow<T>.retryWithBackoff(
    maxRetries: Int = 3,
    initialDelay: Duration = 100.milliseconds,
    maxDelay: Duration = 10.seconds,
    factor: Double = 2.0,
    shouldRetry: (Throwable) -> Boolean = { true },
): Flow<T> = retryWhen { cause, attempt ->
    if (attempt >= maxRetries || !shouldRetry(cause)) {
        false
    } else {
        val delay = (initialDelay.inWholeMilliseconds * Math.pow(factor, attempt.toDouble()))
            .toLong()
            .coerceAtMost(maxDelay.inWholeMilliseconds)
        kotlinx.coroutines.delay(delay)
        true
    }
}

/**
 * Catches exceptions and emits a fallback value.
 */
fun <T> Flow<T>.catchAndEmit(fallback: T): Flow<T> = catch { emit(fallback) }

/**
 * Catches exceptions and emits a computed fallback value.
 */
fun <T> Flow<T>.catchAndEmit(fallbackProvider: (Throwable) -> T): Flow<T> = catch { 
    emit(fallbackProvider(it)) 
}

/**
 * Maps with cancellation checks for long-running transformations.
 */
fun <T, R> Flow<T>.mapWithCancellation(
    checkInterval: Int = 1,
    transform: suspend (T) -> R,
): Flow<R> = transform { value ->
    currentCoroutineContext().ensureActive()
    emit(transform(value))
}

/**
 * Filters items in a collection with periodic cancellation checks.
 */
fun <T> Flow<List<T>>.filterItemsWithCancellation(
    checkInterval: Int = 100,
    predicate: suspend (T) -> Boolean,
): Flow<List<T>> = map { list ->
    val result = mutableListOf<T>()
    for ((index, item) in list.withIndex()) {
        if (index % checkInterval == 0) {
            currentCoroutineContext().ensureActive()
        }
        if (predicate(item)) {
            result.add(item)
        }
    }
    result.toList()
}

/**
 * Logs each emission for debugging purposes.
 */
fun <T> Flow<T>.logEmissions(
    tag: String,
    logger: (String, String) -> Unit = { t, m -> println("[$t] $m") },
): Flow<T> = onEach { value ->
    logger(tag, "Emitted: $value")
}

/**
 * Throttles emissions to at most one per specified duration.
 * Unlike debounce, this emits the first item immediately.
 */
fun <T> Flow<T>.throttleFirst(windowDuration: Duration): Flow<T> = flow {
    var lastEmissionTime = 0L
    collect { value ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmissionTime >= windowDuration.inWholeMilliseconds) {
            lastEmissionTime = currentTime
            emit(value)
        }
    }
}

/**
 * Buffers emissions and emits them in batches.
 */
fun <T> Flow<T>.chunked(size: Int): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    collect { value ->
        buffer.add(value)
        if (buffer.size >= size) {
            emit(buffer.toList())
            buffer.clear()
        }
    }
    if (buffer.isNotEmpty()) {
        emit(buffer.toList())
    }
}

/**
 * Pairs each emission with the previous one.
 */
fun <T> Flow<T>.pairwise(): Flow<Pair<T, T>> = flow {
    var previous: T? = null
    var hasPrevious = false
    collect { value ->
        if (hasPrevious) {
            @Suppress("UNCHECKED_CAST")
            emit(previous as T to value)
        }
        previous = value
        hasPrevious = true
    }
}

/**
 * Emits items only when they differ from the previous by the given selector.
 */
fun <T, K> Flow<T>.distinctUntilChangedBy(selector: (T) -> K): Flow<T> = flow {
    var previousKey: K? = null
    var hasPrevious = false
    collect { value ->
        val key = selector(value)
        if (!hasPrevious || previousKey != key) {
            previousKey = key
            hasPrevious = true
            emit(value)
        }
    }
}
