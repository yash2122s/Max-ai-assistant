# AGENT SYSTEM PROMPT SUMMARY
> **CRITICAL**: Read this before generating any Android code.  
> **Created & Reviewed by**: TrongLB & AI Agents  
> **Version**: 2.2.0 | **Last Updated**: January 2026

---

## 🎯 Quick Navigation

| Task | Go To | AI Prompt |
|------|-------|-----------|
| New Skill | `DomainSkillGuide.kt` | `@gen-skill` |
| ViewModel | `UseCaseViewModelExample.kt` | `@gen-viewmodel` |
| Compose UI | `ComposeExample.kt` | `@gen-screen` |
| Repository | `UserRepositoryImpl.kt` | `@gen-repository` |
| Pipeline | `PipelineExamples.kt` | `@gen-pipeline` |
| Validation | `Validation.kt` | `@add-validation` |
| Full Decision | `00-decision-tree.md` | - |

---

## 1. Architecture (Clean + Feature-First)

```
Presentation → Domain → Data
     ↓            ↓        ↓
  ViewModel    UseCase   Repository
     ↓            ↓        ↓
   Screen     Interface  Implementation
```

- **Structure**: `com.example.app.feature.[name]` (presentation/domain/data)
- **Domain**: Pure Kotlin only. **NO** `Context`, **NO** `Android`, **NO** `Retrofit`
- **Data**: Repository Implementation in `data`. Interface in `domain`
- **DI**: Use Hilt (`@HiltViewModel`, `@Inject`, `@AndroidEntryPoint`) or Koin
- **ViewModel → UseCase → Repository** (NEVER ViewModel → Repository directly)

### SOLID Principles

| Principle | Rule | Example |
|-----------|------|---------|
| **S** - Single Responsibility | 1 class = 1 reason to change | UseCase does ONE thing |
| **O** - Open/Closed | Extend via interface, not modification | Add new `PaymentProcessor` without changing existing |
| **L** - Liskov Substitution | Subtypes replaceable for base types | Any `Repository` impl works interchangeably |
| **I** - Interface Segregation | Small, focused interfaces | `UserReader`, `UserWriter` instead of fat `UserRepository` |
| **D** - Dependency Inversion | Depend on abstractions | ViewModel → UseCase (interface), not concrete class |

---

## 2. Kotlin & Coroutines

- **NEVER** use `GlobalScope`. Use `viewModelScope` or inject `CoroutineScope`
- **Dispatchers**: Always inject, never hardcode
- **Flow**: Use `StateFlow` (UI State) + `Channel` (Events) - NOT SharedFlow for events
- **Safety**: **ALWAYS** rethrow `CancellationException`:

```kotlin
try {
    // work
} catch (e: CancellationException) {
    throw e  // ← MANDATORY
} catch (e: Exception) {
    // handle
}
```

---

## 3. Jetpack Compose

- **Pattern**: `Route` (Stateful) → `Screen` (Stateless)
- **State**: Use `collectAsStateWithLifecycle()` (NOT `collectAsState()`)
- **Parameters**: Order is `Required → State → Modifier → Callbacks`
- **Modifiers**: Pass `modifier: Modifier = Modifier` **before** callbacks
- **Stability**: Mark all state classes with `@Immutable`:

```kotlin
@Immutable
data class MyUiState(
    val data: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)
```

---

## 4. Validation Functions (Top 15)

Use these in `validate {}` block for Skills and UseCases:

| Function | For | Example |
|----------|-----|---------|
| `requireNotBlank` | Non-empty strings | `requireNotBlank(input.name, "name")` |
| `requireValidEmail` | Email format | `requireValidEmail(input.email, "email")` |
| `requireValidPassword` | Password strength | `requireValidPassword(input.pass, "password")` |
| `requireValidUrl` | URL format | `requireValidUrl(input.url, "url")` |
| `requireValidPhone` | Phone format | `requireValidPhone(input.phone, "phone")` |
| `requireInRange` | Numeric range | `requireInRange(input.age, 13..150, "age")` |
| `requirePositive` | Positive number | `requirePositive(input.count, "count")` |
| `requireValidConfidence` | 0.0 - 1.0 | `requireValidConfidence(input.score, "score")` |
| `requireValidPercentage` | 0 - 100 | `requireValidPercentage(input.pct, "percent")` |
| `requireNotEmpty` | Non-empty collection | `requireNotEmpty(input.items, "items")` |
| `requireSize` | Collection size | `requireSize(input.tags, 1..10, "tags")` |
| `requireDateInRange` | Date range | `requireDateInRange(input.date, min..max, "date")` |
| `requireMatches` | Regex pattern | `requireMatches(input.code, regex, "code")` |
| `requireValidAmount` | Money values | `requireValidAmount(input.price, "price")` |
| `require` | Custom rules | `require(a < b) { "a must be less than b" }` |

