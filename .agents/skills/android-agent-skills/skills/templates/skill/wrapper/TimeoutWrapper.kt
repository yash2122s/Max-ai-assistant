package com.agentcore.skill.wrapper

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.ExecutionMetadata
import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for timeout behavior.
 */
data class TimeoutConfig(
    /**
     * Maximum duration before timeout.
     */
    val timeout: Duration = 30.seconds,
    
    /**
     * Whether to use the skill's recommended timeout if available.
     */
    val useSkillRecommendation: Boolean = true,
    
    /**
     * Custom error message on timeout.
     */
    val errorMessage: String? = null,
) {
    companion object {
        val Default = TimeoutConfig()
        
        fun of(duration: Duration) = TimeoutConfig(timeout = duration)
        
        val Short = TimeoutConfig(timeout = 5.seconds)
        val Medium = TimeoutConfig(timeout = 30.seconds)
        val Long = TimeoutConfig(timeout = 120.seconds)
    }
}

/**
 * Wrapper that adds timeout capability to any skill.
 */
class TimeoutWrapper<Input : Any, Output : Any>(
    private val delegate: Skill<Input, Output>,
    private val config: TimeoutConfig = TimeoutConfig.Default,
) : BaseSkill<Input, Output>() {
    
    override val metadata: SkillMetadata = delegate.metadata.copy(
        id = "${delegate.metadata.id}_timeout",
        name = "${delegate.metadata.name} (with timeout)",
        extras = delegate.metadata.extras + ("timeoutConfig" to config),
    )
    
    private val effectiveTimeout: Duration
        get() = if (config.useSkillRecommendation) {
            minOf(config.timeout, delegate.metadata.recommendedTimeoutMs.let { Duration.parse("${it}ms") })
        } else {
            config.timeout
        }
    
    override suspend fun validate(input: Input): SkillResult<Unit> {
        return delegate.validate(input)
    }
    
    override suspend fun doExecute(
        input: Input,
        context: SkillContext,
    ): SkillResult<Output> {
        return try {
            withTimeout(effectiveTimeout) {
                delegate.execute(input, context)
            }
        } catch (e: TimeoutCancellationException) {
            SkillResult.Failure(
                error = SkillError(
                    code = ErrorCode.TIMEOUT,
                    message = config.errorMessage 
                        ?: "Skill '${delegate.metadata.name}' timed out after ${effectiveTimeout}",
                    cause = e,
                    details = mapOf(
                        "timeout" to effectiveTimeout.toString(),
                        "skillId" to delegate.metadata.id,
                    ),
                ),
                metadata = ExecutionMetadata(skillName = delegate.metadata.name),
            )
        }
    }
}

/**
 * Extension function to wrap a skill with timeout capability.
 */
fun <I : Any, O : Any> Skill<I, O>.withTimeout(
    config: TimeoutConfig = TimeoutConfig.Default,
): Skill<I, O> = TimeoutWrapper(this, config)

/**
 * Extension function to wrap a skill with a specific timeout duration.
 */
fun <I : Any, O : Any> Skill<I, O>.withTimeout(
    duration: Duration,
): Skill<I, O> = TimeoutWrapper(this, TimeoutConfig.of(duration))

/**
 * Combines retry and timeout wrappers.
 */
fun <I : Any, O : Any> Skill<I, O>.withRetryAndTimeout(
    retryConfig: RetryConfig = RetryConfig.Default,
    timeoutConfig: TimeoutConfig = TimeoutConfig.Default,
): Skill<I, O> = this
    .withTimeout(timeoutConfig)
    .withRetry(retryConfig)
