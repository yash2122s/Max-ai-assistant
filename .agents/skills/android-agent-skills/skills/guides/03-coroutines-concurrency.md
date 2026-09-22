---
name: Coroutines & Concurrency
description: Guidelines for using Kotlin Coroutines and Flow effectively and safely.
compliance_level: MANDATORY
tags: [coroutines, flow, concurrency, dispatchers, structured-concurrency]
version: 2.2.0
---

# Coroutines & Concurrency

## Context
Coroutines are the foundation of asynchronous programming in Android. Improper usage can lead to memory leaks, crashes, and poor performance. This guide mandates patterns for safety and testability.

**Related Guides:**
- [07-state-management.md](./07-state-management.md) - StateFlow/SharedFlow patterns
- [10-error-handling.md](./10-error-handling.md) - Error handling in coroutines
- [23-memory-performance.md](./23-memory-performance.md) - Memory leak prevention

**Code Templates:**
- [CoroutineDispatchers.kt](../templates/util/CoroutineDispatchers.kt) - Dispatcher utilities
- [FlowExtensions.kt](../templates/util/FlowExtensions.kt) - Flow extension functions
- [CancellationUtils.kt](../templates/util/CancellationUtils.kt) - Cancellation handling

---

## 🎯 AI Quick Reference

```
DISPATCHERS:
• Main → UI updates (Compose state)
• Main.immediate → Immediate UI (no post)
• IO → Network, database, file I/O
• Default → CPU-intensive (sorting, parsing)

SCOPES:
• viewModelScope → ViewModel operations
• lifecycleScope → Fragment/Activity UI
• ApplicationScope → Survives config changes

NEVER:
• GlobalScope (leaks, not testable)
• Hardcode Dispatchers (inject them!)
• Forget to re-throw CancellationException
```

---

## 1. Dispatcher Guidelines

### When to Use Which Dispatcher?

| Operation Type | Dispatcher | Examples | Why? |
|----------------|------------|----------|------|
| **Network calls** | `Dispatchers.IO` | Retrofit, OkHttp, HttpClient | Optimized for blocking I/O, thread pool up to 64 threads |
| **Database operations** | `Dispatchers.IO` | Room, SQLite, Realm | Same as network - blocking I/O operations |
| **File I/O** | `Dispatchers.IO` | Read/write files, SharedPreferences | Blocking file operations |
| **Disk operations** | `Dispatchers.IO` | Cache read/write, image save | Any disk-based I/O |
| **JSON parsing** | `Dispatchers.Default` | Kotlinx Serialization, Gson | CPU-intensive, not I/O |
| **Image processing** | `Dispatchers.Default` | Resize, filter, compress | CPU-bound computation |
| **Sorting/Filtering** | `Dispatchers.Default` | Large list operations | CPU-intensive algorithms |
| **Encryption** | `Dispatchers.Default` | Hash, encrypt, decrypt | CPU-bound cryptography |
| **UI updates** | `Dispatchers.Main` | StateFlow.update, MutableState | Must run on main thread |
| **Immediate UI** | `Dispatchers.Main.immediate` | Critical UI updates | Skip dispatcher queue if already on Main |

### Common Mistakes - Dispatcher Misuse

```kotlin
// ❌ WRONG: Using IO for CPU work
suspend fun parseJson(json: String) = withContext(Dispatchers.IO) {
    Json.decodeFromString<Data>(json) // ❌ This is CPU work, not I/O!
}

// ✅ CORRECT: Use Default for CPU work
suspend fun parseJson(json: String) = withContext(Dispatchers.Default) {
    Json.decodeFromString<Data>(json) // ✅ CPU-intensive parsing
}

// ❌ WRONG: Using Default for network
suspend fun fetchUser() = withContext(Dispatchers.Default) {
    api.getUser() // ❌ Network I/O, not CPU!
}

// ✅ CORRECT: Use IO for network
suspend fun fetchUser() = withContext(Dispatchers.IO) {
    api.getUser() // ✅ Network blocking I/O
}
```

### Decision Flowchart

