package com.agentcore.examples

import com.agentcore.controller.AgentController
import com.agentcore.di.AgentCoreModule
import com.agentcore.pipeline.pipeline
import com.agentcore.pipeline.returning
import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.ErrorCode
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.executor.ChainResult
import com.agentcore.skill.registry.SkillRegistry
import com.agentcore.skills.intent.IntentDetectInput
import com.agentcore.skills.intent.IntentDetectOutput
import com.agentcore.skills.intent.IntentDetectSkill
import com.agentcore.skills.memory.MemoryReadInput
import com.agentcore.skills.memory.MemoryReadOutput
import com.agentcore.skills.memory.MemoryReadSkill
import com.agentcore.skills.memory.MemoryWriteInput
import com.agentcore.skills.memory.MemoryWriteOutput
import com.agentcore.skills.memory.MemoryWriteSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * COMPLETE EXAMPLE: IntentDetect → MemoryRead → DomainSkill → MemoryWrite
 * 
 * This demonstrates the full pipeline pattern with:
 * - Step transformers
 * - Error handling
 * - Result tracing
 * 
 * @see PipelineExamples.kt for simpler pipeline examples
 * @see 31-pipeline-patterns.md for pipeline guide
 * ============================================================================
 */

/**
 * This example demonstrates the complete requested pipeline:
 * 1. IntentDetect - Analyzes user input to determine intent
 * 2. MemoryRead - Reads user context from memory
 * 3. DomainSkill - Processes the query with business logic
 * 4. MemoryWrite - Saves the result to memory
 */

// ============================================================
// STEP 1: DOMAIN SKILL INPUT/OUTPUT
// ============================================================

/**
 * Input for the domain processing skill.
 * Combines intent detection result with user context.
 */
data class DomainProcessInput(
    /**
     * The detected intent name.
     */
    val intentName: String,
    
    /**
     * Confidence score from intent detection.
     */
    val confidence: Float,
    
    /**
     * Original user query.
     */
    val originalQuery: String,
    
    /**
     * User context from memory (if available).
     */
    val userContext: Map<String, Any>,
    
    /**
     * Session ID for tracking.
     */
    val sessionId: String,
)

/**
 * Output from the domain processing skill.
 */
data class DomainProcessOutput(
    /**
     * The response to show to the user.
     */
    val response: String,
    
    /**
     * Confidence in the response.
     */
    val confidence: Float,
    
    /**
     * Suggested follow-up actions.
     */
    val suggestions: List<String>,
    
    /**
     * Updated context to save to memory.
     */
    val updatedContext: Map<String, Any>,
    
    /**
     * Whether to save this interaction.
     */
    val shouldPersist: Boolean,
)

// ============================================================
// STEP 2: DOMAIN SKILL IMPLEMENTATION
// ============================================================

/**
 * Example domain skill that processes user queries.
 * 
 * This skill:
 * - Receives intent + context
 * - Applies business logic
 * - Returns structured response
 * 
 * NO Android dependencies - pure Kotlin.
 */
class DomainProcessSkill : BaseSkill<DomainProcessInput, DomainProcessOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "example.domain.process"
        name = "Domain Process"
        description = "Processes user queries with business logic"
        category = SkillCategory.DOMAIN
        tags("domain", "process", "query")
        inputType = DomainProcessInput::class
        outputType = DomainProcessOutput::class
        isRetryable = true
        recommendedTimeoutMs = 10_000L
    }
    
    override suspend fun validate(input: DomainProcessInput): SkillResult<Unit> {
        if (input.originalQuery.isBlank()) {
            return SkillResult.failure(
                SkillError(
                    code = ErrorCode.VALIDATION,
                    message = "Original query cannot be blank",
                )
            )
        }
        return SkillResult.success(Unit)
    }
    
    override suspend fun doExecute(
        input: DomainProcessInput,
        context: SkillContext,
    ): SkillResult<DomainProcessOutput> {
        // Business logic based on intent
        val (response, suggestions) = processIntent(input)
        
        // Build updated context
        val updatedContext = input.userContext.toMutableMap().apply {
            put("last_intent", input.intentName)
            put("last_query", input.originalQuery)
            put("last_interaction_time", context.clock.currentTimeMillis())
            put("interaction_count", (get("interaction_count") as? Int ?: 0) + 1)
        }
        
        return SkillResult.success(
            DomainProcessOutput(
                response = response,
                confidence = input.confidence,
                suggestions = suggestions,
                updatedContext = updatedContext,
                shouldPersist = true,
            )
        )
    }
    
    private fun processIntent(input: DomainProcessInput): Pair<String, List<String>> {
        return when (input.intentName) {
            "greeting" -> Pair(
                buildGreetingResponse(input),
                listOf("What can I help you with?", "Check your account", "Get support"),
            )
            
            "help" -> Pair(
                "I'm here to help! I can assist you with:\n" +
                "• Answering questions\n" +
                "• Finding information\n" +
                "• Managing your account",
                listOf("Ask a question", "Browse help topics", "Contact support"),
            )
            
            "search" -> Pair(
                "I'll search for: \"${input.originalQuery}\"",
                listOf("Refine search", "Search in category", "See all results"),
            )
            
            "farewell" -> Pair(
                "Goodbye! Have a great day!",
                listOf("Start new conversation", "Leave feedback"),
            )
            
            else -> Pair(
                "I understand you said: \"${input.originalQuery}\"\n" +
                "How can I help you with that?",
                listOf("Rephrase question", "Get help", "Start over"),
            )
        }
    }
    
    private fun buildGreetingResponse(input: DomainProcessInput): String {
        val interactionCount = input.userContext["interaction_count"] as? Int ?: 0
        return if (interactionCount > 0) {
            "Welcome back! How can I help you today?"
        } else {
            "Hello! I'm your AI assistant. How can I help you?"
        }
    }
}

