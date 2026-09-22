package com.agentcore.pipeline

import com.agentcore.skill.base.Skill
import com.agentcore.skill.executor.ChainStep
import com.agentcore.skill.executor.SkillChain

/**
 * A Pipeline represents a sequence of skills to be executed.
 * Implements SkillChain for execution by SkillChainExecutor.
 */
data class Pipeline<Input : Any, Output : Any>(
    override val name: String,
    override val steps: List<ChainStep<*, *>>,
    override val stopOnFailure: Boolean = true,
    val description: String = "",
    val version: String = "1.0.0",
    val tags: Set<String> = emptySet(),
) : SkillChain<Input, Output>

/**
 * Builder for creating pipelines with a fluent DSL.
 */
class PipelineBuilder<I : Any>(
    private val name: String,
) {
    private val steps = mutableListOf<ChainStep<*, *>>()
    private var stopOnFailure: Boolean = true
    private var description: String = ""
    private var version: String = "1.0.0"
    private var tags: MutableSet<String> = mutableSetOf()
    
    /**
     * Sets whether the pipeline should stop on first failure.
     */
    fun stopOnFailure(value: Boolean) = apply {
        this.stopOnFailure = value
    }
    
    /**
     * Sets the pipeline description.
     */
    fun description(value: String) = apply {
        this.description = value
    }
    
    /**
     * Sets the pipeline version.
     */
    fun version(value: String) = apply {
        this.version = value
    }
    
    /**
     * Adds tags to the pipeline.
     */
    fun tags(vararg values: String) = apply {
        this.tags.addAll(values)
    }
    
    /**
     * Adds a skill step to the pipeline.
     */
    @Suppress("UNCHECKED_CAST")
    fun <In : Any, Out : Any> step(
        skill: Skill<In, Out>,
        name: String = skill.metadata.name,
        required: Boolean = true,
        inputTransformer: ((Any) -> In)? = null,
        outputTransformer: ((Out) -> Any)? = null,
        fallbackValue: (() -> Out)? = null,
    ): PipelineBuilder<I> {
        steps.add(
            ChainStep(
                skill = skill,
                name = name,
                required = required,
                inputTransformer = inputTransformer,
                outputTransformer = outputTransformer,
                fallbackValue = fallbackValue,
            )
        )
        return this
    }
    
    /**
     * Adds an optional step that won't stop the pipeline on failure.
     */
    fun <In : Any, Out : Any> optionalStep(
        skill: Skill<In, Out>,
        name: String = skill.metadata.name,
        fallbackValue: (() -> Out)? = null,
    ): PipelineBuilder<I> = step(
        skill = skill,
        name = name,
        required = false,
        fallbackValue = fallbackValue,
    )
    
    /**
     * Builds the pipeline.
     */
    @Suppress("UNCHECKED_CAST")
    fun <O : Any> build(): Pipeline<I, O> = Pipeline(
        name = name,
        steps = steps.toList(),
        stopOnFailure = stopOnFailure,
        description = description,
        version = version,
        tags = tags.toSet(),
    )
}

/**
 * DSL function to create a pipeline.
 */
fun <I : Any> pipeline(
    name: String,
    block: PipelineBuilder<I>.() -> Unit,
): PipelineBuilder<I> = PipelineBuilder<I>(name).apply(block)

/**
 * Extension to finalize pipeline with output type.
 */
@Suppress("UNCHECKED_CAST")
inline fun <I : Any, reified O : Any> PipelineBuilder<I>.returning(): Pipeline<I, O> = build()
