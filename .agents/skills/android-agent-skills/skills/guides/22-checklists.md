---
name: Quick Reference Checklists
description: Summary checklists for common tasks.
compliance_level: OPTIONAL
tags: [checklists, reference, quick-start]
version: 2.2.0
---

# Quick Reference Checklists

## Context
Use these checklists for self-review before submitting PRs. Each checklist covers the most critical points from the detailed guides.

**Related Guides:**
- [21-code-review.md](./21-code-review.md) - Code review guidelines
- All other guides for detailed information

---

## 🎯 AI Quick Reference

```
USE THESE WHEN:
• Creating new screens
• Adding new features
• Submitting PRs
• Reviewing code
• Releasing to production

SOLID CHECK:
• S - Does this class have only ONE reason to change?
• O - Can I extend without modifying existing code?
• L - Can subtypes replace base types safely?
• I - Am I depending on methods I don't use?
• D - Am I depending on abstractions (interfaces)?
```

---

## 0. SOLID Principles Quick Check

Before every PR, verify SOLID compliance:

| Principle | Question | ❌ Violation Example |
|-----------|----------|---------------------|
| **S**ingle Responsibility | Does this class do ONE thing? | ViewModel handles UI + API + DB |
| **O**pen/Closed | Can I add features without changing existing code? | Adding `if/when` branches |
| **L**iskov Substitution | Can any implementation replace the interface? | Subclass throws unexpected errors |
| **I**nterface Segregation | Do I use ALL methods from injected interface? | Injecting `UserRepository` but only using `getUser()` |
| **D**ependency Inversion | Am I depending on abstractions? | ViewModel depends on `Retrofit` directly |

---

## 1. New Screen Checklist

### Files to Create
```
feature/{name}/
├── presentation/
│   ├── {Name}Route.kt       # Stateful + navigation
│   ├── {Name}Screen.kt      # Stateless UI
│   ├── {Name}ViewModel.kt   # State management
│   └── {Name}UiState.kt     # UI state data class
└── (domain/data if needed)
```

### Code Review Points
- [ ] Route uses `hiltViewModel()` and collects state
- [ ] Screen is stateless (state + callbacks as params)
- [ ] ViewModel extends `ViewModel`
- [ ] UiState is `@Immutable` data class
- [ ] Collections use `ImmutableList`
- [ ] Callbacks use method references
- [ ] Navigation route is `@Serializable`
- [ ] Preview functions added
- [ ] Accessibility: content descriptions, 48dp touch targets

---

## 2. New ViewModel Checklist

### Structure
```kotlin
@HiltViewModel
class {Name}ViewModel @Inject constructor(
    private val useCase: {Name}UseCase,
) : ViewModel() {
    
    private val _state = MutableStateFlow({Name}UiState())
    val state: StateFlow<{Name}UiState> = _state.asStateFlow()
    
    private val _events = Channel<{Name}Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    fun onAction(action: {Name}Action) { ... }
}
```

### Code Review Points
- [ ] Extends `ViewModel` (not custom base class unless MviViewModel)
- [ ] Uses `viewModelScope` for coroutines
- [ ] Private `MutableStateFlow`, public `StateFlow`
- [ ] `Channel` for one-time events (not SharedFlow)
- [ ] `_state.update { it.copy(...) }` for updates
- [ ] No Context, View, or Activity references
- [ ] No `android.*` imports except ViewModel
- [ ] Unit tests created

---

## 3. New UseCase Checklist

### Structure
```kotlin
class {Action}{Entity}UseCase @Inject constructor(
    private val repository: {Name}Repository,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {
    suspend operator fun invoke(input: Input): Result<Output> = 
        withContext(dispatcher) {
            runCatching {
                // Business logic
            }
        }
}
```

### Code Review Points
- [ ] Single responsibility (one action per UseCase)
- [ ] Returns `Result<T>` (never throws)
- [ ] Dispatcher injected (for testing)
- [ ] Pure business logic only
- [ ] No Android framework imports
- [ ] Input validation included
- [ ] Unit tests with edge cases

---

## 4. New Repository Checklist

