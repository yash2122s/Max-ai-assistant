package com.agentcore.examples

import com.agentcore.controller.AgentController
import com.agentcore.pipeline.Pipeline
import com.agentcore.pipeline.pipeline
import com.agentcore.pipeline.returning
import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.executor.ChainResult
import com.agentcore.skill.registry.SkillRegistry
import com.agentcore.skills.fallback.FallbackInput
import com.agentcore.skills.fallback.FallbackOutput
import com.agentcore.skills.fallback.FallbackReason
import com.agentcore.skills.fallback.FallbackSkill
import com.agentcore.skills.intent.IntentDetectInput
import com.agentcore.skills.intent.IntentDetectOutput
import com.agentcore.skills.intent.IntentDetectSkill
import com.agentcore.skills.memory.MemoryReadInput
import com.agentcore.skills.memory.MemoryReadOutput
import com.agentcore.skills.memory.MemoryReadSkill
import com.agentcore.skills.memory.MemoryWriteInput
import com.agentcore.skills.memory.MemoryWriteOutput
import com.agentcore.skills.memory.MemoryWriteSkill

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * This file demonstrates Pipeline creation patterns with examples.
 * 
 * PIPELINE PATTERNS:
 *   Sequential:    Step 1 → Step 2 → Step 3
 *   Conditional:   If success → Step A, else → Fallback
 *   Parallel:      (Step 1, Step 2) → Merge → Step 3
 * 
 * @see 31-pipeline-patterns.md for pipeline guide
 * @see CompletePipelineExample.kt for complete example
 * ============================================================================
 */

// ============================================================
// EXAMPLE: CREATING A DOMAIN-SPECIFIC SKILL
// ============================================================

/**
 * Example input for a domain-specific skill.
 */
data class ProcessUserQueryInput(
    val query: String,
    val userId: String,
    val sessionId: String,
)

/**
 * Example output for a domain-specific skill.
 */
data class ProcessUserQueryOutput(
    val response: String,
    val confidence: Float,
    val suggestedActions: List<String>,
    val shouldSaveToMemory: Boolean,
)

/**
 * Example domain-specific skill.
 * This shows how to create a custom skill for your application.
 */
class ProcessUserQuerySkill : BaseSkill<ProcessUserQueryInput, ProcessUserQueryOutput>() {
    
    override val metadata: SkillMetadata = skillMetadata {
        id = "domain.process_query"
        name = "Process User Query"
        description = "Processes user queries and generates appropriate responses"
        category = SkillCategory.DOMAIN
        tags("query", "nlp", "domain")
        inputType = ProcessUserQueryInput::class
        outputType = ProcessUserQueryOutput::class
        isRetryable = true
        recommendedTimeoutMs = 10_000L
    }
    
    override suspend fun doExecute(
        input: ProcessUserQueryInput,
        context: SkillContext,
    ): SkillResult<ProcessUserQueryOutput> {
        // Your domain logic here
        // This is a stub - replace with actual implementation
        
        val response = when {
            input.query.contains("weather", ignoreCase = true) -> 
                "I can help you check the weather. What location are you interested in?"
            input.query.contains("reminder", ignoreCase = true) -> 
                "I can set a reminder for you. What would you like to be reminded about?"
            input.query.contains("search", ignoreCase = true) -> 
                "I can search for that. What specifically are you looking for?"
            else -> 
                "I understand you said: '${input.query}'. How can I help you with that?"
        }
        
        return SkillResult.success(
            ProcessUserQueryOutput(
                response = response,
                confidence = 0.85f,
                suggestedActions = listOf("Ask for more details", "Show related topics"),
                shouldSaveToMemory = true,
            )
        )
    }
}

// ============================================================
// EXAMPLE: BUILDING A PIPELINE
// ============================================================

/**
 * Creates the example pipeline:
 * IntentDetect -> MemoryRead -> DomainSkill -> MemoryWrite
 */
