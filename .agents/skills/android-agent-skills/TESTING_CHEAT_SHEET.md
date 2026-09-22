# Testing Cheat Sheet
> **Quick Reference**: Testing patterns for Android/Kotlin
> **Version**: 2.2.0

---

## 🎯 Quick Decision

```
What am I testing?
    │
    ├─► ViewModel?
    │   └─► Use: JUnit5 + MockK + Turbine
    │       Template: ViewModelTestTemplate.kt
    │       Prompt: @gen-viewmodel-test
    │
    ├─► UseCase?
    │   └─► Use: JUnit5 + MockK
    │       Template: UseCaseTestTemplate.kt
    │       Prompt: @gen-usecase-test
    │
    ├─► Skill?
    │   └─► Use: JUnit5 + MockK
    │       Template: SkillTestTemplate.kt
    │       Prompt: @gen-skill-test
    │
    ├─► Repository?
    │   └─► Use: JUnit5 + MockK
    │       Template: RepositoryTestTemplate.kt
    │       Prompt: @gen-repository-test
    │
    ├─► Compose UI?
    │   └─► Use: Compose Test + Paparazzi
    │       Template: ComposeScreenTestTemplate.kt
    │       Prompt: @gen-screen-test
    │
    └─► Integration (multi-layer)?
        └─► Use: JUnit5 + MockK + Turbine
            Template: IntegrationTestTemplate.kt
            Prompt: @gen-integration-test
```

---

## 1. Test Setup

### Dependencies
```kotlin
// build.gradle.kts
testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
testImplementation("io.mockk:mockk:1.13.8")
testImplementation("app.cash.turbine:turbine:1.0.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
testImplementation("com.google.truth:truth:1.4.0")
```

### Basic Test Structure
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var mockUseCase: MyUseCase
    private lateinit var viewModel: MyViewModel
    
    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mockk()
        viewModel = MyViewModel(mockUseCase)
    }
    
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }
    
    @Test
    fun `test case name`() = runTest {
        // Given
        // When
        // Then
    }
}
```

---

## 2. MockK Cheat Sheet

### Creating Mocks
```kotlin
// Relaxed mock (returns default values)
val mock = mockk<MyClass>(relaxed = true)

// Strict mock (throws if not configured)
val mock = mockk<MyClass>()

// Partial mock (spy)
val spy = spyk(RealClass())
```

### Stubbing
```kotlin
// Regular function
every { mock.getData() } returns data

// Suspend function
coEvery { mock.fetchData() } returns data

// Return sequence
every { mock.getValue() } returnsMany listOf(1, 2, 3)

// Throw exception
coEvery { mock.riskyCall() } throws IOException()

// Answer with logic
coEvery { mock.process(any()) } answers { 
    firstArg<String>().uppercase() 
}

// Capture arguments
val slot = slot<String>()
coEvery { mock.save(capture(slot)) } returns Unit
// Later: slot.captured
```

### Verification
```kotlin
// Was called
verify { mock.doSomething() }
coVerify { mock.fetchData() }

// Exact times
verify(exactly = 2) { mock.doSomething() }
coVerify(exactly = 1) { mock.fetchData() }

// Never called
verify(exactly = 0) { mock.doSomething() }

// With specific arguments
coVerify { mock.save(match { it.id == "123" }) }

// Order verification
verifyOrder {
    mock.first()
    mock.second()
}
```

---

## 3. Turbine (Flow Testing)

### Basic Usage
```kotlin
@Test
fun `test flow emissions`() = runTest {
    viewModel.state.test {
        // Initial state
        val initial = awaitItem()
        assertThat(initial.isLoading).isFalse()
        
        // Trigger action
        viewModel.loadData()
        
        // Loading state
        val loading = awaitItem()
        assertThat(loading.isLoading).isTrue()
        
        // Success state
        val success = awaitItem()
        assertThat(success.data).isNotNull()
        
        // Cleanup
        cancelAndIgnoreRemainingEvents()
    }
}
```

### Useful Methods
```kotlin
flow.test {
    awaitItem()              // Wait for next emission
    awaitEvent()             // Wait for any event (item, complete, error)
    awaitComplete()          // Expect completion
    awaitError()             // Expect error
    
    expectNoEvents()         // Assert no pending events
    cancelAndIgnoreRemainingEvents()  // Cleanup
    
    // With timeout
    val item = awaitItem(timeout = 5.seconds)
}
```

---

## 4. Coroutine Testing

### runTest
```kotlin
@Test
fun `test coroutine`() = runTest {
    // Code runs with virtual time
    val result = useCase.invoke(input)
    assertThat(result.isSuccess).isTrue()
}
```

### Advance Time
```kotlin
@Test
fun `test with delay`() = runTest {
    viewModel.startCountdown(10)
    
    // Skip time
    advanceTimeBy(5000)  // 5 seconds
    assertThat(viewModel.state.value.remaining).isEqualTo(5)
    
    // Or run all pending
    advanceUntilIdle()
}
```

### Test Dispatcher
```kotlin
// For ViewModel that uses Dispatchers.Main
private val testDispatcher = StandardTestDispatcher()

