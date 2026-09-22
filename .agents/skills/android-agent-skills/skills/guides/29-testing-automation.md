---
name: Testing Automation & AI-Driven Test Generation
description: AI-powered test coverage analysis, test generation, and quality validation
compliance_level: RECOMMENDED
tags: [testing, automation, ai, quality, coverage]
version: 2.2.0
last_updated: 2026-01-24
---

# Testing Automation & AI-Driven Test Generation

## Context
Comprehensive testing is critical for code quality, but manual test writing is time-consuming and error-prone. This guide enables AI agents to automatically analyze test coverage, generate comprehensive test suites, and validate test quality.

---

## 1. Test Coverage Analysis (AI-Driven)

### What AI Should Analyze

**Target Detection:**
```
1. Find all production code files:
   - ViewModels (*ViewModel.kt)
   - UseCases (*UseCase.kt)
   - Repositories (*Repository.kt, *RepositoryImpl.kt)
   - Composables (*Screen.kt, *Route.kt)

2. Check corresponding test files:
   - test/**/[FileName]Test.kt
   - androidTest/**/[FileName]Test.kt

3. Identify gaps:
   - Files without tests
   - Files with partial coverage
   - Critical paths untested
```

### Coverage Gap Detection Prompt

```
@analyze-test-coverage

Analyze test coverage for this workspace/module.

ANALYZE:
1. List all ViewModels/UseCases/Repositories
2. Identify files WITHOUT corresponding test files
3. For files WITH tests, check:
   - Are all public methods tested?
   - Are error cases tested?
   - Are edge cases covered?
   - Is state management tested (ViewModels)?
   
PRIORITIZE by risk:
- HIGH: ViewModels with business logic
- HIGH: UseCases with validation
- MEDIUM: Repositories with offline logic
- LOW: Simple data classes, DTOs

OUTPUT:
- Summary table with coverage %
- List of untested files (priority order)
- Specific methods missing tests
- Recommended test scenarios
```

### Example Analysis Output

```markdown
## Test Coverage Report

### Summary
- Total Files: 45
- Files with Tests: 28 (62%)
- Missing Tests: 17 (38%)

### High Priority (No Tests)
1. ❌ ProfileViewModel.kt
   - Risk: HIGH (state management + validation)
   - Missing: All scenarios (load, update, error)
   - Suggest: @gen-viewmodel-test

2. ❌ UpdateUserProfileUseCase.kt
   - Risk: HIGH (email/password validation)
   - Missing: All validation scenarios
   - Suggest: @gen-usecase-test

3. ❌ UserRepositoryImpl.kt
   - Risk: MEDIUM (offline-first logic)
   - Missing: Cache fallback, sync scenarios
   - Suggest: @gen-repository-test

### Medium Priority (Partial Tests)
4. ⚠️  ProductViewModel.kt (45% coverage)
   - Tested: loadProducts()
   - Missing: addToCart(), removeFromCart(), error handling
   - Suggest: Add missing scenarios

### Low Priority (Good Coverage)
5. ✅ LoginViewModel.kt (95% coverage)
   - Tested: All scenarios
   - Quality: Good (includes edge cases)
```

---

## 2. AI Test Generation Strategies

### Test Generation Prompts

See [AI_PROMPTS.md](../AI_PROMPTS.md) for complete prompts:
- `@gen-viewmodel-test` - ViewModel tests with Turbine + state transitions
- `@gen-usecase-test` - UseCase tests with validation scenarios
- `@gen-repository-test` - Repository tests with offline/online scenarios
- `@gen-screen-test` - Compose UI tests with user interactions
- `@gen-integration-test` - Multi-layer integration tests

### Test Generation Template Structure

**All test files should include:**
```kotlin
// 1. Setup (dependencies, test subject)
// 2. Happy path tests
// 3. Error scenarios
// 4. Edge cases
// 5. State transitions (for ViewModels)
// 6. Cancellation tests (for coroutines)
```

---

## 3. ViewModel Test Generation (Detailed)

### What AI Should Generate

