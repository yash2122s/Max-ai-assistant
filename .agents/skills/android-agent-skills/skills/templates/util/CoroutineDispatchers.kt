package com.agentcore.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Abstraction for coroutine dispatchers to enable testing.
 * 
 * Production code should use the default implementation.
 * Tests can provide custom TestDispatchers for synchronous execution.
 */
interface CoroutineDispatchers {
    /**
     * Dispatcher for I/O operations (network, file, database).
     */
    val io: CoroutineDispatcher
    
    /**
     * Dispatcher for CPU-intensive operations.
     */
    val default: CoroutineDispatcher
    
    /**
     * Main/UI thread dispatcher.
     */
    val main: CoroutineDispatcher
    
    /**
     * Main dispatcher that executes immediately if already on main thread.
     */
    val mainImmediate: CoroutineDispatcher
    
    /**
     * Unconfined dispatcher (use with caution, mainly for testing).
     */
    val unconfined: CoroutineDispatcher
}

/**
 * Default implementation using standard Kotlin dispatchers.
 */
class DefaultCoroutineDispatchers : CoroutineDispatchers {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}

/**
 * Test implementation that uses a single dispatcher for all operations.
 * Useful for making coroutine tests deterministic.
 */
class TestCoroutineDispatchers(
    private val testDispatcher: CoroutineDispatcher,
) : CoroutineDispatchers {
    override val io: CoroutineDispatcher = testDispatcher
    override val default: CoroutineDispatcher = testDispatcher
    override val main: CoroutineDispatcher = testDispatcher
    override val mainImmediate: CoroutineDispatcher = testDispatcher
    override val unconfined: CoroutineDispatcher = testDispatcher
}