@BeforeEach
fun setup() {
    Dispatchers.setMain(testDispatcher)
}

@AfterEach  
fun tearDown() {
    Dispatchers.resetMain()
}
```

---

## 5. Common Test Patterns

### ViewModel State Transition Test
```kotlin
@Test
fun `when load succeeds then state shows data`() = runTest {
    // Given
    val data = listOf(Item("1", "Test"))
    coEvery { useCase() } returns Result.success(data)
    
    // When & Then
    viewModel.state.test {
        // Initial
        assertThat(awaitItem()).isEqualTo(UiState())
        
        viewModel.load()
        
        // Loading
        assertThat(awaitItem().isLoading).isTrue()
        
        // Success
        val success = awaitItem()
        assertThat(success.isLoading).isFalse()
        assertThat(success.items).isEqualTo(data)
        
        cancelAndIgnoreRemainingEvents()
    }
}
```

### UseCase Validation Test
```kotlin
@Test
fun `when email invalid then returns validation error`() = runTest {
    // Given
    val input = Input(email = "invalid")
    
    // When
    val result = useCase(input)
    
    // Then
    assertThat(result.isFailure).isTrue()
    val error = result.exceptionOrNull() as ValidationError
    assertThat(error.field).isEqualTo("email")
}
```

### Skill Test
```kotlin
@Test
fun `when input valid then returns success`() = runTest {
    // Given
    val input = SkillInput(query = "test")
    coEvery { repository.search(any()) } returns listOf(Result("1"))
    
    // When
    val result = skill.execute(input, context)
    
    // Then
    assertThat(result.isSuccess).isTrue()
    assertThat(result.getOrNull()?.items).hasSize(1)
}

@Test
fun `when cancelled then throws CancellationException`() = runTest {
    // Given
    coEvery { repository.search(any()) } throws CancellationException()
    
    // When & Then
    assertThrows<CancellationException> {
        skill.execute(input, context)
    }
}
```

---

## 6. Test Naming Convention

Use backtick format with clear intent:
```kotlin
// Pattern: `when [condition] then [expected]`
@Test fun `when email is blank then returns validation error`()
@Test fun `when network fails then shows error state`()
@Test fun `when load succeeds then displays items`()

// Or: `given [setup] when [action] then [result]`
@Test fun `given cached data when network fails then shows cached`()
```

---

## 7. ❌ Anti-Patterns

```kotlin
// ❌ WRONG: Vague test name
@Test fun `test load`()

// ❌ WRONG: No assertions
@Test fun `test something`() = runTest {
    viewModel.load()
    // Nothing verified!
}

// ❌ WRONG: Only verify, no state check
@Test fun `test call`() = runTest {
    viewModel.load()
    coVerify { useCase() }  // Missing state assertions!
}

// ❌ WRONG: Thread.sleep instead of runTest
Thread.sleep(1000)  // Never use!

// ❌ WRONG: Not resetting main dispatcher
// Missing @AfterEach with Dispatchers.resetMain()
```

---

## 8. Quick Coverage Checklist

For each component, test:
- [ ] **Happy path** (success scenario)
- [ ] **Error scenarios** (network, validation, etc.)
- [ ] **Edge cases** (empty, null, boundary values)
- [ ] **State transitions** (initial → loading → success/error)
- [ ] **Cancellation** (CancellationException propagated)

---

**Reference**: `skills/guides/11-testing.md` for full patterns