fun createExamplePipeline(
    intentDetectSkill: IntentDetectSkill,
    memoryReadSkill: MemoryReadSkill,
    processQuerySkill: ProcessUserQuerySkill,
    memoryWriteSkill: MemoryWriteSkill,
    fallbackSkill: FallbackSkill,
): Pipeline<String, MemoryWriteOutput> {
    
    return pipeline<String>("UserQueryPipeline") {
        description("Processes user query through intent detection, memory, and response generation")
        tags("query", "main")
        stopOnFailure(false) // Continue even if some steps fail
        
        // Step 1: Detect intent from user input
        step(
            skill = intentDetectSkill,
            name = "DetectIntent",
            inputTransformer = { input: Any ->
                IntentDetectInput(
                    text = input as String,
                    maxResults = 3,
                    minConfidence = 0.3f,
                )
            },
            outputTransformer = { output: IntentDetectOutput ->
                output // Pass through to next step
            },
        )
        
        // Step 2: Read user context from memory
        step(
            skill = memoryReadSkill,
            name = "ReadUserContext",
            required = false, // Optional step
            inputTransformer = { input: Any ->
                val intentOutput = input as IntentDetectOutput
                MemoryReadInput(
                    key = "user_context_${intentOutput.originalText.hashCode()}",
                    valueType = Map::class.java,
                )
            },
            outputTransformer = { output: MemoryReadOutput ->
                // Combine intent and memory into context for next step
                mapOf(
                    "intent" to output.key,
                    "hasContext" to output.found,
                    "context" to (output.value ?: emptyMap<String, Any>()),
                )
            },
            fallbackValue = {
                MemoryReadOutput(
                    key = "fallback",
                    value = null,
                    found = false,
                )
            },
        )
        
        // Step 3: Process the query with domain logic
        step(
            skill = processQuerySkill,
            name = "ProcessQuery",
            inputTransformer = { input: Any ->
                @Suppress("UNCHECKED_CAST")
                val context = input as Map<String, Any>
                ProcessUserQueryInput(
                    query = context["intent"] as? String ?: "",
                    userId = "default_user",
                    sessionId = "session_${System.currentTimeMillis()}",
                )
            },
            outputTransformer = { output: ProcessUserQueryOutput ->
                output
            },
        )
        
        // Step 4: Save response to memory
        step(
            skill = memoryWriteSkill,
            name = "SaveToMemory",
            inputTransformer = { input: Any ->
                val queryOutput = input as ProcessUserQueryOutput
                MemoryWriteInput(
                    key = "last_response",
                    value = mapOf(
                        "response" to queryOutput.response,
                        "timestamp" to System.currentTimeMillis(),
                    ),
                    ttlMillis = 3600_000, // 1 hour
                    tags = setOf("response", "conversation"),
                )
            },
        )
    }.returning()
}

// ============================================================
// EXAMPLE: PIPELINE EXECUTION
// ============================================================

/**
 * Example of executing the pipeline.
 */
