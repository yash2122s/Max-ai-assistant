---
name: Testing
description: Standards for Unit, Integration, and UI testing.
compliance_level: MANDATORY
tags: [testing, unit-test, integration-test, ui-test, hilt, paparazzi]
version: 2.2.0
---

# Testing

## Context
Tests ensure reliability and prevent regressions. We follow the testing pyramid with a focus on high unit test coverage.

**Related Guides:**
- [08-dependency-injection.md](./08-dependency-injection.md) - Test modules
- [29-testing-automation.md](./29-testing-automation.md) - AI test generation

**Code Templates:**
- [ViewModelTestTemplate.kt](../templates/testing/ViewModelTestTemplate.kt) - ViewModel test patterns
- [UseCaseTestTemplate.kt](../templates/testing/UseCaseTestTemplate.kt) - UseCase test patterns
- [SkillTestTemplate.kt](../templates/testing/SkillTestTemplate.kt) - Skill test patterns
- [RepositoryTestTemplate.kt](../templates/testing/RepositoryTestTemplate.kt) - Repository test patterns
- [ComposeScreenTestTemplate.kt](../templates/testing/ComposeScreenTestTemplate.kt) - Compose UI test patterns
- [IntegrationTestTemplate.kt](../templates/testing/IntegrationTestTemplate.kt) - Multi-layer integration tests

---

## 🎯 AI Quick Reference

```
TOOLS:
• JUnit5 + MockK + Turbine
• runTest for coroutines
• Turbine for Flow testing
• Paparazzi for screenshot tests

TEST NAMING:
• `should [action] when [condition]`
• Use backticks: `should return user when id exists`

STRUCTURE (AAA):
• Arrange: Setup mocks, data
• Act: Call method under test
• Assert: Verify results

MOCKING:
• coEvery { } for suspend
• every { } for regular
• verify { } / coVerify { }
```

---

## 1. Unit Testing

