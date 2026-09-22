package com.agentcore.di

import com.agentcore.controller.AgentController
import com.agentcore.controller.AgentControllerImpl
import com.agentcore.memory.InMemoryStore
import com.agentcore.memory.MemoryStore
import com.agentcore.skill.registry.SkillRegistry
import com.agentcore.skill.registry.SkillRegistryImpl
import com.agentcore.skills.di.KoinBestPracticesSkill
import com.agentcore.skills.di.KoinModuleGeneratorSkill
import com.agentcore.skills.di.KoinValidationSkill
import com.agentcore.skills.fallback.FallbackSkill
import com.agentcore.skills.intent.IntentDetectSkill
import com.agentcore.skills.logging.ConsoleLogger
import com.agentcore.skills.logging.Logger
import com.agentcore.skills.logging.LoggingSkill
import com.agentcore.skills.memory.MemoryReadSkill
import com.agentcore.skills.memory.MemoryWriteSkill
import com.agentcore.skills.network.NetworkMonitor
import com.agentcore.skills.network.NetworkStatusCheckSkill
import com.agentcore.skills.network.StubNetworkMonitor
import com.agentcore.util.Clock
import com.agentcore.util.CoroutineDispatchers
import com.agentcore.util.DefaultCoroutineDispatchers
import com.agentcore.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection setup for Agent Core.
 * 
 * This can be adapted for Hilt, Koin, or any other DI framework.
 * The key principle is that all dependencies are created through
 * interfaces, allowing easy swapping for testing.
 */
class AgentCoreModule private constructor(
    val clock: Clock,
    val dispatchers: CoroutineDispatchers,
    val logger: Logger,
    val networkMonitor: NetworkMonitor,
    val memoryStore: MemoryStore,
    val skillRegistry: SkillRegistry,
    val agentController: AgentController,
) {
    companion object {
        /**
         * Creates a default production configuration.
         */
        fun createDefault(
            applicationScope: CoroutineScope,
            networkMonitor: NetworkMonitor = StubNetworkMonitor(),
        ): AgentCoreModule {
            val clock = SystemClock()
            val dispatchers = DefaultCoroutineDispatchers()
            val logger = ConsoleLogger()
            val memoryStore = InMemoryStore(clock)
            val skillRegistry = createSkillRegistry(logger, networkMonitor)
            
            val agentController = AgentControllerImpl(
                skillRegistry = skillRegistry,
                memoryStore = memoryStore,
                dispatchers = dispatchers,
                clock = clock,
                parentScope = applicationScope,
            )
            
            return AgentCoreModule(
                clock = clock,
                dispatchers = dispatchers,
                logger = logger,
                networkMonitor = networkMonitor,
                memoryStore = memoryStore,
                skillRegistry = skillRegistry,
                agentController = agentController,
            )
        }
        
        /**
         * Creates a test configuration with controllable dependencies.
         */
        fun createForTesting(
            clock: Clock,
            dispatchers: CoroutineDispatchers,
            scope: CoroutineScope,
            networkMonitor: NetworkMonitor = StubNetworkMonitor(),
        ): AgentCoreModule {
            val logger = ConsoleLogger()
            val memoryStore = InMemoryStore(clock)
            val skillRegistry = createSkillRegistry(logger, networkMonitor)
            
            val agentController = AgentControllerImpl(
                skillRegistry = skillRegistry,
                memoryStore = memoryStore,
                dispatchers = dispatchers,
                clock = clock,
                parentScope = scope,
            )
            
            return AgentCoreModule(
                clock = clock,
                dispatchers = dispatchers,
                logger = logger,
                networkMonitor = networkMonitor,
                memoryStore = memoryStore,
                skillRegistry = skillRegistry,
                agentController = agentController,
            )
        }
        
        /**
         * Creates and populates the skill registry with core skills.
         */
        private fun createSkillRegistry(
            logger: Logger,
            networkMonitor: NetworkMonitor,
        ): SkillRegistry {
            val registry = SkillRegistryImpl()
            
            // Register core skills
            registry.register(MemoryReadSkill())
            registry.register(MemoryWriteSkill())
            registry.register(IntentDetectSkill())
            registry.register(FallbackSkill())
            registry.register(NetworkStatusCheckSkill(networkMonitor))
            registry.register(LoggingSkill(logger))
            
            // Register DI skills
            registry.register(KoinValidationSkill())
            registry.register(KoinModuleGeneratorSkill())
            registry.register(KoinBestPracticesSkill())

            return registry
        }
    }
}

/**
 * Builder for custom AgentCoreModule configuration.
 */
class AgentCoreModuleBuilder {
    private var clock: Clock = SystemClock()
    private var dispatchers: CoroutineDispatchers = DefaultCoroutineDispatchers()
    private var logger: Logger = ConsoleLogger()
    private var networkMonitor: NetworkMonitor = StubNetworkMonitor()
    private var memoryStore: MemoryStore? = null
    private var scope: CoroutineScope? = null
    private val additionalSkillRegistrations = mutableListOf<(SkillRegistry) -> Unit>()
    
    fun clock(clock: Clock) = apply { this.clock = clock }
    fun dispatchers(dispatchers: CoroutineDispatchers) = apply { this.dispatchers = dispatchers }
    fun logger(logger: Logger) = apply { this.logger = logger }
    fun networkMonitor(monitor: NetworkMonitor) = apply { this.networkMonitor = monitor }
    fun memoryStore(store: MemoryStore) = apply { this.memoryStore = store }
    fun scope(scope: CoroutineScope) = apply { this.scope = scope }
    
    fun registerSkills(registration: (SkillRegistry) -> Unit) = apply {
        additionalSkillRegistrations.add(registration)
    }
    
    fun build(): AgentCoreModule {
        val actualScope = scope ?: CoroutineScope(SupervisorJob() + dispatchers.default)
        val actualMemoryStore = memoryStore ?: InMemoryStore(clock)
        
        val skillRegistry = SkillRegistryImpl().apply {
            // Register core skills
            register(MemoryReadSkill())
            register(MemoryWriteSkill())
            register(IntentDetectSkill())
            register(FallbackSkill())
            register(NetworkStatusCheckSkill(networkMonitor))
            register(LoggingSkill(logger))
            
            // Register additional skills
            additionalSkillRegistrations.forEach { it(this) }
        }
        
        val agentController = AgentControllerImpl(
            skillRegistry = skillRegistry,
            memoryStore = actualMemoryStore,
            dispatchers = dispatchers,
            clock = clock,
            parentScope = actualScope,
        )
        
        return AgentCoreModule(
            clock = clock,
            dispatchers = dispatchers,
            logger = logger,
            networkMonitor = networkMonitor,
            memoryStore = actualMemoryStore,
            skillRegistry = skillRegistry,
            agentController = agentController,
        )
    }
}

/**
 * DSL function for creating AgentCoreModule.
 */
fun agentCoreModule(block: AgentCoreModuleBuilder.() -> Unit): AgentCoreModule =
    AgentCoreModuleBuilder().apply(block).build()
