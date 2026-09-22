---
name: Modern Kotlin 2.0+ Features
description: Guide for using Kotlin 2.0+ features effectively in Android development
compliance_level: RECOMMENDED
tags: [kotlin, kotlin-2.0, k2-compiler, modern, features, kmp]
version: 1.0.0
last_updated: 2026-01-26
---

# Modern Kotlin 2.0+ Features

## Context
Kotlin 2.0 introduces significant improvements including the K2 compiler, new language features, and enhanced multiplatform support. AI agents must understand these features to generate modern, efficient code.

**Related Guides:**
- [02-coding-conventions.md](./02-coding-conventions.md) - Kotlin style
- [15-kmp-readiness.md](./15-kmp-readiness.md) - Kotlin Multiplatform
- [03-coroutines-concurrency.md](./03-coroutines-concurrency.md) - Coroutines

**Kotlin Version**: 2.0+ (K2 Compiler)

---

## 🎯 AI Quick Reference

```
K2 COMPILER BENEFITS:
• 2x faster compilation
• Better type inference
• Improved smart casts
• More precise diagnostics

KEY NEW FEATURES:
• data object (singleton with toString/equals)
• Stable context receivers (experimental → stable)
• Enhanced when exhaustiveness
• Improved operator overloading
• Better null safety inference

ALWAYS USE:
• data object for singleton states
• Explicit API mode in libraries
• @JvmInline value class for wrappers
• sealed interface over sealed class

AVOID:
• Deprecated features (listed below)
• Non-K2 compatible patterns
```

---

## 1. Data Objects (Kotlin 1.9+)

### What Are Data Objects?
A `data object` is a singleton with auto-generated `toString()`, `equals()`, and `hashCode()`.

### When to Use

| Pattern | Use Case | Example |
|---------|----------|---------|
| `data object` | Singleton state/event | `data object Loading` |
| `object` | Utility/companion | `object Utils` |
| `data class` | Multiple instances | `data class User(val id: String)` |

### Perfect for Sealed Hierarchies

```kotlin
// ════════════════════════════════════════════════════════════
// ✅ CORRECT: Use data object for singleton sealed members
// ════════════════════════════════════════════════════════════

sealed interface UiState {
    data object Loading : UiState
    data object Empty : UiState
    data class Success(val data: List<Item>) : UiState
    data class Error(val message: String) : UiState
}

// Benefits of data object:
// - toString() returns "Loading" (not "Loading@1a2b3c4")
// - equals/hashCode work correctly for comparison
// - Clear intent: this is a data-carrying singleton

// Usage in when
fun handleState(state: UiState) {
    when (state) {
        UiState.Loading -> showLoading()
        UiState.Empty -> showEmpty()
        is UiState.Success -> showData(state.data)
        is UiState.Error -> showError(state.message)
    }
}
```

### Anti-Pattern

```kotlin
// ❌ WRONG: Using object for state (poor toString)
sealed interface UiState {
    object Loading : UiState  // toString = "Loading@3f8e9a"
}

// ❌ WRONG: Using data class for singleton
sealed interface UiState {
    data class Loading(val unit: Unit = Unit) : UiState  // Unnecessary wrapper
}
```

---

## 2. Enhanced Smart Casts

### K2 Compiler Improvements

The K2 compiler provides smarter type inference and better smart casts:

```kotlin
// ════════════════════════════════════════════════════════════
// ✅ K2: Smart casts in more complex scenarios
// ════════════════════════════════════════════════════════════

class UserRepository {
    private var cachedUser: User? = null
    
    // K2 now smart casts in more scenarios
    fun getUser(): User {
        if (cachedUser != null) {
            // ✅ K2 smart casts cachedUser to User here
            return cachedUser  // No !! needed in many cases
        }
        return fetchUser().also { cachedUser = it }
    }
}

// ════════════════════════════════════════════════════════════
// ✅ K2: Smart casts across inline functions
// ════════════════════════════════════════════════════════════

fun processNullable(value: Any?) {
    value?.let {
        // K2 understands 'it' is non-null AND preserves type info
        if (it is String) {
            // ✅ K2 knows 'it' is String here
            println(it.length)
        }
    }
}

// ════════════════════════════════════════════════════════════
// ✅ K2: Combined conditions smart cast
// ════════════════════════════════════════════════════════════

fun handleResult(result: Result<User>?) {
    if (result != null && result.isSuccess) {
        // ✅ K2 smart casts both conditions
        val user = result.getOrNull()  // Known to be non-null
    }
}
```

