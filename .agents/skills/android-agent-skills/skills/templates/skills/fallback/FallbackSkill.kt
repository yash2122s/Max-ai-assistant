package com.agentcore.skills.fallback

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata

/**
 * Input for FallbackSkill.
 */
@Immutable
data class FallbackInput(
    /**
     * The original input that couldn't be handled.
     */
    val originalInput: Any? = null,
    
    /**
     * The reason for fallback (e.g., skill not found, intent unknown).
     */
    val reason: FallbackReason = FallbackReason.UNKNOWN,
    
    /**
     * Error message if available.
     */
    val errorMessage: String? = null,
    
    /**
     * Context about what was attempted.
     */
    val attemptedOperation: String? = null,
    
    /**
     * Additional context data.
     */
    val context: Map<String, Any> = emptyMap(),
)

/**
 * Reasons why fallback was triggered.
 */
enum class FallbackReason {
    SKILL_NOT_FOUND,
    INTENT_NOT_DETECTED,
    EXECUTION_FAILED,
    VALIDATION_FAILED,
    TIMEOUT,
    NETWORK_ERROR,
    PERMISSION_DENIED,
    UNKNOWN,
}

/**
 * Output from FallbackSkill.
 */
@Immutable
data class FallbackOutput(
    /**
     * Human-readable message to display.
     */
    val message: String,
    
    /**
     * Suggested actions the user can take.
     */
    val suggestions: List<String> = emptyList(),
    
    /**
     * Whether retry might help.
     */
    val canRetry: Boolean = false,
    
    /**
     * Recommended next action.
     */
    val recommendedAction: FallbackAction = FallbackAction.SHOW_HELP,
    
    /**
     * Additional data for handling.
     */
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Recommended actions for fallback scenarios.
 */
enum class FallbackAction {
    SHOW_HELP,
    RETRY,
    GO_BACK,
    CONTACT_SUPPORT,
    TRY_ALTERNATIVE,
    NONE,
}

/**
 * Fallback skill that provides graceful handling when other skills fail.
 * 
 * This skill generates user-friendly responses and suggestions when:
 * - No intent could be detected
 * - A required skill is not found
 * - Execution fails
 * - Any other error condition
 */
class FallbackSkill(
    private val messageProvider: FallbackMessageProvider = DefaultFallbackMessageProvider(),
) : BaseSkill<FallbackInput, FallbackOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "core.fallback"
        name = "Fallback Handler"
        description = "Provides graceful fallback responses when other skills fail"
        category = SkillCategory.FALLBACK
        tags("fallback", "error", "core")
        inputType = FallbackInput::class
        outputType = FallbackOutput::class
        isRetryable = false
        recommendedTimeoutMs = 1_000L
    }
    
    override suspend fun doExecute(
        input: FallbackInput,
        context: SkillContext,
    ): SkillResult<FallbackOutput> {
        val message = messageProvider.getMessage(input)
        val suggestions = messageProvider.getSuggestions(input)
        val action = messageProvider.getRecommendedAction(input)
        
        return SkillResult.success(
            FallbackOutput(
                message = message,
                suggestions = suggestions,
                canRetry = input.reason in listOf(
                    FallbackReason.TIMEOUT,
                    FallbackReason.NETWORK_ERROR,
                    FallbackReason.EXECUTION_FAILED,
                ),
                recommendedAction = action,
                metadata = mapOf(
                    "reason" to input.reason.name,
                    "attemptedOperation" to (input.attemptedOperation ?: "unknown"),
                ),
            )
        )
    }
}

/**
 * Interface for providing fallback messages.
 * Allows customization of fallback responses.
 */
interface FallbackMessageProvider {
    fun getMessage(input: FallbackInput): String
    fun getSuggestions(input: FallbackInput): List<String>
    fun getRecommendedAction(input: FallbackInput): FallbackAction
}

/**
 * Default implementation of FallbackMessageProvider.
 */
class DefaultFallbackMessageProvider : FallbackMessageProvider {
    
