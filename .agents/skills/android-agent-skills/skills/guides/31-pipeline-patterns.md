---
name: Pipeline Patterns
description: Building multi-step skill pipelines with error handling and fallback
compliance_level: RECOMMENDED
tags: [pipeline, skills, workflow, agent]
version: 1.0.0
last_updated: 2026-01-25
---

# Pipeline Patterns

## Context
Pipelines enable chaining multiple skills into complex workflows. This guide covers the pipeline DSL, step types, error handling, and best practices.

**Related Guides:**
- [30-skill-analysis.md](./30-skill-analysis.md) - Skill patterns
- [10-error-handling.md](./10-error-handling.md) - Error handling

**Created & Reviewed by**: TrongLB & AI Agents

---

## 🎯 AI Quick Reference

```
PIPELINE DSL:
• pipeline<Input>("name") { steps... }.returning<Output>()
• step(...) - Required step
• optionalStep(...) - Continue on failure

STEP COMPONENTS:
• skill - The skill to execute
• name - Step identifier
• inputTransformer - Map previous output → skill input
• outputTransformer - Map skill output → next input
• fallbackValue - Default if step fails

ERROR HANDLING:
• stopOnFailure(true) - Abort on first error
• ChainResult.Success/PartialSuccess/Failure
```

---

## 1. Pipeline DSL

### Basic Structure
```kotlin
// Create a pipeline with typed input
val pipeline = pipeline<String>("UserQueryPipeline") {
    description("Processes user queries through multiple steps")
    tags("query", "main")
    stopOnFailure(true)  // Stop on first failure
    
    // Add steps...
}.returning<String, FinalOutput>()
```

### Pipeline Builder Methods
| Method | Description |
|--------|-------------|
| `description(text)` | Human-readable description |
| `tags(vararg)` | Tags for categorization |
| `stopOnFailure(bool)` | Whether to stop on first failure |
| `step(...)` | Add required step |
| `optionalStep(...)` | Add optional step |

---

## 2. Step Types

### Required Step (stops on failure)
```kotlin
step(
    skill = intentDetectSkill,
    name = "DetectIntent",
    inputTransformer = { input: Any ->
        IntentDetectInput(text = input as String)
    },
    outputTransformer = { output: IntentDetectOutput ->
        output  // Pass to next step
    },
)
```

### Optional Step (continues on failure)
```kotlin
step(
    skill = memoryReadSkill,
    name = "ReadContext",
    required = false,  // Optional!
    inputTransformer = { input -> ... },
    outputTransformer = { output -> ... },
    fallbackValue = {  // Default if fails
        MemoryReadOutput(
            key = "default",
            value = null,
            found = false,
        )
    },
)
```

### Step with Computed Input
```kotlin
step(
    skill = domainSkill,
    name = "ProcessQuery",
    inputTransformer = { input: Any ->
        when (input) {
            is IntentDetectOutput -> {
                DomainInput(
                    intent = input.primaryIntent?.intent ?: "unknown",
                    query = input.originalText,
                )
            }
            else -> DomainInput(intent = "unknown", query = "")
        }
    },
)
```

---

## 3. Input/Output Transformers

### Passing Data Between Steps
```kotlin
// Intermediate data class for complex flows
data class PipelineData(
    val intentOutput: IntentDetectOutput? = null,
    val memoryOutput: MemoryReadOutput? = null,
    val sessionId: String = "",
)

// Step 1: Wrap output with context
outputTransformer = { output: IntentDetectOutput ->
    PipelineData(
        intentOutput = output,
        sessionId = "session_${System.currentTimeMillis()}",
    )
}

// Step 2: Unwrap and use
inputTransformer = { input: Any ->
    val data = input as PipelineData
    MemoryReadInput(key = "context_${data.sessionId}")
}
```

### Type-Safe Transformers
```kotlin
// ✅ Good: Type-safe with when expression
inputTransformer = { input: Any ->
    when (input) {
        is IntentDetectOutput -> createDomainInput(input)
        is PipelineData -> createDomainInput(input.intentOutput!!)
        else -> throw IllegalStateException("Unexpected input type")
    }
}

// ❌ Bad: Unchecked cast
inputTransformer = { input -> 
    (input as IntentDetectOutput).let { ... }  // May crash
}
```

---

## 4. Error Handling

### Chain Results
```kotlin
when (val result = controller.executePipeline(pipeline, input)) {
    is ChainResult.Success -> {
        // All steps completed
        val output = result.output
        val metadata = result.metadata  // timing, step count
    }
    
    is ChainResult.PartialSuccess -> {
        // Some steps completed, one failed (optional step)
        val partialOutput = result.partialOutput
        val failedStep = result.failedStep
    }
    
    is ChainResult.Failure -> {
        // Pipeline failed at required step
        val error = result.error
        val failedStep = result.failedStep
    }
}
```

### Fallback Values
```kotlin
step(
    skill = memoryReadSkill,
    name = "ReadContext",
    required = false,
    fallbackValue = {
        // Return default when step fails
        MemoryReadOutput(
            key = "default",
            value = mapOf("status" to "new_user"),
            found = false,
        )
    },
)
```

