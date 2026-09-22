package com.agentcore.skills.memory

import com.agentcore.memory.MemoryReadOptions
import com.agentcore.memory.read
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
 * Input for MemoryReadSkill.
 */
@Immutable
data class MemoryReadInput(
    /**
     * The key to read from memory.
     */
    val key: String,
    
    /**
     * The expected type of the value.
     */
    val valueType: Class<*> = Any::class.java,
    
    /**
     * Whether to include expired entries.
     */
    val includeExpired: Boolean = false,
    
    /**
     * Tags to filter by.
     */
    val filterTags: Set<String> = emptySet(),
)

/**
 * Output from MemoryReadSkill.
 */
@Immutable
data class MemoryReadOutput(
    /**
     * The key that was read.
     */
    val key: String,
    
    /**
     * The value found, or null if not found.
     */
    val value: Any?,
    
    /**
     * Whether the key was found in memory.
     */
    val found: Boolean,
    
    /**
     * Timestamp when the value was created.
     */
    val createdAt: Long? = null,
    
    /**
     * Timestamp when the value was last updated.
     */
    val updatedAt: Long? = null,
    
    /**
     * Metadata associated with the entry.
     */
    val metadata: Map<String, Any> = emptyMap(),
    
    /**
     * Tags associated with the entry.
     */
    val tags: Set<String> = emptySet(),
)

/**
 * Skill for reading values from the memory store.
 * 
 * This skill provides a standardized way to read from agent memory
 * with support for type checking, expiration, and tag filtering.
 */
class MemoryReadSkill : BaseSkill<MemoryReadInput, MemoryReadOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "core.memory.read"
        name = "Memory Read"
        description = "Reads a value from the agent memory store"
        category = SkillCategory.MEMORY
        tags("memory", "read", "core")
        inputType = MemoryReadInput::class
        outputType = MemoryReadOutput::class
        isRetryable = true
        recommendedTimeoutMs = 5_000L
    }
    
    override suspend fun validate(input: MemoryReadInput): SkillResult<Unit> {
        return validate {
            requireNotBlank(input.key, "key", "Memory key cannot be blank")
        }
    }
    
    override suspend fun doExecute(
        input: MemoryReadInput,
        context: SkillContext,
    ): SkillResult<MemoryReadOutput> {
        val options = MemoryReadOptions(
            includeExpired = input.includeExpired,
            filterTags = input.filterTags,
        )
        
        return try {
            val entry = context.memoryStore.read<Any>(
                key = input.key,
                options = options,
            )
            
            if (entry != null) {
                // Type check
                if (!input.valueType.isInstance(entry.value)) {
                    return SkillResult.failure(
                        error = SkillError(
                            code = ErrorCode.VALIDATION,
                            message = "Value type mismatch. Expected ${input.valueType.simpleName}, got ${entry.value::class.simpleName}",
                            details = mapOf(
                                "expectedType" to input.valueType.name,
                                "actualType" to entry.value::class.java.name,
                            )
                        )
                    )
                }
                
                SkillResult.success(
                    MemoryReadOutput(
                        key = input.key,
                        value = entry.value,
                        found = true,
                        createdAt = entry.createdAt,
                        updatedAt = entry.updatedAt,
                        metadata = entry.metadata,
                        tags = entry.tags,
                    )
                )
            } else {
                SkillResult.success(
                    MemoryReadOutput(
                        key = input.key,
                        value = null,
                        found = false,
                    )
                )
            }
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
 * Extension function for type-safe memory read input creation.
 */
inline fun <reified T : Any> memoryReadInput(
    key: String,
    includeExpired: Boolean = false,
    filterTags: Set<String> = emptySet(),
): MemoryReadInput = MemoryReadInput(
    key = key,
    valueType = T::class.java,
    includeExpired = includeExpired,
    filterTags = filterTags,
)
