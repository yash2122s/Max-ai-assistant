package com.agentcore.examples

import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.Immutable
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.base.validate
import com.agentcore.skill.registry.SkillRegistry

// ============================================================
// GUIDE: HOW TO ADD NEW DOMAIN SKILLS
// ============================================================
//
// This file provides templates and examples for creating new
// domain-specific skills that follow the Agent Core patterns.
//
// RULES:
// 1. NO Android dependencies in skills
// 2. NO direct API calls (Retrofit, Room) - use injected interfaces
// 3. Skills must be pure Kotlin with suspend functions
// 4. All side effects through injected dependencies
// 5. Input/Output should be @Immutable data classes
// 6. Use the validation DSL for clean input validation
// 7. Support cooperative cancellation where appropriate
// ============================================================

// ============================================================
// TEMPLATE 1: SIMPLE SKILL (No external dependencies)
// ============================================================

/**
 * Input for a simple calculation skill.
 * Marked as @Immutable for Compose stability.
 */
@Immutable
data class CalculateInput(
    val numbers: List<Double>,
    val operation: CalculateOperation,
)

enum class CalculateOperation { SUM, AVERAGE, MIN, MAX }

/**
 * Output from the calculation skill.
 * Marked as @Immutable for Compose stability.
 */
@Immutable
data class CalculateOutput(
    val result: Double,
    val operation: CalculateOperation,
    val inputCount: Int,
)

/**
 * Simple skill example with no external dependencies.
 * Uses the validation DSL for clean input validation.
 */
class CalculateSkill : BaseSkill<CalculateInput, CalculateOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "domain.calculate"
        name = "Calculate"
        description = "Performs basic calculations on a list of numbers"
        category = SkillCategory.DOMAIN
        tags("math", "calculate", "utility")
        inputType = CalculateInput::class
        outputType = CalculateOutput::class
        isRetryable = false // No external calls, no retry needed
        recommendedTimeoutMs = 1_000L
    }
    
    override suspend fun validate(input: CalculateInput): SkillResult<Unit> {
        // Use the validation DSL for clean validation
        return validate {
            requireNotEmpty(input.numbers, "numbers", "Numbers list cannot be empty")
        }
    }
    
    override suspend fun doExecute(
        input: CalculateInput,
        context: SkillContext,
    ): SkillResult<CalculateOutput> {
        val result = when (input.operation) {
            CalculateOperation.SUM -> input.numbers.sum()
            CalculateOperation.AVERAGE -> input.numbers.average()
            CalculateOperation.MIN -> input.numbers.minOrNull() ?: 0.0
            CalculateOperation.MAX -> input.numbers.maxOrNull() ?: 0.0
        }
        
        return SkillResult.success(
            CalculateOutput(
                result = result,
                operation = input.operation,
                inputCount = input.numbers.size,
            )
        )
    }
}

// ============================================================
// TEMPLATE 2: SKILL WITH INJECTED REPOSITORY
// ============================================================

/**
 * Repository interface - to be implemented in the app layer.
 * This keeps the skill pure Kotlin with no Android dependencies.
 */
interface UserRepository {
    suspend fun getUser(userId: String): Result<UserData>
    suspend fun updateUser(user: UserData): Result<Unit>
}

/**
 * Domain model - pure Kotlin data class.
 * Marked as @Immutable for Compose stability.
 */
@Immutable
data class UserData(
    val id: String,
    val name: String,
    val email: String,
    val preferences: Map<String, Any> = emptyMap(),
)

/**
 * Input for the user profile skill.
 * Marked as @Immutable for Compose stability.
 */
@Immutable
data class GetUserProfileInput(
    val userId: String,
    val includePreferences: Boolean = false,
)

/**
 * Output from the user profile skill.
 * Marked as @Immutable for Compose stability.
 */
@Immutable
data class GetUserProfileOutput(
    val user: UserData?,
    val found: Boolean,
    val fetchedAt: Long,
)

/**
 * Skill with injected repository dependency.
 * 
 * The repository is injected via constructor, allowing:
 * - Real implementation in production
 * - Mock implementation in tests
 * - No direct Android/Room dependencies
 * 
 * Uses the validation DSL for clean input validation.
 */