```
Is it a suspend function?
├─ YES → Does it block a thread?
│         ├─ YES → Is it I/O (network/disk/file)?
│         │        ├─ YES → Use Dispatchers.IO
│         │        └─ NO (CPU work) → Use Dispatchers.Default
│         └─ NO (non-blocking) → No withContext needed
└─ NO → Is it UI update?
          ├─ YES → Ensure running on Dispatchers.Main
          └─ NO → Use appropriate dispatcher from table above
```

### Dispatcher Injection Pattern

```kotlin
// ════════════════════════════════════════════════════════════
// DI Module - Define dispatcher qualifiers
// ════════════════════════════════════════════════════════════

// Qualifiers.kt
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

// DispatchersModule.kt (Hilt)
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}

// DispatchersModule.kt (Koin)
val dispatchersModule = module {
    single(named("IoDispatcher")) { Dispatchers.IO }
    single(named("DefaultDispatcher")) { Dispatchers.Default }
    single(named("MainDispatcher")) { Dispatchers.Main }
}
```

### ✅ DO: Proper Dispatcher Usage
```kotlin
class UserRepositoryImpl @Inject constructor(
    private val api: UserApi,
    private val dao: UserDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UserRepository {
    
    // ════════════════════════════════════════════════════════
    // IO Dispatcher for network/database operations
    // ════════════════════════════════════════════════════════
    override suspend fun getUser(id: String): Result<User> = 
        withContext(ioDispatcher) {
            try {
                val response = api.getUser(id) // Network call
                dao.insert(response.toEntity()) // Database write
                Result.success(response.toDomain())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    
    // ════════════════════════════════════════════════════════
    // Flow with flowOn for background processing
    // ════════════════════════════════════════════════════════
    override fun observeUsers(): Flow<List<User>> = 
        dao.observeAll()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher) // Process on IO thread
}

class SearchProcessor @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    // ════════════════════════════════════════════════════════
    // Default Dispatcher for CPU-intensive work
    // ════════════════════════════════════════════════════════
    suspend fun processSearchResults(
        items: List<Product>,
        query: String,
    ): List<Product> = withContext(defaultDispatcher) {
        items
            .filter { it.name.contains(query, ignoreCase = true) }
            .sortedByDescending { calculateRelevanceScore(it, query) }
    }
    
    private fun calculateRelevanceScore(item: Product, query: String): Double {
        // CPU-intensive scoring algorithm
        return item.name.commonPrefixWith(query).length.toDouble() / query.length
    }
}
```

### ❌ DON'T: Hardcoded Dispatchers
```kotlin
// ❌ BAD: Hardcoded dispatcher - not testable
class BadRepository {
    suspend fun getUser(id: String): User = withContext(Dispatchers.IO) { // ❌
        api.getUser(id)
    }
}

// ❌ BAD: Wrong dispatcher for work type
class BadProcessor {
    suspend fun parseJson(json: String) = withContext(Dispatchers.IO) { // ❌ Should be Default
        Json.decodeFromString<Data>(json) // CPU work, not IO
    }
}

// ❌ BAD: Blocking Main thread
class BadViewModel : ViewModel() {
    fun loadData() {
        val data = runBlocking { repository.getData() } // ❌ Blocks UI!
        _state.value = data
    }
}
```

### withContext Best Practices

#### ✅ When to Use withContext

