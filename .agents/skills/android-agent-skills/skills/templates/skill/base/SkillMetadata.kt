package com.agentcore.skill.base

import kotlin.reflect.KClass

/**
 * Metadata that describes a skill's capabilities and requirements.
 */
data class SkillMetadata(
    /**
     * Unique identifier for the skill.
     */
    val id: String,
    
    /**
     * Human-readable name of the skill.
     */
    val name: String,
    
    /**
     * Description of what the skill does.
     */
    val description: String,
    
    /**
     * Version of the skill implementation.
     */
    val version: String = "1.0.0",
    
    /**
     * Category or group this skill belongs to.
     */
    val category: SkillCategory = SkillCategory.GENERAL,
    
    /**
     * Tags for skill discovery and filtering.
     */
    val tags: Set<String> = emptySet(),
    
    /**
     * Input type class for runtime type checking.
     */
    val inputType: KClass<*>,
    
    /**
     * Output type class for runtime type checking.
     */
    val outputType: KClass<*>,
    
    /**
     * Whether this skill requires network connectivity.
     */
    val requiresNetwork: Boolean = false,
    
    /**
     * Whether this skill performs I/O operations.
     */
    val isIoOperation: Boolean = false,
    
    /**
     * Whether this skill can be safely retried on failure.
     */
    val isRetryable: Boolean = true,
    
    /**
     * Maximum recommended timeout for this skill.
     */
    val recommendedTimeoutMs: Long = 30_000L,
    
    /**
     * Priority level for execution ordering.
     */
    val priority: Int = 0,
    
    /**
     * Additional custom metadata.
     */
    val extras: Map<String, Any> = emptyMap(),
)

/**
 * Categories for organizing skills.
 */
enum class SkillCategory {
    GENERAL,
    MEMORY,
    NETWORK,
    INTENT,
    LOGGING,
    FALLBACK,
    DOMAIN,
    UTILITY,
    INTEGRATION,
}

/**
 * Builder for creating SkillMetadata with a DSL-style API.
 */
class SkillMetadataBuilder {
    var id: String = ""
    var name: String = ""
    var description: String = ""
    var version: String = "1.0.0"
    var category: SkillCategory = SkillCategory.GENERAL
    var tags: MutableSet<String> = mutableSetOf()
    var inputType: KClass<*> = Any::class
    var outputType: KClass<*> = Any::class
    var requiresNetwork: Boolean = false
    var isIoOperation: Boolean = false
    var isRetryable: Boolean = true
    var recommendedTimeoutMs: Long = 30_000L
    var priority: Int = 0
    var extras: MutableMap<String, Any> = mutableMapOf()
    
    fun tags(vararg tags: String) {
        this.tags.addAll(tags)
    }
    
    fun extra(key: String, value: Any) {
        extras[key] = value
    }
    
    fun build(): SkillMetadata = SkillMetadata(
        id = id.ifBlank { error("Skill id is required") },
        name = name.ifBlank { id },
        description = description,
        version = version,
        category = category,
        tags = tags.toSet(),
        inputType = inputType,
        outputType = outputType,
        requiresNetwork = requiresNetwork,
        isIoOperation = isIoOperation,
        isRetryable = isRetryable,
        recommendedTimeoutMs = recommendedTimeoutMs,
        priority = priority,
        extras = extras.toMap(),
    )
}

/**
 * DSL function for creating SkillMetadata.
 */
inline fun skillMetadata(block: SkillMetadataBuilder.() -> Unit): SkillMetadata =
    SkillMetadataBuilder().apply(block).build()