suspend fun executePipelineExample(
    controller: AgentController,
    userInput: String,
): String {
    // Get skills from registry
    val intentSkill = controller.skillRegistry.resolve<IntentDetectInput, IntentDetectOutput>(
        "core.intent.detect"
    ) ?: throw IllegalStateException("IntentDetectSkill not found")
    
    val memoryReadSkill = controller.skillRegistry.resolve<MemoryReadInput, MemoryReadOutput>(
        "core.memory.read"
    ) ?: throw IllegalStateException("MemoryReadSkill not found")
    
    val memoryWriteSkill = controller.skillRegistry.resolve<MemoryWriteInput, MemoryWriteOutput>(
        "core.memory.write"
    ) ?: throw IllegalStateException("MemoryWriteSkill not found")
    
    val fallbackSkill = controller.skillRegistry.resolve<FallbackInput, FallbackOutput>(
        "core.fallback"
    ) ?: throw IllegalStateException("FallbackSkill not found")
    
    // Create domain skill (would normally be registered in DI)
    val processQuerySkill = ProcessUserQuerySkill()
    
    // Build pipeline
    val pipeline = createExamplePipeline(
        intentDetectSkill = intentSkill as IntentDetectSkill,
        memoryReadSkill = memoryReadSkill as MemoryReadSkill,
        processQuerySkill = processQuerySkill,
        memoryWriteSkill = memoryWriteSkill as MemoryWriteSkill,
        fallbackSkill = fallbackSkill as FallbackSkill,
    )
    
    // Execute pipeline
    val result = controller.executePipelineWithTrace(pipeline, userInput)
    
    // Handle result
    return when (val chainResult = result.result) {
        is ChainResult.Success -> {
            "Pipeline completed successfully. Steps: ${chainResult.metadata.completedSteps}/${chainResult.metadata.totalSteps}"
        }
        is ChainResult.PartialSuccess -> {
            "Pipeline partially completed. Failed at: ${chainResult.failedStep}"
        }
        is ChainResult.Failure -> {
            "Pipeline failed at step '${chainResult.failedStep}': ${chainResult.error.message}"
        }
    }
}

// ============================================================
// EXAMPLE: SIMPLE SKILL EXECUTION
// ============================================================

/**
 * Example of executing a single skill.
 */
suspend fun executeSkillExample(controller: AgentController, userInput: String): String {
    // Execute intent detection skill
    val result = controller.executeSkill<IntentDetectInput, IntentDetectOutput>(
        skillId = "core.intent.detect",
        input = IntentDetectInput(
            text = userInput,
            maxResults = 3,
            minConfidence = 0.3f,
        ),
    )
    
    return result.fold(
        onSuccess = { output ->
            if (output.hasIntent) {
                "Detected intent: ${output.primaryIntent?.intent} (confidence: ${output.confidence})"
            } else {
                "No intent detected"
            }
        },
        onFailure = { error ->
            "Error: ${error.message}"
        },
    )
}

// ============================================================
// EXAMPLE: REGISTERING DOMAIN SKILLS
// ============================================================

/**
 * Example of registering domain-specific skills.
 */
fun registerDomainSkills(registry: SkillRegistry) {
    // Register your domain skills
    registry.register(ProcessUserQuerySkill())
    
    // You can also register with custom IDs
    // registry.register("my.custom.skill", MyCustomSkill())
    
    // Or use factories for lazy initialization
    // registry.registerFactory(
    //     id = "expensive.skill",
    //     metadata = expensiveSkillMetadata,
    //     factory = { ExpensiveSkill(dependencies) }
    // )
}

// ============================================================
// EXAMPLE: CONDITIONAL PIPELINE WITH FALLBACK
// ============================================================

/**
 * Creates a pipeline with conditional fallback handling.
 */
fun createPipelineWithFallback(
    intentSkill: IntentDetectSkill,
    fallbackSkill: FallbackSkill,
    processQuerySkill: ProcessUserQuerySkill,
): Pipeline<String, Any> {
    
    return pipeline<String>("ConditionalPipeline") {
        description("Pipeline with smart fallback handling")
        stopOnFailure(false)
        
        // Detect intent
        step(
            skill = intentSkill,
            name = "DetectIntent",
            inputTransformer = { input -> IntentDetectInput(text = input as String) },
        )
        
        // Conditional: If no intent detected, use fallback
        optionalStep(
            skill = fallbackSkill,
            name = "FallbackIfNeeded",
            fallbackValue = {
                FallbackOutput(
                    message = "Proceeding with default handling",
                    suggestions = emptyList(),
                )
            },
        )
        
        // Process the query
        step(
            skill = processQuerySkill,
            name = "ProcessQuery",
            inputTransformer = { input ->
                ProcessUserQueryInput(
                    query = (input as? IntentDetectOutput)?.originalText ?: "",
                    userId = "user",
                    sessionId = "session",
                )
            },
        )
    }.returning()
}