```kotlin
// ════════════════════════════════════════════════════════════
// RULE 1: Use at the LOWEST level (Repository/DataSource)
// ════════════════════════════════════════════════════════════

class UserRepositoryImpl @Inject constructor(
    private val api: UserApi,
    private val dao: UserDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UserRepository {
    
    // ✅ GOOD: withContext at repository level
    override suspend fun getUser(id: String): Result<User> = 
        withContext(ioDispatcher) {
            try {
                val response = api.getUser(id)
                dao.insert(response.toEntity())
                Result.success(response.toDomain())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

// ════════════════════════════════════════════════════════════
// RULE 2: Don't nest withContext unnecessarily
// ════════════════════════════════════════════════════════════

// ❌ BAD: Redundant nested withContext
suspend fun processData() = withContext(Dispatchers.IO) {
    val data = fetchData() // Already switches to IO inside
    withContext(Dispatchers.Default) { // ❌ Unnecessary context switch
        transform(data)
    }
}

// ✅ GOOD: Each layer uses its own context once
suspend fun processData(): Result<Data> {
    val data = fetchData() // Handles IO dispatcher internally
    return transformData(data) // Handles Default dispatcher internally
}

private suspend fun fetchData() = withContext(Dispatchers.IO) { /* ... */ }
private suspend fun transformData(data: Data) = withContext(Dispatchers.Default) { /* ... */ }

// ════════════════════════════════════════════════════════════
// RULE 3: Use flowOn instead of withContext for Flows
// ════════════════════════════════════════════════════════════

// ❌ BAD: Using withContext with Flow
fun observeUsers(): Flow<List<User>> = flow {
    withContext(Dispatchers.IO) { // ❌ Don't do this
        dao.observeAll().collect { emit(it) }
    }
}

// ✅ GOOD: Use flowOn
fun observeUsers(): Flow<List<User>> = 
    dao.observeAll()
        .map { it.map { entity -> entity.toDomain() } }
        .flowOn(Dispatchers.IO) // ✅ Correct way
```

#### ❌ When NOT to Use withContext

```kotlin
// ════════════════════════════════════════════════════════════
// DON'T use withContext in ViewModel - Use at lower layers
// ════════════════════════════════════════════════════════════

// ❌ BAD: withContext in ViewModel
class BadViewModel : ViewModel() {
    fun loadUser(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { // ❌ Don't do this here
                val user = repository.getUser(id)
                _state.value = user
            }
        }
    }
}

// ✅ GOOD: Repository handles dispatcher
class GoodViewModel : ViewModel() {
    fun loadUser(id: String) {
        viewModelScope.launch {
            val result = repository.getUser(id) // Repository uses withContext internally
            result.onSuccess { user ->
                _state.update { it.copy(user = user) }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// DON'T use withContext for already-dispatched APIs
// ════════════════════════════════════════════════════════════

// ❌ BAD: Redundant dispatcher - Retrofit/Room already handle this
suspend fun getUser(id: String) = withContext(Dispatchers.IO) { // ❌ Unnecessary
    api.getUser(id) // Retrofit already runs on background thread
}

// ✅ GOOD: Just call it - library handles threading
suspend fun getUser(id: String) = api.getUser(id) // ✅ Retrofit handles it

// ⚠️ EXCEPTION: Wrap if you need error handling or multiple calls
suspend fun getUserWithCache(id: String) = withContext(Dispatchers.IO) { // ✅ OK - orchestrating multiple calls
    try {
        val user = api.getUser(id)
        cache.save(user)
        user
    } catch (e: Exception) {
        cache.get(id) ?: throw e
    }
}
```

#### Performance Impact

```kotlin
// ════════════════════════════════════════════════════════════
// Each withContext has overhead - avoid excessive switching
// ════════════════════════════════════════════════════════════

// ❌ BAD: Too many context switches (slow!)
suspend fun processList(items: List<Item>): List<Result> {
    return items.map { item ->
        withContext(Dispatchers.Default) { // ❌ Switches for EACH item!
            processItem(item)
        }
    }
}

// ✅ GOOD: Single context switch for batch
suspend fun processList(items: List<Item>): List<Result> {
    return withContext(Dispatchers.Default) { // ✅ Switch once
        items.map { item ->
            processItem(item) // All items processed in same context
        }
    }
}

// ✅ BETTER: Parallel processing for large lists
suspend fun processList(items: List<Item>): List<Result> {
    return withContext(Dispatchers.Default) {
        items.chunked(100).flatMap { chunk -> // Process in chunks
            chunk.map { item ->
                async { processItem(item) } // Parallel within chunk
            }.awaitAll()
        }
    }
}
```

#### Summary: withContext Guidelines