### Boolean Variable Smart Casts

```kotlin
// ════════════════════════════════════════════════════════════
// ✅ K2: Smart casts based on Boolean variables
// ════════════════════════════════════════════════════════════

fun process(value: Any) {
    val isString = value is String
    
    if (isString) {
        // ✅ K2 smart casts 'value' to String based on Boolean variable
        println(value.length)
    }
}

// Works with complex conditions too
fun processComplex(a: Any, b: Any) {
    val bothStrings = a is String && b is String
    
    if (bothStrings) {
        // ✅ K2 smart casts both a and b to String
        println(a.length + b.length)
    }
}
```

---

## 3. Explicit API Mode

### For Library/Module Development

When building shared modules or libraries, use explicit API mode to enforce visibility:

```kotlin
// build.gradle.kts
kotlin {
    explicitApi()  // Strict mode (error)
    // OR
    explicitApiWarning()  // Warning mode
}
```

### What It Enforces

```kotlin
// ════════════════════════════════════════════════════════════
// With explicit API mode enabled:
// ════════════════════════════════════════════════════════════

// ❌ WRONG: Missing visibility modifier
class UserRepository { }  // Error: visibility must be explicit

// ❌ WRONG: Missing return type
fun calculate() = 42  // Error: return type must be explicit

// ✅ CORRECT: Explicit everything
public class UserRepository {
    public fun getUser(id: String): User { }
    
    internal fun clearCache(): Unit { }
    
    private fun validateId(id: String): Boolean { }
}

// ✅ CORRECT: Explicit return types
public fun calculate(): Int = 42

public val config: Config = Config()
```

### When to Use

| Scenario | Explicit API? | Reason |
|----------|---------------|--------|
| App module | Optional | Internal code |
| Shared library | **Required** | API stability |
| Domain module | Recommended | Clear contracts |
| Agent Skills | **Required** | Reusable components |

---

## 4. Value Classes (Inline Classes)

### Type-Safe Wrappers Without Overhead

```kotlin
// ════════════════════════════════════════════════════════════
// ✅ CORRECT: Use value class for type safety
// ════════════════════════════════════════════════════════════

@JvmInline
value class UserId(val value: String) {
    init {
        require(value.isNotBlank()) { "UserId cannot be blank" }
    }
    
    // Can have methods
    fun isValid(): Boolean = value.length >= 8
}

@JvmInline
value class Email(val value: String) {
    init {
        require(value.contains("@")) { "Invalid email format" }
    }
}

@JvmInline
value class Password(val value: String)

// Usage: Type-safe, no confusion possible
fun authenticate(userId: UserId, email: Email, password: Password): Result<User> {
    // Parameters are type-checked at compile time
    // No runtime boxing in most cases
}

// ❌ Compile error: wrong parameter types
authenticate(email, userId, password)
```

### When to Use Value Classes

```kotlin
// ════════════════════════════════════════════════════════════
// Use value classes for:
// ════════════════════════════════════════════════════════════

// 1. IDs (prevent mixing different ID types)
@JvmInline value class UserId(val value: String)
@JvmInline value class OrderId(val value: String)
@JvmInline value class ProductId(val value: Long)

// 2. Units (prevent unit confusion)
@JvmInline value class Meters(val value: Double)
@JvmInline value class Kilometers(val value: Double) {
    fun toMeters(): Meters = Meters(value * 1000)
}
@JvmInline value class Milliseconds(val value: Long)
@JvmInline value class Seconds(val value: Long) {
    fun toMillis(): Milliseconds = Milliseconds(value * 1000)
}

// 3. Validated strings
@JvmInline value class NonBlankString(val value: String) {
    init { require(value.isNotBlank()) }
}

// 4. Money/Currency (prevent calculation errors)
@JvmInline value class Cents(val value: Long) {
    operator fun plus(other: Cents) = Cents(value + other.value)
    operator fun minus(other: Cents) = Cents(value - other.value)
    fun toDollars(): Double = value / 100.0
}
```

### Limitations

```kotlin
// ❌ Value classes cannot:
// - Have multiple properties
// - Be used as actual type parameter in some cases
// - Extend other classes

// ❌ WRONG: Multiple properties
@JvmInline
value class Range(val start: Int, val end: Int)  // Error!

// ✅ CORRECT: Single property (wrap a data class if needed)
data class RangeData(val start: Int, val end: Int)
@JvmInline
value class Range(val data: RangeData)
```