### ViewModel Test Structure
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    
    // ════════════════════════════════════════════════════════════
    // TEST SETUP
    // ════════════════════════════════════════════════════════════
    
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var getUserUseCase: GetUserUseCase
    private lateinit var updateUserUseCase: UpdateUserProfileUseCase
    private lateinit var viewModel: ProfileViewModel
    
    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        getUserUseCase = mockk()
        updateUserUseCase = mockk()
        
        viewModel = ProfileViewModel(
            getUserUseCase = getUserUseCase,
            updateUserUseCase = updateUserUseCase,
        )
    }
    
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    // ════════════════════════════════════════════════════════════
    // LOAD PROFILE TESTS
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should show loading then success when loadProfile succeeds`() = runTest {
        // Arrange
        val user = User(id = "1", name = "John", email = "john@test.com")
        coEvery { getUserUseCase("1") } returns Result.success(user)
        
        // Act & Assert with Turbine
        viewModel.state.test {
            // Initial state
            assertThat(awaitItem().isLoading).isFalse()
            
            // Trigger load
            viewModel.loadProfile("1")
            
            // Loading state
            assertThat(awaitItem().isLoading).isTrue()
            
            // Success state
            val successState = awaitItem()
            assertThat(successState.isLoading).isFalse()
            assertThat(successState.user).isEqualTo(user)
            assertThat(successState.name).isEqualTo("John")
            assertThat(successState.email).isEqualTo("john@test.com")
            
            cancelAndIgnoreRemainingEvents()
        }
        
        // Verify
        coVerify(exactly = 1) { getUserUseCase("1") }
    }
    
    @Test
    fun `should show error when loadProfile fails`() = runTest {
        // Arrange
        val error = DomainError.Network.NoConnection.toException()
        coEvery { getUserUseCase("1") } returns Result.failure(error)
        
        // Act
        viewModel.loadProfile("1")
        advanceUntilIdle()
        
        // Assert
        val state = viewModel.state.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.error).isNotNull()
        assertThat(state.error?.isRetryable).isTrue()
    }
    
    @Test
    fun `should navigate to login when unauthorized`() = runTest {
        // Arrange
        val error = DomainError.Server.Unauthorized.toException()
        coEvery { getUserUseCase("1") } returns Result.failure(error)
        
        // Act & Assert events
        viewModel.events.test {
            viewModel.loadProfile("1")
            
            val event = awaitItem()
            assertThat(event).isEqualTo(ProfileEvent.NavigateToLogin)
            
            cancelAndIgnoreRemainingEvents()
        }
    }
    
    // ════════════════════════════════════════════════════════════
    // UPDATE PROFILE TESTS
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should update profile successfully`() = runTest {
        // Arrange
        val initialUser = User(id = "1", name = "John", email = "john@test.com")
        val updatedUser = User(id = "1", name = "Jane", email = "jane@test.com")
        
        coEvery { getUserUseCase("1") } returns Result.success(initialUser)
        coEvery { 
            updateUserUseCase(
                userId = "1",
                name = "Jane",
                email = "jane@test.com",
            )
        } returns Result.success(updatedUser)
        
        // Setup initial state
        viewModel.loadProfile("1")
        advanceUntilIdle()
        
        // Update name and email
        viewModel.onNameChange("Jane")
        viewModel.onEmailChange("jane@test.com")
        
        // Act
        viewModel.updateProfile()
        advanceUntilIdle()
        
        // Assert
        val state = viewModel.state.value
        assertThat(state.user).isEqualTo(updatedUser)
        assertThat(state.isLoading).isFalse()
    }
    
    @Test
    fun `should show field error when validation fails`() = runTest {
        // Arrange
        val user = User(id = "1", name = "John", email = "john@test.com")
        val validationError = DomainError.Validation.InvalidInput(
            field = "email",
            message = "Invalid email format",
        ).toException()
        
        coEvery { getUserUseCase("1") } returns Result.success(user)
        coEvery { updateUserUseCase(any(), any(), any()) } returns Result.failure(validationError)
        
        // Setup
        viewModel.loadProfile("1")
        advanceUntilIdle()
        viewModel.onEmailChange("invalid-email")
        
        // Act
        viewModel.updateProfile()
        advanceUntilIdle()
        
        // Assert
        val state = viewModel.state.value
        assertThat(state.fieldErrors["email"]).isEqualTo("Invalid email format")
    }
}
```

### UseCase Test Structure
```kotlin
class UpdateUserProfileUseCaseTest {
    
    private lateinit var userRepository: UserRepository
    private lateinit var useCase: UpdateUserProfileUseCase
    
    @BeforeEach
    fun setup() {
        userRepository = mockk()
        useCase = UpdateUserProfileUseCase(userRepository)
    }
    
    // ════════════════════════════════════════════════════════════
    // VALIDATION TESTS
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should return error when userId is blank`() = runTest {
        // Act
        val result = useCase(userId = "", name = "John", email = "john@test.com")
        
        // Assert
        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()?.toDomainError()
        assertThat(error).isInstanceOf(DomainError.Validation.InvalidInput::class.java)
        assertThat((error as DomainError.Validation.InvalidInput).field).isEqualTo("userId")
        
        // Verify repository not called
        coVerify(exactly = 0) { userRepository.updateUser(any()) }
    }
    
    @Test
    fun `should return error when name is blank`() = runTest {
        val result = useCase(userId = "1", name = "  ", email = "john@test.com")
        
        assertThat(result.isFailure).isTrue()
        val error = result.domainError() as DomainError.Validation.InvalidInput
        assertThat(error.field).isEqualTo("name")
    }
    
    @Test
    fun `should return error when email is invalid`() = runTest {
        val result = useCase(userId = "1", name = "John", email = "not-an-email")
        
        assertThat(result.isFailure).isTrue()
        val error = result.domainError() as DomainError.Validation.InvalidInput
        assertThat(error.field).isEqualTo("email")
    }
    
    @ParameterizedTest
    @ValueSource(strings = ["test@example.com", "user.name@domain.org", "a@b.co"])
    fun `should accept valid email formats`(email: String) = runTest {
        // Arrange
        val user = User(id = "1", name = "John", email = email)
        coEvery { userRepository.updateUser(any()) } returns Result.success(user)
        
        // Act
        val result = useCase(userId = "1", name = "John", email = email)
        
        // Assert
        assertThat(result.isSuccess).isTrue()
    }
    
    // ════════════════════════════════════════════════════════════
    // SUCCESS PATH
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should call repository with correct data when valid`() = runTest {
        // Arrange
        val expectedUser = User(id = "1", name = "John", email = "john@test.com")
        coEvery { userRepository.updateUser(any()) } returns Result.success(expectedUser)
        
        // Act
        val result = useCase(userId = "1", name = "John", email = "john@test.com")
        
        // Assert
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo(expectedUser)
        
        coVerify { 
            userRepository.updateUser(
                match { it.id == "1" && it.name == "John" && it.email == "john@test.com" }
            )
        }
    }
    