**For ViewModel with:**
```kotlin
class ProfileViewModel(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    
    fun loadProfile(userId: String)
    fun updateProfile(name: String, email: String)
    fun onDismissError()
}
```

**AI Should Generate:**

```kotlin
@OptIn(ExperimentalCoroutinesTest::class)
class ProfileViewModelTest {
    
    // 1. Mock dependencies
    private val getUserUseCase: GetUserUseCase = mockk()
    private val updateUserUseCase: UpdateUserUseCase = mockk()
    
    private lateinit var viewModel: ProfileViewModel
    
    @BeforeEach
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        viewModel = ProfileViewModel(getUserUseCase, updateUserUseCase)
    }
    
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    // 2. Happy Path Tests
    @Test
    fun `when loadProfile succeeds then state updates with user data`() = runTest {
        // Given
        val userId = "123"
        val user = User(id = userId, name = "John", email = "john@example.com")
        coEvery { getUserUseCase(userId) } returns Result.success(user)
        
        // When/Then
        viewModel.state.test {
            val initialState = awaitItem()
            assertFalse(initialState.isLoading)
            assertNull(initialState.user)
            
            viewModel.loadProfile(userId)
            
            val loadingState = awaitItem()
            assertTrue(loadingState.isLoading)
            assertNull(loadingState.user)
            
            val successState = awaitItem()
            assertFalse(successState.isLoading)
            assertEquals(user, successState.user)
            assertNull(successState.error)
        }
        
        // Verify
        coVerify(exactly = 1) { getUserUseCase(userId) }
    }
    
    // 3. Error Scenarios
    @Test
    fun `when loadProfile fails then state updates with error`() = runTest {
        // Given
        val userId = "123"
        val errorMessage = "Network error"
        coEvery { getUserUseCase(userId) } returns Result.failure(Exception(errorMessage))
        
        // When/Then
        viewModel.state.test {
            awaitItem() // initial
            
            viewModel.loadProfile(userId)
            
            awaitItem() // loading
            
            val errorState = awaitItem()
            assertFalse(errorState.isLoading)
            assertNull(errorState.user)
            assertEquals(errorMessage, errorState.error)
        }
    }
    
    // 4. Edge Cases
    @Test
    fun `when loadProfile called with blank userId then does not call use case`() = runTest {
        // When
        viewModel.loadProfile("")
        
        // Then
        advanceUntilIdle()
        coVerify(exactly = 0) { getUserUseCase(any()) }
    }
    
    @Test
    fun `when updateProfile called while loading then ignores request`() = runTest {
        // Given
        coEvery { getUserUseCase(any()) } coAnswers {
            delay(1000) // Simulate slow network
            Result.success(User("123", "John", "john@example.com"))
        }
        
        // When
        viewModel.loadProfile("123")
        viewModel.updateProfile("Jane", "jane@example.com") // Should be ignored
        
        // Then
        advanceUntilIdle()
        coVerify(exactly = 0) { updateUserUseCase(any(), any()) }
    }
    
    // 5. State Transitions
    @Test
    fun `when onDismissError called then error is cleared`() = runTest {
        // Given - Set error state
        coEvery { getUserUseCase(any()) } returns Result.failure(Exception("Error"))
        viewModel.loadProfile("123")
        advanceUntilIdle()
        
        // When
        viewModel.state.test {
            val errorState = awaitItem()
            assertNotNull(errorState.error)
            
            viewModel.onDismissError()
            
            val clearedState = awaitItem()
            assertNull(clearedState.error)
        }
    }
    
    // 6. Cancellation Tests
    @Test
    fun `when ViewModel cleared during operation then coroutine is cancelled`() = runTest {
        // Given
        var operationCompleted = false
        coEvery { getUserUseCase(any()) } coAnswers {
            delay(1000)
            operationCompleted = true
            Result.success(User("123", "John", "john@example.com"))
        }
        
        // When
        viewModel.loadProfile("123")
        advanceTimeBy(500) // Half-way through
        viewModel.onCleared() // Simulate ViewModel destruction
        advanceUntilIdle()
        
        // Then
        assertFalse(operationCompleted, "Operation should be cancelled")
    }
}
```

