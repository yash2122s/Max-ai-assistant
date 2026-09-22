---
name: Refactoring Patterns
description: Step-by-step refactoring guides for common code transformations
compliance_level: RECOMMENDED
tags: [refactoring, migration, transformation, modernization]
version: 2.2.0
last_updated: 2026-01-24
---

# Refactoring Patterns

## Context
Systematic refactoring patterns help AI agents and developers modernize codebases safely. Each pattern includes step-by-step instructions, before/after examples, and automated migration strategies.

## Refactoring Categories

### 1. State Management Migration
### 2. Architecture Modernization
### 3. Performance Optimization
### 4. Dependency Injection Migration
### 5. Testing Enhancement
### 6. Compose Migration
### 7. Error Handling Improvement

---

## 1. State Management Migration

### Pattern 1.1: LiveData → StateFlow

**When to Apply:**
- Existing ViewModels using LiveData
- Want to modernize to Kotlin Flow
- Need better lifecycle handling

**Prerequisites:**
- Kotlin Coroutines dependency
- Lifecycle-runtime-ktx 2.6.0+

**Step-by-Step:**

#### Step 1: Add dependencies
```kotlin
// build.gradle.kts
dependencies {
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
```

#### Step 2: Convert ViewModel
```kotlin
// BEFORE
class UserViewModel : ViewModel() {
    private val _user = MutableLiveData<User>()
    val user: LiveData<User> = _user
    
    fun loadUser() {
        viewModelScope.launch {
            _user.value = repository.getUser()
        }
    }
}

// AFTER
class UserViewModel : ViewModel() {
    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()
    
    fun loadUser() {
        viewModelScope.launch {
            _user.value = repository.getUser()
        }
    }
}
```

#### Step 3: Update UI collection
```kotlin
// BEFORE (Activity/Fragment)
viewModel.user.observe(viewLifecycleOwner) { user ->
    updateUI(user)
}

// AFTER (Activity/Fragment)
lifecycleScope.launch {
    viewModel.user.collect { user ->
        updateUI(user)
    }
}

// AFTER (Compose)
val user by viewModel.user.collectAsStateWithLifecycle()
```

#### Step 4: Handle combinations
```kotlin
// BEFORE
val combined = MediatorLiveData<Result>().apply {
    addSource(user) { update() }
    addSource(settings) { update() }
}

// AFTER
val combined: StateFlow<Result> = combine(user, settings) { u, s ->
    Result(u, s)
}.stateIn(viewModelScope, SharingStarted.Lazily, Result())
```

**Migration Script:**
```kotlin
// Automated regex replacements
val migrations = listOf(
    "MutableLiveData" to "MutableStateFlow",
    "LiveData" to "StateFlow",
    "\\.observe\\(" to ".collectAsStateWithLifecycle(",
    "\\.value = " to ".value = ",  // Same syntax
)
```

---

### Pattern 1.2: Mutable State → StateStoreHolder

**When to Apply:**
- Multiple ViewModels with duplicate state code
- Want to reduce boilerplate
- Need consistent pattern

**Step-by-Step:**

#### Step 1: Create StateStore class
```kotlin
// Create: presentation/base/StateStore.kt
class StateStore<State, Events>(
    private val coroutineScope: CoroutineScope,
    initialState: State,
) {
    private val _events = Channel<Events>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()
    
    fun sendEvent(event: Events) {
        coroutineScope.launch { _events.send(event) }
    }
    
    /** Reducer: receives current state as receiver, returns new state */
    fun reduceState(reducer: State.() -> State) = _state.update(reducer)
}

interface StateStoreHolder<State, Events, Intents> {
    val stateStore: StateStore<State, Events>
    val events: Flow<Events> get() = stateStore.events
    val state: StateFlow<State> get() = stateStore.state
    fun reduceState(reducer: State.() -> State) = stateStore.reduceState(reducer)
    fun sendEvent(event: Events) = stateStore.sendEvent(event)
    fun processIntent(intent: Intents)
}
```

#### Step 2: (Optional) Create base ViewModel for convenience
```kotlin
abstract class MviViewModel<State, Events, Intents>(
    initialState: State,
) : ViewModel(), StateStoreHolder<State, Events, Intents> {
    
    override val stateStore = StateStore<State, Events>(viewModelScope, initialState)
}
```