### Stop vs Continue
```kotlin
// Stop on first failure (default)
pipeline<String>("StrictPipeline") {
    stopOnFailure(true)
    step(criticalSkill, ...)  // Failure = pipeline fails
}

// Continue on failures
pipeline<String>("ResilientPipeline") {
    stopOnFailure(false)
    step(optionalSkill1, required = false, ...)
    step(optionalSkill2, required = false, ...)
    step(finalSkill, ...)  // Still runs
}
```

---

## 5. Complete Example

### User Query Pipeline
```kotlin
fun createUserQueryPipeline(
    intentSkill: IntentDetectSkill,
    memorySkill: MemoryReadSkill,
    domainSkill: DomainProcessSkill,
    writeSkill: MemoryWriteSkill,
) = pipeline<String>("UserQueryPipeline") {
    description("Full user query processing flow")
    stopOnFailure(false)
    
    // Step 1: Detect intent
    step(
        skill = intentSkill,
        name = "1_DetectIntent",
        inputTransformer = { input ->
            IntentDetectInput(text = input as String)
        },
    )
    
    // Step 2: Read context (optional)
    step(
        skill = memorySkill,
        name = "2_ReadContext",
        required = false,
        inputTransformer = { _ ->
            MemoryReadInput(key = "user_context")
        },
        fallbackValue = {
            MemoryReadOutput(key = "user_context", found = false)
        },
    )
    
    // Step 3: Domain processing
    step(
        skill = domainSkill,
        name = "3_ProcessQuery",
        inputTransformer = { input ->
            val intent = input as? IntentDetectOutput
            DomainInput(
                intent = intent?.primaryIntent?.intent ?: "unknown",
                query = intent?.originalText ?: "",
            )
        },
    )
    
    // Step 4: Save response
    step(
        skill = writeSkill,
        name = "4_SaveResponse",
        inputTransformer = { input ->
            val output = input as DomainOutput
            MemoryWriteInput(
                key = "last_response",
                value = output.response,
                ttlMillis = 3600_000,
            )
        },
    )
}.returning()
```

### Execution with Tracing
```kotlin
suspend fun runPipeline(
    controller: AgentController,
    userInput: String,
): String {
    val pipeline = createUserQueryPipeline(...)
    
    // Execute with detailed trace
    val traceResult = controller.executePipelineWithTrace(pipeline, userInput)
    
    // Log each step
    traceResult.trace.forEach { step ->
        println("${step.stepName}: ${if (step.result.isSuccess) "✅" else "❌"}")
        println("  Time: ${step.executionTime}")
    }
    
    return when (val result = traceResult.result) {
        is ChainResult.Success -> "Completed: ${result.output}"
        is ChainResult.PartialSuccess -> "Partial: failed at ${result.failedStep}"
        is ChainResult.Failure -> "Failed at ${result.failedStep}: ${result.error}"
    }
}
```

---

## 6. Best Practices

### ✅ DO
- Use `PipelineData` classes for complex inter-step data
- Always provide `fallbackValue` for optional steps
- Use meaningful step names for tracing
- Handle all input types in transformers
- Test each skill independently before pipelining

### ❌ DON'T
- Don't pass large objects between steps (use IDs)
- Don't catch exceptions in skills (use SkillResult)
- Don't use required steps for optional functionality
- Don't rely on step order for business logic

### Step Naming Convention
```kotlin
// ✅ Good: Numbered + descriptive
"1_DetectIntent"
"2_ReadContext"
"3_ProcessQuery"

// ❌ Bad: Vague
"step1"
"process"
```

---

## 7. Testing Pipelines

```kotlin
@Test
fun `pipeline completes successfully with valid input`() = runTest {
    val intentSkill = mockk<IntentDetectSkill>()
    coEvery { intentSkill.execute(any(), any()) } returns SkillResult.success(
        IntentDetectOutput(primaryIntent = Intent("greeting", 0.9f))
    )
    
    val pipeline = createPipeline(intentSkill, ...)
    val result = controller.executePipeline(pipeline, "Hello")
    
    assertThat(result).isInstanceOf(ChainResult.Success::class.java)
}

@Test
fun `pipeline continues when optional step fails`() = runTest {
    val memorySkill = mockk<MemoryReadSkill>()
    coEvery { memorySkill.execute(any(), any()) } returns SkillResult.failure(
        SkillError(ErrorCode.NOT_FOUND, "Not found")
    )
    
    val pipeline = createPipelineWithOptionalMemory(memorySkill, ...)
    val result = controller.executePipeline(pipeline, "Hello")
    
    // Should still succeed with fallback
    assertThat(result).isInstanceOf(ChainResult.Success::class.java)
}
```

---

## 8. Reference Templates

| Template | Description |
|----------|-------------|
| [PipelineExamples.kt](../templates/examples/PipelineExamples.kt) | Basic pipeline patterns |
| [CompletePipelineExample.kt](../templates/examples/CompletePipelineExample.kt) | Complete 4-step pipeline |

---

**Document Maintained By**: AI Agent / TrongLB  
**Version**: 1.0.0  
**Last Updated**: January 2026
