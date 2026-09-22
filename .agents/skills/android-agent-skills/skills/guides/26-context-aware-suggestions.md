---
name: Context-Aware Suggestions
description: File-type-specific code improvement suggestions for AI agents
compliance_level: RECOMMENDED
tags: [ai, suggestions, context-aware, intelligent, recommendations]
version: 2.2.0
last_updated: 2026-01-24
---

# Context-Aware Suggestions

## Context
AI agents should provide intelligent, context-aware suggestions based on file type, content, and project patterns. This guide enables agents to deliver precise, actionable recommendations.

## Detection Strategy

### File Type Detection
```kotlin
enum class FileType {
    VIEW_MODEL,           // *ViewModel.kt
    REPOSITORY,           // *Repository.kt, *RepositoryImpl.kt
    USE_CASE,             // *UseCase.kt
    COMPOSABLE,           // Has @Composable annotations
    ENTITY,               // @Entity, *Entity.kt
    DAO,                  // @Dao, *Dao.kt
    DI_MODULE,            // @Module, *Module.kt
    SKILL,                // extends BaseSkill
    TEST,                 // *Test.kt, *Spec.kt
    SCREEN,               // *Screen.kt
    ROUTE,                // *Route.kt
}
```

### Context Analysis
```kotlin
data class FileContext(
    val fileType: FileType,
    val hasHilt: Boolean,
    val hasKoin: Boolean,
    val hasCompose: Boolean,
    val hasRoom: Boolean,
    val hasRetrofit: Boolean,
    val patterns: List<Pattern>,
    val antiPatterns: List<AntiPattern>,
    val missingPatterns: List<Pattern>,
)
```

## Suggestions by File Type

### 1. ViewModel Files

#### Detection
```regex
class \w+ViewModel.*: ViewModel\(\)
```

#### Context Checks
- [ ] Extends AndroidX `ViewModel`?
- [ ] Uses `viewModelScope` (not custom scope)?
- [ ] Exposes `StateFlow` (not `MutableStateFlow`)?
- [ ] Uses `Channel` for events (not `SharedFlow`)?
- [ ] Has `@Immutable` UI state?
- [ ] Injects UseCases (not Repositories)?

#### Suggestions
```kotlin
// If missing: private _state + public state
/**
 * SUGGESTION: Expose state as read-only StateFlow
 * 
 * Current:
 *   val state = MutableStateFlow(UiState())
 * 
 * Recommended:
 *   private val _state = MutableStateFlow(UiState())
 *   val state: StateFlow<UiState> = _state.asStateFlow()
 * 
 * Reason: Prevents external mutation, encapsulation
 * Impact: Better architecture, fewer bugs
 * Effort: 2 minutes
 */

// If missing: StateStoreHolder
/**
 * SUGGESTION: Implement StateStoreHolder to reduce boilerplate
 * 
 * Current: 7 lines of state management code
 * 
 * Recommended:
 *   class ProfileViewModel : MviViewModel<ProfileUiState, ProfileEvent>(ProfileUiState())
 * 
 * Benefits:
 *   - Eliminates 5-7 lines per ViewModel
 *   - Consistent pattern
 *   - Easier to test
 * 
 * See: skills/templates/examples/StateStoreHolderExample.kt
 * Effort: 5 minutes
 */

// If injecting Repository
/**
 * ⚠️  ARCHITECTURE ISSUE: Injecting Repository directly
 * 
 * Current:
 *   class ProfileViewModel(private val userRepository: UserRepository)
 * 
 * Problem: Violates Clean Architecture (ViewModel → UseCase → Repository)
 * 
 * Recommended:
 *   class ProfileViewModel(private val getUserUseCase: GetUserUseCase)
 * 
 * Action: Create UseCase layer
 * Priority: HIGH
 * Effort: 10 minutes
 */

// If using GlobalScope
/**
 * 🔴 CRITICAL: GlobalScope causes memory leaks
 * 
 * Current:
 *   GlobalScope.launch { ... }
 * 
 * Fix:
 *   viewModelScope.launch { ... }
 * 
 * Reason: viewModelScope cancels when ViewModel cleared
 * Priority: CRITICAL
 * Effort: 1 minute
 */
```