#### Step 3: Migrate ViewModels
```kotlin
// BEFORE: Boilerplate in every ViewModel
class ProfileViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    fun onNameChanged(name: String) {
        _uiState.update { it.copy(name = name) }
    }
}

// AFTER: Clean with StateStoreHolder
class ProfileViewModel : ViewModel(), 
    StateStoreHolder<ProfileUiState, ProfileEvent, ProfileIntent> {
    
    override val stateStore = StateStore(viewModelScope, ProfileUiState())
    
    override fun processIntent(intent: ProfileIntent) {
        when (intent) {
            is ProfileIntent.NameChanged -> onNameChanged(intent.name)
        }
    }
    
    private fun onNameChanged(name: String) {
        reduceState { copy(name = name) }  // ✅ Clean reducer pattern
    }
}
```

**Lines Saved:** 5-7 lines per ViewModel

---

## 2. Architecture Modernization

### Pattern 2.1: Repository → UseCase Layer

**When to Apply:**
- ViewModel injecting Repository directly
- Complex business logic in ViewModel
- Want Clean Architecture

**Step-by-Step:**

#### Step 1: Create UseCase
```kotlin
// BEFORE: No UseCase
class ProfileViewModel(
    private val userRepository: UserRepository
) : ViewModel() {
    fun loadProfile(userId: String) {
        viewModelScope.launch {
            val user = userRepository.getUser(userId)
            val posts = postRepository.getPosts(userId)
            // Business logic here
        }
    }
}

// AFTER: Create domain/usecases/GetUserProfileUseCase.kt
class GetUserProfileUseCase(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
) {
    suspend operator fun invoke(userId: String): Result<UserProfile> {
        return try {
            val user = userRepository.getUser(userId).getOrThrow()
            val posts = postRepository.getPosts(userId).getOrThrow()
            Result.success(UserProfile(user, posts))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

#### Step 2: Update ViewModel
```kotlin
// AFTER: Inject UseCase + StateStoreHolder
class ProfileViewModel(
    private val getUserProfileUseCase: GetUserProfileUseCase,
) : ViewModel(), StateStoreHolder<ProfileUiState, ProfileEvent, ProfileIntent> {
    
    override val stateStore = StateStore(viewModelScope, ProfileUiState())
    
    override fun processIntent(intent: ProfileIntent) {
        when (intent) {
            is ProfileIntent.LoadProfile -> loadProfile(intent.userId)
        }
    }
    
    private fun loadProfile(userId: String) {
        viewModelScope.launch {
            getUserProfileUseCase(userId)
                .onSuccess { profile ->
                    reduceState { copy(profile = profile) }
                }
                .onFailure { error ->
                    reduceState { copy(error = error.message) }
                }
        }
    }
}
```

#### Step 3: Update DI
```kotlin
// Hilt
@Module
@InstallIn(ViewModelComponent::class)
abstract class UseCaseModule {
    @Binds
    abstract fun bindGetUserProfileUseCase(
        impl: GetUserProfileUseCase
    ): GetUserProfileUseCase
}

// Koin
val useCaseModule = module {
    factoryOf(::GetUserProfileUseCase)
}
```

**Migration Checklist:**
- [ ] Extract business logic to UseCase
- [ ] Update ViewModel to inject UseCase
- [ ] Update DI configuration
- [ ] Update tests

---

### Pattern 2.2: God ViewModel → Feature ViewModels

**When to Apply:**
- ViewModel > 500 lines
- Multiple unrelated features
- Hard to test

**Step-by-Step:**

#### Step 1: Identify features
```kotlin
// BEFORE: God ViewModel (800 lines)
class MainViewModel : ViewModel() {
    // User profile (10 functions)
    fun loadUser() { }
    fun updateProfile() { }
    
    // Shopping cart (8 functions)
    fun addToCart() { }
    fun checkout() { }
    
    // Settings (6 functions)
    fun updateTheme() { }
    fun toggleNotifications() { }
    
    // Analytics (4 functions)
    // ...
}
```

#### Step 2: Extract feature ViewModels
```kotlin
// AFTER: Separate ViewModels

