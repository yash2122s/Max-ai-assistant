package com.agentcore.controller

import com.agentcore.memory.MemoryStore
import com.agentcore.pipeline.Pipeline
import com.agentcore.skill.base.DefaultSkillContext
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.executor.ChainResult
import com.agentcore.skill.executor.ChainTraceResult
import com.agentcore.skill.executor.SkillChainExecutor
import com.agentcore.skill.executor.SkillChainExecutorImpl
import com.agentcore.skill.registry.SkillRegistry
import com.agentcore.util.Clock
import com.agentcore.util.CoroutineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production implementation of AgentController.
 */
class AgentControllerImpl(
    override val skillRegistry: SkillRegistry,
    override val memoryStore: MemoryStore,
    private val dispatchers: CoroutineDispatchers,
    private val clock: Clock,
    private val parentScope: CoroutineScope,
) : AgentController {
    
    private val controllerScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob()
    )
    
    private val chainExecutor: SkillChainExecutor = SkillChainExecutorImpl()
    
    private val _state = MutableStateFlow(AgentState.Initial)
    override val state: StateFlow<AgentState> = _state.asStateFlow()
    
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val events: Flow<AgentEvent> = _events.asSharedFlow()
    
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val stateMutex = Mutex()
    
    override suspend fun <I : Any, O : Any> executeSkill(
        skillId: String,
        input: I,
    ): SkillResult<O> {
        val skill = skillRegistry.resolve<I, O>(skillId)
            ?: return SkillResult.Failure(
                error = SkillError(
                    code = ErrorCode.SKILL_NOT_FOUND,
                    message = "Skill with ID '$skillId' not found",
                )
            )
        
        return executeSkill(skill, input)
    }
    
    override suspend fun <I : Any, O : Any> executeSkill(
        skill: Skill<I, O>,
        input: I,
    ): SkillResult<O> = withContext(dispatchers.default) {
        val context = createContext()
        val startTime = clock.currentTimeMillis()
        
        updateState { copy(isExecuting = true, currentOperation = skill.metadata.name) }
        emitEvent(AgentEvent.SkillStarted(
            skillId = skill.metadata.id,
            skillName = skill.metadata.name,
            timestamp = startTime,
        ))
        
        val result = try {
            skill.execute(input, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // ALWAYS rethrow CancellationException
        } catch (e: Exception) {
            SkillResult.failure(e, ErrorCode.EXECUTION_FAILED)
        }
        
        val endTime = clock.currentTimeMillis()
        val duration = endTime - startTime
        
        updateState { 
            copy(
                isExecuting = false,
                currentOperation = null,
                operationCount = operationCount + 1,
                lastError = if (result.isFailure) result.errorOrNull()?.message else null,
            )
        }
        
        emitEvent(AgentEvent.SkillCompleted(
            skillId = skill.metadata.id,
            skillName = skill.metadata.name,
            success = result.isSuccess,
            durationMs = duration,
            timestamp = endTime,
        ))
        
        result
    }
    
    override suspend fun <I : Any, O : Any> executePipeline(
        pipeline: Pipeline<I, O>,
        input: I,
    ): ChainResult<O> {
        return executePipelineWithTrace(pipeline, input).result
    }
    
    override suspend fun <I : Any, O : Any> executePipelineWithTrace(
        pipeline: Pipeline<I, O>,
        input: I,
    ): ChainTraceResult<O> = withContext(dispatchers.default) {
        val context = createContext()
        val startTime = clock.currentTimeMillis()
        
        updateState { 
            copy(
                isExecuting = true,
                currentOperation = pipeline.name,
                progress = 0f,
            )
        }
        
        emitEvent(AgentEvent.PipelineStarted(
            pipelineName = pipeline.name,
            totalSteps = pipeline.steps.size,
            timestamp = startTime,
        ))
        
        val traceResult = chainExecutor.executeWithTrace(pipeline, input, context)
        
        val endTime = clock.currentTimeMillis()
        val duration = endTime - startTime
        
        // Emit step completion events
        traceResult.trace.forEach { trace ->
            emitEvent(AgentEvent.PipelineStepCompleted(
                pipelineName = pipeline.name,
                stepName = trace.stepName,
                stepIndex = trace.stepIndex,
                success = trace.result.isSuccess,
                timestamp = clock.currentTimeMillis(),
            ))
        }
        
        val success = traceResult.result is ChainResult.Success
        val completedSteps = when (val result = traceResult.result) {
            is ChainResult.Success -> result.metadata.completedSteps
            is ChainResult.PartialSuccess -> result.metadata.completedSteps
            is ChainResult.Failure -> result.metadata.completedSteps
        }
        
        updateState {
            copy(
                isExecuting = false,
                currentOperation = null,
                progress = 0f,
                operationCount = operationCount + 1,
                lastError = if (!success) {
                    when (val result = traceResult.result) {
                        is ChainResult.Failure -> result.error.message
                        is ChainResult.PartialSuccess -> result.error.message
                        else -> null
                    }
                } else null,
            )
        }
        
        emitEvent(AgentEvent.PipelineCompleted(
            pipelineName = pipeline.name,
            success = success,
            completedSteps = completedSteps,
            totalSteps = pipeline.steps.size,
            durationMs = duration,
            timestamp = endTime,
        ))
        
        traceResult
    }
    
    override fun createContext(extras: Map<String, Any>): SkillContext {
        return DefaultSkillContext(
            traceId = UUID.randomUUID().toString(),
            parentTraceId = null,
            startTimeMillis = clock.currentTimeMillis(),
            memoryStore = memoryStore,
            dispatchers = dispatchers,
            clock = clock,
            scope = controllerScope,
            extras = extras,
        )
    }
    
    override suspend fun cancelAll() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        updateState { copy(isExecuting = false, currentOperation = null, progress = 0f) }
    }
    
    override suspend fun reset() {
        cancelAll()
        memoryStore.clear()
        _state.value = AgentState.Initial
    }
    
    private suspend fun updateState(update: AgentState.() -> AgentState) {
        stateMutex.withLock {
            val previous = _state.value
            _state.update(update)
            val new = _state.value
            if (previous != new) {
                emitEvent(AgentEvent.StateChanged(
                    previousState = previous,
                    newState = new,
                    timestamp = clock.currentTimeMillis(),
                ))
            }
        }
    }
    
    private suspend fun emitEvent(event: AgentEvent) {
        _events.emit(event)
    }
}

