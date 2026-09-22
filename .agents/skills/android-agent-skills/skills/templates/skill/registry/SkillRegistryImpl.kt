package com.agentcore.skill.registry

import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillMetadata
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Thread-safe implementation of SkillRegistry.
 */
class SkillRegistryImpl : SkillRegistry {
    
    private sealed interface SkillEntry {
        val metadata: SkillMetadata
        fun getSkill(): Skill<*, *>
        
        data class Direct(
            val skill: Skill<*, *>,
        ) : SkillEntry {
            override val metadata: SkillMetadata = skill.metadata
            override fun getSkill(): Skill<*, *> = skill
        }
        
        data class Factory(
            override val metadata: SkillMetadata,
            val factory: () -> Skill<*, *>,
        ) : SkillEntry {
            private val lazySkill by lazy { factory() }
            override fun getSkill(): Skill<*, *> = lazySkill
        }
    }
    
    private val skills = ConcurrentHashMap<String, SkillEntry>()
    private val typeIndex = ConcurrentHashMap<KClass<*>, String>()
    
    override fun <I : Any, O : Any> register(skill: Skill<I, O>) {
        register(skill.metadata.id, skill)
    }
    
    override fun <I : Any, O : Any> register(id: String, skill: Skill<I, O>) {
        if (skills.containsKey(id)) {
            throw SkillAlreadyRegisteredException(id)
        }
        
        skills[id] = SkillEntry.Direct(skill)
        typeIndex[skill::class] = id
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <I : Any, O : Any> registerFactory(
        id: String,
        metadata: SkillMetadata,
        factory: () -> Skill<I, O>,
    ) {
        if (skills.containsKey(id)) {
            throw SkillAlreadyRegisteredException(id)
        }
        
        skills[id] = SkillEntry.Factory(
            metadata = metadata,
            factory = factory as () -> Skill<*, *>,
        )
    }
    
    override fun unregister(id: String): Boolean {
        val entry = skills.remove(id)
        if (entry != null) {
            // Remove from type index
            typeIndex.entries.removeIf { it.value == id }
            return true
        }
        return false
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <I : Any, O : Any> resolve(id: String): Skill<I, O>? {
        return skills[id]?.getSkill() as? Skill<I, O>
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun <I : Any, O : Any, S : Skill<I, O>> resolveByType(type: KClass<S>): S? {
        val id = typeIndex[type] ?: return null
        return skills[id]?.getSkill() as? S
    }
    
    override fun getAll(): List<Skill<*, *>> {
        return skills.values.map { it.getSkill() }
    }
    
    override fun getAllMetadata(): List<SkillMetadata> {
        return skills.values.map { it.metadata }
    }
    
    override fun findByCategory(category: SkillCategory): List<Skill<*, *>> {
        return skills.values
            .filter { it.metadata.category == category }
            .map { it.getSkill() }
    }
    
    override fun findByTags(tags: Set<String>): List<Skill<*, *>> {
        return skills.values
            .filter { entry -> tags.any { it in entry.metadata.tags } }
            .map { it.getSkill() }
    }
    
    override fun findByMetadata(predicate: (SkillMetadata) -> Boolean): List<Skill<*, *>> {
        return skills.values
            .filter { predicate(it.metadata) }
            .map { it.getSkill() }
    }
    
    override fun contains(id: String): Boolean = skills.containsKey(id)
    
    override fun count(): Int = skills.size
    
    override fun clear() {
        skills.clear()
        typeIndex.clear()
    }
}

/**
 * Builder DSL for SkillRegistry configuration.
 */
class SkillRegistryBuilder {
    private val registry = SkillRegistryImpl()
    
    fun <I : Any, O : Any> skill(skill: Skill<I, O>) {
        registry.register(skill)
    }
    
    fun <I : Any, O : Any> skill(id: String, skill: Skill<I, O>) {
        registry.register(id, skill)
    }
    
    fun <I : Any, O : Any> skillFactory(
        id: String,
        metadata: SkillMetadata,
        factory: () -> Skill<I, O>,
    ) {
        registry.registerFactory(id, metadata, factory)
    }
    
    fun build(): SkillRegistry = registry
}

/**
 * DSL function for creating a configured SkillRegistry.
 */
fun skillRegistry(block: SkillRegistryBuilder.() -> Unit): SkillRegistry =
    SkillRegistryBuilder().apply(block).build()
