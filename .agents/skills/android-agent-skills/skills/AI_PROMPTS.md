# AI Code Generation Prompts

> **Purpose**: Optimized prompts for AI agents to generate production-ready Android code following project standards.
> 
> **For**: GitHub Copilot, Claude, GPT, and other AI coding assistants
> 
> **Created & Reviewed by**: TrongLB & AI Agents
> 
> **Maintained**: Always in sync with latest guides (no scripts to maintain!)

---

## 🔧 Mode Types

Each prompt is tagged with a Mode to help AI agents understand context:

| Mode | Meaning | Android Deps Allowed? |
|------|---------|----------------------|
| `ANDROID_MODE` | Android-specific code (ViewModel, Compose, etc.) | ✅ Yes |
| `SKILL_MODE` | Pure Kotlin Agent Skills (no Android) | ❌ No |
| `Testing` | Unit/Integration tests | Depends on target |

---

## 🎯 Quick Reference

| Component | Prompt | When to Use |
|-----------|--------|-------------|
| ViewModel | `@gen-viewmodel` | Need MVI ViewModel with state management |
| Repository | `@gen-repository` | Need data layer with offline-first |
| UseCase | `@gen-usecase` | Need business logic with validation |
| Screen | `@gen-screen` | Need Compose UI with Route/Screen pattern |
| Test | `@gen-test` | Need unit tests with Turbine + MockK |
| Feature | `@gen-feature` | Need complete feature (all layers) |
| **Testing & Quality** |  |  |
| ViewModel Test | `@gen-viewmodel-test` | Generate comprehensive ViewModel tests |
| UseCase Test | `@gen-usecase-test` | Generate UseCase tests with validation scenarios |
| Repository Test | `@gen-repository-test` | Generate Repository tests (offline/online) |
| Screen Test | `@gen-screen-test` | Generate Compose UI tests |
| Integration Test | `@gen-integration-test` | Generate multi-layer integration tests |
| **Analysis** |  |  |
| Coverage Analysis | `@analyze-test-coverage` | Identify untested code and gaps |
| Quality Check | `@analyze-test-quality` | Validate test quality and detect smells |
| Feedback Analysis | `@analyze-feedback` | Analyze agent feedback, suggest improvements |
| **Code Review** |  |  |
| Review File | `@review [file]` | Review single file with severity levels |
| Review Changes | `@review-changes` | Review staged/unstaged git changes |
| Review Commit | `@review-commit [hash]` | Review specific commit by hash |
| Review PR | `@review-pr [number]` | Review pull request |
| Generate Report | `@review-report [source] --output [file.md]` | Generate markdown review report |
| **Skill Framework** |  |  |
| Generate Skill | `@gen-skill` | Generate new Agent Skill with validation and tests |
| Generate Pipeline | `@gen-pipeline` | Generate skill pipeline with error handling |
| Generate Skill Test | `@gen-skill-test` | Generate comprehensive tests for Agent Skill |
| Skill Analysis | `@analyze-skill` | Analyze Agent Skill quality (validation, errors, docs) |
| Skill Optimization | `@optimize-skill` | Optimize Skill performance (algorithm, memory) |
| Add Validation | `@add-validation` | Generate comprehensive validation for Skill inputs |

---

## @gen-viewmodel
> **Mode**: ANDROID_MODE | **Layer**: Presentation

### Prompt Template

```
Generate an Android ViewModel following MVI pattern for [FEATURE_NAME].

REQUIREMENTS:
• Feature purpose: [DESCRIPTION]
• State properties: [LIST with types and defaults]
• User actions: [LIST]
• Dependencies: [UseCases to inject]
• DI framework: [hilt OR koin]
• Special needs: [e.g., pagination, search, offline-first]

MANDATORY CONSTRAINTS:
1. Extend AndroidX ViewModel (import androidx.lifecycle.ViewModel)
2. Use StateFlow<UiState> (private mutable, public read-only via asStateFlow())
3. Use Channel<Event> for one-time events (NOT SharedFlow)
4. Use viewModelScope for all coroutines (NOT GlobalScope, NOT custom scope)
5. Mark UiState with @Immutable annotation
6. Use Result<T> pattern for error handling (onSuccess/onFailure)
7. Include derived properties in UiState (computed fields)
8. Add comprehensive KDoc (class, methods, parameters)
9. Follow naming: on[Action] for user actions (e.g., onSubmitClick, onInputChanged)

ARCHITECTURE:
• Clean Architecture: UI → ViewModel → UseCase → Repository
• NEVER inject Repository directly into ViewModel
• Inject UseCases only

FILE STRUCTURE:
1. Package declaration
2. Imports (organized: Android → Third-party → Project)
3. KDoc for class
4. ViewModel class with DI annotation
5. State management (private _state, public state, Channel)
6. Public action methods (user interactions)
7. Private business logic methods
8. UiState data class (@Immutable, with derived properties)
9. Event sealed interface

REFERENCE GUIDES:
• Architecture: skills/guides/01-architecture.md
• State Management: skills/guides/07-state-management.md  
• Conventions: skills/guides/02-coding-conventions.md
• Pattern Reference: skills/templates/examples/UseCaseViewModelExample.kt

OUTPUT: Complete, production-ready Kotlin code with NO placeholders or TODOs
```

### Common Mistakes to Avoid

| ❌ DON'T | ✅ DO |
|----------|------|
| Inject Repository directly | Inject UseCase instead |
| Use `SharedFlow<Event>` | Use `Channel<Event>` |
| Use `GlobalScope.launch` | Use `viewModelScope.launch` |
| Expose `MutableStateFlow` | Expose read-only `StateFlow` via `.asStateFlow()` |
| Miss `@Immutable` on UiState | Always add `@Immutable` annotation |
| Use `collectAsState()` in Compose | Use `collectAsStateWithLifecycle()` |
| Put business logic in ViewModel | Extract to UseCase |

### Example Usage

```
Generate an Android ViewModel following MVI pattern for UserProfile.

REQUIREMENTS:
• Feature purpose: Display and edit user profile information
• State properties:
  - userId: String = ""
  - name: String = ""
  - email: String = ""
  - bio: String = ""
  - avatarUrl: String? = null
  - isLoading: Boolean = false
  - error: String? = null
• User actions: loadProfile(userId), updateProfile(name, email, bio), onDismissError()
• Dependencies: GetUserProfileUseCase, UpdateUserProfileUseCase
• DI framework: hilt
• Special needs: Cache profile data, handle offline mode

[Template above continues...]
```

---

## @gen-repository
> **Mode**: ANDROID_MODE | **Layer**: Data

### Prompt Template

```
Generate Android Repository (interface + implementation) for [ENTITY_NAME].

REQUIREMENTS:
• Entity: [NAME with description]
• Operations: [LIST: get, observe, create, update, delete, search, etc.]
• Data sources: [local/remote/both]
• Offline strategy: [cache-first/network-first/cache-then-network]
• Caching: [yes/no with duration]

MANDATORY CONSTRAINTS:
1. Interface in domain layer (NO Android dependencies)
2. Implementation in data layer
3. Use suspend fun for one-shot operations
4. Use Flow<T> for reactive/observable data
5. Return Result<T> for operations that can fail
6. Handle offline gracefully with cache fallback
7. Add comprehensive error handling (try-catch with specific exceptions)
8. Include mapper functions (toEntity, toDomain) if using DTOs
9. Add KDoc for all public methods

OFFLINE-FIRST PATTERN (if applicable):
1. Try remote fetch
2. On success: update local cache + return
3. On failure: fallback to cache if available
4. Handle stale data with timestamps

FILE STRUCTURE:
1. Repository interface (domain/repository/)
   - Method signatures only
   - KDoc for each method
2. Repository implementation (data/repository/)
   - Constructor injection (API, DAO, etc.)
   - Method implementations with error handling
   - Mapper functions

REFERENCE GUIDES:
• Architecture: skills/guides/01-architecture.md
• Error Handling: skills/guides/10-error-handling.md
• Offline-First: skills/guides/14-offline-first.md

OUTPUT: Two files - Interface + Implementation, both complete and production-ready
```

### Common Mistakes to Avoid

| ❌ DON'T | ✅ DO |
|----------|------|
| Return DTOs from interface | Return domain models only |
| Put interface in data layer | Interface in domain, impl in data |
| Swallow `CancellationException` | Always rethrow it |
| Hardcode `Dispatchers.IO` | Inject dispatcher via constructor |
| Ignore cache fallback | Implement offline-first pattern |
| Use `getOrThrow()` | Return `Result<T>` for error handling |

### Example Usage

```
Generate Android Repository (interface + implementation) for User.

REQUIREMENTS:
• Entity: User profile with id, name, email, avatar
• Operations: getUser(id), observeUser(id), updateUser(user), searchUsers(query)
• Data sources: both (API + Room database)
• Offline strategy: cache-first
• Caching: yes, 5 minutes expiration

[Template continues...]
```

---

## @gen-usecase
> **Mode**: ANDROID_MODE | **Layer**: Domain

### Prompt Template

```
Generate Android UseCase for [USE_CASE_NAME].

REQUIREMENTS:
• Purpose: [One-sentence description]
• Input parameters: [LIST with types]
• Output: Result<[TYPE]>
• Repository: [REPOSITORY_NAME]
• Business rules: [LIST of validations and logic]
• Side effects: [e.g., analytics, logging]

MANDATORY CONSTRAINTS:
1. Pure Kotlin (NO Android dependencies)
2. Single Responsibility (one clear purpose)
3. Use operator fun invoke() for execution
4. Return Result<T> (never throw exceptions)
5. Validate all inputs before repository call
6. Add comprehensive KDoc (@param, @return, @see)
7. Include business logic (NOT just repository wrapper)
8. Use validation DSL from skills/templates/skill/base/Validation.kt

VALIDATION EXAMPLES:
• requireNotBlank(value, "fieldName")
• requireValidEmail(email, "email")
• requireInRange(age, 13..150, "age")
• requireValidUrl(url, "website")
• See: skills/guides/24-validation-rules.md

FILE STRUCTURE:
1. Package declaration
2. Imports
3. KDoc for class
4. Class with constructor injection
5. operator fun invoke() with:
   - Input validation
   - Business logic
   - Repository call
   - Result transformation

REFERENCE GUIDES:
• Architecture: skills/guides/01-architecture.md
• Validation: skills/guides/24-validation-rules.md
• Error Handling: skills/guides/10-error-handling.md

OUTPUT: Complete UseCase with validation and business logic
```

### Common Mistakes to Avoid

| ❌ DON'T | ✅ DO |
|----------|------|
| Import Android classes | Pure Kotlin only |
| Just wrap repository call | Add validation + business logic |
| Throw exceptions | Return `Result<T>` |
| Skip input validation | Validate all inputs first |
| Multiple responsibilities | One UseCase = One purpose |
| Name as `UseCase.execute()` | Use `operator fun invoke()` |

### Example Usage