### 2. Composable Files

#### Detection
```regex
@Composable\s+fun
```

#### Context Checks
- [ ] Route vs Screen separation?
- [ ] State hoisting?
- [ ] Preview functions?
- [ ] Proper parameter order?
- [ ] `modifier: Modifier = Modifier`?
- [ ] Method references for callbacks?

#### Suggestions
```kotlin
// If missing: Route/Screen separation
/**
 * SUGGESTION: Separate Route (stateful) and Screen (stateless)
 * 
 * Current: Single Composable with ViewModel
 * 
 * Recommended:
 *   @Composable
 *   fun ProfileRoute(viewModel: ProfileViewModel = hiltViewModel()) {
 *       val state by viewModel.state.collectAsStateWithLifecycle()
 *       ProfileScreen(state = state, onAction = viewModel::onAction)
 *   }
 *   
 *   @Composable
 *   fun ProfileScreen(state: ProfileUiState, onAction: (Action) -> Unit) {
 *       // Pure UI
 *   }
 * 
 * Benefits:
 *   - Testable without ViewModel
 *   - Preview-friendly
 *   - Reusable
 * 
 * See: skills/guides/05-jetpack-compose.md
 * Effort: 5 minutes
 */

// If missing: @Immutable on data class
/**
 * SUGGESTION: Mark data classes with @Immutable
 * 
 * Current:
 *   data class User(val name: String)
 * 
 * Recommended:
 *   @Immutable
 *   data class User(val name: String)
 * 
 * Reason: Enables Compose compiler optimizations
 * Impact: Fewer unnecessary recompositions
 * Effort: 30 seconds per class
 */

// If lambda in items()
/**
 * ⚠️  PERFORMANCE: Lambda allocations in items() loop
 * 
 * Current:
 *   items(users) { user ->
 *       UserCard(onClick = { viewModel.onUserClick(user.id) })
 *   }
 * 
 * Issue: New lambda created for each item on every recomposition
 * 
 * Fix 1: Use key parameter
 *   items(users, key = { it.id }) { user ->
 *       UserCard(onClick = { viewModel.onUserClick(user.id) })
 *   }
 * 
 * Fix 2: Extract callback
 *   val onUserClick = remember { { id: String -> viewModel.onUserClick(id) } }
 *   items(users) { user ->
 *       UserCard(onClick = { onUserClick(user.id) })
 *   }
 * 
 * Priority: MEDIUM
 * Effort: 2 minutes
 */
```

### 3. Repository Files

#### Detection
```regex
(class|interface) \w+Repository
```

#### Context Checks
- [ ] Returns `Result<T>` (not throws)?
- [ ] Suspend functions?
- [ ] Offline-first pattern?
- [ ] Proper caching?

#### Suggestions
```kotlin
// If throwing exceptions
/**
 * 🔴 ERROR HANDLING: Throwing exceptions from Repository
 * 
 * Current:
 *   suspend fun getUser(id: String): User {
 *       if (offline) throw NetworkException()
 *   }
 * 
 * Problem: Forces try-catch at every call site
 * 
 * Recommended:
 *   suspend fun getUser(id: String): Result<User> {
 *       return try {
 *           Result.success(api.getUser(id))
 *       } catch (e: Exception) {
 *           Result.failure(e)
 *       }
 *   }
 * 
 * See: skills/guides/10-error-handling.md
 * Priority: HIGH
 * Effort: 5 minutes per function
 */

// If missing caching
/**
 * SUGGESTION: Implement cache-first strategy
 * 
 * Current: Always fetches from network
 * 
 * Recommended:
 *   suspend fun getUser(id: String, forceRefresh: Boolean = false): Result<User> {
 *       if (!forceRefresh) {
 *           cache.get(id)?.let { return Result.success(it) }
 *       }
 *       return api.getUser(id)
 *           .onSuccess { cache.put(id, it) }
 *   }
 * 
 * Benefits:
 *   - Faster response time
 *   - Offline support
 *   - Reduced network usage
 * 
 * See: skills/guides/14-offline-first.md
 * Effort: 15 minutes
 */
```

