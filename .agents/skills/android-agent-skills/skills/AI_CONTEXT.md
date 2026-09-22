# AI Context - Quick Reference
> **Purpose**: Rapid context loading for AI agents. Read this FIRST.  
> **Version**: 2.2.0 | **Last Updated**: January 2026  
> **Entry Point**: `SKILL.md` (Agent Skills spec compliant)

---

## 📂 File Priority

```
1. SKILL.md           → Entry point (metadata + overview)
2. AI_CONTEXT.md      → Quick reference (you're here)
3. AGENT_SUMMARY.md   → Core rules & patterns
4. guides/            → Detailed documentation
5. templates/         → Code templates
```

---

## 🎯 Identify Your Task

```
What are you building?
    │
    ├─► Agent Skill?
    │   └─► Template: DomainSkillGuide.kt
    │       Prompt: @gen-skill
    │       Quality: @analyze-skill (aim 90+)
    │
    ├─► ViewModel?
    │   └─► Template: UseCaseViewModelExample.kt
    │       Prompt: @gen-viewmodel
    │       Test: @gen-viewmodel-test
    │
    ├─► Compose Screen?
    │   └─► Template: ComposeExample.kt
    │       Prompt: @gen-screen
    │       Pattern: Route (stateful) → Screen (stateless)
    │
    ├─► Repository?
    │   └─► Template: UserRepositoryImpl.kt
    │       Prompt: @gen-repository
    │       Pattern: Interface in domain, Impl in data
    │
    ├─► UseCase?
    │   └─► Pattern: operator fun invoke()
    │       Prompt: @gen-usecase
    │       Return: Result<T> or Flow<T>
    │
    ├─► Pipeline?
    │   └─► Template: PipelineExamples.kt
    │       Prompt: @gen-pipeline
    │
    └─► Tests?
        └─► Templates: testing/*.kt
            Prompts: @gen-*-test
```

---

## ⚡ Critical Rules (Never Break)

### 1. CancellationException
```kotlin
// ALWAYS rethrow - NEVER swallow
try {
    doWork()
} catch (e: CancellationException) {
    throw e  // ← MANDATORY
} catch (e: Exception) {
    handleError(e)
}
```

### 2. State Management
```kotlin
// Private mutable, public immutable
private val _state = MutableStateFlow(UiState())
val state: StateFlow<UiState> = _state.asStateFlow()

// Events use Channel, NOT SharedFlow
private val _events = Channel<Event>()
val events = _events.receiveAsFlow()
```

### 3. Compose Stability
```kotlin
// ALWAYS mark state with @Immutable
@Immutable
data class UiState(
    val items: List<Item> = emptyList(),  // Use immutable list
    val isLoading: Boolean = false,
)
```

### 4. Architecture Flow
```
ViewModel → UseCase → Repository
    ↓           ↓          ↓
  Screen    Business    Data Access
    
❌ NEVER: ViewModel → Repository (skip UseCase)
```

### 5. Domain Layer Purity
```kotlin
// ❌ WRONG - Android in domain
import android.content.Context  // NO!

// ✅ CORRECT - Pure Kotlin
import kotlinx.coroutines.flow.Flow
```

---

## 🔧 Top 10 Validation Functions

```kotlin
override suspend fun validate(input: Input): SkillResult<Unit> {
    return validate {
        // String validation
        requireNotBlank(input.userId, "userId")
        requireValidEmail(input.email, "email")
        requireValidUrl(input.website, "website")
        
        // Numeric validation  
        requireInRange(input.age, 13..150, "age")
        requirePositive(input.count, "count")
        requireValidConfidence(input.score, "score")  // 0.0-1.0
        
        // Collection validation
        requireNotEmpty(input.items, "items")
        requireSize(input.tags, 1..10, "tags")
        
        // Custom validation
        require(input.minPrice <= input.maxPrice) {
            "minPrice must be <= maxPrice"
        }
    }
}
```

---

## ⚠️ Error Type Quick Reference

| Exception | Map To |
|-----------|--------|
| `IOException` | `TypedSkillError.Network` |
| `HttpException(401/403)` | `TypedSkillError.Unauthorized` |
| `HttpException(404)` | `TypedSkillError.NotFound` |
| `HttpException(429)` | `TypedSkillError.RateLimited` |
| `JsonParseException` | `TypedSkillError.Serialization` |
| `SQLiteException` | `TypedSkillError.Database` |
| `CancellationException` | **RETHROW** (never catch!) |
| Other | `TypedSkillError.Execution` |

---

## 📁 File Priority (Read Order)

When starting work, read files in this order:

### For Skill Development
1. `skills/AGENT_SUMMARY.md` ← You are here (if reading this)
2. `skills/templates/examples/DomainSkillGuide.kt`
3. `skills/guides/30-skill-analysis.md`
4. `skills/guides/24-validation-rules.md`

### For ViewModel Development
1. `skills/AGENT_SUMMARY.md`
2. `skills/templates/examples/UseCaseViewModelExample.kt`
3. `skills/guides/07-state-management.md`
4. `skills/guides/01-architecture.md`

### For Compose Development
1. `skills/AGENT_SUMMARY.md`
2. `skills/templates/compose/ComposeExample.kt`
3. `skills/guides/05-jetpack-compose.md`
4. `skills/guides/06-compose-performance.md`

### For Testing
1. `skills/templates/testing/SkillTestTemplate.kt` (or ViewModel/UseCase)
2. `skills/guides/11-testing.md`
3. `skills/guides/29-testing-automation.md`

### For Modern Kotlin (2.0+)
1. `skills/guides/32-modern-kotlin-features.md`
2. data object, sealed interface, value class patterns
3. K2 compiler best practices

---

## 🚫 Quick Anti-Pattern Check

Before submitting, verify NONE of these exist:

| Pattern | Problem | Fix |
|---------|---------|-----|
| `GlobalScope.launch` | Uncontrolled lifecycle | Use `viewModelScope` |
| `SharedFlow<Event>` | Events can be lost | Use `Channel<Event>` |
| `collectAsState()` | Not lifecycle-aware | Use `collectAsStateWithLifecycle()` |
| `throw Exception` in Skill | Breaks SkillResult contract | Return `SkillResult.failure()` |
| `catch (e: Exception)` without rethrow | Swallows cancellation | Add `catch (e: CancellationException) { throw e }` |
| Repository in ViewModel | Skips business logic | Inject UseCase |
| `import android.*` in domain | Breaks clean arch | Move to data layer |
| No `@Immutable` on state | Compose instability | Add annotation |

---

## 📊 Quality Targets

| Component | Metric | Target |
|-----------|--------|--------|
| Skill | @analyze-skill score | ≥ 90/100 |
| ViewModel | Test coverage | ≥ 80% |
| Validation | Field coverage | 100% |
| Documentation | KDoc coverage | All public APIs |

---

## 🔗 Full References

| Resource | Path |
|----------|------|
| All AI Prompts | `skills/AI_PROMPTS.md` |
| Decision Tree | `skills/guides/00-decision-tree.md` |
| Architecture | `skills/guides/01-architecture.md` |
| Validation Rules | `skills/guides/24-validation-rules.md` |
| Validation Cheat Sheet | `VALIDATION_CHEAT_SHEET.md` |