// ============================================================
// STEP 3: BUILD THE PIPELINE
// ============================================================

/**
 * Intermediate data class for passing data between pipeline steps.
 */
data class PipelineData(
    val intentOutput: IntentDetectOutput? = null,
    val memoryOutput: MemoryReadOutput? = null,
    val domainOutput: DomainProcessOutput? = null,
    val originalInput: String = "",
    val sessionId: String = "",
)

/**
 * Creates the complete example pipeline.
 * 
 * Flow: IntentDetect -> MemoryRead -> DomainSkill -> MemoryWrite
 */
fun createCompletePipeline(
    intentDetectSkill: IntentDetectSkill,
    memoryReadSkill: MemoryReadSkill,
    domainProcessSkill: DomainProcessSkill,
    memoryWriteSkill: MemoryWriteSkill,
) = pipeline<String>("CompleteExamplePipeline") {
    description("Complete pipeline: IntentDetect -> MemoryRead -> DomainSkill -> MemoryWrite")
    tags("example", "complete")
    stopOnFailure(true)
    
    // STEP 1: Detect intent from user input
    step(
        skill = intentDetectSkill,
        name = "1. Detect Intent",
        inputTransformer = { input: Any ->
            val query = input as String
            IntentDetectInput(
                text = query,
                maxResults = 3,
                minConfidence = 0.3f,
            )
        },
        outputTransformer = { output: IntentDetectOutput ->
            // Wrap output with original input for next step
            PipelineData(
                intentOutput = output,
                originalInput = output.originalText,
                sessionId = "session_${System.currentTimeMillis()}",
            )
        },
    )
    
    // STEP 2: Read user context from memory
    step(
        skill = memoryReadSkill,
        name = "2. Read User Context",
        required = false, // Optional - continue if not found
        inputTransformer = { input: Any ->
            val data = input as PipelineData
            MemoryReadInput(
                key = "user_context",
                valueType = Map::class.java,
            )
        },
        outputTransformer = { output: MemoryReadOutput ->
            output // Pass through for merging
        },
        fallbackValue = {
            MemoryReadOutput(
                key = "user_context",
                value = null,
                found = false,
            )
        },
    )
    
    // STEP 3: Process with domain logic
    step(
        skill = domainProcessSkill,
        name = "3. Domain Processing",
        inputTransformer = { input: Any ->
            // Handle both cases: with or without memory read
            when (input) {
                is MemoryReadOutput -> {
                    // Need to reconstruct - in real impl, use context extras
                    DomainProcessInput(
                        intentName = "unknown", // Would get from context
                        confidence = 0.5f,
                        originalQuery = input.key,
                        userContext = (input.value as? Map<String, Any>) ?: emptyMap(),
                        sessionId = "session",
                    )
                }
                is PipelineData -> {
                    DomainProcessInput(
                        intentName = input.intentOutput?.primaryIntent?.intent ?: "unknown",
                        confidence = input.intentOutput?.confidence ?: 0f,
                        originalQuery = input.originalInput,
                        userContext = (input.memoryOutput?.value as? Map<String, Any>) ?: emptyMap(),
                        sessionId = input.sessionId,
                    )
                }
                else -> {
                    DomainProcessInput(
                        intentName = "unknown",
                        confidence = 0f,
                        originalQuery = input.toString(),
                        userContext = emptyMap(),
                        sessionId = "fallback",
                    )
                }
            }
        },
        outputTransformer = { output: DomainProcessOutput ->
            output
        },
    )
    
    // STEP 4: Save to memory
    step(
        skill = memoryWriteSkill,
        name = "4. Save to Memory",
        inputTransformer = { input: Any ->
            val domainOutput = input as DomainProcessOutput
            MemoryWriteInput(
                key = "user_context",
                value = domainOutput.updatedContext,
                ttlMillis = 24 * 60 * 60 * 1000L, // 24 hours
                overwrite = true,
                tags = setOf("context", "user"),
                metadata = mapOf(
                    "last_response" to domainOutput.response,
                    "confidence" to domainOutput.confidence,
                ),
            )
        },
    )
}.returning<String, MemoryWriteOutput>()