---

## 5. Sealed Interfaces Best Practices

### Prefer Sealed Interface Over Sealed Class

```kotlin
// ════════════════════════════════════════════════════════════
// ✅ CORRECT: Sealed interface (more flexible)
// ════════════════════════════════════════════════════════════

sealed interface Result<out T> {
    data class Success<T>(val data: T) : Result<T>
    data class Error(val exception: Throwable) : Result<Nothing>
    data object Loading : Result<Nothing>
}

// Benefits:
// 1. Classes can implement multiple sealed interfaces
// 2. No constructor restrictions
// 3. Better for modeling states/events

// ════════════════════════════════════════════════════════════
// Multiple inheritance with sealed interfaces
// ════════════════════════════════════════════════════════════

sealed interface Loadable {
    data object Loading : Loadable
    data object Loaded : Loadable
}

sealed interface Refreshable {
    data object Refreshing : Refreshable
}

// Can implement both!
sealed interface DataState : Loadable, Refreshable {
    data object Initial : DataState
    data object Loading : DataState, Loadable  // Also Loadable
    data class Success(val data: List<Item>) : DataState
    data class Error(val message: String) : DataState
    data object Refreshing : DataState, Refreshable  // Also Refreshable
}
```

### Exhaustive When with Sealed Interfaces

```kotlin
// ════════════════════════════════════════════════════════════
// K2: Better exhaustiveness checking
// ════════════════════════════════════════════════════════════

sealed interface Event {
    data class Click(val id: String) : Event
    data class Swipe(val direction: Direction) : Event
    data object LongPress : Event
}

fun handleEvent(event: Event): String = when (event) {
    is Event.Click -> "Clicked ${event.id}"
    is Event.Swipe -> "Swiped ${event.direction}"
    Event.LongPress -> "Long pressed"
    // K2 compiler ensures all cases covered
    // No 'else' branch needed
}

// Adding new sealed member = compile error everywhere
// This is GOOD - forces handling of all cases
```

---

## 6. Context Receivers (Experimental → Stable)

### Cleaner DSL and Scope Functions

```kotlin
// ════════════════════════════════════════════════════════════
// Context receivers for cleaner APIs
// ════════════════════════════════════════════════════════════

// Enable in build.gradle.kts:
// kotlin {
//     compilerOptions {
//         freeCompilerArgs.add("-Xcontext-receivers")
//     }
// }

// Define a logging context
interface LoggingContext {
    fun log(message: String)
}

// Function that requires logging context
context(LoggingContext)
fun performOperation(data: String): Result<Unit> {
    log("Starting operation with: $data")  // 'log' available from context
    return try {
        // ... operation
        log("Operation completed")
        Result.success(Unit)
    } catch (e: Exception) {
        log("Operation failed: ${e.message}")
        Result.failure(e)
    }
}

// Usage
class MyService : LoggingContext {
    override fun log(message: String) = println("[MyService] $message")
    
    fun doWork() {
        // Context is 'this'
        performOperation("test data")
    }
}
```

### Context Receivers with CoroutineScope

```kotlin
// ════════════════════════════════════════════════════════════
// Cleaner coroutine utilities with context receivers
// ════════════════════════════════════════════════════════════

context(CoroutineScope)
fun <T> launchWithLogging(
    block: suspend () -> T
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Error in coroutine: ${e.message}")
    }
}

// Usage in ViewModel
class MyViewModel : ViewModel() {
    fun loadData() {
        with(viewModelScope) {
            launchWithLogging {
                // ... async work
            }
        }
    }
}
```

### Repository Pattern with Context Receivers

```kotlin
// ════════════════════════════════════════════════════════════
// Clean repository transactions
// ════════════════════════════════════════════════════════════

interface TransactionContext {
    suspend fun <T> withTransaction(block: suspend () -> T): T
}

context(TransactionContext)
suspend fun transferFunds(from: AccountId, to: AccountId, amount: Cents): Result<Unit> {
    return withTransaction {
        // All operations in single transaction
        debit(from, amount)
        credit(to, amount)
        recordTransfer(from, to, amount)
        Result.success(Unit)
    }
}
```

---