// profile/ProfileViewModel.kt (150 lines)
class ProfileViewModel(
    private val getUserUseCase: GetUserUseCase,
    private val updateProfileUseCase: UpdateProfileUseCase,
) : MviViewModel<ProfileUiState, ProfileEvent>(ProfileUiState()) {
    fun loadUser() { }
    fun updateProfile() { }
}

// cart/CartViewModel.kt (200 lines)
class CartViewModel(
    private val addToCartUseCase: AddToCartUseCase,
    private val checkoutUseCase: CheckoutUseCase,
) : MviViewModel<CartUiState, CartEvent>(CartUiState()) {
    fun addToCart() { }
    fun checkout() { }
}

// settings/SettingsViewModel.kt (120 lines)
class SettingsViewModel(
    private val updateThemeUseCase: UpdateThemeUseCase,
) : MviViewModel<SettingsUiState, SettingsEvent>(SettingsUiState()) {
    fun updateTheme() { }
}
```

#### Step 3: Update navigation
```kotlin
// BEFORE: Single screen with everything
@Composable
fun MainScreen(viewModel: MainViewModel) { }

// AFTER: Separate screens
@Composable
fun ProfileRoute(viewModel: ProfileViewModel = hiltViewModel()) { }

@Composable
fun CartRoute(viewModel: CartViewModel = hiltViewModel()) { }

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) { }
```

---

## 3. Performance Optimization

### Pattern 3.1: Add @Immutable Annotations

**When to Apply:**
- Compose recomposing unnecessarily
- Data classes used in Composables
- Performance issues

**Automated Migration:**
```kotlin
// Script: add-immutable-annotations.kts
import java.io.File

fun processFile(file: File) {
    val content = file.readText()
    
    // Find data classes used in Composables
    val dataClassPattern = """data class (\w+)\(""".toRegex()
    val composablePattern = """@Composable.*fun \w+\([^)]*\b\1\b""".toRegex()
    
    dataClassPattern.findAll(content).forEach { match ->
        val className = match.groupValues[1]
        if (composablePattern.find(content)?.value?.contains(className) == true) {
            // Add @Immutable if not present
            if (!content.contains("@Immutable\ndata class $className")) {
                val updated = content.replace(
                    "data class $className",
                    "@Immutable\ndata class $className"
                )
                file.writeText("import androidx.compose.runtime.Immutable\n\n$updated")
            }
        }
    }
}

// Usage: Find all Kotlin files and process
File("src/main/kotlin").walkTopDown()
    .filter { it.extension == "kt" }
    .forEach { processFile(it) }
```

---

### Pattern 3.2: Remember Expensive Calculations

**When to Apply:**
- Heavy computation in Composables
- Filtering/mapping in UI
- Performance drops during recomposition

**Step-by-Step:**

#### Step 1: Identify expensive operations
```kotlin
// BEFORE
@Composable
fun ProductList(products: List<Product>, query: String) {
    val filtered = products.filter {  // ❌ Runs every recomposition
        it.name.contains(query, ignoreCase = true)
    }
    
    LazyColumn {
        items(filtered) { product ->
            ProductCard(product)
        }
    }
}
```

#### Step 2: Use remember with keys
```kotlin
// AFTER
@Composable
fun ProductList(products: List<Product>, query: String) {
    val filtered = remember(products, query) {  // ✅ Only when products/query change
        products.filter {
            it.name.contains(query, ignoreCase = true)
        }
    }
    
    LazyColumn {
        items(filtered, key = { it.id }) {  // ✅ Stable keys
            ProductCard(it)
        }
    }
}
```

#### Step 3: Use derivedStateOf for read optimization
```kotlin
// BETTER: Defer read with derivedStateOf
@Composable
fun ProductList(products: List<Product>, query: String) {
    val filtered by remember {
        derivedStateOf {  // ✅ Only recomputes when accessed and dependencies changed
            products.filter {
                it.name.contains(query, ignoreCase = true)
            }
        }
    }
    
    LazyColumn {
        items(filtered, key = { it.id }) {
            ProductCard(it)
        }
    }
}
```

---

## 4. Dependency Injection Migration

### Pattern 4.1: Hilt → Koin

**When to Apply:**
- Want faster compile times
- Preparing for Kotlin Multiplatform
- Prefer runtime DI

**Step-by-Step:**

#### Step 1: Update dependencies
```kotlin
// build.gradle.kts