### 4. UseCase Files

#### Detection
```regex
class \w+UseCase
```

#### Context Checks
- [ ] Single responsibility?
- [ ] Has `operator fun invoke()`?
- [ ] Returns `Result<T>`?
- [ ] Proper dispatcher?

#### Suggestions
```kotlin
// If missing invoke operator
/**
 * SUGGESTION: Add invoke operator for cleaner syntax
 * 
 * Current:
 *   class GetUserUseCase {
 *       suspend fun execute(id: String): Result<User> { ... }
 *   }
 *   // Usage: getUserUseCase.execute(id)
 * 
 * Recommended:
 *   class GetUserUseCase {
 *       suspend operator fun invoke(id: String): Result<User> { ... }
 *   }
 *   // Usage: getUserUseCase(id)
 * 
 * Benefits: More concise, idiomatic Kotlin
 * Effort: 30 seconds
 */

// If doing too much
/**
 * ⚠️  ARCHITECTURE: UseCase doing multiple operations
 * 
 * Current:
 *   class ManageUserUseCase {
 *       suspend operator fun invoke(action: Action): Result<User> {
 *           when (action) {
 *               is Create -> repository.create(action.user)
 *               is Update -> repository.update(action.user)
 *               is Delete -> repository.delete(action.id)
 *           }
 *       }
 *   }
 * 
 * Problem: Violates Single Responsibility Principle
 * 
 * Recommended: Split into separate UseCases
 *   - CreateUserUseCase
 *   - UpdateUserUseCase
 *   - DeleteUserUseCase
 * 
 * Priority: MEDIUM
 * Effort: 10 minutes
 */
```

### 5. Skill Files

#### Detection
```regex
class \w+Skill.*: BaseSkill
```

#### Context Checks
- [ ] Has metadata?
- [ ] Validates input?
- [ ] Returns `SkillResult<T>`?
- [ ] Pure Kotlin (no Android dependencies)?
- [ ] Uses `checkCancellation()`?

#### Suggestions
```kotlin
// If missing validation
/**
 * SUGGESTION: Add input validation
 * 
 * Current: No validation in validate() method
 * 
 * Recommended:
 *   override suspend fun validate(input: MyInput): SkillResult<Unit> {
 *       return validate {
 *           requireNotBlank(input.query, "query")
 *           requireInRange(input.limit, 1..100, "limit")
 *       }
 *   }
 * 
 * See: skills/guides/24-validation-rules.md
 * Available: requireNotBlank, requireValidEmail, requireValidUrl, etc.
 * Effort: 2 minutes
 */

// If missing cancellation support
/**
 * SUGGESTION: Add cancellation support for long operations
 * 
 * Current: No checkCancellation() calls
 * 
 * Recommended:
 *   override suspend fun doExecute(input: Input, context: SkillContext): SkillResult<Output> {
 *       checkCancellation()  // Check before expensive operation
 *       val result = expensiveOperation()
 *       checkCancellation()  // Check again if needed
 *       return SkillResult.success(result)
 *   }
 * 
 * Reason: Allows graceful cancellation
 * Effort: 30 seconds
 */

// If has Android dependencies
/**
 * 🔴 ARCHITECTURE: Skill has Android dependencies
 * 
 * Current:
 *   import android.content.Context
 *   class MySkill(private val context: Context) : BaseSkill<Input, Output>()
 * 
 * Problem: Skills should be pure Kotlin for testability and KMP
 * 
 * Solution: Inject side effects via SkillContext
 *   class MySkill : BaseSkill<Input, Output>() {
 *       override suspend fun doExecute(input: Input, context: SkillContext): SkillResult<Output> {
 *           val androidContext = context.get<Context>("androidContext")
 *           // ...
 *       }
 *   }
 * 
 * Priority: HIGH
 * Effort: 5 minutes
 */
```

