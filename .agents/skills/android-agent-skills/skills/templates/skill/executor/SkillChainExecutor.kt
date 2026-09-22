package com.agentcore.skill.executor

import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.ExecutionMetadata
import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillResult
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * Executor for running multi-step skill pipelines.
 */
interface SkillChainExecutor {
    
    /**
     * Executes a chain of skills sequentially.
     * The output of each skill becomes the input to the next.
     * 
     * @param chain The skill chain definition
     * @param initialInput The input to the first skill
     * @param context The execution context
     * @return The result of the chain execution
     */
    suspend fun <I : Any, O : Any> execute(
        chain: SkillChain<I, O>,
        initialInput: I,
        context: SkillContext,
    ): ChainResult<O>
    
    /**
     * Executes a chain and collects intermediate results.
     * 
     * @param chain The skill chain definition
     * @param initialInput The input to the first skill
     * @param context The execution context
     * @return The detailed result including all intermediate outputs
     */
    suspend fun <I : Any, O : Any> executeWithTrace(
        chain: SkillChain<I, O>,
        initialInput: I,
        context: SkillContext,
    ): ChainTraceResult<O>
}

/**
 * Represents a chain of skills to execute.
 */
interface SkillChain<Input : Any, Output : Any> {
    /**
     * The steps in this chain.
     */
    val steps: List<ChainStep<*, *>>
    
    /**
     * Name of this chain for debugging.
     */
    val name: String
    
    /**
     * Whether to stop on first failure.
     */
    val stopOnFailure: Boolean
}

/**
 * A single step in a skill chain.
 */
data class ChainStep<Input : Any, Output : Any>(
    /**
     * The skill to execute in this step.
     */
    val skill: Skill<Input, Output>,
    
    /**
     * Optional name for this step.
     */
    val name: String = skill.metadata.name,
    
    /**
     * Whether this step is required.
     * If false, failure will not stop the chain.
     */
    val required: Boolean = true,
    
    /**
     * Optional transformer for the input before execution.
     */
    val inputTransformer: ((Any) -> Input)? = null,
    
    /**
     * Optional transformer for the output after execution.
     */
    val outputTransformer: ((Output) -> Any)? = null,
    
    /**
     * Fallback value if this step fails and is not required.
     */
    val fallbackValue: (() -> Output)? = null,
)

/**
 * Result of a chain execution.
 */
sealed interface ChainResult<out T> {
    data class Success<T>(
        val data: T,
        val metadata: ChainExecutionMetadata,
    ) : ChainResult<T>
    
    data class PartialSuccess<T>(
        val data: T?,
        val failedStep: String,
        val error: SkillError,
        val metadata: ChainExecutionMetadata,
    ) : ChainResult<T>
    
    data class Failure(
        val failedStep: String,
        val stepIndex: Int,
        val error: SkillError,
        val metadata: ChainExecutionMetadata,
    ) : ChainResult<Nothing>
    
    val isSuccess: Boolean
        get() = this is Success
    
    val isFailure: Boolean
        get() = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is PartialSuccess -> data
        is Failure -> null
    }
}

/**
 * Metadata about chain execution.
 */
data class ChainExecutionMetadata(
    val chainName: String,
    val totalSteps: Int,
    val completedSteps: Int,
    val totalExecutionTime: Duration,
    val stepTimes: Map<String, Duration>,
    val traceId: String,
)

/**
 * Detailed trace result including intermediate outputs.
 */
data class ChainTraceResult<Output>(
    val result: ChainResult<Output>,
    val trace: List<StepTrace>,
) {
    /**
     * Gets all successful step outputs.
     */
    fun getSuccessfulOutputs(): List<Any> =
        trace.filter { it.result.isSuccess }.mapNotNull { it.result.getOrNull() }
}

/**
 * Trace information for a single step.
 */
data class StepTrace(
    val stepName: String,
    val stepIndex: Int,
    val skillId: String,
    val input: Any,
    val result: SkillResult<Any>,
    val executionTime: Duration,
)

/**
 * Default implementation of SkillChainExecutor.
 */
class SkillChainExecutorImpl : SkillChainExecutor {
    