// REMOVE Hilt
// id("com.google.dagger.hilt.android")
// kapt("com.google.dagger:hilt-compiler:2.48")

// ADD Koin
implementation("io.insert-koin:koin-android:3.5.0")
implementation("io.insert-koin:koin-androidx-compose:3.5.0")
```

#### Step 2: Convert modules
```kotlin
// BEFORE: Hilt
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(
        impl: UserRepositoryImpl
    ): UserRepository
}

// AFTER: Koin
val repositoryModule = module {
    singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
}
```

#### Step 3: Convert ViewModels
```kotlin
// BEFORE: Hilt
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
) : ViewModel()

// AFTER: Koin (remove @HiltViewModel and @Inject)
class ProfileViewModel(
    private val getUserUseCase: GetUserUseCase,
) : ViewModel()

// In Koin module
val viewModelModule = module {
    viewModelOf(::ProfileViewModel)
}
```

#### Step 4: Update Application
```kotlin
// BEFORE: Hilt
@HiltAndroidApp
class MyApplication : Application()

// AFTER: Koin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@MyApplication)
            modules(repositoryModule, viewModelModule, useCaseModule)
        }
    }
}
```

#### Step 5: Update Composables
```kotlin
// BEFORE: Hilt
@Composable
fun ProfileRoute(viewModel: ProfileViewModel = hiltViewModel()) { }

// AFTER: Koin
@Composable
fun ProfileRoute(viewModel: ProfileViewModel = koinViewModel()) { }
```

**Migration Checklist:**
- [ ] Update dependencies
- [ ] Convert all @Module to Koin modules
- [ ] Remove @HiltViewModel and @Inject
- [ ] Update Application class
- [ ] Update Composables (hiltViewModel → koinViewModel)
- [ ] Test dependency graph

---

## 5. Testing Enhancement

### Pattern 5.1: Add Turbine for Flow Testing

**When to Apply:**
- Testing ViewModels with StateFlow
- Need to verify state transitions
- Want to test intermediate states

**Step-by-Step:**

#### Step 1: Add dependency
```kotlin
testImplementation("app.cash.turbine:turbine:1.0.0")
```

#### Step 2: Update tests
```kotlin
// BEFORE
@Test
fun `loadUser updates state`() = runTest {
    viewModel.loadUser("123")
    
    val finalState = viewModel.state.value
    assertEquals("John", finalState.user?.name)
}

// AFTER
@Test
fun `loadUser updates state with loading and success`() = runTest {
    viewModel.state.test {
        // Initial state
        val initial = awaitItem()
        assertEquals(false, initial.isLoading)
        
        // Trigger action
        viewModel.loadUser("123")
        
        // Loading state
        val loading = awaitItem()
        assertEquals(true, loading.isLoading)
        
        // Success state
        val success = awaitItem()
        assertEquals(false, success.isLoading)
        assertEquals("John", success.user?.name)
    }
}
```

---

## 6. Compose Migration

### Pattern 6.1: XML → Compose

**When to Apply:**
- Modernizing legacy screens
- Want declarative UI
- Better performance

**Step-by-Step:**

#### Step 1: Create Compose version alongside XML
```kotlin
// Keep: fragment_profile.xml
// Create: ProfileScreen.kt

@Composable
fun ProfileScreen(
    name: String,
    email: String,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(text = name, style = MaterialTheme.typography.headlineMedium)
        Text(text = email, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onEditClick) {
            Text("Edit Profile")
        }
    }
}
```

#### Step 2: Update Fragment to use Compose
```kotlin
// BEFORE: XML-based Fragment
class ProfileFragment : Fragment(R.layout.fragment_profile) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.nameText).text = viewModel.name
        // ...
    }
}