**Reference**: `skills/templates/skill/base/Validation.kt`

---

## 5. Error Type Mapping (Skills)

| Exception | Error Type | Usage |
|-----------|------------|-------|
| `IOException` | `TypedSkillError.Network` | Connection failures |
| `SocketTimeoutException` | `TypedSkillError.Timeout` | Timeouts |
| `HttpException(401/403)` | `TypedSkillError.Unauthorized` | Auth failures |
| `HttpException(404)` | `TypedSkillError.NotFound` | Not found |
| `HttpException(429)` | `TypedSkillError.RateLimited` | Rate limiting |
| `HttpException(5xx)` | `TypedSkillError.Execution` | Server errors |
| `JsonParseException` | `TypedSkillError.Serialization` | Parse errors |
| `SQLiteException` | `TypedSkillError.Database` | DB errors |
| `CancellationException` | **RETHROW** | Never catch! |

**Pattern**:
```kotlin
override suspend fun doExecute(input: I, context: SkillContext): SkillResult<O> {
    return try {
        SkillResult.success(output)
    } catch (e: CancellationException) {
        throw e  // ALWAYS rethrow
    } catch (e: IOException) {
        TypedSkillError.Network("Network failed: ${e.message}", e).toFailure()
    } catch (e: Exception) {
        TypedSkillError.Execution("Unexpected: ${e.message}", e).toFailure()
    }
}
```

---

## 6. Key Annotations

| Annotation | When to Use |
|------------|-------------|
| `@Immutable` | All Input/Output data classes for Skills |
| `@Immutable` | All UiState classes for Compose |
| `@Stable` | Classes with stable identity but mutable internal state |
| `@HiltViewModel` | ViewModels with Hilt DI |
| `@Inject` | Constructor injection |

---

## 7. Code Template References

| Pattern | Template File |
|---------|---------------|
| Agent Skill | `skills/templates/examples/DomainSkillGuide.kt` |
| ViewModel + UseCase | `skills/templates/examples/UseCaseViewModelExample.kt` |
| Compose UI | `skills/templates/compose/ComposeExample.kt` |
| Repository | `skills/templates/examples/UserRepositoryImpl.kt` |
| Pipeline | `skills/templates/examples/PipelineExamples.kt` |
| Navigation | `skills/templates/examples/NavigationExamples.kt` |
| Koin DI | `skills/templates/examples/KoinSkillExamples.kt` |
| State Holder | `skills/templates/examples/StateStoreHolderExample.kt` |
| Skill Tests | `skills/templates/testing/SkillTestTemplate.kt` |
| ViewModel Tests | `skills/templates/testing/ViewModelTestTemplate.kt` |
| Repository Tests | `skills/templates/testing/RepositoryTestTemplate.kt` |
| Compose Tests | `skills/templates/testing/ComposeScreenTestTemplate.kt` |
| Integration Tests | `skills/templates/testing/IntegrationTestTemplate.kt` |

---

## 8. Verification Checklist

Before submitting code, verify:

### Architecture
- [ ] ViewModel injects UseCases, NOT Repositories
- [ ] Domain layer has NO Android imports
- [ ] UseCases return `Result<T>` or `Flow<T>`

### Skills
- [ ] Extends `BaseSkill<I, O>`
- [ ] Input/Output marked `@Immutable`
- [ ] `validate()` covers ALL fields
- [ ] `doExecute()` has try-catch with CancellationException rethrow
- [ ] Returns `SkillResult<T>`, never throws

### Compose
- [ ] Route/Screen separation
- [ ] State class marked `@Immutable`
- [ ] Uses `collectAsStateWithLifecycle()`
- [ ] `modifier` is last parameter

### Testing
- [ ] Uses JUnit5 + MockK + Turbine
- [ ] Uses `runTest` for coroutines
- [ ] Tests all paths: success, error, edge cases

---

## 9. Anti-Patterns to Avoid

| ❌ DON'T | ✅ DO |
|----------|-------|
| `GlobalScope.launch` | `viewModelScope.launch` |
| `SharedFlow<Event>` | `Channel<Event>` |
| `collectAsState()` | `collectAsStateWithLifecycle()` |
| `var state = MutableStateFlow()` | `private val _state` + public `StateFlow` |
| Throw exceptions in Skills | Return `SkillResult.failure()` |
| Catch `CancellationException` | Always rethrow it |
| Repository in ViewModel | Inject UseCase instead |

---

**Full Reference**: `skills/AI_PROMPTS.md` for all generation prompts
