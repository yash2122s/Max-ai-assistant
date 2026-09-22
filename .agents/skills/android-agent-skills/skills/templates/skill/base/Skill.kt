package com.agentcore.skill.base

/**
 * Base interface for all agent skills.
 * 
 * A Skill is an isolated, reusable module that:
 * - Receives structured input
 * - Performs one focused task
 * - Returns structured output
 * - Is fully testable
 * - Has no dependency on Android framework or Compose
 * 
 * @param Input The type of input this skill accepts
 * @param Output The type of output this skill produces
 */
interface Skill<Input : Any, Output : Any> {
    
    /**
     * Metadata describing this skill's capabilities.
     */
    val metadata: SkillMetadata
    
    /**
     * Executes the skill with the given input and context.
     * 
     * @param input The input data for the skill
     * @param context The execution context providing shared resources
     * @return A SkillResult containing either the output or an error
     */
    suspend fun execute(input: Input, context: SkillContext): SkillResult<Output>
    
    /**
     * Validates the input before execution.
     * Override to provide custom validation logic.
     * 
     * @param input The input to validate
     * @return A SkillResult with Unit on success, or Failure with validation error
     */
    suspend fun validate(input: Input): SkillResult<Unit> = SkillResult.success(Unit)
    
    /**
     * Called before execution starts.
     * Override to perform setup operations.
     */
    suspend fun onBeforeExecute(input: Input, context: SkillContext) {}
    
    /**
     * Called after execution completes (success or failure).
     * Override to perform cleanup operations.
     */
    suspend fun onAfterExecute(
        input: Input,
        context: SkillContext,
        result: SkillResult<Output>,
    ) {}
}

/**
 * Abstract base implementation providing common functionality.
 */
abstract class BaseSkill<Input : Any, Output : Any> : Skill<Input, Output> {
    
    /**
     * Template method that handles the execution lifecycle.
     */
    final override suspend fun execute(
        input: Input,
        context: SkillContext,
    ): SkillResult<Output> {
        // Validate input
        val validationResult = validate(input)
        if (validationResult.isFailure) {
            return SkillResult.Failure(
                error = (validationResult as SkillResult.Failure).error,
                metadata = ExecutionMetadata(skillName = metadata.name),
            )
        }
        
        // Execute with lifecycle hooks
        onBeforeExecute(input, context)
        
        val startTime = context.clock.currentTimeMillis()
        val result = try {
            doExecute(input, context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // ALWAYS rethrow CancellationException
        } catch (e: Exception) {
            SkillResult.failure(
                exception = e,
                code = ErrorCode.EXECUTION_FAILED,
            )
        }
        val executionTime = context.clock.currentTimeMillis() - startTime
        
        // Add execution metadata
        val resultWithMetadata = when (result) {
            is SkillResult.Success -> result.copy(
                metadata = result.metadata.copy(
                    executionTime = kotlin.time.Duration.Companion.milliseconds(executionTime),
                    skillName = metadata.name,
                    traceId = context.traceId,
                )
            )
            is SkillResult.Failure -> result.copy(
                metadata = result.metadata.copy(
                    executionTime = kotlin.time.Duration.Companion.milliseconds(executionTime),
                    skillName = metadata.name,
                    traceId = context.traceId,
                )
            )
        }
        
        onAfterExecute(input, context, resultWithMetadata)
        
        return resultWithMetadata
    }
    
    /**
     * Implement the actual skill logic here.
     * This is called after validation and lifecycle hooks.
     */
    protected abstract suspend fun doExecute(
        input: Input,
        context: SkillContext,
    ): SkillResult<Output>
}

/**
 * Marker interface for skills that can be composed in chains.
 */
interface ChainableSkill<Input : Any, Output : Any> : Skill<Input, Output>

/**
 * Interface for skills that can handle multiple input types.
 */
interface PolymorphicSkill {
    /**
     * Returns true if this skill can handle the given input type.
     */
    fun canHandle(inputType: Class<*>): Boolean
}

/**
 * Type alias for skill factory functions.
 */
typealias SkillFactory<Input, Output> = () -> Skill<Input, Output>