    @Suppress("UNCHECKED_CAST")
    override suspend fun <I : Any, O : Any> execute(
        chain: SkillChain<I, O>,
        initialInput: I,
        context: SkillContext,
    ): ChainResult<O> {
        val traceResult = executeWithTrace(chain, initialInput, context)
        return traceResult.result
    }
    
    @Suppress("UNCHECKED_CAST")
    override suspend fun <I : Any, O : Any> executeWithTrace(
        chain: SkillChain<I, O>,
        initialInput: I,
        context: SkillContext,
    ): ChainTraceResult<O> {
        val traces = mutableListOf<StepTrace>()
        val stepTimes = mutableMapOf<String, Duration>()
        val startTime = context.clock.currentTimeMillis()
        
        var currentInput: Any = initialInput
        var lastOutput: Any? = null
        var completedSteps = 0
        
        for ((index, step) in chain.steps.withIndex()) {
            // Check for cancellation before each step (cooperative cancellation)
            coroutineContext.ensureActive()
            
            val stepStartTime = context.clock.currentTimeMillis()
            
            // Transform input if transformer provided
            val transformedInput = step.inputTransformer?.invoke(currentInput) ?: currentInput
            
            // Execute the skill
            val skill = step.skill as Skill<Any, Any>
            val result = try {
                skill.execute(transformedInput, context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // ALWAYS rethrow CancellationException
            } catch (e: Exception) {
                SkillResult.failure(e, ErrorCode.EXECUTION_FAILED)
            }
            
            val stepTime = Duration.Companion.milliseconds(
                context.clock.currentTimeMillis() - stepStartTime
            )
            stepTimes[step.name] = stepTime
            
            // Record trace
            traces.add(
                StepTrace(
                    stepName = step.name,
                    stepIndex = index,
                    skillId = step.skill.metadata.id,
                    input = transformedInput,
                    result = result,
                    executionTime = stepTime,
                )
            )
            
            when (result) {
                is SkillResult.Success -> {
                    // Transform output if transformer provided
                    val transformedOutput = step.outputTransformer?.invoke(result.data)
                        ?: result.data
                    currentInput = transformedOutput
                    lastOutput = transformedOutput
                    completedSteps++
                }
                
                is SkillResult.Failure -> {
                    val totalTime = Duration.Companion.milliseconds(
                        context.clock.currentTimeMillis() - startTime
                    )
                    val metadata = ChainExecutionMetadata(
                        chainName = chain.name,
                        totalSteps = chain.steps.size,
                        completedSteps = completedSteps,
                        totalExecutionTime = totalTime,
                        stepTimes = stepTimes,
                        traceId = context.traceId,
                    )
                    
                    // Check if step is required
                    if (step.required) {
                        if (chain.stopOnFailure) {
                            return ChainTraceResult(
                                result = ChainResult.Failure(
                                    failedStep = step.name,
                                    stepIndex = index,
                                    error = result.error,
                                    metadata = metadata,
                                ),
                                trace = traces,
                            )
                        }
                    }
                    
                    // Try fallback value
                    val fallback = step.fallbackValue?.invoke()
                    if (fallback != null) {
                        val transformedOutput = step.outputTransformer?.invoke(fallback)
                            ?: fallback
                        currentInput = transformedOutput
                        lastOutput = transformedOutput
                        completedSteps++
                    } else if (chain.stopOnFailure && step.required) {
                        return ChainTraceResult(
                            result = ChainResult.Failure(
                                failedStep = step.name,
                                stepIndex = index,
                                error = result.error,
                                metadata = metadata,
                            ),
                            trace = traces,
                        )
                    }
                }
            }
        }
        
        val totalTime = Duration.Companion.milliseconds(
            context.clock.currentTimeMillis() - startTime
        )
        val metadata = ChainExecutionMetadata(
            chainName = chain.name,
            totalSteps = chain.steps.size,
            completedSteps = completedSteps,
            totalExecutionTime = totalTime,
            stepTimes = stepTimes,
            traceId = context.traceId,
        )
        
        return ChainTraceResult(
            result = ChainResult.Success(
                data = lastOutput as O,
                metadata = metadata,
            ),
            trace = traces,
        )
    }
}