## 7. Collection Operations (Modern Patterns)

### New Collection Functions

```kotlin
// ════════════════════════════════════════════════════════════
// Modern collection operations
// ════════════════════════════════════════════════════════════

// 1. ifEmpty / ifBlank with default
val items = list.ifEmpty { defaultList }
val text = input.ifBlank { "default" }

// 2. getOrElse for safe access
val item = list.getOrElse(index) { defaultItem }
val value = map.getOrElse(key) { computeDefault() }

// 3. scan / runningFold for accumulation
val runningSums = numbers.scan(0) { acc, n -> acc + n }
// [0, 1, 3, 6, 10, ...] for [1, 2, 3, 4, ...]

// 4. chunked with transform
val batches = items.chunked(3) { chunk ->
    processBatch(chunk)
}

// 5. windowed for sliding windows
val movingAverages = prices.windowed(3) { window ->
    window.average()
}

// 6. partition for splitting
val (adults, minors) = users.partition { it.age >= 18 }

// 7. groupBy with transform
val usersByCity = users.groupBy(
    keySelector = { it.city },
    valueTransform = { it.name }
)
// Map<City, List<String>>

// 8. associate variations
val idToUser = users.associateBy { it.id }  // Map<Id, User>
val idToName = users.associate { it.id to it.name }  // Map<Id, String>

// 9. flatMapIndexed
val indexedItems = lists.flatMapIndexed { index, list ->
    list.map { "$index: $it" }
}

// 10. buildList / buildMap / buildSet
val result = buildList {
    add("first")
    if (condition) add("conditional")
    addAll(otherList)
}
```

### Sequence vs Collection

```kotlin
// ════════════════════════════════════════════════════════════
// Use Sequence for large collections with multiple operations
// ════════════════════════════════════════════════════════════

// ❌ WRONG: Multiple intermediate collections
val result = largeList
    .filter { it.isValid }     // Creates new List
    .map { transform(it) }      // Creates another List
    .take(10)                   // Creates another List
    .toList()

// ✅ CORRECT: Single pass with Sequence
val result = largeList.asSequence()
    .filter { it.isValid }      // Lazy
    .map { transform(it) }      // Lazy
    .take(10)                   // Lazy
    .toList()                   // Terminal - executes

// Rule of thumb:
// - < 100 items + few operations → Collection
// - > 100 items OR many operations → Sequence
// - Infinite/unknown size → Sequence
```

---

## 8. Coroutines 1.8+ Features

### Improved Flow Operators

```kotlin
// ════════════════════════════════════════════════════════════
// Modern Flow patterns
// ════════════════════════════════════════════════════════════

// 1. stateIn with better defaults
val uiState = dataFlow
    .map { it.toUiState() }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),  // 5s stop delay
        initialValue = UiState.Loading
    )

// 2. shareIn for shared cold flows
val sharedEvents = eventFlow
    .shareIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        replay = 0  // No replay for events
    )

// 3. transformLatest for async transforms
val searchResults = searchQuery
    .debounce(300)
    .transformLatest { query ->
        emit(SearchState.Loading)
        val results = repository.search(query)
        emit(SearchState.Success(results))
    }

// 4. flatMapMerge for concurrent processing
val processedItems = itemsFlow
    .flatMapMerge(concurrency = 4) { item ->
        flow { emit(processItem(item)) }
    }

// 5. catch with emit
val safeFlow = riskyFlow
    .catch { e ->
        emit(ErrorState(e.message))  // Emit error state instead of throwing
    }

// 6. onCompletion for cleanup
val flowWithCleanup = dataFlow
    .onCompletion { cause ->
        if (cause == null) {
            log("Flow completed normally")
        } else {
            log("Flow failed: ${cause.message}")
        }
    }
```

### Structured Concurrency Improvements

```kotlin
// ════════════════════════════════════════════════════════════
// Modern structured concurrency
// ════════════════════════════════════════════════════════════

// 1. supervisorScope for partial failure handling
suspend fun loadDashboard(): Dashboard = supervisorScope {
    val user = async { userRepository.getUser() }
    val settings = async { settingsRepository.getSettings() }
    val notifications = async { notificationRepository.getRecent() }
    
    Dashboard(
        user = user.await(),
        settings = settings.await(),
        // If notifications fail, use empty list
        notifications = runCatching { notifications.await() }.getOrDefault(emptyList())
    )
}

// 2. coroutineScope for all-or-nothing
suspend fun transferFunds(from: Account, to: Account, amount: Money) = coroutineScope {
    // If any fails, all are cancelled
    async { from.debit(amount) }
    async { to.credit(amount) }
    async { recordTransaction(from, to, amount) }
}

// 3. withTimeout patterns
suspend fun fetchWithFallback(): Data {
    return withTimeoutOrNull(5_000) {
        remoteRepository.fetch()
    } ?: localRepository.getCached()
}
```