/**
 * Builder for AgentController.
 */
class AgentControllerBuilder {
    private var skillRegistry: SkillRegistry? = null
    private var memoryStore: MemoryStore? = null
    private var dispatchers: CoroutineDispatchers? = null
    private var clock: Clock? = null
    private var scope: CoroutineScope? = null
    
    fun skillRegistry(registry: SkillRegistry) = apply { this.skillRegistry = registry }
    fun memoryStore(store: MemoryStore) = apply { this.memoryStore = store }
    fun dispatchers(dispatchers: CoroutineDispatchers) = apply { this.dispatchers = dispatchers }
    fun clock(clock: Clock) = apply { this.clock = clock }
    fun scope(scope: CoroutineScope) = apply { this.scope = scope }
    
    fun build(): AgentController {
        return AgentControllerImpl(
            skillRegistry = skillRegistry ?: error("SkillRegistry is required"),
            memoryStore = memoryStore ?: error("MemoryStore is required"),
            dispatchers = dispatchers ?: error("CoroutineDispatchers is required"),
            clock = clock ?: error("Clock is required"),
            parentScope = scope ?: error("CoroutineScope is required"),
        )
    }
}

/**
 * DSL function for creating AgentController.
 */
fun agentController(block: AgentControllerBuilder.() -> Unit): AgentController =
    AgentControllerBuilder().apply(block).build()