### Interface (Domain Layer)
```kotlin
interface {Name}Repository {
    fun observe{Entities}(): Flow<List<Entity>>
    suspend fun get{Entity}(id: String): Result<Entity>
    suspend fun save{Entity}(entity: Entity): Result<Unit>
}
```

### Implementation (Data Layer)
```kotlin
class {Name}RepositoryImpl @Inject constructor(
    private val api: {Name}Api,
    private val dao: {Name}Dao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : {Name}Repository {
    // Implementation
}
```

### Code Review Points
- [ ] Interface in Domain layer
- [ ] Implementation in Data layer
- [ ] `Flow` for observing data
- [ ] `Result<T>` for operations
- [ ] Offline-first: observe DB, refresh from API
- [ ] Exception handling (no uncaught exceptions)
- [ ] Mapper functions for Entity ↔ DTO ↔ Domain

---

## 5. New Compose Component Checklist

### Structure
```kotlin
@Composable
fun {Name}(
    // Required params
    data: Data,
    // State
    isEnabled: Boolean = true,
    // Modifier
    modifier: Modifier = Modifier,
    // Callbacks
    onClick: () -> Unit = {},
) {
    // Implementation
}

@Preview
@Composable
private fun {Name}Preview() {
    AppTheme {
        {Name}(data = previewData)
    }
}
```

### Code Review Points
- [ ] Parameter order: Required → State → Modifier → Callbacks
- [ ] `modifier: Modifier = Modifier` included
- [ ] Callbacks have default empty lambda
- [ ] `@Immutable` on custom data classes
- [ ] No side effects in composition
- [ ] Preview function added
- [ ] Accessibility semantics if needed

---

## 6. PR Submission Checklist

### Before Submitting
- [ ] Self-reviewed all changes
- [ ] `./gradlew detekt ktlintCheck` passes
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] No TODOs without issue references
- [ ] No `println` or `Log.d` debugging code
- [ ] No hardcoded strings (use resources)

### PR Description
- [ ] Summary of changes
- [ ] Architecture diagram if complex
- [ ] Screenshots for UI changes
- [ ] Test coverage noted
- [ ] Breaking changes documented

### Tests
- [ ] Unit tests for new ViewModel
- [ ] Unit tests for new UseCase
- [ ] Edge cases covered
- [ ] Error scenarios tested

---

## 7. Pre-Release Checklist

### Code Quality
- [ ] All lint warnings resolved
- [ ] No TODO/FIXME in release code
- [ ] ProGuard rules updated
- [ ] Crashlytics/Analytics configured

### Security
- [ ] No hardcoded secrets
- [ ] `isDebuggable = false`
- [ ] Certificate pinning enabled
- [ ] Logs stripped

### Performance
- [ ] Compose compiler reports clean
- [ ] No unstable classes in hot paths
- [ ] Memory leaks checked (LeakCanary)
- [ ] App startup time acceptable

### Accessibility
- [ ] TalkBack tested
- [ ] All icons have descriptions
- [ ] Touch targets ≥ 48dp
- [ ] Color contrast sufficient

### Testing
- [ ] Unit test coverage ≥ 80%
- [ ] Critical flows manually tested
- [ ] Different screen sizes tested
- [ ] Dark mode tested

---

## 8. Offline Support Checklist

### Repository
- [ ] UI observes Database, not Network
- [ ] `refresh()` fetches API → saves to DB
- [ ] Entities have sync status field
- [ ] Optimistic updates implemented

### Sync
- [ ] WorkManager for background sync
- [ ] Network constraints configured
- [ ] Exponential backoff on failure
- [ ] Conflict resolution strategy

### UI
- [ ] Offline indicator shown
- [ ] Stale data warning if applicable
- [ ] Refresh available when online

---

## 9. Migration Checklist

### Database Migration
- [ ] Migration class created
- [ ] Schema export enabled
- [ ] Migration tested with fallback
- [ ] Destructive migration avoided

### API Migration
- [ ] New endpoints added alongside old
- [ ] Feature flag for gradual rollout
- [ ] Backward compatibility maintained
- [ ] Error handling for both versions

---

## 10. Verification

### For Each Checklist
- [ ] Have you used the relevant checklist?
- [ ] All mandatory items checked?
- [ ] Non-blocking items documented for follow-up?
