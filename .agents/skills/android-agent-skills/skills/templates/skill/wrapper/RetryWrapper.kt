package com.agentcore.skill.wrapper

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.ExecutionMetadata
import com.agentcore.skill.base.Skill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for retry behavior.
 */
data class RetryConfig(
    /**
     * Maximum number of retry attempts.
     */
    val maxAttempts: Int = 3,
    
    /**
     * Initial delay between retries.
     */
    val initialDelay: Duration = 100.milliseconds,
    
    /**
     * Maximum delay between retries.
     */
    val maxDelay: Duration = 10.seconds,
    
    /**
     * Multiplier for exponential backoff.
     */
    val backoffMultiplier: Double = 2.0,
    
    /**
     * Whether to add jitter to delays.
     */
    val useJitter: Boolean = true,
    
    /**
     * Jitter factor (0.0 to 1.0).
     */
    val jitterFactor: Double = 0.1,
    
    /**
     * Predicate to determine if an error should be retried.
     */
    val shouldRetry: (SkillError) -> Boolean = { true },
) {
    companion object {
        val Default = RetryConfig()
        
        val NoRetry = RetryConfig(maxAttempts = 1)
        
        val Aggressive = RetryConfig(
            maxAttempts = 5,
            initialDelay = 50.milliseconds,
            maxDelay = 30.seconds,
            backoffMultiplier = 2.5,
        )
    }
}

/**
 * Wrapper that adds retry capability to any skill.
 * Uses exponential backoff with optional jitter.
 */
class RetryWrapper<Input : Any, Output : Any>(
    private val delegate: Skill<Input, Output>,
    private val config: RetryConfig = RetryConfig.Default,
) : BaseSkill<Input, Output>() {
    
    override val metadata: SkillMetadata = delegate.metadata.copy(
        id = "${delegate.metadata.id}_retry",
        name = "${delegate.metadata.name} (with retry)",
        extras = delegate.metadata.extras + ("retryConfig" to config),
    )
    
    override suspend fun validate(input: Input): SkillResult<Unit> {
        return delegate.validate(input)
    }
    
    override suspend fun doExecute(
        input: Input,
        context: SkillContext,
    ): SkillResult<Output> {
        var lastError: SkillError? = null
        var attempt = 0
        
        while (attempt < config.maxAttempts) {
            // Check for cancellation before each attempt
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            
            val result = delegate.execute(input, context)
            
            when (result) {
                is SkillResult.Success -> {
                    return result.copy(
                        metadata = result.metadata.copy(
                            retryCount = attempt,
                        )
                    )
                }
                is SkillResult.Failure -> {
                    lastError = result.error
                    
                    // Check if we should retry
                    if (!config.shouldRetry(result.error)) {
                        return result
                    }
                    
                    // Check if we have more attempts
                    if (attempt + 1 >= config.maxAttempts) {
                        break
                    }
                    
                    // Calculate delay with exponential backoff
                    val baseDelay = config.initialDelay.inWholeMilliseconds *
                        config.backoffMultiplier.pow(attempt.toDouble())
                    val cappedDelay = min(baseDelay.toLong(), config.maxDelay.inWholeMilliseconds)
                    
                    // Add jitter if enabled (using full jitter algorithm - AWS best practice)
                    // Full jitter: delay = random(0, cappedDelay)
                    // This provides better distribution and prevents thundering herd
                    val finalDelay = if (config.useJitter) {
                        // Full jitter with configurable factor
                        val jitterRange = (cappedDelay * config.jitterFactor).toLong()
                        val jitter = (Math.random() * jitterRange * 2 - jitterRange).toLong()
                        (cappedDelay + jitter).coerceAtLeast(0)
                    } else {
                        cappedDelay
                    }
                    
                    delay(finalDelay)
                    attempt++
                }
            }
        }
        
        // All retries exhausted
        return SkillResult.Failure(
            error = SkillError(
                code = ErrorCode.EXECUTION_FAILED,
                message = "All ${config.maxAttempts} retry attempts failed: ${lastError?.message}",
                cause = lastError?.cause,
                details = mapOf(
                    "attempts" to config.maxAttempts,
                    "lastErrorCode" to (lastError?.code ?: "unknown"),
                ),
            ),
            metadata = ExecutionMetadata(retryCount = attempt),
        )
    }
}

/**
 * Extension function to wrap a skill with retry capability.
 */
fun <I : Any, O : Any> Skill<I, O>.withRetry(
    config: RetryConfig = RetryConfig.Default,
): Skill<I, O> = RetryWrapper(this, config)

/**
 * Extension function to wrap a skill with retry using lambda configuration.
 */
fun <I : Any, O : Any> Skill<I, O>.withRetry(
    configure: RetryConfig.() -> RetryConfig,
): Skill<I, O> = RetryWrapper(this, RetryConfig.Default.configure())