```
Generate Android UseCase for UpdateUserProfile.

REQUIREMENTS:
• Purpose: Update user profile with validation and analytics
• Input parameters: userId: String, name: String, email: String, bio: String
• Output: Result<Unit>
• Repository: UserRepository
• Business rules:
  - userId cannot be blank
  - name must be 2-50 characters
  - email must be valid format
  - bio max 500 characters
  - Log analytics event on success
• Side effects: Track analytics, log to Crashlytics

[Template continues...]
```

---

## @gen-screen

### Prompt Template

```
Generate Jetpack Compose screen for [SCREEN_NAME].

REQUIREMENTS:
• Screen purpose: [DESCRIPTION]
• ViewModel: [VIEWMODEL_NAME]
• UI layout: [DESCRIPTION of layout and components]
• User interactions: [LIST of buttons, inputs, etc.]
• Screen states: [loading, success, error, empty, etc.]
• Navigation: [back, forward to other screens]

MANDATORY CONSTRAINTS:
1. Separate Route (stateful) and Screen (stateless) composables
2. Route handles: ViewModel injection, state collection, event handling
3. Screen is pure UI: receives State + Callbacks only
4. Use collectAsStateWithLifecycle() for state (NOT collectAsState)
5. Handle events in LaunchedEffect(Unit) within Route
6. Follow parameter order: State → Callbacks → Modifier
7. Add modifier: Modifier = Modifier to Screen
8. Include 3+ Preview functions (normal, loading, error, empty)
9. Use Material3 components (Scaffold, TopAppBar, etc.)
10. Add @Immutable to all state classes passed to composables

ROUTE PATTERN:
@Composable
fun [Name]Route(
    onNavigate: () -> Unit,
    viewModel: [Name]ViewModel = hiltViewModel(), // or koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) { /* handle */ }
        }
    }
    
    [Name]Screen(state, viewModel::onAction, onNavigate)
}

SCREEN PATTERN:
@Composable
fun [Name]Screen(
    state: [Name]UiState,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(topBar = { /* */ }) { padding ->
        when {
            state.isLoading -> LoadingContent()
            state.error != null -> ErrorContent(state.error)
            else -> SuccessContent(state)
        }
    }
}

FILE STRUCTURE:
1. Imports
2. Route composable (stateful)
3. Screen composable (stateless)
4. Content composables (LoadingContent, ErrorContent, etc.)
5. Preview functions (3+)

REFERENCE GUIDES:
• Compose: skills/guides/05-jetpack-compose.md
• Performance: skills/guides/06-compose-performance.md
• State: skills/guides/07-state-management.md

OUTPUT: Complete Compose file with Route + Screen + Content + Previews
```

### Common Mistakes to Avoid

| ❌ DON'T | ✅ DO |
|----------|------|
| Put ViewModel in Screen | ViewModel in Route only |
| Use `collectAsState()` | Use `collectAsStateWithLifecycle()` |
| Skip Route/Screen separation | Always separate stateful/stateless |
| Callbacks before Modifier | Order: Required → State → Modifier → Callbacks |
| Miss `@Immutable` on state | Mark all state classes `@Immutable` |
| Only 1 Preview | Add 3+ Previews (normal, loading, error) |
| Business logic in composable | Keep in ViewModel/UseCase |

---

## @gen-test
> **Mode**: ANDROID_MODE | **Layer**: Testing

### Prompt Template

```
Generate unit test for [CLASS_NAME] using JUnit5 + MockK + Turbine.

REQUIREMENTS:
• Test target: [CLASS_NAME]
• Test scenarios: [LIST of test cases]
• Dependencies to mock: [LIST]
• Special setup: [e.g., fake data, test dispatchers]

MANDATORY CONSTRAINTS:
1. Use JUnit5 (org.junit.jupiter.api.Test, NOT junit4)
2. Use MockK for mocking (mockk(), coEvery, verify)
3. Use Turbine for Flow/StateFlow testing (test { awaitItem() })
4. Use runTest for coroutine tests
5. Test naming: Backtick format with "when [condition] then [expected]" pattern
6. AAA pattern: Arrange (Given) → Act (When) → Assert (Then)
7. Test state transitions for ViewModels (initial → loading → success/error)
8. One assertion focus per test method
9. Mock setup in @BeforeEach
10. Include negative test cases (error scenarios)

VIEWMODEL TEST PATTERN:
@Test
fun `when load succeeds then state updates correctly`() = runTest {
    // Given
    coEvery { useCase() } returns Result.success(data)
    
    // When
    viewModel.state.test {
        val initial = awaitItem()
        // Assert initial state
        
        viewModel.load()
        
        val loading = awaitItem()
        // Assert loading state
        
        val success = awaitItem()
        // Assert success state
    }
}

FILE STRUCTURE:
1. Package + imports
2. Test class
3. Mock dependencies (late-initialized)
4. @BeforeEach setup method
5. Test methods (one per scenario)
6. Helper methods (if needed)

REFERENCE GUIDES:
• Testing: skills/guides/11-testing.md
• Turbine: app.cash.turbine library

OUTPUT: Complete test file covering all scenarios
```

---

## @gen-feature

### Prompt Template

```
Generate complete Android feature for [FEATURE_NAME] with all layers.

REQUIREMENTS:
• Feature description: [DETAILED description]
• Entity/Model: [NAME with all fields and types]
• CRUD operations: [LIST: create, read, update, delete, list, search]
• User flows: [LIST of user scenarios]
• DI framework: [hilt OR koin]
• Include tests: [yes/no]

MANDATORY CONSTRAINTS:
Follow ALL patterns from individual prompts above:
• ViewModel: @gen-viewmodel pattern
• Repository: @gen-repository pattern
• UseCase: @gen-usecase pattern
• Screen: @gen-screen pattern
• Test: @gen-test pattern (if requested)

GENERATE FILES:
1. Domain Layer:
   - Model: domain/model/[Entity].kt
   - Repository interface: domain/repository/[Entity]Repository.kt
   - UseCases: domain/usecases/Get[Entity]UseCase.kt, etc.

2. Data Layer:
   - Repository impl: data/repository/[Entity]RepositoryImpl.kt
   - DTOs/Mappers: data/dto/[Entity]Dto.kt (if needed)

3. Presentation Layer:
   - ViewModel: presentation/[feature]/[Feature]ViewModel.kt
   - Screen: presentation/[feature]/[Feature]Screen.kt
   - UiState + Events in ViewModel file

4. Test Layer (if requested):
   - ViewModel test: test/.../[Feature]ViewModelTest.kt
   - UseCase tests (optional)

DI SETUP:
Include DI module setup (Hilt @Module or Koin module {})

REFERENCE ALL GUIDES:
• Architecture: skills/guides/01-architecture.md
• All component guides as per individual prompts

OUTPUT: Complete feature with ALL files, production-ready
```

---

## 🎨 Advanced Patterns

### With StateStoreHolder
Add to @gen-viewmodel:
```
ADDITIONAL REQUIREMENT:
• Use StateStoreHolder pattern to reduce boilerplate
• Implement StateStoreHolder<S> and EventHolder<E> interfaces
• Create base MviViewModel if needed

REFERENCE: skills/templates/examples/StateStoreHolderExample.kt
```

### With Pagination
Add to @gen-viewmodel:
```
ADDITIONAL REQUIREMENTS:
• Implement pagination using Paging3 library
• State includes: items, isLoadingMore, hasMore, page
• Actions: loadInitial(), loadMore(), refresh()
• Use collectAsLazyPagingItems() in Screen

REFERENCE: androidx.paging:paging-compose
```

### With Offline-First
Add to @gen-repository:
```
ADDITIONAL REQUIREMENTS:
• Cache-first with background sync
• Track lastSyncTime in state
• Handle conflict resolution (last-write-wins)
• Sync on connectivity restored

REFERENCE: skills/guides/14-offline-first.md
```

---

## 💡 Best Practices

### ✅ DO:
- Be **specific** with requirements (types, defaults, validations)
- List **all** state properties and actions
- Specify DI framework explicitly
- Mention special patterns (pagination, offline, etc.)
- Reference relevant guides
- Request **complete** code (no TODOs or placeholders)
- Ask for **production-ready** output

### ❌ DON'T:
- Use vague descriptions ("basic ViewModel", "simple screen")
- Omit required fields or types
- Mix patterns without clarification
- Skip error handling requirements
- Request "skeleton" or "template" code
- Forget to specify DI framework

---

## 🔍 Validation Checklist

After code generation, verify:

### Code Quality
- [ ] Compiles without errors
- [ ] Follows naming conventions (02-coding-conventions.md)
- [ ] Has proper imports (organized, no unused)
- [ ] Uses correct types (StateFlow, Channel, Result)
- [ ] Has comprehensive KDoc

### Architecture
- [ ] Correct layer (domain/data/presentation)
- [ ] Proper separation of concerns
- [ ] UseCase pattern (not Repository in ViewModel)
- [ ] No Android dependencies in domain layer

### Patterns
- [ ] MVI pattern (ViewModel)
- [ ] Route/Screen separation (Compose)
- [ ] Result<T> for error handling
- [ ] @Immutable annotations

### Best Practices
- [ ] Error handling included
- [ ] Offline support (if needed)
- [ ] Tests included (if requested)
- [ ] No hardcoded values
- [ ] Proper null safety

---

## 📖 Quick Examples

### Minimal ViewModel
```
Generate MVI ViewModel for Login. State: email, password, isLoading, error. Actions: onEmailChanged, onPasswordChanged, onLoginClick. UseCase: LoginUseCase. DI: hilt.
```

### Full Feature
```
Generate complete feature for Product. Entity: Product(id: String, name: String, price: Double, imageUrl: String, description: String). Operations: list, search, get details. Offline-first. DI: koin. Include tests.
```

### Screen Only
```
Generate Compose screen for ProductDetail. ViewModel: ProductDetailViewModel. UI: AsyncImage for product, title, price, description, Add to Cart button. States: loading (skeleton), success, error. Navigation: back button.
```

### Repository with Offline
```
Generate Repository for Article. Operations: getAll, getById, search, bookmark. Data sources: both (Retrofit API + Room). Offline: cache-first, 10 min expiration. Sync bookmarks on connectivity.
```

---

## @gen-viewmodel-test
> **Mode**: ANDROID_MODE | **Layer**: Testing

### Prompt Template