### 6. DI Module Files

#### Detection
```regex
@Module|module \{
```

#### Context Checks
- [ ] Using `@Binds` (Hilt)?
- [ ] Using `singleOf`/`viewModelOf` (Koin)?
- [ ] Custom qualifiers (not `@Named`)?
- [ ] Proper scoping?

#### Suggestions
```kotlin
// If using @Provides for interfaces (Hilt)
/**
 * SUGGESTION: Use @Binds instead of @Provides for interfaces
 * 
 * Current:
 *   @Provides
 *   fun provideUserRepository(impl: UserRepositoryImpl): UserRepository = impl
 * 
 * Problem: Generates more code, slower compilation
 * 
 * Recommended:
 *   @Binds
 *   abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository
 * 
 * Benefits:
 *   - Faster compilation
 *   - Less generated code
 * 
 * Effort: 1 minute per binding
 */

// If using old Koin syntax
/**
 * SUGGESTION: Use Koin DSL functions for less boilerplate
 * 
 * Current:
 *   single<UserRepository> { UserRepositoryImpl(get()) }
 *   viewModel { ProfileViewModel(get()) }
 * 
 * Recommended:
 *   singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
 *   viewModelOf(::ProfileViewModel)
 * 
 * Benefits: More concise, type-safe
 * Effort: 30 seconds per declaration
 */
```

### 7. Test Files

#### Detection
```regex
@Test|class \w+Test
```

#### Context Checks
- [ ] Using JUnit5?
- [ ] Using Turbine for Flow testing?
- [ ] Using MockK?
- [ ] Proper test naming?

#### Suggestions
```kotlin
// If testing StateFlow without Turbine
/**
 * SUGGESTION: Use Turbine for Flow testing
 * 
 * Current:
 *   viewModel.state.value shouldBe expectedState
 * 
 * Problem: Can miss intermediate states
 * 
 * Recommended:
 *   viewModel.state.test {
 *       assertEquals(initialState, awaitItem())
 *       viewModel.loadData()
 *       assertEquals(loadingState, awaitItem())
 *       assertEquals(successState, awaitItem())
 *   }
 * 
 * Add dependency:
 *   testImplementation("app.cash.turbine:turbine:1.0.0")
 * 
 * Effort: 3 minutes
 */
```

## Suggestion Priority System

### Priority Levels
| Priority | Color | When to Suggest | Action |
|----------|-------|-----------------|--------|
| 🔴 CRITICAL | Red | Memory leaks, crashes, security | Fix immediately |
| 🟠 HIGH | Orange | Architecture violations, performance | Fix before merge |
| 🟡 MEDIUM | Yellow | Best practices, maintainability | Fix soon |
| 🟢 LOW | Green | Style, conventions | Optional |

### Effort Estimation
- < 1 minute: Quick fix
- 1-5 minutes: Simple refactor
- 5-15 minutes: Moderate change
- 15-30 minutes: Significant refactor
- > 30 minutes: Major change (suggest breakdown)

## Intelligent Suggestion Algorithm

