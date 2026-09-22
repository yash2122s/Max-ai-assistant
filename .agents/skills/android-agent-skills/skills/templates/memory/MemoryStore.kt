package com.agentcore.memory

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for persistent memory storage.
 * 
 * Implementations can be:
 * - In-memory (for testing or ephemeral data)
 * - SharedPreferences-based
 * - Room database-based
 * - DataStore-based
 * - Remote storage
 */
interface MemoryStore {
    
    /**
     * Reads a value from memory.
     * 
     * @param key The unique key for the entry
     * @param options Read options
     * @return The memory entry, or null if not found or expired
     */
    suspend fun <T : Any> read(
        key: String,
        type: Class<T>,
        options: MemoryReadOptions = MemoryReadOptions(),
    ): MemoryEntry<T>?
    
    /**
     * Writes a value to memory.
     * 
     * @param key The unique key for the entry
     * @param value The value to store
     * @param options Write options
     * @return true if write was successful
     */
    suspend fun <T : Any> write(
        key: String,
        value: T,
        options: MemoryWriteOptions = MemoryWriteOptions(),
    ): Boolean
    
    /**
     * Deletes an entry from memory.
     * 
     * @param key The key of the entry to delete
     * @return true if an entry was deleted
     */
    suspend fun delete(key: String): Boolean
    
    /**
     * Checks if an entry exists and is not expired.
     * 
     * @param key The key to check
     * @return true if the entry exists and is valid
     */
    suspend fun exists(key: String): Boolean
    
    /**
     * Queries entries matching the given criteria.
     * 
     * @param predicate Filter function
     * @param limit Maximum number of results
     * @param offset Number of results to skip
     * @return Query result with matching entries
     */
    suspend fun <T : Any> query(
        type: Class<T>,
        predicate: (MemoryEntry<T>) -> Boolean = { true },
        limit: Int = 100,
        offset: Int = 0,
    ): MemoryQueryResult<T>
    
    /**
     * Queries entries by tags.
     * 
     * @param tags Tags to match (any of)
     * @param type The type of values
     * @return Query result with matching entries
     */
    suspend fun <T : Any> queryByTags(
        tags: Set<String>,
        type: Class<T>,
    ): MemoryQueryResult<T>
    
    /**
     * Observes changes to a specific key.
     * 
     * @param key The key to observe
     * @return Flow that emits whenever the entry changes
     */
    fun <T : Any> observe(key: String, type: Class<T>): Flow<MemoryEntry<T>?>
    
    /**
     * Observes all changes in the store.
     * 
     * @return Flow that emits change events
     */
    fun observeChanges(): Flow<MemoryChangeEvent>
    
    /**
     * Clears all entries from memory.
     */
    suspend fun clear()
    
    /**
     * Clears expired entries from memory.
     * 
     * @return Number of entries cleared
     */
    suspend fun clearExpired(): Int
    
    /**
     * Gets the total number of entries.
     */
    suspend fun count(): Int
    
    /**
     * Gets all keys in the store.
     */
    suspend fun keys(): Set<String>
}

/**
 * Event emitted when memory store changes.
 */
sealed interface MemoryChangeEvent {
    val key: String
    val timestamp: Long
    
    data class Created(
        override val key: String,
        override val timestamp: Long,
    ) : MemoryChangeEvent
    
    data class Updated(
        override val key: String,
        override val timestamp: Long,
    ) : MemoryChangeEvent
    
    data class Deleted(
        override val key: String,
        override val timestamp: Long,
    ) : MemoryChangeEvent
    
    data class Cleared(
        override val key: String = "*",
        override val timestamp: Long,
    ) : MemoryChangeEvent
}

/**
 * Extension function for type-safe read.
 */
suspend inline fun <reified T : Any> MemoryStore.read(
    key: String,
    options: MemoryReadOptions = MemoryReadOptions(),
): MemoryEntry<T>? = read(key, T::class.java, options)

/**
 * Extension function for type-safe query.
 */
suspend inline fun <reified T : Any> MemoryStore.query(
    noinline predicate: (MemoryEntry<T>) -> Boolean = { true },
    limit: Int = 100,
    offset: Int = 0,
): MemoryQueryResult<T> = query(T::class.java, predicate, limit, offset)

/**
 * Extension function for type-safe observe.
 */
inline fun <reified T : Any> MemoryStore.observe(key: String): Flow<MemoryEntry<T>?> =
    observe(key, T::class.java)

/**
 * Extension function for type-safe queryByTags.
 */
suspend inline fun <reified T : Any> MemoryStore.queryByTags(
    tags: Set<String>,
): MemoryQueryResult<T> = queryByTags(tags, T::class.java)
