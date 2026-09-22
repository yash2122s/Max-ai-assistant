package com.agentcore.memory

/**
 * Represents an entry stored in the memory store.
 * 
 * @param T The type of value stored
 */
data class MemoryEntry<T : Any>(
    /**
     * Unique key identifying this entry.
     */
    val key: String,
    
    /**
     * The stored value.
     */
    val value: T,
    
    /**
     * Timestamp when this entry was created.
     */
    val createdAt: Long,
    
    /**
     * Timestamp when this entry was last updated.
     */
    val updatedAt: Long,
    
    /**
     * Optional expiration timestamp (null = never expires).
     */
    val expiresAt: Long? = null,
    
    /**
     * Metadata associated with this entry.
     */
    val metadata: Map<String, Any> = emptyMap(),
    
    /**
     * Tags for categorizing and querying entries.
     */
    val tags: Set<String> = emptySet(),
) {
    /**
     * Checks if this entry has expired.
     */
    fun isExpired(currentTimeMillis: Long): Boolean =
        expiresAt != null && currentTimeMillis > expiresAt
    
    /**
     * Creates a copy with updated value and timestamp.
     */
    fun withUpdatedValue(newValue: T, currentTimeMillis: Long): MemoryEntry<T> =
        copy(value = newValue, updatedAt = currentTimeMillis)
    
    /**
     * Creates a copy with additional metadata.
     */
    fun withMetadata(key: String, value: Any): MemoryEntry<T> =
        copy(metadata = metadata + (key to value))
    
    /**
     * Creates a copy with additional tags.
     */
    fun withTags(vararg newTags: String): MemoryEntry<T> =
        copy(tags = tags + newTags.toSet())
}

/**
 * Options for memory write operations.
 */
data class MemoryWriteOptions(
    /**
     * Time-to-live in milliseconds (null = never expires).
     */
    val ttlMillis: Long? = null,
    
    /**
     * Whether to overwrite existing entries.
     */
    val overwrite: Boolean = true,
    
    /**
     * Metadata to attach to the entry.
     */
    val metadata: Map<String, Any> = emptyMap(),
    
    /**
     * Tags to attach to the entry.
     */
    val tags: Set<String> = emptySet(),
)

/**
 * Options for memory read operations.
 */
data class MemoryReadOptions(
    /**
     * Whether to include expired entries.
     */
    val includeExpired: Boolean = false,
    
    /**
     * Filter by tags (empty = no filter).
     */
    val filterTags: Set<String> = emptySet(),
)

/**
 * Result of a memory query operation.
 */
data class MemoryQueryResult<T : Any>(
    val entries: List<MemoryEntry<T>>,
    val totalCount: Int,
    val hasMore: Boolean = false,
)
