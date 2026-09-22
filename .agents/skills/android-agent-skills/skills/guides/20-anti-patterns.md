---
name: Anti-Patterns
description: Common mistakes and bad practices to avoid with detection rules.
compliance_level: MANDATORY
tags: [anti-patterns, mistakes, bad-practices, architecture, detection]
version: 3.0.0
---

# Anti-Patterns

## Context
Identifying and avoiding common pitfalls ensures long-term codebase health. This guide provides detection rules for AI agents to automatically identify anti-patterns.

## Detection Rules

### 1. Architecture Anti-Patterns

#### God ViewModel
**Detection:**
```regex
class \w+ViewModel.*\{[\s\S]{1500,}\}
```
**Indicators:**
- ViewModel file > 500 lines
- More than 10 public functions
- Manages multiple unrelated features

**Example:**
```kotlin
// ❌ God ViewModel (too many responsibilities)
class MainViewModel : ViewModel() {
    // User management
    fun loadUser() { }
    fun updateProfile() { }
    
    // Shopping cart
    fun addToCart() { }
    fun checkout() { }
    
    // Settings
    fun updateTheme() { }
    fun toggleNotifications() { }
    
    // ... 20+ more functions
}

// ✅ Split into feature-specific ViewModels
class ProfileViewModel : ViewModel() {
    fun loadUser() { }
    fun updateProfile() { }
}

class CartViewModel : ViewModel() {
    fun addToCart() { }
    fun checkout() { }
}
```

#### Business Logic in UI
**Detection:**
```regex
@Composable[\s\S]*?if\s*\([^)]*\.(calculate|process|validate|compute)
```
**Indicators:**
- Calculations in Composable functions
- Data transformation in UI layer
- Validation logic in screens

**Example:**
```kotlin
// ❌ Business logic in Composable
@Composable
fun PriceDisplay(items: List<Item>) {
    val total = items.sumOf { it.price * (1 - it.discount) }  // ❌
    Text("Total: $$total")
}

// ✅ Business logic in ViewModel/UseCase
@Composable
fun PriceDisplay(total: Double) {  // ✅ Receives processed data
    Text("Total: $$total")
}

class CartViewModel : ViewModel() {
    val total: StateFlow<Double> = cartRepository.items
        .map { items -> calculateTotal(items) }  // ✅ Logic in ViewModel
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)
}
```

#### Repository in ViewModel
**Detection:**
```regex
class \w+ViewModel.*constructor\([^)]*Repository[^)]*\)
```
**Indicators:**
- Direct Repository injection in ViewModel
- ViewModel calling Repository methods directly
- No UseCase layer

**Example:**
```kotlin
// ❌ Direct Repository usage
class UserViewModel(
    private val userRepository: UserRepository  // ❌
) : ViewModel() {
    fun loadUser() {
        viewModelScope.launch {
            userRepository.getUser()  // ❌ Business logic leak
        }
    }
}

// ✅ UseCase layer
class UserViewModel(
    private val getUserUseCase: GetUserUseCase  // ✅
) : ViewModel() {
    fun loadUser() {
        viewModelScope.launch {
            getUserUseCase()  // ✅ Clear separation
        }
    }
}
```

### 2. Coroutines Anti-Patterns

#### GlobalScope Usage
**Detection:**
```regex
GlobalScope\.(launch|async)
```
**Indicators:**
- `GlobalScope.launch` or `GlobalScope.async`
- Coroutines not tied to lifecycle

**Example:**
```kotlin
// ❌ GlobalScope (memory leak)
fun fetchData() {
    GlobalScope.launch {  // ❌ Never cancelled
        repository.getData()
    }
}

// ✅ Use appropriate scope
class MyViewModel : ViewModel() {
    fun fetchData() {
        viewModelScope.launch {  // ✅ Cancelled when ViewModel cleared
            repository.getData()
        }
    }
}

@Composable
fun MyScreen() {
    val scope = rememberCoroutineScope()
    Button(onClick = {
        scope.launch {  // ✅ Tied to composition lifecycle
            doWork()
        }
    }) { Text("Click") }
}
```

#### runBlocking in Production
**Detection:**
```regex
runBlocking\s*\{
```
**Indicators:**
- `runBlocking` in Activity/Fragment/ViewModel
- Blocking main thread
- Used outside of test code

**Example:**
```kotlin
// ❌ Blocks main thread
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val user = runBlocking {  // ❌ UI freezes
        repository.getUser()
    }
}

// ✅ Use suspend functions or collect Flow
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch {  // ✅ Non-blocking
        val user = repository.getUser()
    }
}
```

#### Swallowing CancellationException
**Detection:**
```regex
catch\s*\([^)]*Exception[^)]*\)\s*\{[^}]*\}
```
**Indicators:**
- Catching broad Exception without rethrowing CancellationException
- Empty catch blocks
- Logging but not rethrowing