```
Generate comprehensive unit tests for [VIEWMODEL_NAME] using JUnit5, MockK, and Turbine.

REQUIREMENTS:
• ViewModel: [ViewModel class name]
• Dependencies to mock: [List of UseCases/Repositories]
• State properties to test: [List state fields]
• Actions to test: [List user actions]
• Special scenarios: [e.g., pagination, concurrent calls, cancellation]

MANDATORY CONSTRAINTS:
1. Use JUnit5 (@Test from org.junit.jupiter.api)
2. Use MockK for mocking (mockk(), coEvery, coVerify)
3. Use Turbine for StateFlow testing (flow.test { awaitItem() })
4. Use runTest for coroutine tests
5. Setup TestDispatcher in @BeforeEach, reset in @AfterEach
6. Test naming: Backtick format "when [condition] then [expected]"
7. Follow AAA pattern: Arrange (Given) → Act (When) → Assert (Then)
8. Include state transition tests (initial → loading → success/error)
9. Include error scenarios (network failure, validation errors)
10. Include edge cases (empty input, concurrent calls, cancellation)

TEST COVERAGE REQUIRED:
• Happy path (success flow with all state transitions)
• Error scenarios (at least 2: network failure, business logic error)
• Edge cases (empty/invalid input, boundary conditions)
• State transitions (verify each state change)
• Use case verification (correct parameters, call count)
• Cancellation (ViewModel.onCleared() during operation)

STRUCTURE:
1. Mock dependencies declaration
2. ViewModel lateinit var
3. @BeforeEach setup (Dispatchers.setMain, initialize mocks)
4. @AfterEach tearDown (Dispatchers.resetMain)
5. Happy path tests
6. Error scenario tests
7. Edge case tests
8. Cancellation tests

REFERENCE: guides/29-testing-automation.md section 3

OUTPUT: Complete test file with 6+ test methods, production-ready
```

### Example Usage

```
@gen-viewmodel-test

ViewModel: ProfileViewModel
Dependencies: GetUserProfileUseCase, UpdateUserProfileUseCase
State: user (User?), isLoading (Boolean), error (String?)
Actions: loadProfile(userId), updateProfile(name, email), onDismissError()
Special scenarios: Test concurrent loadProfile calls, cancellation during update
```

---

## @gen-usecase-test
> **Mode**: ANDROID_MODE | **Layer**: Testing

### Prompt Template

```
Generate comprehensive unit tests for [USECASE_NAME] with validation scenarios.

REQUIREMENTS:
• UseCase: [UseCase class name]
• Input parameters: [List with types]
• Return type: Result<[TYPE]>
• Repository to mock: [Repository name]
• Validation rules: [List all validation rules]
• Business logic: [Any transformations, calculations]

MANDATORY CONSTRAINTS:
1. Use JUnit5 and MockK
2. Test naming: "when [condition] then [expected]"
3. Mock repository only (UseCase logic should be real)
4. Test ALL validation rules (one test per rule)
5. Test error messages (exact message assertions)
6. Test data transformations (e.g., email normalization)
7. Verify repository calls with exact parameters
8. Test repository failure scenarios

TEST COVERAGE REQUIRED (CRITICAL):
• Happy path (valid input, repository succeeds)
• Validation errors (one test per validation rule)
• Repository failure (network error, database error)
• Edge cases (special characters, Unicode, max length)
• Data transformation (normalization, sanitization)
• Boundary conditions (min/max values)

VALIDATION TESTS (MOST IMPORTANT):
For each validation rule, create dedicated test:
• requireNotBlank → test with blank/empty string
• requireValidEmail → test with invalid formats
• requireInRange → test below min, above max
• requireValidPassword → test each requirement separately

REFERENCE: guides/29-testing-automation.md section 4

OUTPUT: Complete test file with validation tests for EVERY rule
```

### Example Usage

```
@gen-usecase-test

UseCase: UpdateUserProfileUseCase
Input: userId (String), name (String), email (String), bio (String)
Return: Result<Unit>
Repository: UserRepository
Validation rules:
- userId cannot be blank
- name must be 2-50 characters
- email must be valid format
- bio max 500 characters
Business logic: Normalize email to lowercase, trim whitespace
```

---

## @gen-repository-test

### Prompt Template

```
Generate comprehensive Repository tests for offline-first architecture.

REQUIREMENTS:
• Repository: [Repository interface and implementation name]
• Data sources: [API, DAO, etc.]
• Operations: [List: get, observe, create, update, delete]
• Offline strategy: [cache-first/network-first/cache-then-network]
• Caching: [Duration, staleness handling]

MANDATORY CONSTRAINTS:
1. Use JUnit5, MockK, Turbine
2. Mock external sources (API, DAO, etc.)
3. Test offline-first pattern:
   - Remote success → cache update → return data
   - Remote failure → fallback to cache
   - No cache + no network → failure
4. Test Flow-based operations with Turbine
5. Test concurrent requests (deduplication)
6. Verify cache updates after remote success
7. Test data mapping (DTO → Entity → Domain)

TEST SCENARIOS REQUIRED:
• Remote success (verify cache update)
• Remote failure with cache fallback
• Remote failure without cache (return error)
• observe() Flow emissions (multiple updates)
• Concurrent requests (should deduplicate)
• Stale cache handling (if applicable)
• Sync operations (background sync)

REFERENCE: guides/29-testing-automation.md section 5

OUTPUT: Complete test file covering offline-first scenarios
```

### Example Usage

```
@gen-repository-test

Repository: UserRepository / UserRepositoryImpl
Data sources: UserApi (Retrofit), UserDao (Room)
Operations: getUser(id), observeUser(id), updateUser(user), searchUsers(query)
Offline strategy: cache-first
Caching: 5 minutes expiration, background sync on connectivity
```

---

## @gen-screen-test

### Prompt Template

```
Generate Jetpack Compose UI tests for [SCREEN_NAME].

REQUIREMENTS:
• Screen: [Composable name]
• UI state: [UiState properties to test]
• User interactions: [Buttons, inputs, gestures]
• Test scenarios: [loading, success, error, empty]

MANDATORY CONSTRAINTS:
1. Use createComposeRule()
2. Use semantic matchers (onNodeWithText, onNodeWithTag, etc.)
3. Test all UI states (loading, success, error, empty)
4. Test user interactions (clicks, input, swipes)
5. Test accessibility (content descriptions, click actions)
6. Use TestTags for non-text elements
7. Verify callbacks are triggered

TEST COVERAGE:
• Loading state (shows loading indicator)
• Success state (displays data correctly)
• Error state (shows error message)
• Empty state (shows empty placeholder)
• User interactions (button clicks trigger callbacks)
• Accessibility (all interactive elements have semantics)

REFERENCE: guides/29-testing-automation.md section 6

OUTPUT: Complete UI test file with state and interaction tests
```

---

## @gen-integration-test

### Prompt Template

```
Generate integration test for [FEATURE_NAME] testing full flow.

REQUIREMENTS:
• Feature: [Feature description]
• Layers involved: ViewModel → UseCase → Repository
• External dependencies: [API, Database, etc. to mock]
• Test scenario: [End-to-end user flow]

MANDATORY CONSTRAINTS:
1. Mock ONLY external boundaries (API, Database)
2. Use REAL intermediate layers (UseCase, Repository logic)
3. Use Fake implementations for complex dependencies
4. Test full user flow from ViewModel to data source
5. Verify state changes through entire flow
6. Test offline scenarios (network fails, cache works)

STRUCTURE:
• Setup: Create real UseCase and Repository, mock API/DAO
• Test happy path: Full flow succeeds
• Test offline path: Network fails, cache works
• Verify: Cache updates, state changes correctly

REFERENCE: guides/29-testing-automation.md section 9

OUTPUT: Complete integration test file
```

---

## @analyze-test-coverage

### Prompt Template

```
Analyze test coverage for [MODULE/PACKAGE].

ANALYZE:
1. List all production files:
   - ViewModels (*ViewModel.kt)
   - UseCases (*UseCase.kt)
   - Repositories (*Repository*.kt)
   - Screens (*Screen.kt)

2. Check corresponding test files:
   - test/**/*Test.kt
   - androidTest/**/*Test.kt

3. Identify gaps:
   - Files WITHOUT tests
   - Files with PARTIAL coverage (check public methods)
   - Critical flows UNTESTED

4. Prioritize by risk:
   - HIGH: ViewModels with complex logic, UseCases with validation
   - MEDIUM: Repositories with offline logic
   - LOW: Simple data classes, DTOs

OUTPUT FORMAT:
## Test Coverage Report

### Summary
- Total Files: [count]
- Files with Tests: [count] ([percentage]%)
- Missing Tests: [count] ([percentage]%)

### High Priority (No Tests)
1. ❌ [FileName]
   - Risk: [HIGH/MEDIUM/LOW]
   - Reason: [why it's risky]
   - Missing: [what's not tested]
   - Recommend: [which @gen-* prompt to use]

### Medium Priority (Partial Tests)
[List files with partial coverage]

### Low Priority (Good Coverage)
[List files with >80% coverage]

REFERENCE: guides/29-testing-automation.md section 1

OUTPUT: Comprehensive coverage report with actionable recommendations
```

---

## @analyze-test-quality

### Prompt Template

```
Analyze test quality for [TEST_FILE].

CHECK:
1. Test naming:
   - Uses backtick format? ("when X then Y")
   - Descriptive and clear?

2. Assertions:
   - Has state assertions (not just verify())?
   - Checks actual behavior changes?
   - Meaningful error messages?

3. Coverage:
   - All public methods tested?
   - Error scenarios included?
   - Edge cases covered?

4. Test smells (detect these):
   - Only verify(), no state checks
   - No error testing
   - Vague names ("test1", "testLoad")
   - Multiple assertions unrelated
   - Thread.sleep() or arbitrary delays
   - No cleanup (@AfterEach missing)

5. Missing scenarios:
   - What's NOT tested?
   - What edge cases are missing?

OUTPUT FORMAT:
## Test Quality Report: [FileName]

### Quality Score: [0-100]

### ✅ Strengths
- [List good practices found]

### ❌ Issues Found
1. [Issue with line number]
   - Severity: [HIGH/MEDIUM/LOW]
   - Fix: [specific recommendation]

### 📋 Missing Scenarios
- [Scenario 1 that should be tested]
- [Scenario 2 that should be tested]

### 🔧 Recommended Improvements
[Specific code changes or additional tests]

REFERENCE: guides/29-testing-automation.md section 7

OUTPUT: Detailed quality report with actionable improvements
```

---

## 🧪 Testing Best Practices

### ✅ DO:
- Use **Turbine** for Flow/StateFlow testing (cleaner than collectAsState)
- Test **state transitions**, not just final state
- Include **error scenarios** (50% of tests should be error cases)
- Use **descriptive names** ("when valid email then succeeds")
- **One assertion focus** per test method
- Mock **external dependencies only** (API, database)
- Use **Fake implementations** for complex dependencies
- Write **integration tests** for critical flows

### ❌ DON'T:
- Don't use real database/network in unit tests
- Don't test Android framework (assume it works)
- Don't use Thread.sleep() (use TestDispatcher)
- Don't ignore flaky tests (fix or delete)
- Don't test private methods (test public API)
- Don't skip cleanup (@AfterEach)
- Don't use verify() without state assertions

---

## @gen-skill
> **Mode**: SKILL_MODE | **Layer**: Domain (Pure Kotlin)

### Prompt Template