---

## 9. KMP (Kotlin Multiplatform) Patterns

### expect/actual Pattern

```kotlin
// ════════════════════════════════════════════════════════════
// Common code (shared module)
// ════════════════════════════════════════════════════════════

// commonMain/kotlin/Platform.kt
expect fun getPlatform(): Platform

interface Platform {
    val name: String
    val version: String
}

expect fun getCurrentTimeMillis(): Long

expect class AtomicReference<T>(initial: T) {
    fun get(): T
    fun set(value: T)
    fun compareAndSet(expected: T, new: T): Boolean
}

// ════════════════════════════════════════════════════════════
// Android implementation
// ════════════════════════════════════════════════════════════

// androidMain/kotlin/Platform.android.kt
actual fun getPlatform(): Platform = object : Platform {
    override val name = "Android"
    override val version = Build.VERSION.RELEASE
}

actual fun getCurrentTimeMillis(): Long = System.currentTimeMillis()

actual class AtomicReference<T> actual constructor(initial: T) {
    private val ref = java.util.concurrent.atomic.AtomicReference(initial)
    actual fun get(): T = ref.get()
    actual fun set(value: T) = ref.set(value)
    actual fun compareAndSet(expected: T, new: T): Boolean = 
        ref.compareAndSet(expected, new)
}

// ════════════════════════════════════════════════════════════
// iOS implementation
// ════════════════════════════════════════════════════════════

// iosMain/kotlin/Platform.ios.kt
actual fun getPlatform(): Platform = object : Platform {
    override val name = "iOS"
    override val version = UIDevice.currentDevice.systemVersion
}

actual fun getCurrentTimeMillis(): Long = 
    (NSDate().timeIntervalSince1970 * 1000).toLong()
```

### KMP-Friendly Architecture

```kotlin
// ════════════════════════════════════════════════════════════
// Design for KMP from the start
// ════════════════════════════════════════════════════════════

// 1. Domain layer = 100% shared
// commonMain/kotlin/domain/
interface UserRepository {
    suspend fun getUser(id: String): Result<User>
    fun observeUser(id: String): Flow<User>
}

data class User(
    val id: String,
    val name: String,
    val email: String,
)

class GetUserUseCase(
    private val repository: UserRepository
) {
    suspend operator fun invoke(id: String): Result<User> {
        return repository.getUser(id)
    }
}

// 2. Data layer = platform-specific implementations
// androidMain/kotlin/data/
class AndroidUserRepository(
    private val api: UserApi,
    private val dao: UserDao,
) : UserRepository {
    // Android-specific implementation
}

// iosMain/kotlin/data/
class IOSUserRepository(
    private val api: UserApi,
    private val dataStore: NSUserDefaults,
) : UserRepository {
    // iOS-specific implementation
}
```

---

## 10. Deprecations & Migration

### Features to Avoid

```kotlin
// ════════════════════════════════════════════════════════════
// ❌ DEPRECATED: Avoid these patterns
// ════════════════════════════════════════════════════════════

// ❌ Old: @Experimental annotations (use @RequiresOptIn)
@Experimental
annotation class MyExperimentalApi  // DEPRECATED

// ✅ New: @RequiresOptIn
@RequiresOptIn(message = "This API is experimental")
@Retention(AnnotationRetention.BINARY)
annotation class MyExperimentalApi

// ❌ Old: Enum.values() (creates new array each call)
val values = MyEnum.values()  // Deprecated

// ✅ New: Enum.entries (reuses immutable list)
val entries = MyEnum.entries  // Preferred

// ❌ Old: Random.nextInt() without range
val random = Random.nextInt()  // Can be negative

// ✅ New: Explicit range
val random = Random.nextInt(0, 100)
val random = (0..99).random()

// ❌ Old: String.toLowerCase()/toUpperCase() without locale
val lower = text.toLowerCase()  // Deprecated

// ✅ New: Explicit locale
val lower = text.lowercase()  // Uses default locale
val lower = text.lowercase(Locale.US)  // Explicit locale
```