| Scenario | Use withContext? | Reason |
|----------|-----------------|--------|
| Repository network call | ✅ YES | Wrap network + database operations |
| ViewModel logic | ❌ NO | Let lower layers handle it |
| UseCase composition | ⚠️ MAYBE | Only if doing CPU work (parsing, etc.) |
| Flow operations | ❌ NO | Use `flowOn()` instead |
| Single Retrofit/Room call | ❌ NO | Library handles threading |
| Multiple I/O operations | ✅ YES | Batch them in one context |
| Loop with many items | ✅ YES | But switch ONCE, not per item |
| Already on correct thread | ❌ NO | Unnecessary overhead |

**Golden Rule:** Use `withContext` at the **lowest possible level** (Repository/DataSource), not in ViewModel/UseCase.

---

## 2. Structured Concurrency

### Scope Hierarchy
```
Application Scope (Process lifetime)
    └── ViewModelScope (ViewModel lifetime)
            └── viewModelScope.launch { }
                    └── Child coroutines
    └── LifecycleScope (Activity/Fragment lifetime)
            └── lifecycleScope.launch { }
                    └── launchWhenStarted { }
                    └── launchWhenResumed { }
```

### ✅ DO: Proper Scope Usage
```kotlin
// ════════════════════════════════════════════════════════════
// ViewModel - Use viewModelScope
// ════════════════════════════════════════════════════════════
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
) : ViewModel() {
    
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    
    fun loadProfile(userId: String) {
        viewModelScope.launch { // ✅ Auto-cancelled when ViewModel cleared
            _state.update { it.copy(isLoading = true) }
            
            getUserUseCase(userId)
                .onSuccess { user ->
                    _state.update { it.copy(user = user, isLoading = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message, isLoading = false) }
                }
        }
    }
    
    // Parallel loading with structured concurrency
    fun loadDashboard(userId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            // Parallel execution - both cancelled if one fails
            coroutineScope {
                val userDeferred = async { getUserUseCase(userId) }
                val ordersDeferred = async { getOrdersUseCase(userId) }
                
                val user = userDeferred.await()
                val orders = ordersDeferred.await()
                
                _state.update { 
                    it.copy(
                        user = user.getOrNull(),
                        orders = orders.getOrNull(),
                        isLoading = false,
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// Application Scope - For work that survives configuration changes
// ════════════════════════════════════════════════════════════
@Singleton
class UploadManager @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val uploadRepository: UploadRepository,
) {
    fun uploadFile(file: File): Job = scope.launch {
        // Continues even if Activity is destroyed
        uploadRepository.upload(file)
    }
}

// Provide ApplicationScope
@Module
@InstallIn(SingletonComponent::class)
object CoroutineScopeModule {
    
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
```

### ❌ DON'T: Unstructured Concurrency
```kotlin
// ❌ BAD: GlobalScope - never cancelled, causes leaks
class BadRepository {
    fun saveData(data: Data) {
        GlobalScope.launch { // ❌ NEVER use GlobalScope
            database.save(data)
        }
    }
}

// ❌ BAD: Creating own scope without proper management
class BadViewModel : ViewModel() {
    private val scope = CoroutineScope(Dispatchers.Main) // ❌ Not cancelled
    
    fun loadData() {
        scope.launch { /* leaks when ViewModel cleared */ }
    }
    
    // Missing: override fun onCleared() { scope.cancel() }
}

// ❌ BAD: Fire and forget in ViewModel
class BadViewModel : ViewModel() {
    fun logEvent(event: String) {
        CoroutineScope(Dispatchers.IO).launch { // ❌ Leaks
            analytics.log(event)
        }
    }
}
```

---

## 3. Flow Best Practices

### StateFlow vs SharedFlow vs Channel

| Type | Use Case | Initial Value | Replay |
|------|----------|---------------|--------|
| **StateFlow** | UI State | Required | Last value |
| **SharedFlow** | Events (multiple collectors) | None | Configurable |
| **Channel** | One-time events (single collector) | None | None |

