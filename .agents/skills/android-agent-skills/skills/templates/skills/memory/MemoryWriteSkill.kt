package com.agentcore.skills.memory

import com.agentcore.memory.MemoryWriteOptions
import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.base.validate

/**
 * Input for MemoryWriteSkill.
 */
@Immutable
data class MemoryWriteInput(
    /**
     * The key to write to memory.
     */
    val key: String,
    
    /**
     * The value to store.
     */
    val value: Any,
    
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
     * Tags to attach to the entry for filtering.
     */
    val tags: Set<String> = emptySet(),
)

/**
 * Output from MemoryWriteSkill.
 */
@Immutable
data class MemoryWriteOutput(
    /**
     * The key that was written.
     */
    val key: String,
    
    /**
     * Whether the write was successful.
     */
    val success: Boolean,
    
    /**
     * Whether this was a new entry or an update.
     */
    val wasUpdate: Boolean,
    
    /**
     * Timestamp when the write occurred.
     */
    val timestamp: Long,
    
    /**
     * Expiration timestamp if TTL was set.
     */
    val expiresAt: Long? = null,
)

/**
 * Skill for writing values to the memory store.
 * 
 * This skill provides a standardized way to write to agent memory
 * with support for TTL, overwrite policies, and metadata tagging.
 */
class MemoryWriteSkill : BaseSkill<MemoryWriteInput, MemoryWriteOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "core.memory.write"
        name = "Memory Write"
        description = "Writes a value to the agent memory store"
        category = SkillCategory.MEMORY
        tags("memory", "write", "core")
        inputType = MemoryWriteInput::class
        outputType = MemoryWriteOutput::class
        isRetryable = true
        recommendedTimeoutMs = 5_000L
    }
    
    override suspend fun validate(input: MemoryWriteInput): SkillResult<Unit> {
        return validate {
            requireNotBlank(input.key, "key", "Memory key cannot be blank")
            
            if (input.ttlMillis != null) {
                requirePositive(input.ttlMillis, "ttlMillis", "TTL must be positive if specified")
            }
        }
    }
    
    override suspend fun doExecute(
        input: MemoryWriteInput,
        context: SkillContext,
    ): SkillResult<MemoryWriteOutput> {
        val currentTime = context.clock.currentTimeMillis()
        val wasExisting = context.memoryStore.exists(input.key)
        
        val options = MemoryWriteOptions(
            ttlMillis = input.ttlMillis,
            overwrite = input.overwrite,
            metadata = input.metadata,
            tags = input.tags,
        )
        
        return try {
            val success = context.memoryStore.write(
                key = input.key,
                value = input.value,
                options = options,
            )
            
            val expiresAt = input.ttlMillis?.let { currentTime + it }
            
            SkillResult.success(
                MemoryWriteOutput(
                    key = input.key,
                    success = success,
                    wasUpdate = wasExisting && success,
                    timestamp = currentTime,
                    expiresAt = expiresAt,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // ALWAYS rethrow CancellationException
        } catch (e: Exception) {
            SkillResult.failure(
                exception = e,
                code = ErrorCode.MEMORY_ERROR,
            )
        }
    }
}

/**
 * Builder for MemoryWriteInput with DSL-style API.
 */
class MemoryWriteInputBuilder(private val key: String) {
    private var value: Any? = null
    private var ttlMillis: Long? = null
    private var overwrite: Boolean = true
    private var metadata: MutableMap<String, Any> = mutableMapOf()
    private var tags: MutableSet<String> = mutableSetOf()
    
    fun value(value: Any) = apply { this.value = value }
    fun ttl(millis: Long) = apply { this.ttlMillis = millis }
    fun noOverwrite() = apply { this.overwrite = false }
    fun metadata(key: String, value: Any) = apply { this.metadata[key] = value }
    fun tags(vararg tags: String) = apply { this.tags.addAll(tags) }
    
    fun build(): MemoryWriteInput = MemoryWriteInput(
        key = key,
        value = value ?: error("Value is required"),
        ttlMillis = ttlMillis,
        overwrite = overwrite,
        metadata = metadata.toMap(),
        tags = tags.toSet(),
    )
}

/**
 * DSL function for creating MemoryWriteInput.
 */
fun memoryWriteInput(key: String, block: MemoryWriteInputBuilder.() -> Unit): MemoryWriteInput =
    MemoryWriteInputBuilder(key).apply(block).build()

/**
 * Simple factory for MemoryWriteInput.
 */
fun memoryWriteInput(
    key: String,
    value: Any,
    ttlMillis: Long? = null,
    tags: Set<String> = emptySet(),
): MemoryWriteInput = MemoryWriteInput(
    key = key,
    value = value,
    ttlMillis = ttlMillis,
    tags = tags,
)
