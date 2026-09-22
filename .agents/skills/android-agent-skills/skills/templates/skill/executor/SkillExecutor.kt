package com.agentcore.skill.executor

import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillResult

/**
 * Executor for running individual skills.
 * Provides a consistent execution interface with hooks for monitoring.
 */
interface SkillExecutor {
    
    /**
     * Executes a skill with the given input.
     * 
     * @param skill The skill to execute
     * @param input The input data
     * @param context The execution context
     * @return The skill result
     */
    suspend fun <I : Any, O : Any> execute(
        skill: Skill<I, O>,
        input: I,
        context: SkillContext,
    ): SkillResult<O>
    
    /**
     * Executes a skill by ID with the given input.
     * 
     * @param skillId The ID of the skill to execute
     * @param input The input data
     * @param context The execution context
     * @return The skill result
     */
    suspend fun <I : Any, O : Any> executeById(
        skillId: String,
        input: I,
        context: SkillContext,
    ): SkillResult<O>
}

/**
 * Listener for skill execution events.
 */
interface SkillExecutionListener {
    /**
     * Called before skill execution starts.
     */
    suspend fun onBeforeExecution(
        skill: Skill<*, *>,
        input: Any,
        context: SkillContext,
    ) {}
    
    /**
     * Called after skill execution completes.
     */
    suspend fun onAfterExecution(
        skill: Skill<*, *>,
        input: Any,
        context: SkillContext,
        result: SkillResult<*>,
    ) {}
    
    /**
     * Called when skill execution fails with an exception.
     */
    suspend fun onExecutionError(
        skill: Skill<*, *>,
        input: Any,
        context: SkillContext,
        error: Throwable,
    ) {}
}

/**
 * Composite listener that delegates to multiple listeners.
 */
class CompositeSkillExecutionListener(
    private val listeners: List<SkillExecutionListener>,
) : SkillExecutionListener {
    
    override suspend fun onBeforeExecution(
        skill: Skill<*, *>,
        input: Any,
        context: SkillContext,
    ) {
        listeners.forEach { it.onBeforeExecution(skill, input, context) }
    }
    
    override suspend fun onAfterExecution(
        skill: Skill<*, *>,
        input: Any,
        context: SkillContext,
        result: SkillResult<*>,
    ) {
        listeners.forEach { it.onAfterExecution(skill, input, context, result) }
    }
    
    override suspend fun onExecutionError(
        skill: Skill<*, *>,
        input: Any,
        context: SkillContext,
        error: Throwable,
    ) {
        listeners.forEach { it.onExecutionError(skill, input, context, error) }
    }
}
