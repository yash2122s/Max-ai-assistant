package com.agentcore.skill.base

import com.agentcore.memory.MemoryStore
import com.agentcore.util.Clock
import com.agentcore.util.CoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Context provided to skills during execution.
 * Contains shared resources and execution metadata.
 */
interface SkillContext {
    /**
     * Unique identifier for this execution trace.
     */
    val traceId: String
    
    /**
     * Parent trace ID for nested executions.
     */
    val parentTraceId: String?
    
    /**
     * Timestamp when execution started.
     */
    val startTimeMillis: Long
    
    /**
     * Memory store for reading/writing persistent data.
     */
    val memoryStore: MemoryStore
    
    /**
     * Coroutine dispatchers for proper threading.
     */
    val dispatchers: CoroutineDispatchers
    
    /**
     * Clock for time-related operations.
     */
    val clock: Clock
    
    /**
     * Coroutine scope for the current execution.
     */
    val scope: CoroutineScope
    
    /**
     * Additional context data passed through the execution.
     */
    val extras: Map<String, Any>
    
    /**
     * Creates a child context for nested skill execution.
     */
    fun createChildContext(
        extras: Map<String, Any> = emptyMap(),
    ): SkillContext
    
    /**
     * Gets a typed extra value.
     */
    fun <T> getExtra(key: String): T?
    
    /**
     * Gets a typed extra value with a default.
     */
    fun <T> getExtra(key: String, default: T): T
}

/**
 * Default implementation of SkillContext.
 */
data class DefaultSkillContext(
    override val traceId: String = UUID.randomUUID().toString(),
    override val parentTraceId: String? = null,
    override val startTimeMillis: Long,
    override val memoryStore: MemoryStore,
    override val dispatchers: CoroutineDispatchers,
    override val clock: Clock,
    override val scope: CoroutineScope,
    override val extras: Map<String, Any> = emptyMap(),
) : SkillContext {
    
    override fun createChildContext(
        extras: Map<String, Any>,
    ): SkillContext = copy(
        traceId = UUID.randomUUID().toString(),
        parentTraceId = this.traceId,
        startTimeMillis = clock.currentTimeMillis(),
        extras = this.extras + extras,
    )
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> getExtra(key: String): T? = extras[key] as? T
    
    @Suppress("UNCHECKED_CAST")
    override fun <T> getExtra(key: String, default: T): T = 
        (extras[key] as? T) ?: default
}

/**
 * Builder for creating SkillContext.
 */
class SkillContextBuilder(
    private val memoryStore: MemoryStore,
    private val dispatchers: CoroutineDispatchers,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private var traceId: String = UUID.randomUUID().toString()
    private var parentTraceId: String? = null
    private var extras: MutableMap<String, Any> = mutableMapOf()
    
    fun traceId(id: String) = apply { this.traceId = id }
    
    fun parentTraceId(id: String?) = apply { this.parentTraceId = id }
    
    fun extra(key: String, value: Any) = apply { extras[key] = value }
    
    fun extras(map: Map<String, Any>) = apply { extras.putAll(map) }
    
    fun build(): SkillContext = DefaultSkillContext(
        traceId = traceId,
        parentTraceId = parentTraceId,
        startTimeMillis = clock.currentTimeMillis(),
        memoryStore = memoryStore,
        dispatchers = dispatchers,
        clock = clock,
        scope = scope,
        extras = extras.toMap(),
    )
}