    // ════════════════════════════════════════════════════════════
    // ERROR PROPAGATION
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should propagate repository errors`() = runTest {
        // Arrange
        val networkError = DomainError.Network.NoConnection.toException()
        coEvery { userRepository.updateUser(any()) } returns Result.failure(networkError)
        
        // Act
        val result = useCase(userId = "1", name = "John", email = "john@test.com")
        
        // Assert
        assertThat(result.isFailure).isTrue()
        assertThat(result.domainError()).isEqualTo(DomainError.Network.NoConnection)
    }
}
```

### Repository Test Structure
```kotlin
class UserRepositoryImplTest {
    
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var api: UserApi
    private lateinit var dao: UserDao
    private lateinit var repository: UserRepositoryImpl
    
    @BeforeEach
    fun setup() {
        api = mockk()
        dao = mockk(relaxed = true)
        repository = UserRepositoryImpl(
            api = api,
            dao = dao,
            ioDispatcher = testDispatcher,
        )
    }
    
    // ════════════════════════════════════════════════════════════
    // SUCCESS TESTS
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should return user from API and cache locally`() = runTest {
        // Arrange
        val dto = UserDto(id = "1", name = "John", email = "john@test.com")
        coEvery { api.getUser("1") } returns dto
        
        // Act
        val result = repository.getUser("1")
        
        // Assert
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.name).isEqualTo("John")
        
        // Verify caching
        coVerify { dao.insert(any()) }
    }
    
    // ════════════════════════════════════════════════════════════
    // CACHE FALLBACK TESTS
    // ════════════════════════════════════════════════════════════
    
    @Test
    fun `should return cached data when API fails`() = runTest {
        // Arrange
        val cachedEntity = UserEntity(id = "1", name = "Cached John", email = "john@test.com")
        coEvery { api.getUser("1") } throws UnknownHostException()
        coEvery { dao.getUser("1") } returns cachedEntity
        
        // Act
        val result = repository.getUser("1")
        
        // Assert
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()?.name).isEqualTo("Cached John")
    }
    
    @Test
    fun `should return error when API fails and no cache`() = runTest {
        // Arrange
        coEvery { api.getUser("1") } throws UnknownHostException()
        coEvery { dao.getUser("1") } returns null
        
        // Act
        val result = repository.getUser("1")
        
        // Assert
        assertThat(result.isFailure).isTrue()
        assertThat(result.domainError()).isEqualTo(DomainError.Network.NoConnection)
    }
    
    // ════════════════════════════════════════════════════════════
    // ERROR MAPPING TESTS
    // ════════════════════════════════════════════════════════════
    
    @ParameterizedTest
    @MethodSource("errorMappingCases")
    fun `should map exceptions to domain errors`(
        exception: Exception,
        expectedError: DomainError,
    ) = runTest {
        // Arrange
        coEvery { api.getUser(any()) } throws exception
        coEvery { dao.getUser(any()) } returns null
        
        // Act
        val result = repository.getUser("1")
        
        // Assert
        assertThat(result.domainError()).isEqualTo(expectedError)
    }
    
    companion object {
        @JvmStatic
        fun errorMappingCases() = listOf(
            Arguments.of(UnknownHostException(), DomainError.Network.NoConnection),
            Arguments.of(SocketTimeoutException(), DomainError.Network.Timeout),
            Arguments.of(HttpException(Response.error<Any>(401, "".toResponseBody())), DomainError.Server.Unauthorized),
            Arguments.of(HttpException(Response.error<Any>(404, "".toResponseBody())), DomainError.Server.NotFound),
        )
    }
}
```

---

## 2. Flow Testing with Turbine

```kotlin
class ProductViewModelTest {
    