class GetUserProfileSkill(
    private val userRepository: UserRepository,
) : BaseSkill<GetUserProfileInput, GetUserProfileOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "domain.user.get_profile"
        name = "Get User Profile"
        description = "Retrieves user profile from repository"
        category = SkillCategory.DOMAIN
        tags("user", "profile", "data")
        inputType = GetUserProfileInput::class
        outputType = GetUserProfileOutput::class
        requiresNetwork = true // Might need network for API call
        isIoOperation = true
        isRetryable = true
        recommendedTimeoutMs = 10_000L
    }
    
    override suspend fun validate(input: GetUserProfileInput): SkillResult<Unit> {
        // Use the validation DSL for clean validation
        return validate {
            requireNotBlank(input.userId, "userId", "User ID cannot be blank")
        }
    }
    
    override suspend fun doExecute(
        input: GetUserProfileInput,
        context: SkillContext,
    ): SkillResult<GetUserProfileOutput> {
        // Use injected repository - no direct Room/Retrofit call
        val userResult = userRepository.getUser(input.userId)
        
        return userResult.fold(
            onSuccess = { user ->
                SkillResult.success(
                    GetUserProfileOutput(
                        user = user,
                        found = true,
                        fetchedAt = context.clock.currentTimeMillis(),
                    )
                )
            },
            onFailure = { e ->
                // Decide if failure means "not found" or actual error
                // Here we assume failure is an error, but null data would be not found
                SkillResult.failure(e)
            }
        )
    }
}

// ============================================================
// TEMPLATE 3: SKILL WITH MULTIPLE DEPENDENCIES
// ============================================================

/**
 * Analytics interface - implemented in app layer.
 */
interface AnalyticsTracker {
    fun trackEvent(name: String, params: Map<String, Any>)
}

/**
 * Configuration interface - implemented in app layer.
 */
interface FeatureConfig {
    fun isFeatureEnabled(feature: String): Boolean
    fun getConfigValue(key: String): Any?
}

/**
 * Input for a feature-gated action.
 */
data class FeatureActionInput(
    val actionName: String,
    val userId: String,
    val params: Map<String, Any> = emptyMap(),
)

/**
 * Output from the feature action.
 */
data class FeatureActionOutput(
    val executed: Boolean,
    val blocked: Boolean,
    val blockReason: String? = null,
    val result: Any? = null,
)

/**
 * Skill with multiple injected dependencies.
 */
class FeatureGatedActionSkill(
    private val featureConfig: FeatureConfig,
    private val analyticsTracker: AnalyticsTracker,
    private val userRepository: UserRepository,
) : BaseSkill<FeatureActionInput, FeatureActionOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "domain.feature.action"
        name = "Feature Gated Action"
        description = "Executes an action if the feature is enabled"
        category = SkillCategory.DOMAIN
        tags("feature", "gated", "action")
        inputType = FeatureActionInput::class
        outputType = FeatureActionOutput::class
        isRetryable = true
        recommendedTimeoutMs = 5_000L
    }
    
    override suspend fun doExecute(
        input: FeatureActionInput,
        context: SkillContext,
    ): SkillResult<FeatureActionOutput> {
        // Check feature flag
        val isEnabled = featureConfig.isFeatureEnabled(input.actionName)
        
        if (!isEnabled) {
            // Track blocked attempt
            analyticsTracker.trackEvent("feature_blocked", mapOf(
                "action" to input.actionName,
                "user_id" to input.userId,
            ))
            
            return SkillResult.success(
                FeatureActionOutput(
                    executed = false,
                    blocked = true,
                    blockReason = "Feature '${input.actionName}' is not enabled",
                )
            )
        }
        
        // Execute the action
        val user = userRepository.getUser(input.userId)
        
        // Track success
        analyticsTracker.trackEvent("feature_executed", mapOf(
            "action" to input.actionName,
            "user_id" to input.userId,
        ))
        
        return SkillResult.success(
            FeatureActionOutput(
                executed = true,
                blocked = false,
                result = mapOf(
                    "user_found" to (user != null),
                    "action" to input.actionName,
                ),
            )
        )
    }
}

// ============================================================
// TEMPLATE 4: SKILL WITH MEMORY INTERACTION
// ============================================================