**Example:**
```kotlin
// ❌ Swallows cancellation
viewModelScope.launch {
    try {
        doWork()
    } catch (e: Exception) {  // ❌ Catches CancellationException too
        Log.e("Error", e.message)
    }
}

// ✅ Rethrow CancellationException
viewModelScope.launch {
    try {
        doWork()
    } catch (e: CancellationException) {
        throw e  // ✅ Always rethrow
    } catch (e: Exception) {
        Log.e("Error", e.message)
    }
}
```

### 3. State Management Anti-Patterns

#### Exposing MutableStateFlow
**Detection:**
```regex
val \w+:\s*MutableStateFlow
```
**Indicators:**
- Public `MutableStateFlow` property
- No private `_state` with public `state`
- Direct mutation from UI

**Example:**
```kotlin
// ❌ Mutable state exposed
class UserViewModel : ViewModel() {
    val state = MutableStateFlow(UiState())  // ❌ Can be mutated from anywhere
}

// ✅ Expose read-only StateFlow
class UserViewModel : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()  // ✅ Read-only
}
```

#### Using LiveData in New Code
**Detection:**
```regex
(MutableLiveData|LiveData)<
```
**Indicators:**
- New ViewModels using LiveData
- Not using StateFlow/Flow

**Example:**
```kotlin
// ❌ LiveData in new code
class UserViewModel : ViewModel() {
    private val _user = MutableLiveData<User>()
    val user: LiveData<User> = _user  // ❌ Old pattern
}

// ✅ Use StateFlow
class UserViewModel : ViewModel() {
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()  // ✅ Modern Kotlin
}
```

#### State in Composable
**Detection:**
```regex
@Composable[\s\S]*?var \w+\s*=\s*remember
```
**Indicators:**
- `var` with `remember` (should be `by remember`)
- Mutable state in Composable (should be in ViewModel)
- Direct state mutation in UI

**Example:**
```kotlin
// ❌ State in Composable
@Composable
fun UserScreen() {
    var username by remember { mutableStateOf("") }  // ❌ State in UI
    var isLoading by remember { mutableStateOf(false) }
    
    Button(onClick = {
        isLoading = true  // ❌ Logic in UI
        // ... API call
    }) { Text("Submit") }
}

// ✅ State in ViewModel
@Composable
fun UserScreen(viewModel: UserViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    UserContent(
        username = state.username,
        isLoading = state.isLoading,
        onUsernameChange = viewModel::onUsernameChange,  // ✅ Events to ViewModel
        onSubmit = viewModel::onSubmit
    )
}
```

### 4. Compose Anti-Patterns

#### Non-Stable Parameters
**Detection:**
```regex
@Composable\s+fun \w+\([^)]*data class[^)]*\)
```
**Indicators:**
- Data classes without `@Immutable`
- Lists without `@Immutable` wrapper
- Mutable parameters

**Example:**
```kotlin
// ❌ Non-stable parameters (unnecessary recomposition)
data class User(val name: String)  // ❌ Not @Immutable

@Composable
fun UserCard(user: User) {  // ❌ Unstable parameter
    Text(user.name)
}

// ✅ Stable parameters
@Immutable
data class User(val name: String)  // ✅

@Composable
fun UserCard(user: User) {  // ✅ Stable
    Text(user.name)
}
```

#### Side Effects Without Keys
**Detection:**
```regex
LaunchedEffect\(Unit\)|LaunchedEffect\(true\)
```
**Indicators:**
- `LaunchedEffect(Unit)` for non-startup effects
- `LaunchedEffect(true)` 
- Missing dependency keys

**Example:**
```kotlin
// ❌ Wrong key usage
@Composable
fun UserScreen(userId: String) {
    LaunchedEffect(Unit) {  // ❌ Never reloads when userId changes
        viewModel.loadUser(userId)
    }
}

// ✅ Proper key
@Composable
fun UserScreen(userId: String) {
    LaunchedEffect(userId) {  // ✅ Reloads when userId changes
        viewModel.loadUser(userId)
    }
}
```

#### Unstable Collections
**Detection:**
```regex
@Composable.*\([^)]*List<[^)]*\)
```
**Indicators:**
- Passing `List` directly (unstable)
- Not using `ImmutableList` or `@Immutable` wrapper

**Example:**
```kotlin
// ❌ Unstable collection
@Composable
fun UserList(users: List<User>) {  // ❌ List is unstable
    LazyColumn {
        items(users) { user ->
            UserCard(user)
        }
    }
}

// ✅ Stable collection
import kotlinx.collections.immutable.ImmutableList

@Composable
fun UserList(users: ImmutableList<User>) {  // ✅ Stable
    LazyColumn {
        items(users) { user ->
            UserCard(user)
        }
    }
}

// Or wrap in @Immutable
@Immutable
data class UserList(val users: List<User>)
```

### 5. Dependency Injection Anti-Patterns

#### Service Locator Pattern
**Detection:**
```regex
object \w+\s*\{[\s\S]*?get\w+\(\)
```
**Indicators:**
- Singleton objects providing dependencies
- Manual dependency retrieval
- Not using DI framework

