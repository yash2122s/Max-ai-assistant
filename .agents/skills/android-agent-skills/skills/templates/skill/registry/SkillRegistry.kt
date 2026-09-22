package com.agentcore.skill.registry

import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillMetadata
import kotlin.reflect.KClass

/**
 * Registry for managing and resolving skills.
 * 
 * Provides functionality to:
 * - Register skills by ID, name, or type
 * - Resolve skills at runtime
 * - Query skills by category, tags, or capabilities
 */
interface SkillRegistry {
    
    /**
     * Registers a skill in the registry.
     * 
     * @param skill The skill to register
     * @throws SkillAlreadyRegisteredException if a skill with same ID exists
     */
    fun <I : Any, O : Any> register(skill: Skill<I, O>)
    
    /**
     * Registers a skill with a custom ID.
     * 
     * @param id Custom identifier for the skill
     * @param skill The skill to register
     */
    fun <I : Any, O : Any> register(id: String, skill: Skill<I, O>)
    
    /**
     * Registers a skill factory for lazy instantiation.
     * 
     * @param id Identifier for the skill
     * @param factory Factory function to create the skill
     */
    fun <I : Any, O : Any> registerFactory(
        id: String,
        metadata: SkillMetadata,
        factory: () -> Skill<I, O>,
    )
    
    /**
     * Unregisters a skill by ID.
     * 
     * @param id The ID of the skill to unregister
     * @return true if a skill was unregistered
     */
    fun unregister(id: String): Boolean
    
    /**
     * Resolves a skill by ID.
     * 
     * @param id The skill ID
     * @return The skill, or null if not found
     */
    fun <I : Any, O : Any> resolve(id: String): Skill<I, O>?
    
    /**
     * Resolves a skill by type.
     * 
     * @param type The skill class type
     * @return The skill, or null if not found
     */
    fun <I : Any, O : Any, S : Skill<I, O>> resolveByType(type: KClass<S>): S?
    
    /**
     * Gets all registered skills.
     * 
     * @return List of all skills
     */
    fun getAll(): List<Skill<*, *>>
    
    /**
     * Gets all skill metadata.
     * 
     * @return List of all skill metadata
     */
    fun getAllMetadata(): List<SkillMetadata>
    
    /**
     * Queries skills by category.
     * 
     * @param category The category to filter by
     * @return Skills in the specified category
     */
    fun findByCategory(category: SkillCategory): List<Skill<*, *>>
    
    /**
     * Queries skills by tags.
     * 
     * @param tags Tags to match (any of)
     * @return Skills matching any of the tags
     */
    fun findByTags(tags: Set<String>): List<Skill<*, *>>
    
    /**
     * Queries skills by a custom predicate on metadata.
     * 
     * @param predicate Filter function on metadata
     * @return Skills matching the predicate
     */
    fun findByMetadata(predicate: (SkillMetadata) -> Boolean): List<Skill<*, *>>
    
    /**
     * Checks if a skill with the given ID exists.
     * 
     * @param id The skill ID to check
     * @return true if the skill exists
     */
    fun contains(id: String): Boolean
    
    /**
     * Gets the count of registered skills.
     */
    fun count(): Int
    
    /**
     * Clears all registered skills.
     */
    fun clear()
}

/**
 * Exception thrown when attempting to register a skill with a duplicate ID.
 */
class SkillAlreadyRegisteredException(
    val skillId: String,
) : RuntimeException("Skill with ID '$skillId' is already registered")

/**
 * Exception thrown when a required skill is not found.
 */
class SkillNotFoundException(
    val skillId: String,
) : RuntimeException("Skill with ID '$skillId' not found")