```
Generate an Agent Skill following framework patterns for [SKILL_NAME].

REQUIREMENTS:
• Skill purpose: [DESCRIPTION]
• Input fields: [LIST with types]
• Output fields: [LIST with types]
• Dependencies (injected): [LIST - e.g., HttpClient, Database, etc.]
• Category: [DOMAIN | UTILITY | CORE]
• Error scenarios: [LIST - e.g., Network failure, Invalid input, etc.]

MANDATORY CONSTRAINTS:
1. Extend BaseSkill<InputType, OutputType>
2. Mark Input/Output data classes with @Immutable
3. Use skillMetadata DSL for metadata (id, name, description, category)
4. Implement validate() using validation DSL from Validation.kt
5. Implement doExecute() with checkCancellation() calls
6. Return SkillResult<T> (NEVER throw exceptions from doExecute)
7. Rethrow CancellationException explicitly in catch blocks
8. Add comprehensive KDoc with usage examples and error scenarios
9. Map exceptions to specific SkillError types (Network, Validation, etc.)
10. Support dependency injection via constructor

VALIDATION CHECKLIST (aim for 100% coverage):
- [ ] All String fields: requireNotBlank() or specific validator (email, URL, phone)
- [ ] All numeric fields: requireInRange() or specific validator (positive, confidence, percentage)
- [ ] All collections: requireNotEmpty() if required, requireSize() for constraints
- [ ] All email fields: requireValidEmail()
- [ ] All URL fields: requireValidUrl()
- [ ] All password fields: requireValidPassword() with strength requirements
- [ ] All date fields: requireDateInRange()
- [ ] Custom business rules: require() with clear error messages

ERROR HANDLING:
• Use try-catch in doExecute()
• Rethrow CancellationException explicitly
• Map exceptions to SkillError using TypedSkillError:

**COMPLETE ERROR TYPE MAPPING TABLE:**

| Exception Type | TypedSkillError | Usage |
|----------------|-----------------|-------|
| `IOException` | `TypedSkillError.Network` | Connection failures, DNS errors |
| `SocketTimeoutException` | `TypedSkillError.Timeout` | Network timeouts |
| `UnknownHostException` | `TypedSkillError.Network` | DNS resolution failures |
| `SSLException` | `TypedSkillError.Network.sslError()` | SSL/TLS errors |
| `HttpException(401)` | `TypedSkillError.Unauthorized` | Authentication failures |
| `HttpException(403)` | `TypedSkillError.Unauthorized` | Permission denied |
| `HttpException(404)` | `TypedSkillError.NotFound` | Resource not found |
| `HttpException(409)` | `TypedSkillError.Conflict` | Resource conflicts |
| `HttpException(429)` | `TypedSkillError.RateLimited` | Rate limiting |
| `HttpException(4xx)` | `TypedSkillError.Validation` | Client errors |
| `HttpException(503)` | `TypedSkillError.ServiceUnavailable` | Service down |
| `HttpException(5xx)` | `TypedSkillError.Execution` | Server errors |
| `JsonParseException` | `TypedSkillError.Serialization` | JSON parsing errors |
| `SerializationException` | `TypedSkillError.Serialization` | Serialization errors |
| `SQLiteException` | `TypedSkillError.Database` | Database errors |
| `SQLiteConstraintException` | `TypedSkillError.Database.constraintViolation()` | Constraint violations |
| `IllegalArgumentException` | `TypedSkillError.InvalidInput` | Invalid arguments |
| `IllegalStateException` | `TypedSkillError.Execution` | Invalid state |
| `SecurityException` | `TypedSkillError.Unauthorized` | Security violations |
| `TimeoutCancellationException` | `TypedSkillError.Timeout` | Coroutine timeouts |
| `CancellationException` | **RETHROW** | Never catch, always rethrow |
| `OutOfMemoryError` | **RETHROW** | Never catch, let crash |
| Any other `Exception` | `TypedSkillError.Execution` | Unexpected errors |

**ERROR HANDLING PATTERN:**
```kotlin
override suspend fun doExecute(
    input: Input,
    context: SkillContext,
): SkillResult<Output> {
    checkCancellation()
    
    return try {
        // Your logic here
        SkillResult.success(output)
    } catch (e: CancellationException) {
        throw e // ALWAYS rethrow
    } catch (e: IOException) {
        TypedSkillError.Network(
            message = "Network request failed: ${e.message}",
            cause = e
        ).toFailure()
    } catch (e: HttpException) {
        when (e.code()) {
            401, 403 -> TypedSkillError.Unauthorized.invalidCredentials().toFailure()
            404 -> TypedSkillError.NotFound.resource("Resource", id).toFailure()
            429 -> TypedSkillError.RateLimited.limitExceeded(100, "minute").toFailure()
            503 -> TypedSkillError.ServiceUnavailable.overloaded("API").toFailure()
            else -> TypedSkillError.Execution("HTTP ${e.code()}: ${e.message}", e).toFailure()
        }
    } catch (e: Exception) {
        TypedSkillError.Execution(
            message = "Unexpected error: ${e.message}",
            cause = e
        ).toFailure()
    }
}
```

**HELPER USAGE (SkillErrors object):**
```kotlin
// Quick error creation
SkillErrors.network<Output>("Connection failed", cause)
SkillErrors.validation<Output>("Invalid email", "email")
SkillErrors.notFound<Output>("User", userId)
SkillErrors.unauthorized<Output>("Token expired")
SkillErrors.timeout<Output>("API call", 30000)
```

CANCELLATION SUPPORT:
• Call checkCancellation() at start of doExecute()
• Call checkCancellation() inside long loops or before expensive operations
• Use withContext(NonCancellable) for cleanup if needed

REFERENCE:
- skills/templates/examples/DomainSkillGuide.kt (comprehensive guide)
- skills/templates/skill/base/Skill.kt (base interface)
- skills/templates/skill/base/Validation.kt (validation DSL)
- skills/guides/30-skill-analysis.md (quality standards)
- skills/guides/24-validation-rules.md (validation patterns)

OUTPUT: Complete skill implementation with:
1. Package declaration
2. All necessary imports
3. Input data class with @Immutable
4. Output data class with @Immutable
5. Skill class extending BaseSkill
6. Full metadata definition
7. Complete validate() method
8. Complete doExecute() method with error handling
9. Comprehensive KDoc for class and methods
10. Usage example in KDoc
```

### Example Usage

```
@gen-skill

SKILL_NAME: SearchProductsSkill

REQUIREMENTS:
• Skill purpose: Search for products by query with filters and pagination
• Input fields:
  - query: String (search term)
  - category: String? (optional category filter)
  - minPrice: Double? (optional minimum price)
  - maxPrice: Double? (optional maximum price)
  - page: Int (page number, default 1)
  - pageSize: Int (items per page, default 20)
• Output fields:
  - products: List<Product> (search results)
  - totalCount: Int (total matching products)
  - hasMore: Boolean (more pages available)
• Dependencies:
  - productRepository: ProductRepository
• Category: DOMAIN
• Error scenarios:
  - Network failure
  - Invalid query (empty)
  - Invalid price range (min > max)
  - Invalid pagination (page < 1, pageSize < 1 or > 100)
```

**AI generates:**
```kotlin
package com.example.skills.product

import androidx.compose.runtime.Immutable
import com.agentcore.skill.base.BaseSkill
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillResult
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillMetadata
import com.agentcore.skill.base.SkillCategory
import com.agentcore.skill.base.skillMetadata
import com.agentcore.skill.base.validate
import com.agentcore.skill.base.requireNotBlank
import com.agentcore.skill.base.requireInRange
import com.agentcore.skill.base.requirePositive
import com.agentcore.util.checkCancellation
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Searches for products based on query and filters.
 * 
 * Features:
 * - Text search with optional category filter
 * - Price range filtering
 * - Pagination support
 * - Network error handling
 * - Cancellation support
 * 
 * Usage:
 * ```kotlin
 * val skill = SearchProductsSkill(productRepository)
 * val result = skill.execute(
 *     SearchProductsInput(
 *         query = "laptop",
 *         category = "electronics",
 *         minPrice = 500.0,
 *         maxPrice = 2000.0,
 *         page = 1,
 *         pageSize = 20
 *     ),
 *     context
 * )
 * 
 * result.onSuccess { output ->
 *     println("Found ${output.totalCount} products")
 *     output.products.forEach { println(it.name) }
 * }
 * ```
 * 
 * @see ProductRepository
 */
class SearchProductsSkill(
    private val productRepository: ProductRepository,
) : BaseSkill<SearchProductsInput, SearchProductsOutput>() {
    
    override val metadata = skillMetadata {
        id = "product.search"
        name = "Search Products"
        description = "Search for products with filters and pagination"
        category = SkillCategory.DOMAIN
    }
    
    override suspend fun validate(input: SearchProductsInput): SkillResult<Unit> {
        return validate {
            // Query validation
            requireNotBlank(input.query, "query")
            requireInRange(input.query.length, 1..200, "query length")
            
            // Price range validation
            input.minPrice?.let { min ->
                requirePositive(min, "minPrice")
                input.maxPrice?.let { max ->
                    require(min <= max) { "minPrice must be less than or equal to maxPrice" }
                }
            }
            
            input.maxPrice?.let { max ->
                requirePositive(max, "maxPrice")
            }
            
            // Pagination validation
            requireInRange(input.page, 1..Int.MAX_VALUE, "page")
            requireInRange(input.pageSize, 1..100, "pageSize")
        }
    }
    
    override suspend fun doExecute(
        input: SearchProductsInput,
        context: SkillContext,
    ): SkillResult<SearchProductsOutput> {
        checkCancellation()
        
        return try {
            val products = productRepository.search(
                query = input.query,
                category = input.category,
                minPrice = input.minPrice,
                maxPrice = input.maxPrice,
                page = input.page,
                pageSize = input.pageSize
            )
            
            checkCancellation()
            
            SkillResult.success(
                SearchProductsOutput(
                    products = products.items,
                    totalCount = products.totalCount,
                    hasMore = (input.page * input.pageSize) < products.totalCount
                )
            )
        } catch (e: CancellationException) {
            throw e // Always rethrow cancellation
        } catch (e: IOException) {
            SkillResult.failure(
                SkillError.Network(
                    message = "Failed to search products: ${e.message}",
                    cause = e
                )
            )
        } catch (e: Exception) {
            SkillResult.failure(
                SkillError.Execution(
                    message = "Unexpected error during product search: ${e.message}",
                    cause = e
                )
            )
        }
    }
}

@Immutable
data class SearchProductsInput(
    val query: String,
    val category: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
)

@Immutable
data class SearchProductsOutput(
    val products: List<Product>,
    val totalCount: Int,
    val hasMore: Boolean,
)
```

### Common Mistakes to Avoid

| ❌ DON'T | ✅ DO |
|----------|------|
| Import Android classes | Pure Kotlin only (no Context, no Android) |
| Throw exceptions from `doExecute()` | Return `SkillResult.failure()` |
| Catch `CancellationException` silently | Always rethrow `CancellationException` |
| Skip validation for any field | Validate ALL input fields in `validate()` |
| Use generic `SkillError.Execution` | Use specific `TypedSkillError` types |
| Forget `checkCancellation()` | Call at start + in loops |
| Miss `@Immutable` on Input/Output | Always mark with `@Immutable` |
| Skip KDoc documentation | Add comprehensive KDoc with examples |

