package com.agentcore.controller

import com.agentcore.memory.MemoryStore
import com.agentcore.pipeline.Pipeline
import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.executor.ChainResult
import com.agentcore.skill.executor.ChainTraceResult
import com.agentcore.skill.registry.SkillRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main controller for agent operations.
 * Provides a high-level API for executing skills and pipelines.
 */
interface AgentController {
    
    /**
     * The skill registry containing all available skills.
     */
    val skillRegistry: SkillRegistry
    
    /**
     * The memory store for agent state.
     */
    val memoryStore: MemoryStore
    
    /**
     * Current agent state observable.
     */
    val state: StateFlow<AgentState>
    
    /**
     * Stream of agent events.
     */
    val events: Flow<AgentEvent>
    
    /**
     * Executes a skill by ID.
     * 
     * @param skillId The ID of the skill to execute
     * @param input The input data
     * @return The skill result
     */
    suspend fun <I : Any, O : Any> executeSkill(
        skillId: String,
        input: I,
    ): SkillResult<O>
    
    /**
     * Executes a skill directly.
     * 
     * @param skill The skill to execute
     * @param input The input data
     * @return The skill result
     */
    suspend fun <I : Any, O : Any> executeSkill(
        skill: Skill<I, O>,
        input: I,
    ): SkillResult<O>
    
    /**
     * Executes a pipeline.
     * 
     * @param pipeline The pipeline to execute
     * @param input The initial input
     * @return The chain result
     */
    suspend fun <I : Any, O : Any> executePipeline(
        pipeline: Pipeline<I, O>,
        input: I,
    ): ChainResult<O>
    
    /**
     * Executes a pipeline with detailed trace.
     * 
     * @param pipeline The pipeline to execute
     * @param input The initial input
     * @return The chain trace result
     */
    suspend fun <I : Any, O : Any> executePipelineWithTrace(
        pipeline: Pipeline<I, O>,
        input: I,
    ): ChainTraceResult<O>
    
    /**
     * Creates a new execution context.
     * 
     * @param extras Additional context data
     * @return A new SkillContext
     */
    fun createContext(extras: Map<String, Any> = emptyMap()): SkillContext
    
    /**
     * Cancels all running operations.
     */
    suspend fun cancelAll()
    
    /**
     * Resets the agent state.
     */
    suspend fun reset()
}

/**
 * Represents the current state of the agent.
 */
data class AgentState(
    /**
     * Whether the agent is currently executing.
     */
    val isExecuting: Boolean = false,
    
    /**
     * Name of the currently executing skill/pipeline.
     */
    val currentOperation: String? = null,
    
    /**
     * Progress of the current operation (0.0 to 1.0).
     */
    val progress: Float = 0f,
    
    /**
     * Number of operations executed in this session.
     */
    val operationCount: Int = 0,
    
    /**
     * Last error that occurred, if any.
     */
    val lastError: String? = null,
    
    /**
     * Additional state data.
     */
    val extras: Map<String, Any> = emptyMap(),
) {
    companion object {
        val Initial = AgentState()
    }
}

/**
 * Events emitted by the agent.
 */
sealed interface AgentEvent {
    val timestamp: Long
    
    data class SkillStarted(
        val skillId: String,
        val skillName: String,
        override val timestamp: Long,
    ) : AgentEvent
    
    data class SkillCompleted(
        val skillId: String,
        val skillName: String,
        val success: Boolean,
        val durationMs: Long,
        override val timestamp: Long,
    ) : AgentEvent
    
    data class PipelineStarted(
        val pipelineName: String,
        val totalSteps: Int,
        override val timestamp: Long,
    ) : AgentEvent
    
    data class PipelineStepCompleted(
        val pipelineName: String,
        val stepName: String,
        val stepIndex: Int,
        val success: Boolean,
        override val timestamp: Long,
    ) : AgentEvent
    
    data class PipelineCompleted(
        val pipelineName: String,
        val success: Boolean,
        val completedSteps: Int,
        val totalSteps: Int,
        val durationMs: Long,
        override val timestamp: Long,
    ) : AgentEvent
    
    data class Error(
        val message: String,
        val source: String,
        override val timestamp: Long,
    ) : AgentEvent
    
    data class StateChanged(
        val previousState: AgentState,
        val newState: AgentState,
        override val timestamp: Long,
    ) : AgentEvent
}