**Test Scenarios Coverage:**
- ✅ Happy path (success flow)
- ✅ Error handling (network failure, validation errors)
- ✅ Edge cases (empty input, concurrent calls)
- ✅ State transitions (loading → success → error → cleared)
- ✅ Cancellation (ViewModel lifecycle)
- ✅ Verify use case calls (correct parameters, call count)

---

## 4. UseCase Test Generation

### Template for UseCase Tests

```kotlin
class UpdateUserProfileUseCaseTest {
    
    private val repository: UserRepository = mockk()
    private val useCase = UpdateUserProfileUseCase(repository)
    
    // 1. Happy Path
    @Test
    fun `when valid input then returns success`() = runTest {
        // Given
        val userId = "123"
        val name = "John Doe"
        val email = "john@example.com"
        coEvery { repository.updateUser(any()) } returns Result.success(Unit)
        
        // When
        val result = useCase(userId, name, email)
        
        // Then
        assertTrue(result.isSuccess)
        coVerify { repository.updateUser(match {
            it.id == userId && it.name == name && it.email == email
        }) }
    }
    
    // 2. Validation Errors (CRITICAL for UseCases)
    @Test
    fun `when userId is blank then returns validation error`() = runTest {
        val result = useCase("", "John", "john@example.com")
        
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()!!
        assertTrue(error is ValidationException)
        assertEquals("userId cannot be blank", error.message)
    }
    
    @Test
    fun `when email is invalid then returns validation error`() = runTest {
        val result = useCase("123", "John", "invalid-email")
        
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ValidationException)
        assertContains(result.exceptionOrNull()!!.message!!, "email")
    }
    
    @Test
    fun `when name is too short then returns validation error`() = runTest {
        val result = useCase("123", "J", "john@example.com")
        
        assertTrue(result.isFailure)
        assertContains(result.exceptionOrNull()!!.message!!, "name")
    }
    
    // 3. Repository Failure
    @Test
    fun `when repository fails then returns failure`() = runTest {
        // Given
        val errorMessage = "Database error"
        coEvery { repository.updateUser(any()) } returns Result.failure(Exception(errorMessage))
        
        // When
        val result = useCase("123", "John", "john@example.com")
        
        // Then
        assertTrue(result.isFailure)
        assertEquals(errorMessage, result.exceptionOrNull()?.message)
    }
    
    // 4. Edge Cases
    @Test
    fun `when name has special characters then validates correctly`() = runTest {
        coEvery { repository.updateUser(any()) } returns Result.success(Unit)
        
        val result = useCase("123", "O'Brien-Smith", "john@example.com")
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `when email is uppercase then normalizes to lowercase`() = runTest {
        coEvery { repository.updateUser(any()) } returns Result.success(Unit)
        
        useCase("123", "John", "JOHN@EXAMPLE.COM")
        
        coVerify { repository.updateUser(match {
            it.email == "john@example.com"
        }) }
    }
}
```

**Key Points for UseCase Tests:**
- ✅ **Validation is CRITICAL** - Test every validation rule
- ✅ Test all error messages
- ✅ Test edge cases (special chars, Unicode, max length)
- ✅ Verify repository calls with exact parameters
- ✅ Test data transformation (normalization, sanitization)

---

## 5. Repository Test Generation

### Offline-First Repository Tests