    @Test
    fun `search should debounce and return results`() = runTest {
        // Arrange
        val products = listOf(Product(id = "1", name = "Test"))
        coEvery { searchProductsUseCase("test") } returns flowOf(products)
        
        // Act & Assert
        viewModel.searchResults.test {
            // Initial empty
            assertThat(awaitItem()).isEmpty()
            
            // Type search query (with debounce)
            viewModel.onSearchQueryChange("t")
            viewModel.onSearchQueryChange("te")
            viewModel.onSearchQueryChange("tes")
            viewModel.onSearchQueryChange("test")
            
            // Wait for debounce
            advanceTimeBy(400)
            
            // Verify results
            assertThat(awaitItem()).isEqualTo(products)
            
            cancelAndIgnoreRemainingEvents()
        }
    }
    
    @Test
    fun `combined flow should emit when any source changes`() = runTest {
        // Arrange
        val productsFlow = MutableStateFlow(listOf<Product>())
        val categoriesFlow = MutableStateFlow(listOf<Category>())
        
        coEvery { productRepository.observeProducts() } returns productsFlow
        coEvery { productRepository.observeCategories() } returns categoriesFlow
        
        // Act & Assert
        viewModel.dashboardState.test {
            // Initial empty
            val initial = awaitItem()
            assertThat(initial.products).isEmpty()
            assertThat(initial.categories).isEmpty()
            
            // Update products
            productsFlow.emit(listOf(Product(id = "1", name = "Product 1")))
            val afterProducts = awaitItem()
            assertThat(afterProducts.products).hasSize(1)
            
            // Update categories
            categoriesFlow.emit(listOf(Category(id = "1", name = "Category 1")))
            val afterCategories = awaitItem()
            assertThat(afterCategories.categories).hasSize(1)
            
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

---

## 3. Compose UI Testing

```kotlin
class ProfileScreenTest {
    
    @get:Rule
    val composeRule = createComposeRule()
    
    @Test
    fun `should display user name and email`() {
        // Arrange
        val state = ProfileUiState(
            user = User(id = "1", name = "John Doe", email = "john@test.com"),
            name = "John Doe",
            email = "john@test.com",
        )
        
        // Act
        composeRule.setContent {
            MaterialTheme {
                ProfileScreen(state = state)
            }
        }
        
        // Assert
        composeRule.onNodeWithText("John Doe").assertIsDisplayed()
        composeRule.onNodeWithText("john@test.com").assertIsDisplayed()
    }
    
    @Test
    fun `should show loading indicator when loading`() {
        composeRule.setContent {
            ProfileScreen(state = ProfileUiState(isLoading = true))
        }
        
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }
    
    @Test
    fun `should show error content when error and no user`() {
        val state = ProfileUiState(
            error = UiError(message = "Network error", isRetryable = true),
        )
        
        composeRule.setContent {
            ProfileScreen(state = state)
        }
        
        composeRule.onNodeWithText("Network error").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }
    
    @Test
    fun `should call onSaveClick when save button clicked`() {
        // Arrange
        var saveClicked = false
        val state = ProfileUiState(
            name = "John",
            email = "john@test.com",
        )
        
        composeRule.setContent {
            ProfileScreen(
                state = state,
                onSaveClick = { saveClicked = true },
            )
        }
        
        // Act
        composeRule.onNodeWithText("Save").performClick()
        
        // Assert
        assertThat(saveClicked).isTrue()
    }
    
    @Test
    fun `should disable save button when invalid`() {
        val state = ProfileUiState(
            name = "", // Invalid
            email = "john@test.com",
        )
        
        composeRule.setContent {
            ProfileScreen(state = state)
        }
        
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }
}
```

---

## 4. Screenshot Testing with Paparazzi

```kotlin
class ProfileScreenSnapshotTest {
    
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
    )
    
    @Test
    fun `profile screen - loading state`() {
        paparazzi.snapshot {
            MaterialTheme {
                ProfileScreen(state = ProfileUiState(isLoading = true))
            }
        }
    }
    
    @Test
    fun `profile screen - content state`() {
        paparazzi.snapshot {
            MaterialTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        user = User(id = "1", name = "John Doe", email = "john@example.com"),
                        name = "John Doe",
                        email = "john@example.com",
                    ),
                )
            }
        }
    }
    
    @Test
    fun `profile screen - error state`() {
        paparazzi.snapshot {
            MaterialTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        error = UiError("No internet connection", isRetryable = true),
                    ),
                )
            }
        }
    }
    
    @Test
    fun `profile screen - dark mode`() {
        paparazzi.snapshot {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProfileScreen(
                    state = ProfileUiState(
                        name = "John Doe",
                        email = "john@example.com",
                    ),
                )
            }
        }
    }
    
    @Test
    fun `profile screen - field validation error`() {
        paparazzi.snapshot {
            MaterialTheme {
                ProfileScreen(
                    state = ProfileUiState(
                        name = "John",
                        email = "invalid",
                        fieldErrors = mapOf("email" to "Invalid email format"),
                    ),
                )
            }
        }
    }
}
```

---

## 5. Test Utilities

```kotlin
// ════════════════════════════════════════════════════════════
// COMMON TEST FIXTURES
// ════════════════════════════════════════════════════════════
object TestFixtures {
    fun createUser(
        id: String = "1",
        name: String = "Test User",
        email: String = "test@example.com",
    ) = User(id = id, name = name, email = email)
    
    fun createProduct(
        id: String = "1",
        name: String = "Test Product",
        price: Double = 9.99,
    ) = Product(id = id, name = name, price = price)
}

// ════════════════════════════════════════════════════════════
// RESULT EXTENSIONS FOR TESTING
// ════════════════════════════════════════════════════════════
fun <T> Result<T>.assertSuccess(): T {
    assertThat(isSuccess).isTrue()
    return getOrThrow()
}

fun <T> Result<T>.assertFailure(): Throwable {
    assertThat(isFailure).isTrue()
    return exceptionOrNull()!!
}

fun <T> Result<T>.assertDomainError(expected: DomainError) {
    assertThat(domainError()).isEqualTo(expected)
}

// ════════════════════════════════════════════════════════════
// COROUTINE TEST RULE
// ════════════════════════════════════════════════════════════
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }
    
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

// Usage
class MyViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    
    @Test
    fun test() = runTest { /* ... */ }
}
```

---

## 6. Verification Checklist

### Unit Tests
- [ ] ViewModels have comprehensive tests
- [ ] UseCases test validation + success + error paths
- [ ] Repositories test API + cache + error mapping
- [ ] `runTest` used for coroutines
- [ ] Turbine used for Flow testing

### Test Quality
- [ ] Test names describe behavior clearly
- [ ] AAA pattern (Arrange-Act-Assert)
- [ ] Mocks verified with `coVerify`
- [ ] Edge cases covered

### UI Tests
- [ ] Critical screens have UI tests
- [ ] Loading, content, error states tested
- [ ] User interactions verified

### Screenshot Tests
- [ ] Key components have screenshots
- [ ] Light and dark mode covered
- [ ] Different states captured