### Migration Checklist

```kotlin
// ════════════════════════════════════════════════════════════
// Migration checklist for Kotlin 2.0
// ════════════════════════════════════════════════════════════

/*
PRE-MIGRATION:
[ ] Update Kotlin plugin to 2.0+
[ ] Update kotlinx libraries (coroutines, serialization)
[ ] Run `./gradlew build` with K2 warnings enabled

DURING MIGRATION:
[ ] Replace object with data object in sealed hierarchies
[ ] Replace Enum.values() with Enum.entries
[ ] Update deprecated String functions
[ ] Add @JvmInline to value classes
[ ] Convert sealed class to sealed interface where applicable
[ ] Enable explicit API mode for library modules

POST-MIGRATION:
[ ] Run full test suite
[ ] Check for new K2 warnings
[ ] Review generated bytecode if needed
[ ] Update CI/CD for K2 compiler
*/
```

---

## 11. AI Code Generation Guidelines

### When Generating Kotlin Code

```kotlin
// ════════════════════════════════════════════════════════════
// AI MUST follow these patterns for Kotlin 2.0+ projects
// ════════════════════════════════════════════════════════════

// 1. Always use data object for singleton states
sealed interface UiState {
    data object Loading : UiState  // ✅ Not just 'object'
}

// 2. Prefer sealed interface over sealed class
sealed interface Event  // ✅ Not 'sealed class'

// 3. Use value class for type safety
@JvmInline
value class UserId(val value: String)  // ✅

// 4. Use Enum.entries not Enum.values()
val states = State.entries  // ✅

// 5. Use modern string functions
val lower = text.lowercase()  // ✅

// 6. Use ifEmpty/ifBlank
val result = list.ifEmpty { default }  // ✅

// 7. Use buildList/buildMap for construction
val items = buildList {  // ✅
    add(first)
    addAll(others)
}

// 8. Explicit return types in public APIs
public fun getData(): List<Data>  // ✅ Not inferred

// 9. Use Result type properly
fun process(): Result<Data> = runCatching {
    // ... 
}
```

---

## Verification Checklist

Before submitting Kotlin code, verify:

### Modern Features
- [ ] Using `data object` for singleton sealed members?
- [ ] Using `sealed interface` instead of `sealed class` where appropriate?
- [ ] Using `@JvmInline value class` for type-safe wrappers?
- [ ] Using `Enum.entries` instead of `Enum.values()`?

### Collections
- [ ] Using `ifEmpty`/`ifBlank` for defaults?
- [ ] Using `buildList`/`buildMap` for construction?
- [ ] Using `Sequence` for large collection chains?

### Type Safety
- [ ] Explicit return types for public APIs?
- [ ] Value classes for IDs and typed wrappers?
- [ ] Proper null handling with smart casts?

### Deprecations
- [ ] No deprecated String functions?
- [ ] No `@Experimental` (use `@RequiresOptIn`)?
- [ ] No `Enum.values()`?

---

## Quick Reference Card

```
┌─────────────────────────────────────────────────────────────┐
│              KOTLIN 2.0+ QUICK REFERENCE                     │
├─────────────────────────────────────────────────────────────┤
│ SEALED HIERARCHY:                                           │
│   sealed interface State {                                  │
│       data object Loading : State                           │
│       data class Success(val data: T) : State              │
│   }                                                         │
├─────────────────────────────────────────────────────────────┤
│ VALUE CLASS:                                                │
│   @JvmInline                                                │
│   value class UserId(val value: String)                     │
├─────────────────────────────────────────────────────────────┤
│ COLLECTIONS:                                                │
│   list.ifEmpty { default }                                  │
│   Enum.entries (not .values())                              │
│   buildList { add(item) }                                   │
│   items.asSequence().filter().map().toList()               │
├─────────────────────────────────────────────────────────────┤
│ STRINGS:                                                    │
│   text.lowercase() / text.uppercase()                       │
│   text.ifBlank { "default" }                               │
├─────────────────────────────────────────────────────────────┤
│ FLOW:                                                       │
│   stateIn(scope, SharingStarted.WhileSubscribed(5000), init)│
│   flow.transformLatest { emit(process(it)) }               │
└─────────────────────────────────────────────────────────────┘
```