```kotlin
@OptIn(ExperimentalCoroutinesTest::class)
class UserRepositoryImplTest {
    
    private val api: UserApi = mockk()
    private val dao: UserDao = mockk()
    private val repository = UserRepositoryImpl(api, dao)
    
    @BeforeEach
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }
    
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    // 1. Remote Success (Cache Update)
    @Test
    fun `when getUser succeeds remotely then updates cache and returns data`() = runTest {
        // Given
        val userId = "123"
        val remoteUser = UserDto(userId, "John", "john@example.com")
        coEvery { api.getUser(userId) } returns remoteUser
        coEvery { dao.insertUser(any()) } just Runs
        
        // When
        val result = repository.getUser(userId)
        
        // Then
        assertTrue(result.isSuccess)
        assertEquals("John", result.getOrNull()?.name)
        
        coVerify { api.getUser(userId) }
        coVerify { dao.insertUser(match { it.id == userId }) }
    }
    
    // 2. Remote Failure (Cache Fallback)
    @Test
    fun `when remote fails then returns cached data`() = runTest {
        // Given
        val userId = "123"
        val cachedUser = UserEntity(userId, "John", "john@example.com")
        coEvery { api.getUser(userId) } throws IOException("Network error")
        coEvery { dao.getUser(userId) } returns cachedUser
        
        // When
        val result = repository.getUser(userId)
        
        // Then
        assertTrue(result.isSuccess)
        assertEquals("John", result.getOrNull()?.name)
    }
    
    // 3. No Cache + No Network (Failure)
    @Test
    fun `when remote fails and no cache then returns failure`() = runTest {
        // Given
        coEvery { api.getUser(any()) } throws IOException("Network error")
        coEvery { dao.getUser(any()) } returns null
        
        // When
        val result = repository.getUser("123")
        
        // Then
        assertTrue(result.isFailure)
    }
    
    // 4. Observe (Flow) Tests
    @Test
    fun `observeUser returns flow of cached data`() = runTest {
        // Given
        val userId = "123"
        val userFlow = flow {
            emit(UserEntity(userId, "John", "john@example.com"))
            delay(100)
            emit(UserEntity(userId, "Jane", "jane@example.com"))
        }
        every { dao.observeUser(userId) } returns userFlow
        
        // When/Then
        repository.observeUser(userId).test {
            val firstUser = awaitItem()
            assertEquals("John", firstUser?.name)
            
            val updatedUser = awaitItem()
            assertEquals("Jane", updatedUser?.name)
            
            awaitComplete()
        }
    }
    
    // 5. Concurrent Requests (Deduplication)
    @Test
    fun `when multiple getUser calls then deduplicates requests`() = runTest {
        // Given
        val userId = "123"
        coEvery { api.getUser(userId) } coAnswers {
            delay(100)
            UserDto(userId, "John", "john@example.com")
        }
        coEvery { dao.insertUser(any()) } just Runs
        coEvery { dao.getUser(userId) } returns null
        
        // When - Call simultaneously
        val job1 = async { repository.getUser(userId) }
        val job2 = async { repository.getUser(userId) }
        val job3 = async { repository.getUser(userId) }
        
        awaitAll(job1, job2, job3)
        
        // Then - API called only ONCE
        coVerify(exactly = 1) { api.getUser(userId) }
    }
}
```

---

## 6. Compose UI Test Generation

### Screen Test Template

```kotlin
@OptIn(ExperimentalTestApi::class)
class ProfileScreenTest {
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
    // 1. UI Rendering Tests
    @Test
    fun `when loading then shows loading indicator`() {
        // Given
        val state = ProfileUiState(isLoading = true)
        
        // When
        composeTestRule.setContent {
            ProfileScreen(state = state, onAction = {})
        }
        
        // Then
        composeTestRule.onNodeWithTag("loading").assertIsDisplayed()
    }
    
    @Test
    fun `when success then shows user profile`() {
        // Given
        val user = User("123", "John Doe", "john@example.com")
        val state = ProfileUiState(user = user)
        
        // When
        composeTestRule.setContent {
            ProfileScreen(state = state, onAction = {})
        }
        
        // Then
        composeTestRule.onNodeWithText("John Doe").assertIsDisplayed()
        composeTestRule.onNodeWithText("john@example.com").assertIsDisplayed()
    }
    
    @Test
    fun `when error then shows error message`() {
        // Given
        val state = ProfileUiState(error = "Network error")
        
        // When
        composeTestRule.setContent {
            ProfileScreen(state = state, onAction = {})
        }
        
        // Then
        composeTestRule.onNodeWithText("Network error").assertIsDisplayed()
    }
    
    // 2. User Interaction Tests
    @Test
    fun `when edit button clicked then calls onEditClick`() {
        // Given
        val state = ProfileUiState(user = User("123", "John", "john@example.com"))
        var editClicked = false
        
        // When
        composeTestRule.setContent {
            ProfileScreen(state = state, onAction = { editClicked = true })
        }
        
        composeTestRule.onNodeWithTag("edit_button").performClick()
        
        // Then
        assertTrue(editClicked)
    }
    
    // 3. Accessibility Tests
    @Test
    fun `all interactive elements have content descriptions`() {
        val state = ProfileUiState(user = User("123", "John", "john@example.com"))
        
        composeTestRule.setContent {
            ProfileScreen(state = state, onAction = {})
        }
        
        // Verify semantics
        composeTestRule.onNodeWithTag("edit_button")
            .assertHasClickAction()
            .assert(hasContentDescription())
    }
}
```