---

## @gen-pipeline
> **Mode**: SKILL_MODE | **Layer**: Domain (Pure Kotlin)

### Prompt Template

```
Generate an Agent Pipeline for [PIPELINE_NAME].

REQUIREMENTS:
• Pipeline purpose: [DESCRIPTION]
• Input type: [TYPE]
• Output type: [TYPE]
• Steps: [LIST of skills in order with transformations]
• Error handling strategy: [e.g., fail-fast, fallback, retry]
• Special needs: [e.g., parallel execution, conditional steps]

MANDATORY CONSTRAINTS:
1. Use pipeline DSL from pipeline/Pipeline.kt
2. Define clear input and output types
3. Add inputTransformer for type conversions between steps
4. Include error handling per step or globally
5. Support cancellation propagation
6. Add descriptive step names for tracing
7. Return typed pipeline with .returning<OutputType>()
8. Add comprehensive KDoc with execution flow

PIPELINE PATTERNS:

**Sequential Pipeline** (each step depends on previous):
```kotlin
val pipeline = pipeline<InputType>("PipelineName") {
    step(skill1, name = "Step1") {
        inputTransformer = { input -> Skill1Input(input) }
    }
    step(skill2, name = "Step2") {
        inputTransformer = { skill1Output -> Skill2Input(skill1Output.data) }
    }
}.returning<OutputType>()
```

**Parallel Steps** (independent steps):
```kotlin
val results = listOf(
    async { skill1.execute(input1, context) },
    async { skill2.execute(input2, context) },
).awaitAll()
```

**Conditional Steps**:
```kotlin
step(skill1, name = "Step1")
if (condition) {
    step(skill2, name = "ConditionalStep")
}
```

**Error Handling**:
```kotlin
step(skill, name = "Step") {
    onError { error ->
        // Handle error: log, fallback, or propagate
        SkillResult.failure(error)
    }
}
```

REFERENCE:
- skills/templates/examples/PipelineExamples.kt (patterns)
- skills/templates/examples/CompletePipelineExample.kt (full example)
- skills/templates/pipeline/Pipeline.kt (DSL implementation)
- ARCHITECTURE.md (pipeline architecture)

OUTPUT: Complete pipeline implementation with:
1. Package declaration
2. All necessary imports
3. Pipeline definition with DSL
4. All step configurations
5. Input transformers
6. Error handling
7. Comprehensive KDoc
8. Usage example
9. Execution example
```

### Example Usage

```
@gen-pipeline

PIPELINE_NAME: UserOnboardingPipeline

REQUIREMENTS:
• Pipeline purpose: Complete user onboarding flow (validate → create account → send email → setup profile)
• Input type: UserRegistrationData
• Output type: OnboardingResult
• Steps:
  1. ValidateUserDataSkill - Validate email, password, name
  2. CreateAccountSkill - Create user account in database
  3. SendWelcomeEmailSkill - Send welcome email
  4. SetupDefaultProfileSkill - Create default user profile
• Error handling strategy: Fail-fast (stop on first error)
• Special needs: Log each step completion
```

**AI generates:**
```kotlin
package com.example.pipeline.onboarding

import com.agentcore.pipeline.pipeline
import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillResult
import com.example.skills.user.*
import kotlinx.coroutines.flow.Flow

/**
 * Complete user onboarding pipeline.
 * 
 * Flow:
 * 1. Validate user data (email, password, name)
 * 2. Create user account
 * 3. Send welcome email
 * 4. Setup default profile
 * 
 * Error Handling:
 * - Fail-fast: Pipeline stops at first error
 * - Each step logs execution
 * - Returns detailed error information
 * 
 * Usage:
 * ```kotlin
 * val pipeline = UserOnboardingPipeline(
 *     validateSkill,
 *     createAccountSkill,
 *     sendEmailSkill,
 *     setupProfileSkill
 * )
 * 
 * val result = pipeline.execute(
 *     UserRegistrationData(
 *         email = "user@example.com",
 *         password = "SecurePass123",
 *         name = "John Doe"
 *     ),
 *     context
 * )
 * 
 * result.onSuccess { onboardingResult ->
 *     println("User ${onboardingResult.userId} onboarded successfully")
 * }
 * ```
 */
class UserOnboardingPipeline(
    private val validateUserDataSkill: ValidateUserDataSkill,
    private val createAccountSkill: CreateAccountSkill,
    private val sendWelcomeEmailSkill: SendWelcomeEmailSkill,
    private val setupDefaultProfileSkill: SetupDefaultProfileSkill,
) {
    
    private val pipeline = pipeline<UserRegistrationData>("UserOnboarding") {
        
        // Step 1: Validate user data
        step(validateUserDataSkill, name = "ValidateUserData") {
            inputTransformer = { registrationData ->
                ValidateUserDataInput(
                    email = registrationData.email,
                    password = registrationData.password,
                    name = registrationData.name
                )
            }
        }
        
        // Step 2: Create account
        step(createAccountSkill, name = "CreateAccount") {
            inputTransformer = { validationOutput ->
                CreateAccountInput(
                    email = validationOutput.validatedEmail,
                    passwordHash = validationOutput.passwordHash,
                    name = validationOutput.sanitizedName
                )
            }
        }
        
        // Step 3: Send welcome email
        step(sendWelcomeEmailSkill, name = "SendWelcomeEmail") {
            inputTransformer = { accountOutput ->
                SendWelcomeEmailInput(
                    userId = accountOutput.userId,
                    email = accountOutput.email,
                    name = accountOutput.name
                )
            }
        }
        
        // Step 4: Setup default profile
        step(setupDefaultProfileSkill, name = "SetupDefaultProfile") {
            inputTransformer = { emailOutput ->
                SetupDefaultProfileInput(
                    userId = emailOutput.userId
                )
            }
        }
        
    }.returning<OnboardingResult>()
    
    /**
     * Execute the onboarding pipeline.
     * 
     * @param data User registration data
     * @param context Skill execution context
     * @return OnboardingResult with user ID and profile
     */
    suspend fun execute(
        data: UserRegistrationData,
        context: SkillContext,
    ): SkillResult<OnboardingResult> {
        return pipeline.execute(data, context)
    }
    
    /**
     * Execute with progress tracking.
     * 
     * @param data User registration data
     * @param context Skill execution context
     * @return Flow of step results
     */
    fun executeWithProgress(
        data: UserRegistrationData,
        context: SkillContext,
    ): Flow<StepResult> {
        return pipeline.executeWithProgress(data, context)
    }
}

data class UserRegistrationData(
    val email: String,
    val password: String,
    val name: String,
)

data class OnboardingResult(
    val userId: String,
    val email: String,
    val profileId: String,
)
```

---

## @gen-skill-test
> **Mode**: SKILL_MODE | **Layer**: Testing

### Prompt Template