```kotlin
fun generateSuggestions(file: KtFile, context: FileContext): List<Suggestion> {
    val suggestions = mutableListOf<Suggestion>()
    
    // 1. Check for critical issues first
    suggestions += detectCriticalIssues(file)
    
    // 2. Check for anti-patterns
    context.antiPatterns.forEach { antiPattern ->
        suggestions += createAntiPatternSuggestion(antiPattern)
    }
    
    // 3. Check for missing best practices
    context.missingPatterns.forEach { missingPattern ->
        suggestions += createMissingPatternSuggestion(missingPattern)
    }
    
    // 4. Check for optimization opportunities
    suggestions += detectOptimizationOpportunities(file, context)
    
    // 5. Sort by priority (Critical → High → Medium → Low)
    return suggestions.sortedByDescending { it.priority }
}
```

## Context-Aware Examples

### Example 1: ViewModel Analysis
```kotlin
// Input file: ProfileViewModel.kt
class ProfileViewModel(
    private val userRepository: UserRepository  // ❌ Direct repository
) : ViewModel() {
    val state = MutableStateFlow(ProfileUiState())  // ❌ Exposed mutable
    
    fun loadProfile() {
        GlobalScope.launch {  // ❌ GlobalScope
            state.value = state.value.copy(isLoading = true)
        }
    }
}

// AI Agent Output:
/**
 * 🤖 AI ANALYSIS: 3 issues found
 * 
 * 🔴 CRITICAL (1):
 *   - Line 8: GlobalScope causes memory leak
 *     Fix: Use viewModelScope.launch
 *     Effort: 1 minute
 * 
 * 🟠 HIGH (2):
 *   - Line 3: Direct Repository injection violates Clean Architecture
 *     Fix: Create GetUserUseCase
 *     Effort: 10 minutes
 *   
 *   - Line 5: MutableStateFlow exposed publicly
 *     Fix: Make private, expose StateFlow
 *     Effort: 2 minutes
 * 
 * 💡 SUGGESTIONS (2):
 *   - Consider StateStoreHolder to reduce boilerplate (5-7 lines saved)
 *   - Add @Immutable to ProfileUiState
 */
```

### Example 2: Composable Analysis
```kotlin
// Input file: UserListScreen.kt
@Composable
fun UserListScreen(viewModel: UserViewModel = hiltViewModel()) {
    val users = viewModel.users.collectAsState()
    
    LazyColumn {
        items(users.value) { user ->
            UserCard(
                user = user,
                onClick = { viewModel.onUserClick(user.id) }  // ❌ Lambda allocation
            )
        }
    }
}

// AI Agent Output:
/**
 * 🤖 AI ANALYSIS: 2 suggestions
 * 
 * 🟡 MEDIUM:
 *   - Line 9: Lambda allocations in items() loop
 *     Impact: Unnecessary recompositions
 *     Fix: Add key parameter or use remember
 *     Effort: 2 minutes
 * 
 * 💡 SUGGESTIONS:
 *   - Separate Route/Screen for better testability
 *   - Use collectAsStateWithLifecycle() instead of collectAsState()
 *   - Add Preview functions
 */
```

## Integration with Development Workflow

### 1. On File Open
```
→ Analyze file type and context
→ Show quick suggestions in IDE
→ Highlight critical issues inline
```

### 2. On Code Edit
```
→ Real-time analysis
→ Update suggestions dynamically
→ Show fix previews
```

### 3. On Save/Commit
```
→ Full project analysis
→ Block critical issues
→ Generate improvement report
```

### 4. On Pull Request
```
→ Comprehensive review
→ Prioritized feedback
→ Effort estimation
→ Auto-fix suggestions
```

## Verification Checklist
- [ ] Suggestions are context-aware (file type specific)
- [ ] Priority levels assigned correctly
- [ ] Effort estimation provided
- [ ] Code examples included (before/after)
- [ ] Related guides referenced
- [ ] Fix impact explained
- [ ] Auto-fix available for simple issues

## Related Guides
- [20-anti-patterns.md](./20-anti-patterns.md) - Detection rules
- [25-performance-benchmarks.md](./25-performance-benchmarks.md) - Performance targets
- [21-code-review.md](./21-code-review.md) - Review guidelines