---

## 7. Test Quality Validation

### What Makes a GOOD Test?

**✅ Good Test Checklist:**
```
[ ] Follows AAA pattern (Arrange, Act, Assert)
[ ] Tests ONE thing (single responsibility)
[ ] Has clear, descriptive name (backtick format)
[ ] Includes BOTH verification AND assertion
[ ] Tests error scenarios (not just happy path)
[ ] Uses proper test doubles (mockk, fake data)
[ ] Cleans up resources (@AfterEach)
[ ] Runs fast (<100ms per test)
[ ] Is deterministic (no random, no delays)
[ ] Has meaningful assertions (not just verify())
```

### Common Test Smells (AI Should Detect)

**❌ BAD Test Examples:**

```kotlin
// ❌ SMELL 1: Only verify, no state assertion
@Test
fun `test load`() = runTest {
    viewModel.load()
    coVerify { useCase() } // Only checks call, not result!
}

// ✅ FIXED:
@Test
fun `when load succeeds then state updates with data`() = runTest {
    coEvery { useCase() } returns Result.success(data)
    
    viewModel.state.test {
        awaitItem() // initial
        viewModel.load()
        awaitItem() // loading
        
        val successState = awaitItem()
        assertEquals(data, successState.data) // ✅ Assert state!
    }
    coVerify { useCase() }
}

// ❌ SMELL 2: No error testing
@Test
fun `test update`() = runTest {
    // Only tests success, what about failure?
}

// ✅ FIXED: Add error scenario
@Test
fun `when update fails then state shows error`() = runTest {
    coEvery { useCase() } returns Result.failure(Exception("Error"))
    
    viewModel.update()
    
    viewModel.state.test {
        val errorState = awaitItem()
        assertNotNull(errorState.error)
    }
}

// ❌ SMELL 3: Vague test name
@Test
fun `test1`() { }

// ✅ FIXED: Descriptive name
@Test
fun `when email is invalid then validation fails with error message`() { }

// ❌ SMELL 4: Multiple assertions (testing multiple things)
@Test
fun `test everything`() {
    // Tests load, update, delete all together
}

// ✅ FIXED: Separate tests
@Test
fun `when load succeeds then updates state`() { }
@Test
fun `when update succeeds then shows success`() { }
@Test
fun `when delete succeeds then removes item`() { }
```

---

## 8. AI Test Coverage Prompts

### @analyze-test-quality

```
Analyze test file quality for [FileName]Test.kt

CHECK:
1. Test naming:
   - Uses backtick format?
   - Descriptive (when/then)?
   
2. Assertions:
   - Has state assertions (not just verify)?
   - Checks actual behavior?
   
3. Coverage:
   - All public methods tested?
   - Error scenarios included?
   - Edge cases covered?
   
4. Test smells:
   - Tests one thing per test?
   - No Thread.sleep() or arbitrary delays?
   - Proper cleanup (@AfterEach)?
   
5. Missing scenarios:
   - What's NOT tested?
   - Recommend additional tests

OUTPUT:
- Quality score (0-100)
- List of issues
- Recommended improvements
```