// ============================================================
// STEP 4: USAGE EXAMPLE
// ============================================================

/**
 * Complete usage example with all steps.
 */
suspend fun runCompleteExample(controller: AgentController): String {
    // Register domain skill if not already registered
    if (controller.skillRegistry.resolve<DomainProcessInput, DomainProcessOutput>("example.domain.process") == null) {
        controller.skillRegistry.register(DomainProcessSkill())
    }
    
    // Get required skills from registry
    val intentSkill = controller.skillRegistry.resolve<IntentDetectInput, IntentDetectOutput>(
        "core.intent.detect"
    ) as IntentDetectSkill
    
    val memoryReadSkill = controller.skillRegistry.resolve<MemoryReadInput, MemoryReadOutput>(
        "core.memory.read"
    ) as MemoryReadSkill
    
    val memoryWriteSkill = controller.skillRegistry.resolve<MemoryWriteInput, MemoryWriteOutput>(
        "core.memory.write"
    ) as MemoryWriteSkill
    
    val domainSkill = controller.skillRegistry.resolve<DomainProcessInput, DomainProcessOutput>(
        "example.domain.process"
    ) as DomainProcessSkill
    
    // Create pipeline
    val pipeline = createCompletePipeline(
        intentDetectSkill = intentSkill,
        memoryReadSkill = memoryReadSkill,
        domainProcessSkill = domainSkill,
        memoryWriteSkill = memoryWriteSkill,
    )
    
    // Execute with trace for detailed logging
    val userInput = "Hello, I need help!"
    val traceResult = controller.executePipelineWithTrace(pipeline, userInput)
    
    // Build result summary
    return buildString {
        appendLine("=" .repeat(60))
        appendLine("Pipeline Execution Result")
        appendLine("=" .repeat(60))
        appendLine()
        appendLine("Input: \"$userInput\"")
        appendLine()
        
        // Show each step
        appendLine("Step Results:")
        traceResult.trace.forEachIndexed { index, step ->
            val status = if (step.result.isSuccess) "✅" else "❌"
            appendLine("  ${index + 1}. ${step.stepName}: $status (${step.executionTime})")
            if (step.result.isSuccess) {
                val output = step.result.getOrNull()
                when (output) {
                    is IntentDetectOutput -> {
                        appendLine("     Intent: ${output.primaryIntent?.intent ?: "none"}")
                        appendLine("     Confidence: ${String.format("%.1f", output.confidence * 100)}%")
                    }
                    is MemoryReadOutput -> {
                        appendLine("     Found: ${output.found}")
                    }
                    is DomainProcessOutput -> {
                        appendLine("     Response: ${output.response.take(50)}...")
                    }
                    is MemoryWriteOutput -> {
                        appendLine("     Saved: ${output.success}")
                    }
                }
            }
        }
        
        appendLine()
        
        // Final result
        when (val result = traceResult.result) {
            is ChainResult.Success -> {
                appendLine("Final Status: ✅ SUCCESS")
                appendLine("Completed ${result.metadata.completedSteps}/${result.metadata.totalSteps} steps")
                appendLine("Total time: ${result.metadata.totalExecutionTime}")
            }
            is ChainResult.PartialSuccess -> {
                appendLine("Final Status: ⚠️ PARTIAL SUCCESS")
                appendLine("Failed at: ${result.failedStep}")
            }
            is ChainResult.Failure -> {
                appendLine("Final Status: ❌ FAILED")
                appendLine("Failed at: ${result.failedStep}")
                appendLine("Error: ${result.error.message}")
            }
        }
        
        appendLine()
        appendLine("=" .repeat(60))
    }
}

// ============================================================
// STEP 5: MAIN ENTRY POINT (for testing)
// ============================================================

/**
 * Runnable main function for testing the pipeline.
 * 
 * Usage: Run this file directly to see the pipeline in action.
 */
fun main() = runBlocking {
    println("Initializing Agent Core...")
    
    // Create module with application scope
    val scope = CoroutineScope(SupervisorJob())
    val module = AgentCoreModule.createDefault(
        applicationScope = scope,
    )
    
    println("Running complete pipeline example...")
    println()
    
    val result = runCompleteExample(module.agentController)
    println(result)
    
    // Cleanup
    module.agentController.cancelAll()
}