```
Generate comprehensive tests for Agent Skill [SKILL_NAME].

TARGET: Skills extending BaseSkill<InputType, OutputType>

TEST SCENARIOS TO COVER:

1. **Happy Path** (20% of tests)
   - Valid input with all fields → Expected output
   - Valid input with optional fields → Expected output
   - Edge cases within valid range (min, max values)

2. **Validation Errors** (30% of tests)
   - EACH invalid field independently → Specific validation error
   - Multiple validation failures → First error returned
   - Boundary violations (length, range, format)
   - Special characters and Unicode in strings

3. **Execution Errors** (30% of tests)
   - Network failure → SkillError.Network
   - Timeout → SkillError.Timeout
   - Dependency failure → Appropriate error type
   - Invalid state → SkillError.InvalidState
   - Unexpected exceptions → SkillError.Execution

4. **Cancellation** (10% of tests)
   - Cancellation before execution → CancellationException thrown
   - Cancellation during execution → CancellationException thrown
   - Cancellation not swallowed in catch blocks

5. **Edge Cases** (10% of tests)
   - Empty collections where allowed
   - Null optional fields
   - Boundary values (Int.MAX_VALUE, Long.MIN_VALUE)
   - Very long strings (performance)
   - Concurrent execution (if applicable)

MANDATORY PATTERNS:
- Use **JUnit5** (@Test, @BeforeEach, @AfterEach)
- Use **MockK** for mocking dependencies (mockk<T>(), coEvery, coVerify)
- Use **runTest** for coroutine testing (sets up TestDispatcher)
- Use **Turbine** for Flow testing if skill emits flows
- Verify **SkillResult.Success** or **SkillResult.Failure** (NEVER catch exceptions)
- Test **cancellation propagation** explicitly
- Use **descriptive test names**: `when[Condition]_then[Expected]` or `given[Setup]_when[Action]_then[Result]`
- One primary assertion per test (can have multiple supporting assertions)
- Mock only external dependencies (network, database, etc.)
- Use **Fake** implementations for complex dependencies when mocking is awkward

TEST CLASS STRUCTURE:
```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SkillNameTest {
    
    // Dependencies (mocked)
    private lateinit var dependency: Dependency
    
    // System under test
    private lateinit var skill: SkillName
    
    // Test context
    private lateinit var context: SkillContext
    
    @BeforeEach
    fun setup() {
        dependency = mockk()
        skill = SkillName(dependency)
        context = SkillContext(/* test context */)
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    // Tests grouped by scenario
    @Nested
    inner class HappyPath { }
    
    @Nested
    inner class ValidationErrors { }
    
    @Nested
    inner class ExecutionErrors { }
    
    @Nested
    inner class CancellationHandling { }
    
    @Nested
    inner class EdgeCases { }
}
```

ASSERTION PATTERNS:
```kotlin
// Success assertions
val result = skill.execute(input, context)
assertTrue(result.isSuccess)
val output = result.getOrThrow()
assertEquals(expectedValue, output.field)

// Failure assertions
val result = skill.execute(input, context)
assertTrue(result.isFailure)
val error = result.getErrorOrNull()
assertEquals(SkillError.Validation::class, error::class)
assertTrue(error.message.contains("expected text"))

// Cancellation assertions
assertThrows<CancellationException> {
    runTest {
        skill.execute(input, context)
    }
}
```

REFERENCE:
- skills/guides/11-testing.md (testing guidelines)
- skills/guides/29-testing-automation.md (testing patterns)
- skills/guides/30-skill-analysis.md (quality criteria)

OUTPUT: Complete test file with:
1. Package declaration
2. All necessary imports (JUnit5, MockK, Turbine, etc.)
3. Test class with proper annotations
4. Setup and teardown methods
5. Nested test classes for each scenario group
6. All test methods with descriptive names
7. Comprehensive assertions
8. Comments explaining complex test setups
9. Test coverage: 100% of validate() and doExecute() paths
```

### Example Usage

```
@gen-skill-test

SKILL_NAME: SearchProductsSkill

SKILL CODE:
```kotlin
class SearchProductsSkill(
    private val productRepository: ProductRepository,
) : BaseSkill<SearchProductsInput, SearchProductsOutput>() {
    override suspend fun validate(input: SearchProductsInput): SkillResult<Unit> {
        return validate {
            requireNotBlank(input.query, "query")
            requireInRange(input.query.length, 1..200, "query length")
            input.minPrice?.let { min ->
                requirePositive(min, "minPrice")
                input.maxPrice?.let { max ->
                    require(min <= max) { "minPrice must be <= maxPrice" }
                }
            }
            requireInRange(input.page, 1..Int.MAX_VALUE, "page")
            requireInRange(input.pageSize, 1..100, "pageSize")
        }
    }
    
    override suspend fun doExecute(
        input: SearchProductsInput,
        context: SkillContext,
    ): SkillResult<SearchProductsOutput> {
        // ... implementation
    }
}
```
```

**AI generates:**
```kotlin
package com.example.skills.product

import com.agentcore.skill.base.SkillContext
import com.agentcore.skill.base.SkillError
import com.agentcore.skill.base.SkillResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.IOException
import kotlinx.coroutines.CancellationException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchProductsSkillTest {
    
    private lateinit var productRepository: ProductRepository
    private lateinit var skill: SearchProductsSkill
    private lateinit var context: SkillContext
    
    @BeforeEach
    fun setup() {
        productRepository = mockk()
        skill = SearchProductsSkill(productRepository)
        context = mockk(relaxed = true)
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    @Nested
    inner class HappyPath {
        
        @Test
        fun `when valid input with all fields then returns success`() = runTest {
            // Given
            val input = SearchProductsInput(
                query = "laptop",
                category = "electronics",
                minPrice = 500.0,
                maxPrice = 2000.0,
                page = 1,
                pageSize = 20
            )
            val expectedProducts = listOf(
                Product("1", "Laptop A", 999.0),
                Product("2", "Laptop B", 1299.0)
            )
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } returns SearchResult(expectedProducts, 2)
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
            val output = result.getOrThrow()
            assertEquals(2, output.products.size)
            assertEquals(2, output.totalCount)
            assertFalse(output.hasMore)
        }
        
        @Test
        fun `when valid input with optional fields null then returns success`() = runTest {
            // Given
            val input = SearchProductsInput(
                query = "laptop",
                category = null,
                minPrice = null,
                maxPrice = null,
                page = 1,
                pageSize = 20
            )
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } returns SearchResult(emptyList(), 0)
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
        }
    }
    
    @Nested
    inner class ValidationErrors {
        
        @Test
        fun `when query is blank then returns validation error`() = runTest {
            // Given
            val input = SearchProductsInput(query = "", page = 1, pageSize = 20)
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error?.message?.contains("query") == true)
        }
        
        @Test
        fun `when query exceeds max length then returns validation error`() = runTest {
            // Given
            val input = SearchProductsInput(
                query = "a".repeat(201),
                page = 1,
                pageSize = 20
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error?.message?.contains("query length") == true)
        }
        
        @Test
        fun `when minPrice is negative then returns validation error`() = runTest {
            // Given
            val input = SearchProductsInput(
                query = "laptop",
                minPrice = -10.0,
                page = 1,
                pageSize = 20
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error?.message?.contains("minPrice") == true)
        }
        
        @Test
        fun `when minPrice greater than maxPrice then returns validation error`() = runTest {
            // Given
            val input = SearchProductsInput(
                query = "laptop",
                minPrice = 2000.0,
                maxPrice = 500.0,
                page = 1,
                pageSize = 20
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error?.message?.contains("minPrice") == true)
        }
        
        @Test
        fun `when page is zero then returns validation error`() = runTest {
            // Given
            val input = SearchProductsInput(query = "laptop", page = 0, pageSize = 20)
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            assertTrue(result.getErrorOrNull() is SkillError.Validation)
        }
        
        @Test
        fun `when pageSize exceeds max then returns validation error`() = runTest {
            // Given
            val input = SearchProductsInput(query = "laptop", page = 1, pageSize = 101)
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error?.message?.contains("pageSize") == true)
        }
    }
    
    @Nested
    inner class ExecutionErrors {
        
        @Test
        fun `when repository throws IOException then returns network error`() = runTest {
            // Given
            val input = SearchProductsInput(query = "laptop", page = 1, pageSize = 20)
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } throws IOException("Connection timeout")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Network)
            assertTrue(error?.message?.contains("Connection timeout") == true)
        }
        
        @Test
        fun `when repository throws unexpected exception then returns execution error`() = runTest {
            // Given
            val input = SearchProductsInput(query = "laptop", page = 1, pageSize = 20)
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } throws RuntimeException("Unexpected error")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Execution)
        }
    }
    
    @Nested
    inner class CancellationHandling {
        
        @Test
        fun `when cancelled during execution then throws CancellationException`() = runTest {
            // Given
            val input = SearchProductsInput(query = "laptop", page = 1, pageSize = 20)
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } throws CancellationException("Test cancellation")
            
            // When & Then
            assertThrows<CancellationException> {
                runTest {
                    skill.execute(input, context)
                }
            }
        }
    }
    
    @Nested
    inner class EdgeCases {
        
        @Test
        fun `when query at max length then returns success`() = runTest {
            // Given
            val input = SearchProductsInput(
                query = "a".repeat(200),
                page = 1,
                pageSize = 20
            )
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } returns SearchResult(emptyList(), 0)
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
        }
        
        @Test
        fun `when hasMore calculation with exact page boundary then returns false`() = runTest {
            // Given
            val input = SearchProductsInput(query = "laptop", page = 2, pageSize = 10)
            coEvery { 
                productRepository.search(any(), any(), any(), any(), any(), any()) 
            } returns SearchResult(emptyList(), 20) // Exactly 2 pages
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
            val output = result.getOrThrow()
            assertFalse(output.hasMore) // No more pages
        }
    }
}
```

---

## @analyze-skill
> **Mode**: SKILL_MODE | **Type**: Analysis

### Prompt Template

```
Analyze Agent Skill quality for [SkillName].

TARGET: Skills extending BaseSkill<I, O> from Agent Skills Framework

CHECK ALL QUALITY DIMENSIONS:

1. **Validation Coverage** (25 points):
   - List all input fields in @Immutable data class
   - Check if each field has validation in validate() method
   - Identify missing validations
   - Score: (Validated Fields / Total Fields) × 25
   
   Common gaps:
   - String fields without requireNotBlank()
   - Email fields without requireValidEmail()
   - Numeric fields without requireInRange()
   - Collections without requireNotEmpty()

2. **Error Handling** (25 points):
   - Uses SkillResult<T> return type? (+5)
   - Has try-catch blocks? (+5)
   - Rethrows CancellationException? (+5)
   - Maps specific error types (Network, Validation, etc.)? (+5)
   - Provides descriptive error messages? (+5)
   
   Check for:
   ❌ Throws exceptions instead of SkillResult.failure()
   ❌ Catches CancellationException in generic catch block
   ❌ Generic error messages like "Error occurred"

3. **Cancellation Support** (20 points):
   - Calls checkCancellation() at start? (+6)
   - Calls checkCancellation() in long loops? (+6)
   - Rethrows CancellationException? (+6)
   - Cleanup with withContext(NonCancellable)? (+2)
   
   Check for:
   ❌ No checkCancellation() calls
   ❌ CancellationException swallowed in catch block

4. **Documentation** (15 points):
   - Class-level KDoc with description? (+3)
   - @param documentation for all parameters? (+2)
   - @return documentation? (+2)
   - Usage example in KDoc? (+4)
   - Error scenarios documented? (+2)
   - @see references to related classes/guides? (+2)

5. **Performance** (10 points):
   - No blocking operations (Thread.sleep, blocking IO)? (+3)
   - Uses appropriate dispatcher (Dispatchers.IO/Default)? (+3)
   - Efficient algorithm (not O(n²) when O(n) possible)? (+3)
   - Minimal object allocations in loops? (+1)

6. **Testability** (5 points):
   - Clear input/output contract? (+2)
   - Dependencies mockable (injected, not hardcoded)? (+2)
   - Deterministic (no random, no system time)? (+1)

CALCULATE TOTAL SCORE:
Total = Validation + Error + Cancellation + Docs + Performance + Testability

OUTPUT FORMAT:
## Skill Quality Report: [SkillName]

### Overall Score: [0-100]/100
Quality Tier: [Production 90-100 | Good 75-89 | Acceptable 60-74 | Needs Work 40-59 | Critical 0-39]

### Dimension Scores:
- ✅ Validation Coverage: [score]/25
- ✅ Error Handling: [score]/25
- ✅ Cancellation Support: [score]/20
- ✅ Documentation: [score]/15
- ✅ Performance: [score]/10
- ✅ Testability: [score]/5

### ✅ Strengths
- [List good practices found with line numbers]

### ❌ Issues Found (Priority Order)
1. **[Dimension]**: [Issue description]
   - Severity: [CRITICAL/HIGH/MEDIUM/LOW]
   - Location: [Line number or method name]
   - Current Code:
   ```kotlin
   [Problematic code snippet]
   ```
   - Fix:
   ```kotlin
   [Corrected code]
   ```

### 📋 Missing Validations
- [Field name] ([Type]): [Suggested validation function]
  Example: `requireValidEmail(input.email, "email")`

### 🔧 Recommended Improvements (Step-by-Step)
1. [Highest priority fix - exact code change]
2. [Second priority - exact code change]
3. [Third priority - exact code change]

### Priority Actions
- [ ] CRITICAL: [Fix that blocks production]
- [ ] HIGH: [Fix that should be done before deployment]
- [ ] MEDIUM: [Fix for better quality]

REFERENCE: guides/30-skill-analysis.md

OUTPUT: Comprehensive quality report with actionable fixes
```

### Example Usage

```
@analyze-skill

Skill: UpdateUserSkill
File: skills/domain/UpdateUserSkill.kt

[Paste or reference skill code]
```

---

## @optimize-skill

### Prompt Template

```
Optimize Agent Skill performance for [SkillName].

ANALYZE PERFORMANCE:

1. **Algorithm Efficiency**:
   - Identify time complexity (O(1), O(n), O(n log n), O(n²), etc.)
   - Find nested loops (potential O(n²) or worse)
   - Check for unnecessary iterations
   - Suggest efficient alternatives (use Map for lookups, etc.)

2. **Memory Usage**:
   - Find unnecessary object allocations (especially in loops)
   - Identify String concatenation in loops (use StringBuilder)
   - Check for large collection copies (use sequences for lazy eval)
   - Suggest memory-efficient patterns

3. **Concurrency Optimization**:
   - Check if using correct dispatcher:
     - Dispatchers.IO for I/O operations (network, file, database)
     - Dispatchers.Default for CPU-intensive work
     - Dispatchers.Main for UI updates (not in skills)
   - Identify parallelization opportunities (can independent ops run concurrently?)
   - Check for unnecessary sequential execution

4. **Caching Opportunities**:
   - Find repeated computations (calculate once, reuse)
   - Identify expensive operations that could be cached
   - Suggest caching strategies (in-memory, with expiration)

5. **Data Structure Optimization**:
   - Check if using optimal data structures:
     - Use ArrayList when size known (avoid resizing)
     - Use HashMap/Set for O(1) lookups (not List.find())
     - Use Sequence for large collections with multiple operations

PERFORMANCE PATTERNS TO CHECK:

❌ BAD: Nested loops with linear search
```kotlin
items.map { item ->
    allItems.find { it.id == item.relatedId } // O(n²)
}
```

✅ GOOD: Build index once
```kotlin
val itemsById = allItems.associateBy { it.id } // O(n)
items.mapNotNull { itemsById[it.relatedId] } // O(n)
```

❌ BAD: String concatenation in loop
```kotlin
var result = ""
items.forEach { result += it.name + ", " } // Creates new String each iteration
```

✅ GOOD: StringBuilder
```kotlin
val result = items.joinToString(", ") { it.name }
// or: buildString { items.forEach { append(it.name).append(", ") } }
```

OUTPUT FORMAT:
## Performance Optimization Report: [SkillName]

### Current Performance Profile:
- Time Complexity: [O notation]
- Space Complexity: [O notation]
- Blocking Operations: [count found]
- Unnecessary Allocations: [count found]

### 🚀 Optimization Opportunities (Priority Order)

#### 1. **[Category]** (Impact: [HIGH/MEDIUM/LOW])
**Issue**: [Description of inefficiency]

**Current Code** (Line [X]):
```kotlin
[Current inefficient code]
```

**Optimized Code**:
```kotlin
[Improved efficient code]
```

**Benefit**: 
- Time: [Expected improvement, e.g., "O(n²) → O(n)"]
- Memory: [Expected reduction, e.g., "50% fewer allocations"]
- Measured: [If measurable, e.g., "~2x faster for 1000 items"]

### Performance Improvements Summary
- Expected time reduction: [percentage or "O(n²) → O(n)"]
- Expected memory reduction: [percentage]
- Reduced allocations: [count]
- Improved concurrency: [parallelized operations]

### Before/After Benchmark (Estimated)
| Metric | Before | After | Improvement |
|--------|--------|-------|-------------|
| Time (1K items) | [ms] | [ms] | [%] |
| Allocations | [count] | [count] | [%] |
| Memory | [KB] | [KB] | [%] |

REFERENCE: guides/30-skill-analysis.md, guides/25-performance-benchmarks.md

OUTPUT: Detailed optimization plan with code examples
```

---

## @add-validation

### Prompt Template

```
Add comprehensive validation to Agent Skill [SkillName].

ANALYZE INPUT CLASS:
```kotlin
@Immutable
data class [InputClassName](
    [Paste all fields with types]
)
```

GENERATE VALIDATION:

For EACH field, determine appropriate validation:

**String fields**:
- General strings: `requireNotBlank(input.field, "field")`
- Email: `requireValidEmail(input.email, "email")`
- URL: `requireValidUrl(input.url, "url")`
- Phone: `requireValidPhone(input.phone, "phone")`
- Password: `requireValidPassword(input.password, "password", minLength=8, requireUppercase=true, requireDigit=true)`
- Length constraints: `requireInRange(input.field.length, 2..100, "field length")`

**Numeric fields**:
- Positive numbers: `requirePositive(input.count, "count")`
- Range validation: `requireInRange(input.age, 13..150, "age")`
- Confidence (0.0-1.0): `requireValidConfidence(input.score, "score")`
- Percentage (0-100): `requireValidPercentage(input.percent, "percent")`
- Money: `requireValidAmount(input.price, "price")`

**Collections**:
- Not empty: `requireNotEmpty(input.items, "items")`
- Size constraints: `requireSize(input.tags, 1..10, "tags")`

**Dates**:
- Date range: `requireDateInRange(input.birthdate, minDate..maxDate, "birthdate")`

**Custom patterns**:
- Regex: `requireMatches(input.username, usernameRegex, "username")`
- Custom business rule: `require(condition) { "Error message" }`

OUTPUT:
Complete validate() method implementation with ALL fields validated.

```kotlin
override suspend fun validate(input: [InputClassName]): SkillResult<Unit> {
    return validate {
        // String validations
        [Generated validations for String fields]
        
        // Numeric validations
        [Generated validations for numeric fields]
        
        // Collection validations
        [Generated validations for collections]
        
        // Custom business rules
        [Any additional business logic validations]
    }
}
```

ALSO PROVIDE:
- List of all validations added
- Rationale for each validation choice
- Any assumptions made about constraints

REFERENCE: guides/24-validation-rules.md, skills/templates/skill/base/Validation.kt

OUTPUT: Complete validation method ready to copy-paste
```

### Example Usage

```
@add-validation

Skill: UpdateUserProfileSkill

Input:
```kotlin
@Immutable
data class UpdateUserProfileInput(
    val userId: String,
    val name: String,
    val email: String,
    val bio: String,
    val age: Int,
    val websiteUrl: String?,
)
```
```

**AI generates:**
```kotlin
override suspend fun validate(input: UpdateUserProfileInput): SkillResult<Unit> {
    return validate {
        // User ID validation
        requireNotBlank(input.userId, "userId")
        
        // Name validation
        requireNotBlank(input.name, "name")
        requireInRange(input.name.length, 2..50, "name length")
        
        // Email validation
        requireValidEmail(input.email, "email")
        
        // Bio validation
        requireInRange(input.bio.length, 0..500, "bio length")
        
        // Age validation
        requireInRange(input.age, 13..150, "age")
        
        // Website URL validation (optional field)
        input.websiteUrl?.let { url ->
            requireValidUrl(url, "websiteUrl")
        }
    }
}
```

---

## @review
> **Mode**: ANALYSIS | **Target**: Single file

### Prompt Template

```
Review [FILE_PATH] for compliance with Android Agent Skills rules.

SEVERITY LEVELS:
• 🔴 CRITICAL (C1-C5): Must fix before merge
• 🟡 WARNING (W1-W8): Should fix for quality
• 🔵 INFO (I1-I6): Nice to have improvements

RULES TO CHECK:
Critical (🔴):
• C1: CancellationException rethrown
• C2: State is private mutable, public immutable
• C3: Events use Channel (not SharedFlow)
• C4: @Immutable on all state classes
• C5: ViewModel → UseCase → Repository

Warning (🟡):
• W1: No Android imports in Domain
• W2: Single Responsibility (UseCase)
• W3: collectAsStateWithLifecycle()
• W4: Skills return SkillResult<T>
• W5: Validation DSL used
• W6: modifier parameter is last
• W7: val over var
• W8: Error mapping follows pattern

Info (🔵):
• I1: Route/Screen separation
• I2: sealed interface for states
• I3: data class for models
• I4: checkCancellation() in loops
• I5: Type-safe navigation
• I6: Inject dispatchers

OUTPUT FORMAT:
## 🔍 Code Review: [filename]

### 🔴 Critical Violations (MUST FIX)
1. **[C1] CancellationException not rethrown** - Line X
   - Issue: [description]
   - Fix: [code snippet]
   - Ref: [guide link]

### 🟡 Warnings (SHOULD FIX)
[same format]

### 🔵 Suggestions (NICE TO HAVE)
[same format]

### ✅ Compliance (What's Good)
- ✅ [list good practices found]

### 📊 Score: X/10

REFERENCE: skills/guides/21-code-review.md
```

---

## @review-changes
> **Mode**: ANALYSIS | **Target**: Git changes (staged/unstaged)

### Prompt Template

```
Review staged and unstaged git changes for rule violations.

WORKFLOW:
1. Run: git diff --cached (staged)
2. Run: git diff (unstaged)
3. Analyze ONLY changed lines
4. Group violations by file and severity

FOCUS ON:
• What changed? (additions/modifications)
• Which rules apply to change type?
• Critical violations that break app?

CHANGE TYPE DETECTION:
| Pattern | Rules to Check |
|---------|----------------|
| New ViewModel.kt | C2, C3, C5 |
| New *Screen.kt | C4, W3, W6 |
| try-catch block | C1 |
| New Skill.kt | W4, W5 |
| repository.* call | C5 |

OUTPUT FORMAT:
## 🔄 Changes Review

### Files Changed
- [file] ([+X, -Y]) - [status]

### 🔴 Critical Violations in Changes
| File | Line | Rule | Issue | Fix |
|------|------|------|-------|-----|
[...]

### 📊 Summary
- 🔴 Critical: X (MUST FIX)
- 🟡 Warning: Y (SHOULD FIX)
- 🔵 Info: Z suggestions

### Recommendation
[ ] ✅ Ready to commit
[ ] ❌ Needs fixes

REFERENCE: skills/guides/21-code-review.md
```

---

## @review-commit
> **Mode**: ANALYSIS | **Target**: Specific commit

### Prompt Template

```
Review commit [COMMIT_HASH] for rule violations.

WORKFLOW:
1. Run: git show [HASH] --no-color
2. Parse commit metadata (author, date, message)
3. Extract changed files and diffs
4. Apply severity rules to changes
5. Generate detailed report

COMMIT INFO TO EXTRACT:
• Hash (short and full)
• Author name and email
• Commit date
• Commit message
• Files changed (with stats)
• Line-by-line diffs

ANALYSIS:
• Check each changed line against rules
• Group violations by severity
• Calculate commit quality score
• Provide fix recommendations

OUTPUT FORMAT:
## 🔍 Commit Review: [hash] - [message]

**Author**: [name]  
**Date**: [date]  
**Files**: X files changed

### 📁 Files in Commit
| File | Changes | Status |
|------|---------|--------|
[...]

### 🔴 Critical Violations
[detailed list with file:line]

### 🟡 Warnings
[detailed list]

### 📊 Commit Quality Score: X/10
- 🔴 Critical: X
- 🟡 Warning: Y
- 🔵 Info: Z

### Recommendation
[ ] ✅ Clean commit
[ ] ⚠️ Can merge with warnings
[ ] ❌ Must fix critical issues

### 🔧 Auto-Fix Suggestions
```kotlin
[code snippets]
```

REFERENCE: skills/guides/21-code-review.md
```

---

## @review-report
> **Mode**: ANALYSIS | **Output**: Markdown file

### Prompt Template

```
Generate detailed markdown review report for [SOURCE].

SOURCES:
• Single file: --file [path]
• Changes: --changes
• Commit: --commit [hash]
• PR: --pr [number]
• Branch: --branch [name]

OUTPUT FILE:
• Path: --output [file.md]
• Default: reviews/review-[timestamp].md

REPORT STRUCTURE:
1. Header (Generated, Reviewer, Source, Status)
2. Executive Summary (counts, score, recommendation)
3. Detailed Findings (by severity, with references)
4. Files Reviewed (table with scores)
5. Auto-Fix Snippets (actionable code)
6. Next Steps (prioritized tasks)
7. Footer (agent info, version, rules link)

SCORING:
• Per-file: violations → score (0-10)
• Overall: average + critical penalty
• Status: PASSED (8+) | WARNINGS (5-7) | FAILED (<5)

EXAMPLE COMMAND:
@review-report --changes --output reviews/pre-commit.md
@review-report --commit abc123 --output reviews/commit-abc123.md
@review-report --pr 456 --output reviews/pr-456.md

TEMPLATE:
# Code Review Report

**Generated**: [timestamp]  
**Reviewer**: AI Agent (21-code-review.md)  
**Source**: [source]  
**Status**: [PASSED|WARNINGS|FAILED]

## Executive Summary
- Files: X
- 🔴 Critical: X
- 🟡 Warning: X
- 🔵 Info: X
- Score: X/10

## Detailed Findings
[grouped by severity and rule]

## Files Reviewed
| File | Score | 🔴 | 🟡 | 🔵 | Status |
[...]

## Auto-Fix Code Snippets
[actionable fixes]

## Next Steps
1. Fix critical issues
2. Address warnings
3. Consider suggestions
4. Re-run review

REFERENCE: skills/guides/21-code-review.md
```

---

## 🎯 Skill Framework Best Practices

### ✅ DO (Agent Skills):
- **Validate 100%** of input fields (aim for 25/25 score)
- **Use validation DSL** from `Validation.kt` (never manual checks)
- **Return SkillResult**, never throw exceptions
- **Rethrow CancellationException** explicitly
- **Call checkCancellation()** at start and in loops
- **Write KDoc** with usage examples
- **Map specific errors** (Network, Validation, NotFound, etc.)
- **Inject dependencies** (don't create instances in skill)
- **Mark inputs @Immutable** for stability
- **Test comprehensively** (all scenarios)

### ❌ DON'T (Agent Skills):
- **Don't skip validation** (even for "trusted" inputs)
- **Don't throw exceptions** (use SkillResult.failure())
- **Don't catch CancellationException** in generic catch
- **Don't use blocking calls** (no Thread.sleep, blocking IO)
- **Don't hardcode dependencies** (use constructor injection)
- **Don't skip documentation** (KDoc is mandatory)
- **Don't deploy** skills with quality score < 75/100
- **Don't ignore @analyze-skill** warnings

### Quality Standards

| Score | Action |
|-------|--------|
| 90-100 | ✅ Production-ready, deploy |
| 75-89 | ✅ Good, deploy with review |
| 60-74 | ⚠️ Needs improvement before deploy |
| 40-59 | ❌ Do not deploy, fix issues |
| 0-39 | 🚫 Critical issues, consider rewrite |

---

## � Negative Examples (What NOT to Generate)

> **Purpose**: Explicit anti-patterns to help AI agents avoid common mistakes.
> AI should NEVER generate code matching these patterns.

### ❌ ViewModel Anti-Patterns

```kotlin
// ❌ WRONG: GlobalScope (will leak memory, not cancelled with ViewModel)
class BadViewModel : ViewModel() {
    fun loadData() {
        GlobalScope.launch {  // ❌ NEVER use GlobalScope
            repository.getData()
        }
    }
}

// ✅ CORRECT: Use viewModelScope
class GoodViewModel : ViewModel() {
    fun loadData() {
        viewModelScope.launch {  // ✅ Cancelled when ViewModel cleared
            repository.getData()
        }
    }
}
```

```kotlin
// ❌ WRONG: Repository injected directly
@HiltViewModel
class BadViewModel @Inject constructor(
    private val userRepository: UserRepository,  // ❌ NEVER inject Repository
) : ViewModel()

// ✅ CORRECT: UseCase injected
@HiltViewModel
class GoodViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,  // ✅ Inject UseCase
) : ViewModel()
```

```kotlin
// ❌ WRONG: SharedFlow for events (events can be lost)
class BadViewModel : ViewModel() {
    private val _events = MutableSharedFlow<Event>()  // ❌ Events may be lost
    val events = _events.asSharedFlow()
}

// ✅ CORRECT: Channel for events (guaranteed delivery)
class GoodViewModel : ViewModel() {
    private val _events = Channel<Event>()  // ✅ Buffer ensures delivery
    val events = _events.receiveAsFlow()
}
```

```kotlin
// ❌ WRONG: Exposing MutableStateFlow
class BadViewModel : ViewModel() {
    val state = MutableStateFlow(UiState())  // ❌ UI can modify state!
}

// ✅ CORRECT: Private mutable, public read-only
class GoodViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()  // ✅ Read-only
}
```

### ❌ Coroutine Anti-Patterns

```kotlin
// ❌ WRONG: Swallowing CancellationException (breaks structured concurrency)
suspend fun badFunction() {
    try {
        doWork()
    } catch (e: Exception) {  // ❌ Catches CancellationException!
        log.error("Failed", e)
    }
}

// ✅ CORRECT: Rethrow CancellationException
suspend fun goodFunction() {
    try {
        doWork()
    } catch (e: CancellationException) {
        throw e  // ✅ ALWAYS rethrow
    } catch (e: Exception) {
        log.error("Failed", e)
    }
}
```

```kotlin
// ❌ WRONG: Hardcoded Dispatchers (not testable)
class BadRepository {
    suspend fun getData(): Data = withContext(Dispatchers.IO) {  // ❌ Hardcoded
        api.fetch()
    }
}

// ✅ CORRECT: Injected Dispatchers
class GoodRepository(
    private val ioDispatcher: CoroutineDispatcher,  // ✅ Inject
) {
    suspend fun getData(): Data = withContext(ioDispatcher) {
        api.fetch()
    }
}
```

### ❌ Compose Anti-Patterns

```kotlin
// ❌ WRONG: Missing @Immutable (causes unnecessary recomposition)
data class BadUiState(
    val items: List<Item> = emptyList(),  // ❌ Not stable
)

// ✅ CORRECT: @Immutable annotation
@Immutable
data class GoodUiState(
    val items: List<Item> = emptyList(),  // ✅ Compiler trusts immutability
)
```

```kotlin
// ❌ WRONG: Callbacks before Modifier
@Composable
fun BadScreen(
    state: UiState,
    onClick: () -> Unit,           // ❌ Callback before modifier
    modifier: Modifier = Modifier,
)

// ✅ CORRECT: Required → State → Modifier → Callbacks
@Composable
fun GoodScreen(
    state: UiState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,           // ✅ Callback after modifier
)
```

```kotlin
// ❌ WRONG: Using collectAsState (not lifecycle-aware)
@Composable
fun BadScreen(viewModel: MyViewModel) {
    val state by viewModel.state.collectAsState()  // ❌ Keeps collecting when backgrounded
}

// ✅ CORRECT: Using collectAsStateWithLifecycle
@Composable  
fun GoodScreen(viewModel: MyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()  // ✅ Stops when backgrounded
}
```

```kotlin
// ❌ WRONG: ViewModel in Screen (breaks testability)
@Composable
fun BadScreen() {
    val viewModel: MyViewModel = hiltViewModel()  // ❌ Hard to test
    // ...
}

// ✅ CORRECT: ViewModel in Route, Screen is stateless
@Composable
fun MyRoute(viewModel: MyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MyScreen(state = state, onAction = viewModel::onAction)  // ✅ Testable
}

@Composable
fun MyScreen(state: UiState, modifier: Modifier = Modifier, onAction: (Action) -> Unit) {
    // Pure UI, no ViewModel
}
```

### ❌ Skill Anti-Patterns

```kotlin
// ❌ WRONG: Throwing exceptions
class BadSkill : BaseSkill<Input, Output>() {
    override suspend fun doExecute(input: Input, context: SkillContext): SkillResult<Output> {
        if (input.query.isBlank()) {
            throw IllegalArgumentException("Query cannot be blank")  // ❌ NEVER throw
        }
        return SkillResult.success(output)
    }
}

// ✅ CORRECT: Return SkillResult.failure()
class GoodSkill : BaseSkill<Input, Output>() {
    override suspend fun doExecute(input: Input, context: SkillContext): SkillResult<Output> {
        if (input.query.isBlank()) {
            return SkillResult.failure(
                SkillError.Validation("Query cannot be blank")  // ✅ Return failure
            )
        }
        return SkillResult.success(output)
    }
}
```

```kotlin
// ❌ WRONG: Manual validation (error-prone, inconsistent)
class BadSkill : BaseSkill<Input, Output>() {
    override suspend fun validate(input: Input): SkillResult<Unit> {
        if (input.query.isBlank()) {  // ❌ Manual checks
            return SkillResult.failure(SkillError.Validation("Query required"))
        }
        return SkillResult.success(Unit)
    }
}

// ✅ CORRECT: Use validation DSL
class GoodSkill : BaseSkill<Input, Output>() {
    override suspend fun validate(input: Input): SkillResult<Unit> {
        return validate {  // ✅ DSL with consistent error messages
            requireNotBlank(input.query, "query")
            requireInRange(input.limit, 1..100, "limit")
        }
    }
}
```

```kotlin
// ❌ WRONG: Missing @Immutable on Input/Output
data class BadInput(val query: String)  // ❌ Not marked

// ✅ CORRECT: Always @Immutable
@Immutable
data class GoodInput(val query: String)  // ✅ Marked
```

### ❌ Testing Anti-Patterns

```kotlin
// ❌ WRONG: Using JUnit4 annotations
import org.junit.Test  // ❌ JUnit4
import org.junit.Before  // ❌ JUnit4

class BadTest {
    @Before  // ❌ JUnit4
    fun setup() {}
    
    @Test  // ❌ JUnit4
    fun test() {}
}

// ✅ CORRECT: Use JUnit5
import org.junit.jupiter.api.Test  // ✅ JUnit5
import org.junit.jupiter.api.BeforeEach  // ✅ JUnit5

class GoodTest {
    @BeforeEach  // ✅ JUnit5
    fun setup() {}
    
    @Test  // ✅ JUnit5
    fun `when condition then expected`() {}  // ✅ Descriptive name
}
```

```kotlin
// ❌ WRONG: Not using runTest for coroutines
@Test
fun badTest() = runBlocking {  // ❌ Doesn't control virtual time
    viewModel.loadData()
    delay(1000)  // Actually waits 1 second!
}

// ✅ CORRECT: Use runTest
@Test
fun goodTest() = runTest {  // ✅ Virtual time, instant execution
    viewModel.loadData()
    advanceUntilIdle()  // ✅ Advances virtual time
}
```

---

## 🔗 Related Documentation

- **Main Guide**: [28-code-generation.md](guides/28-code-generation.md)
- **Testing Automation**: [29-testing-automation.md](guides/29-testing-automation.md)
- **Skill Analysis**: [30-skill-analysis.md](guides/30-skill-analysis.md)
- **Architecture**: [01-architecture.md](guides/01-architecture.md)
- **State Management**: [07-state-management.md](guides/07-state-management.md)
- **Compose**: [05-jetpack-compose.md](guides/05-jetpack-compose.md)
- **Testing**: [11-testing.md](guides/11-testing.md)
- **Validation**: [24-validation-rules.md](guides/24-validation-rules.md)
- **Examples**: [skills/templates/examples/](templates/examples/)

---

**Last Updated**: 2026-01-26  
**Maintained By**: AI Agent / TrongLB  
**Always Current**: Reads latest guides dynamically