### ✅ DO: Proper Flow Usage
```kotlin
@HiltViewModel
class ProductViewModel @Inject constructor(
    private val productRepository: ProductRepository,
) : ViewModel() {
    
    // ════════════════════════════════════════════════════════
    // StateFlow for UI State
    // ════════════════════════════════════════════════════════
    private val _state = MutableStateFlow(ProductUiState())
    val state: StateFlow<ProductUiState> = _state.asStateFlow()
    
    // ════════════════════════════════════════════════════════
    // Channel for one-time events (RECOMMENDED)
    // ════════════════════════════════════════════════════════
    private val _events = Channel<ProductEvent>(Channel.BUFFERED)
    val events: Flow<ProductEvent> = _events.receiveAsFlow()
    
    // ════════════════════════════════════════════════════════
    // Flow transformations
    // ════════════════════════════════════════════════════════
    private val searchQuery = MutableStateFlow("")
    
    val searchResults: StateFlow<List<Product>> = searchQuery
        .debounce(300) // Wait for typing to stop
        .filter { it.length >= 2 } // Min query length
        .distinctUntilChanged() // Skip duplicates
        .flatMapLatest { query ->
            productRepository.search(query)
                .catch { emit(emptyList()) } // Handle errors gracefully
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
    
    // ════════════════════════════════════════════════════════
    // Combining multiple flows
    // ════════════════════════════════════════════════════════
    val dashboardState: StateFlow<DashboardState> = combine(
        productRepository.observeProducts(),
        productRepository.observeCategories(),
        productRepository.observeFavorites(),
    ) { products, categories, favorites ->
        DashboardState(
            products = products,
            categories = categories,
            favoriteIds = favorites.map { it.id }.toSet(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardState(),
    )
    
    // Send one-time event
    fun onProductClick(productId: String) {
        viewModelScope.launch {
            _events.send(ProductEvent.NavigateToDetail(productId))
        }
    }
}

// ════════════════════════════════════════════════════════════
// Compose - Collect with lifecycle awareness
// ════════════════════════════════════════════════════════════
@Composable
fun ProductRoute(
    viewModel: ProductViewModel = hiltViewModel(),
) {
    // ✅ Stops collecting when app goes to background
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    // ✅ Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ProductEvent.NavigateToDetail -> navigateToDetail(event.productId)
                is ProductEvent.ShowMessage -> showSnackbar(event.message)
            }
        }
    }
    
    ProductScreen(
        state = state,
        onProductClick = viewModel::onProductClick,
    )
}
```

### ❌ DON'T: Flow Anti-Patterns
```kotlin
// ❌ BAD: collectAsState without lifecycle (leaks in background)
@Composable
fun BadScreen(viewModel: MyViewModel) {
    val state by viewModel.state.collectAsState() // ❌ Use collectAsStateWithLifecycle
}

// ❌ BAD: SharedFlow for events (can lose events)
class BadViewModel : ViewModel() {
    private val _events = MutableSharedFlow<Event>() // ❌ Can lose if no collector
    val events = _events.asSharedFlow()
}

// ❌ BAD: Collecting flow in launch without cancellation
class BadViewModel : ViewModel() {
    init {
        viewModelScope.launch {
            repository.observeData().collect { data ->
                // This never stops even if not needed anymore
            }
        }
    }
}

// ❌ BAD: Creating new flow on each recomposition
@Composable
fun BadScreen(viewModel: MyViewModel) {
    val data by remember {
        viewModel.getData() // ❌ Creates new flow each time
    }.collectAsStateWithLifecycle(initial = emptyList())
}
```

---

## 4. Exception Handling

### CancellationException Rule
**ALWAYS re-throw `CancellationException`** - it's how structured concurrency works!

