package com.agentcore.memory

import com.agentcore.util.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of MemoryStore.
 * 
 * Suitable for:
 * - Testing
 * - Session-scoped data
 * - Caching layer
 * - Ephemeral agent state
 */
class InMemoryStore(
    private val clock: Clock,
) : MemoryStore {
    
    private val storage = ConcurrentHashMap<String, MemoryEntry<Any>>()
    private val mutex = Mutex()
    
    private val _changes = MutableSharedFlow<MemoryChangeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    
    private val _keyFlows = ConcurrentHashMap<String, MutableSharedFlow<MemoryEntry<Any>?>>()
    
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> read(
        key: String,
        type: Class<T>,
        options: MemoryReadOptions,
    ): MemoryEntry<T>? {
        val entry = storage[key] as? MemoryEntry<T> ?: return null
        
        // Check expiration
        if (!options.includeExpired && entry.isExpired(clock.currentTimeMillis())) {
            // Optionally clean up expired entry
            delete(key)
            return null
        }
        
        // Filter by tags if specified
        if (options.filterTags.isNotEmpty() && 
            options.filterTags.none { it in entry.tags }
        ) {
            return null
        }
        
        return entry
    }
    
    override suspend fun <T : Any> write(
        key: String,
        value: T,
        options: MemoryWriteOptions,
    ): Boolean = mutex.withLock {
        val currentTime = clock.currentTimeMillis()
        val existingEntry = storage[key]
        
        // Check overwrite policy
        if (existingEntry != null && !options.overwrite) {
            return false
        }
        
        val expiresAt = options.ttlMillis?.let { currentTime + it }
        
        val entry = if (existingEntry != null) {
            @Suppress("UNCHECKED_CAST")
            (existingEntry as MemoryEntry<T>).copy(
                value = value,
                updatedAt = currentTime,
                expiresAt = expiresAt ?: existingEntry.expiresAt,
                metadata = existingEntry.metadata + options.metadata,
                tags = existingEntry.tags + options.tags,
            )
        } else {
            MemoryEntry(
                key = key,
                value = value,
                createdAt = currentTime,
                updatedAt = currentTime,
                expiresAt = expiresAt,
                metadata = options.metadata,
                tags = options.tags,
            )
        }
        
        @Suppress("UNCHECKED_CAST")
        storage[key] = entry as MemoryEntry<Any>
        
        // Emit change event
        val event = if (existingEntry != null) {
            MemoryChangeEvent.Updated(key, currentTime)
        } else {
            MemoryChangeEvent.Created(key, currentTime)
        }
        _changes.emit(event)
        
        // Emit to key-specific flow
        _keyFlows[key]?.emit(entry)
        
        return true
    }
    
    override suspend fun delete(key: String): Boolean = mutex.withLock {
        val removed = storage.remove(key)
        if (removed != null) {
            val currentTime = clock.currentTimeMillis()
            _changes.emit(MemoryChangeEvent.Deleted(key, currentTime))
            _keyFlows[key]?.emit(null)
        }
        return removed != null
    }
    
    override suspend fun exists(key: String): Boolean {
        val entry = storage[key] ?: return false
        if (entry.isExpired(clock.currentTimeMillis())) {
            delete(key)
            return false
        }
        return true
    }
    
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> query(
        type: Class<T>,
        predicate: (MemoryEntry<T>) -> Boolean,
        limit: Int,
        offset: Int,
    ): MemoryQueryResult<T> {
        val currentTime = clock.currentTimeMillis()
        
        val allMatching = storage.values
            .asSequence()
            .filter { !it.isExpired(currentTime) }
            .filter { type.isInstance(it.value) }
            .map { it as MemoryEntry<T> }
            .filter(predicate)
            .toList()
        
        val results = allMatching
            .drop(offset)
            .take(limit)
        
        return MemoryQueryResult(
            entries = results,
            totalCount = allMatching.size,
            hasMore = offset + limit < allMatching.size,
        )
    }
    
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> queryByTags(
        tags: Set<String>,
        type: Class<T>,
    ): MemoryQueryResult<T> {
        val currentTime = clock.currentTimeMillis()
        
        val results = storage.values
            .asSequence()
            .filter { !it.isExpired(currentTime) }
            .filter { type.isInstance(it.value) }
            .filter { entry -> tags.any { it in entry.tags } }
            .map { it as MemoryEntry<T> }
            .toList()
        
        return MemoryQueryResult(
            entries = results,
            totalCount = results.size,
            hasMore = false,
        )
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> observe(key: String, type: Class<T>): Flow<MemoryEntry<T>?> {
        val flow = _keyFlows.getOrPut(key) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 16)
        }
        
        // Emit current value
        val currentEntry = storage[key]
        if (currentEntry != null && type.isInstance(currentEntry.value)) {
            flow.tryEmit(currentEntry)
        }
        
        return flow.asSharedFlow().map { it as? MemoryEntry<T> }
    }
    
    override fun observeChanges(): Flow<MemoryChangeEvent> = _changes.asSharedFlow()
    
    override suspend fun clear(): Unit = mutex.withLock {
        storage.clear()
        _changes.emit(MemoryChangeEvent.Cleared(timestamp = clock.currentTimeMillis()))
        _keyFlows.values.forEach { it.emit(null) }
    }
    
    override suspend fun clearExpired(): Int = mutex.withLock {
        val currentTime = clock.currentTimeMillis()
        var cleared = 0
        
        val keysToRemove = storage.entries
            .filter { it.value.isExpired(currentTime) }
            .map { it.key }
        
        keysToRemove.forEach { key ->
            storage.remove(key)
            _changes.emit(MemoryChangeEvent.Deleted(key, currentTime))
            _keyFlows[key]?.emit(null)
            cleared++
        }
        
        return cleared
    }
    
    override suspend fun count(): Int = storage.size
    
    override suspend fun keys(): Set<String> = storage.keys.toSet()
    
    /**
     * Creates a snapshot of current state for debugging.
     */
    fun snapshot(): Map<String, MemoryEntry<Any>> = storage.toMap()
}