    override fun getMessage(input: FallbackInput): String {
        return when (input.reason) {
            FallbackReason.SKILL_NOT_FOUND -> 
                "I don't have the capability to handle that request yet."
            
            FallbackReason.INTENT_NOT_DETECTED -> 
                "I'm not sure what you'd like me to do. Could you try rephrasing?"
            
            FallbackReason.EXECUTION_FAILED -> 
                "Something went wrong while processing your request. ${input.errorMessage ?: "Please try again."}"
            
            FallbackReason.VALIDATION_FAILED -> 
                "The input provided wasn't quite right. ${input.errorMessage ?: "Please check and try again."}"
            
            FallbackReason.TIMEOUT -> 
                "The request took too long to complete. Please try again."
            
            FallbackReason.NETWORK_ERROR -> 
                "There seems to be a connection issue. Please check your network and try again."
            
            FallbackReason.PERMISSION_DENIED -> 
                "I don't have permission to perform that action."
            
            FallbackReason.UNKNOWN -> 
                "I encountered an unexpected issue. Please try again or ask for help."
        }
    }
    
    override fun getSuggestions(input: FallbackInput): List<String> {
        return when (input.reason) {
            FallbackReason.SKILL_NOT_FOUND -> listOf(
                "Try asking for help to see what I can do",
                "Rephrase your request differently",
            )
            
            FallbackReason.INTENT_NOT_DETECTED -> listOf(
                "Say 'help' to see available commands",
                "Try using simpler phrases",
                "Be more specific about what you need",
            )
            
            FallbackReason.EXECUTION_FAILED,
            FallbackReason.TIMEOUT -> listOf(
                "Try the same request again",
                "Simplify your request if possible",
            )
            
            FallbackReason.NETWORK_ERROR -> listOf(
                "Check your internet connection",
                "Try again in a few moments",
            )
            
            FallbackReason.VALIDATION_FAILED -> listOf(
                "Check the format of your input",
                "Make sure all required information is provided",
            )
            
            FallbackReason.PERMISSION_DENIED -> listOf(
                "Check app permissions in settings",
                "Contact support if you believe this is an error",
            )
            
            FallbackReason.UNKNOWN -> listOf(
                "Try again",
                "Ask for help",
            )
        }
    }
    
    override fun getRecommendedAction(input: FallbackInput): FallbackAction {
        return when (input.reason) {
            FallbackReason.SKILL_NOT_FOUND -> FallbackAction.SHOW_HELP
            FallbackReason.INTENT_NOT_DETECTED -> FallbackAction.SHOW_HELP
            FallbackReason.EXECUTION_FAILED -> FallbackAction.RETRY
            FallbackReason.VALIDATION_FAILED -> FallbackAction.TRY_ALTERNATIVE
            FallbackReason.TIMEOUT -> FallbackAction.RETRY
            FallbackReason.NETWORK_ERROR -> FallbackAction.RETRY
            FallbackReason.PERMISSION_DENIED -> FallbackAction.CONTACT_SUPPORT
            FallbackReason.UNKNOWN -> FallbackAction.SHOW_HELP
        }
    }
}

/**
 * Helper to create FallbackInput for common scenarios.
 */
object FallbackInputFactory {
    
    fun intentNotDetected(originalInput: Any?): FallbackInput = FallbackInput(
        originalInput = originalInput,
        reason = FallbackReason.INTENT_NOT_DETECTED,
    )
    
    fun skillNotFound(skillId: String): FallbackInput = FallbackInput(
        reason = FallbackReason.SKILL_NOT_FOUND,
        attemptedOperation = "Execute skill: $skillId",
    )
    
    fun executionFailed(error: Throwable, operation: String? = null): FallbackInput = FallbackInput(
        reason = FallbackReason.EXECUTION_FAILED,
        errorMessage = error.message,
        attemptedOperation = operation,
    )
    
    fun timeout(operation: String? = null): FallbackInput = FallbackInput(
        reason = FallbackReason.TIMEOUT,
        attemptedOperation = operation,
    )
    
    fun networkError(error: Throwable? = null): FallbackInput = FallbackInput(
        reason = FallbackReason.NETWORK_ERROR,
        errorMessage = error?.message,
    )
}