```kotlin
// ✅ DO: Proper exception handling
suspend fun fetchData(): Result<Data> {
    return try {
        val data = api.getData()
        Result.success(data)
    } catch (e: CancellationException) {
        throw e // ✅ ALWAYS re-throw
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// ✅ DO: Using runCatching with proper handling
suspend fun safeFetch(): Result<Data> {
    return runCatching {
        api.getData()
    }.onFailure { error ->
        if (error is CancellationException) throw error // ✅ Re-throw
        logger.error("Fetch failed", error)
    }
}

// ✅ DO: ensureActive() for long-running loops
suspend fun processLargeList(items: List<Item>) {
    items.forEach { item ->
        ensureActive() // ✅ Check if still active
        processItem(item)
    }
}

// ✅ DO: withTimeout for bounded operations
suspend fun fetchWithTimeout(): Data {
    return withTimeout(30.seconds) {
        api.getData()
    }
}
```

### ❌ DON'T: Swallow CancellationException
```kotlin
// ❌ BAD: Catching all exceptions
suspend fun badFetch(): Data? {
    return try {
        api.getData()
    } catch (e: Exception) { // ❌ Catches CancellationException!
        null
    }
}

// ❌ BAD: Using runCatching without handling CancellationException
suspend fun badRunCatching(): Result<Data> {
    return runCatching { // ❌ Catches CancellationException
        api.getData()
    }
}
```

---

## 5. Testing Coroutines

### Test Setup
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModelTest {
    
    // ════════════════════════════════════════════════════════
    // Test dispatcher - controls virtual time
    // ════════════════════════════════════════════════════════
    private val testDispatcher = StandardTestDispatcher()
    
    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }
    
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }
    
    // ════════════════════════════════════════════════════════
    // Test with runTest
    // ════════════════════════════════════════════════════════
    @Test
    fun `loadUser should update state on success`() = runTest {
        // Given
        val user = User(id = "1", name = "John")
        coEvery { getUserUseCase("1") } returns Result.success(user)
        
        // When
        viewModel.loadUser("1")
        advanceUntilIdle() // Process all coroutines
        
        // Then
        assertThat(viewModel.state.value.user).isEqualTo(user)
        assertThat(viewModel.state.value.isLoading).isFalse()
    }
    
    // ════════════════════════════════════════════════════════
    // Test Flows with Turbine
    // ════════════════════════════════════════════════════════
    @Test
    fun `state flow should emit loading then success`() = runTest {
        val user = User(id = "1", name = "John")
        coEvery { getUserUseCase("1") } coAnswers {
            delay(100) // Simulate network delay
            Result.success(user)
        }
        
        viewModel.state.test {
            // Initial state
            assertThat(awaitItem().isLoading).isFalse()
            
            // Trigger load
            viewModel.loadUser("1")
            
            // Loading state
            assertThat(awaitItem().isLoading).isTrue()
            
            // Success state
            val successState = awaitItem()
            assertThat(successState.isLoading).isFalse()
            assertThat(successState.user).isEqualTo(user)
            
            cancelAndIgnoreRemainingEvents()
        }
    }
    
    // ════════════════════════════════════════════════════════
    // Test timeout behavior
    // ════════════════════════════════════════════════════════
    @Test
    fun `should timeout after 30 seconds`() = runTest {
        coEvery { api.getData() } coAnswers {
            delay(60.seconds) // Never completes in time
            Data()
        }
        
        val result = runCatching {
            withTimeout(30.seconds) {
                api.getData()
            }
        }
        
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(TimeoutCancellationException::class.java)
    }
}
```

---

## 6. Verification Checklist

### Dispatchers
- [ ] All dispatchers injected via constructor
- [ ] IO dispatcher for network/database
- [ ] Default dispatcher for CPU work
- [ ] No hardcoded `Dispatchers.X` in classes

### Scopes
- [ ] `viewModelScope` for ViewModel operations
- [ ] `lifecycleScope` for Fragment/Activity
- [ ] No `GlobalScope` usage
- [ ] No unmanaged `CoroutineScope`

### Flows
- [ ] `collectAsStateWithLifecycle()` in Compose
- [ ] Channel for one-time events (not SharedFlow)
- [ ] `stateIn` with `WhileSubscribed(5000)`
- [ ] `flowOn` for background processing

### Exception Handling
- [ ] `CancellationException` always re-thrown
- [ ] `runCatching` with cancellation check
- [ ] `withTimeout` for bounded operations
- [ ] `ensureActive()` in long loops