/**
 * Input for a context-aware skill.
 */
data class ContextAwareInput(
    val query: String,
    val sessionId: String,
)

/**
 * Output from the context-aware skill.
 */
data class ContextAwareOutput(
    val response: String,
    val usedHistory: Boolean,
    val historySize: Int,
)

/**
 * Skill that uses the MemoryStore from context.
 * 
 * This demonstrates using the built-in memory system.
 */
class ContextAwareSkill : BaseSkill<ContextAwareInput, ContextAwareOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "domain.context_aware"
        name = "Context Aware"
        description = "Processes queries with conversation history"
        category = SkillCategory.DOMAIN
        tags("context", "history", "conversation")
        inputType = ContextAwareInput::class
        outputType = ContextAwareOutput::class
        isRetryable = true
        recommendedTimeoutMs = 5_000L
    }
    
    override suspend fun doExecute(
        input: ContextAwareInput,
        context: SkillContext,
    ): SkillResult<ContextAwareOutput> {
        val memoryKey = "conversation_${input.sessionId}"
        
        // Read conversation history from memory
        @Suppress("UNCHECKED_CAST")
        val historyEntry = context.memoryStore.read(
            key = memoryKey,
            type = List::class.java,
        )
        
        val history = (historyEntry?.value as? List<String>) ?: emptyList()
        
        // Build response based on history
        val response = if (history.isNotEmpty()) {
            "Based on our previous conversation about ${history.lastOrNull()}, " +
            "here's my response to '${input.query}'"
        } else {
            "This is the start of our conversation. " +
            "Regarding '${input.query}'..."
        }
        
        // Update history
        val updatedHistory = history + input.query
        context.memoryStore.write(
            key = memoryKey,
            value = updatedHistory.takeLast(10), // Keep last 10 queries
        )
        
        return SkillResult.success(
            ContextAwareOutput(
                response = response,
                usedHistory = history.isNotEmpty(),
                historySize = history.size,
            )
        )
    }
}

// ============================================================
// HOW TO REGISTER YOUR SKILLS
// ============================================================

/**
 * Example function showing how to register domain skills.
 */
fun registerDomainSkillsExample(
    registry: SkillRegistry,
    userRepository: UserRepository,
    analyticsTracker: AnalyticsTracker,
    featureConfig: FeatureConfig,
) {
    // 1. Simple skill (no dependencies)
    registry.register(CalculateSkill())
    
    // 2. Skill with single dependency
    registry.register(GetUserProfileSkill(userRepository))
    
    // 3. Skill with multiple dependencies
    registry.register(
        FeatureGatedActionSkill(
            featureConfig = featureConfig,
            analyticsTracker = analyticsTracker,
            userRepository = userRepository,
        )
    )
    
    // 4. Skill using context memory
    registry.register(ContextAwareSkill())
    
    // 5. Custom ID registration
    registry.register(
        id = "my.custom.id",
        skill = CalculateSkill(),
    )
    
    // 6. Lazy factory registration (skill created on first use)
    registry.registerFactory(
        id = "lazy.expensive.skill",
        metadata = skillMetadata {
            id = "lazy.expensive.skill"
            name = "Lazy Expensive Skill"
            description = "Created only when needed"
            category = SkillCategory.DOMAIN
            inputType = CalculateInput::class
            outputType = CalculateOutput::class
        },
        factory = { CalculateSkill() }
    )
}

// ============================================================
// SUMMARY: SKILL CREATION CHECKLIST
// ============================================================
//
// ✅ Define Input data class (immutable)
// ✅ Define Output data class (immutable)
// ✅ Extend BaseSkill<Input, Output>
// ✅ Implement metadata with skillMetadata DSL
// ✅ Override validate() for input validation
// ✅ Override doExecute() with business logic
// ✅ Inject all external dependencies via constructor
// ✅ Use interfaces for all side effects
// ✅ Return SkillResult.success() or SkillResult.failure()
// ✅ Register in SkillRegistry at app startup
//
// ❌ NO Android imports
// ❌ NO Context references
// ❌ NO direct Retrofit/Room calls
// ❌ NO hardcoded network/database access
// ============================================================