---

## 9. Integration Test Generation

### Multi-Layer Test Example

```kotlin
/**
 * Integration test: ViewModel → UseCase → Repository
 * Tests the full flow without mocking intermediate layers
 */
class ProfileFeatureIntegrationTest {
    
    private val api: UserApi = mockk()
    private val dao: UserDao = FakeUserDao() // Use fake, not mock
    private val repository = UserRepositoryImpl(api, dao)
    private val useCase = GetUserProfileUseCase(repository)
    private val viewModel = ProfileViewModel(useCase)
    
    @Test
    fun `full flow - load profile from API, cache, and display`() = runTest {
        // Given
        val userId = "123"
        val apiUser = UserDto(userId, "John", "john@example.com")
        coEvery { api.getUser(userId) } returns apiUser
        
        // When
        viewModel.state.test {
            val initialState = awaitItem()
            assertNull(initialState.user)
            
            viewModel.loadProfile(userId)
            
            val loadingState = awaitItem()
            assertTrue(loadingState.isLoading)
            
            val successState = awaitItem()
            assertFalse(successState.isLoading)
            assertEquals("John", successState.user?.name)
            
            // Verify cache was updated
            val cachedUser = dao.getUser(userId)
            assertNotNull(cachedUser)
            assertEquals("John", cachedUser?.name)
        }
    }
    
    @Test
    fun `offline flow - network fails, loads from cache`() = runTest {
        // Given - Prime cache
        dao.insertUser(UserEntity("123", "Cached John", "cached@example.com"))
        
        // Network fails
        coEvery { api.getUser("123") } throws IOException("No network")
        
        // When
        viewModel.loadProfile("123")
        
        // Then - Should load from cache
        viewModel.state.test {
            val state = expectMostRecentItem()
            assertEquals("Cached John", state.user?.name)
        }
    }
}
```

---

## 10. Best Practices Summary

### ✅ DO:
1. **Test behavior, not implementation**
   - Assert state changes, not internal method calls
   
2. **Use Turbine for Flow/StateFlow testing**
   - `flow.test { awaitItem() }` is cleaner than `collectAsState()`
   
3. **Mock external dependencies only**
   - Mock API, database, system services
   - Use real domain logic (UseCases, models)
   
4. **Test error scenarios** (50% of tests should be errors)
   - Network failures
   - Validation errors
   - Edge cases
   
5. **Write integration tests** for critical flows
   - User authentication
   - Payment processing
   - Data sync
   
6. **Use descriptive test names**
   - `when [condition] then [expected]`
   
7. **One assertion focus per test**
   - Test passes/fails for ONE reason

### ❌ DON'T:
1. **Don't use real databases/network** in unit tests
2. **Don't test Android framework** (assume it works)
3. **Don't use Thread.sleep()** (use TestDispatcher)
4. **Don't ignore flaky tests** (fix or delete them)
5. **Don't test private methods** (test public API)
6. **Don't skip cleanup** (memory leaks in tests)

---

## References

### Related Guides
- [11-testing.md](11-testing.md) - Core testing principles
- [07-state-management.md](07-state-management.md) - ViewModel testing patterns
- [10-error-handling.md](10-error-handling.md) - Error scenario testing
- [26-context-aware-suggestions.md](26-context-aware-suggestions.md) - AI code analysis

### AI Prompts
- [AI_PROMPTS.md](../AI_PROMPTS.md) - Test generation prompts
  - @gen-viewmodel-test
  - @gen-usecase-test
  - @gen-repository-test
  - @gen-screen-test
  - @analyze-test-coverage
  - @analyze-test-quality

### Tools
- **JUnit5** - Test framework
- **MockK** - Mocking library
- **Turbine** - Flow testing
- **Robolectric** - Android unit tests on JVM
- **Paparazzi/Roborazzi** - Screenshot testing

---

**Last Updated**: 2026-01-24  
**Maintained By**: AI Agent / TrongLB  
**Focus**: AI-driven test generation and quality validation