// AFTER: Compose-based Fragment
class ProfileFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                AppTheme {
                    val state by viewModel.state.collectAsStateWithLifecycle()
                    ProfileScreen(
                        name = state.name,
                        email = state.email,
                        onEditClick = viewModel::onEditClick
                    )
                }
            }
        }
    }
}
```

#### Step 3: Remove XML (optional)
```bash
# After testing
rm app/src/main/res/layout/fragment_profile.xml
```

---

## 7. Error Handling Improvement

### Pattern 7.1: Exceptions → Result<T>

**When to Apply:**
- Repositories throwing exceptions
- Want functional error handling
- Better type safety

**Step-by-Step:**

#### Step 1: Update Repository interface
```kotlin
// BEFORE
interface UserRepository {
    suspend fun getUser(id: String): User  // Can throw
}

// AFTER
interface UserRepository {
    suspend fun getUser(id: String): Result<User>
}
```

#### Step 2: Update implementation
```kotlin
// BEFORE
class UserRepositoryImpl : UserRepository {
    override suspend fun getUser(id: String): User {
        return api.getUser(id)  // Throws on error
    }
}

// AFTER
class UserRepositoryImpl : UserRepository {
    override suspend fun getUser(id: String): Result<User> {
        return try {
            Result.success(api.getUser(id))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

#### Step 3: Update callers
```kotlin
// BEFORE
viewModelScope.launch {
    try {
        val user = repository.getUser(id)
        _state.update { it.copy(user = user) }
    } catch (e: Exception) {
        _state.update { it.copy(error = e.message) }
    }
}

// AFTER
viewModelScope.launch {
    repository.getUser(id)
        .onSuccess { user ->
            _state.update { it.copy(user = user) }
        }
        .onFailure { error ->
            _state.update { it.copy(error = error.message) }
        }
}
```

---

## Automated Migration Tools

### Tool 1: Regex-Based Replacements
```bash
# Replace LiveData with StateFlow
find . -name "*.kt" -exec sed -i '' 's/MutableLiveData/MutableStateFlow/g' {} +
find . -name "*.kt" -exec sed -i '' 's/: LiveData</: StateFlow</g' {} +
```

### Tool 2: IntelliJ Structural Search & Replace
```
// Search template
class $ViewModel$ : ViewModel() {
    private val _$state$ = MutableStateFlow($init$)
    val $state$ : StateFlow<$Type$> = _$state$.asStateFlow()
}

// Replace template
class $ViewModel$ : MviViewModel<$Type$, $Event$>($init$)
```

### Tool 3: Custom Kotlin Script
```kotlin
// migration-script.main.kts
import java.io.File

val projectRoot = File(".")

projectRoot.walkTopDown()
    .filter { it.extension == "kt" && it.name.endsWith("ViewModel.kt") }
    .forEach { file ->
        val content = file.readText()
        
        // Apply transformations
        val updated = content
            .replace("MutableLiveData", "MutableStateFlow")
            .replace(": LiveData<", ": StateFlow<")
        
        file.writeText(updated)
        println("✅ Migrated: ${file.path}")
    }
```

---

## Verification After Refactoring

### Checklist
- [ ] All tests pass
- [ ] No compilation errors
- [ ] Performance metrics unchanged or improved
- [ ] Code coverage maintained
- [ ] Documentation updated
- [ ] Git history preserved (incremental commits)

### Testing Strategy
1. **Unit tests**: Verify business logic unchanged
2. **Integration tests**: Verify component interactions
3. **UI tests**: Verify user flows
4. **Performance tests**: Verify no regressions

---

## Related Guides
- [20-anti-patterns.md](./20-anti-patterns.md) - What to avoid
- [25-performance-benchmarks.md](./25-performance-benchmarks.md) - Performance targets
- [26-context-aware-suggestions.md](./26-context-aware-suggestions.md) - When to refactor

## Migration Timeline Example

```
Week 1: State Management
  Day 1-2: LiveData → StateFlow (ViewModels)
  Day 3-4: Add StateStoreHolder pattern
  Day 5: Testing and verification

Week 2: Architecture
  Day 1-3: Extract UseCase layer
  Day 4-5: Split God ViewModels

Week 3: Performance
  Day 1-2: Add @Immutable annotations
  Day 3-4: Optimize Composables
  Day 5: Benchmark and verify

Week 4: Testing & Polish
  Day 1-2: Add Turbine tests
  Day 3-4: Documentation
  Day 5: Final review
```