**Example:**
```kotlin
// ❌ Service Locator
object ServiceLocator {
    fun getUserRepository(): UserRepository = UserRepositoryImpl()
    fun getNetworkClient(): NetworkClient = NetworkClientImpl()
}

class UserViewModel : ViewModel() {
    private val repository = ServiceLocator.getUserRepository()  // ❌
}

// ✅ Dependency Injection
@HiltViewModel
class UserViewModel @Inject constructor(
    private val repository: UserRepository  // ✅ Injected
) : ViewModel()
```

#### Constructor with Too Many Parameters
**Detection:**
```regex
constructor\([^)]{200,}\)
```
**Indicators:**
- More than 5 constructor parameters
- Many repository injections
- No facade pattern

**Example:**
```kotlin
// ❌ Too many dependencies
class UserViewModel(
    private val userRepo: UserRepository,
    private val postRepo: PostRepository,
    private val commentRepo: CommentRepository,
    private val likeRepo: LikeRepository,
    private val shareRepo: ShareRepository,
    private val analyticsRepo: AnalyticsRepository,
    // ... more
) : ViewModel()

// ✅ Use UseCases or Facade
class UserViewModel(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserUseCase
) : ViewModel()
```

### 6. Error Handling Anti-Patterns

#### Silent Failures
**Detection:**
```regex
catch\s*\([^)]*\)\s*\{\s*\}
```
**Indicators:**
- Empty catch blocks
- No logging
- No error propagation

**Example:**
```kotlin
// ❌ Silent failure
try {
    repository.saveUser(user)
} catch (e: Exception) {  // ❌ Error ignored
}

// ✅ Handle or propagate
try {
    repository.saveUser(user)
} catch (e: Exception) {
    Log.e("UserViewModel", "Save failed", e)
    _state.update { it.copy(error = e.message) }
}
```

#### Throwing from Repository
**Detection:**
```regex
class \w+Repository[\s\S]*?throw \w+Exception
```
**Indicators:**
- Repository throwing exceptions
- Not using Result<T>
- Forcing try-catch at call site

**Example:**
```kotlin
// ❌ Throwing exceptions
class UserRepository {
    suspend fun getUser(id: String): User {
        throw NetworkException()  // ❌
    }
}

// ✅ Return Result
class UserRepository {
    suspend fun getUser(id: String): Result<User> {
        return try {
            Result.success(api.getUser(id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### 7. Memory Leak Anti-Patterns

#### Holding Activity Reference
**Detection:**
```regex
class \w+\s*\{[\s\S]*?private val activity: Activity
```
**Indicators:**
- Activity/Fragment stored in singleton
- Context stored in ViewModel
- Callback listeners not cleared

**Example:**
```kotlin
// ❌ Activity leak
object Analytics {
    private var activity: Activity? = null  // ❌ Leak
    
    fun init(activity: Activity) {
        this.activity = activity
    }
}

// ✅ Use Application Context
object Analytics {
    private lateinit var appContext: Context  // ✅ Application context
    
    fun init(context: Context) {
        this.appContext = context.applicationContext
    }
}
```

## AI Agent Detection Strategy

### Automated Analysis
```kotlin
// Pseudo-code for AI agent analysis
fun analyzeCodeForAntiPatterns(file: KtFile): List<AntiPattern> {
    val patterns = mutableListOf<AntiPattern>()
    
    // Check ViewModel size
    file.classes
        .filter { it.name.endsWith("ViewModel") }
        .forEach { viewModel ->
            if (viewModel.lines > 500) {
                patterns.add(GodViewModel(viewModel))
            }
            
            // Check for Repository injection
            viewModel.constructorParameters
                .filter { it.type.contains("Repository") }
                .forEach { param ->
                    patterns.add(RepositoryInViewModel(param))
                }
        }
    
    // Check for GlobalScope
    file.text.findAll("""GlobalScope\.(launch|async)""")
        .forEach { match ->
            patterns.add(GlobalScopeUsage(match))
        }
    
    // Check exposed MutableStateFlow
    file.properties
        .filter { it.visibility == PUBLIC && it.type == "MutableStateFlow" }
        .forEach { prop ->
            patterns.add(ExposedMutableState(prop))
        }
    
    return patterns
}
```

### Severity Levels
| Level | Description | Action |
|-------|-------------|--------|
| 🔴 CRITICAL | Memory leaks, crashes | Block merge |
| 🟠 HIGH | Performance issues | Require fix |
| 🟡 MEDIUM | Maintainability issues | Suggest fix |
| 🟢 LOW | Style issues | Optional fix |

## Verification Checklist
- [ ] No God ViewModels (> 500 lines)
- [ ] No business logic in Composables
- [ ] No Repository in ViewModel constructor
- [ ] No `GlobalScope` usage
- [ ] No `runBlocking` in production code
- [ ] No exposed `MutableStateFlow`
- [ ] No `LiveData` in new code
- [ ] All data classes marked `@Immutable`
- [ ] No empty catch blocks
- [ ] No Activity references in singletons

## Related Guides
- [01-architecture.md](./01-architecture.md) - Clean Architecture
- [07-state-management.md](./07-state-management.md) - State patterns
- [25-performance-benchmarks.md](./25-performance-benchmarks.md) - Performance metrics


