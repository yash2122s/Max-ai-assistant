---
name: Coding Conventions & Standards
description: Naming, formatting, and coding style guidelines for Kotlin Android projects.
compliance_level: MANDATORY
tags: [conventions, naming, style, formatting]
version: 2.2.0
---

# Coding Conventions & Standards

## Context
Consistent coding standards improve readability, reduce cognitive load, and make the codebase easier to maintain. This guide outlines the mandatory conventions for this project.

**Related Guides:**
- [01-architecture.md](./01-architecture.md) - Architecture patterns
- [05-jetpack-compose.md](./05-jetpack-compose.md) - Compose conventions
- [21-code-review.md](./21-code-review.md) - Review guidelines

---

## 🎯 AI Quick Reference

```
NAMING:
• Classes: PascalCase (UserRepository)
• Functions/Variables: camelCase (getUserById)
• Constants: SCREAMING_SNAKE_CASE (MAX_RETRY_COUNT)
• Packages: lowercase.dot.separated (com.example.feature)

FILE ORDER:
1. Package declaration
2. Imports (grouped: Android → Third-party → Project)
3. Top-level constants
4. Main class
5. Extensions
6. Nested/Internal classes

KOTLIN IDIOMS:
• data class for models
• sealed interface for states/results
• object for singletons
• Trailing commas ALWAYS
```

---

## 1. Naming Conventions

### Class Naming

| Type | Convention | Example |
|------|------------|---------|
| Class/Interface | PascalCase | `UserRepository`, `ProductService` |
| Implementation | PascalCase + Impl | `UserRepositoryImpl` |
| ViewModel | Feature + ViewModel | `ProfileViewModel` |
| StateStore | StateStore<S, E> | `StateStore<ProfileUiState, ProfileEvent>` |
| UseCase | Verb + Noun + UseCase | `GetUserProfileUseCase` |
| Screen | Feature + Screen | `ProfileScreen` |
| State | Feature + UiState | `ProfileUiState` |
| Event | Feature + Event | `ProfileEvent` |
| Intent | Feature + Intent | `ProfileIntent` |

### Function Naming

| Type | Convention | Example |
|------|------------|---------|
| Actions | verb + Object | `getUserById()`, `saveProfile()` |
| Boolean getters | is/has/can + Condition | `isValid()`, `hasPermission()` |
| Factories | create/new + Type | `createUser()`, `newInstance()` |
| Callbacks | on + Event | `onUserClick()`, `onSubmit()` |
| Converters | to + Target | `toDto()`, `toDomain()` |

### ✅ DO: Consistent Naming
```kotlin
// ════════════════════════════════════════════════════════════
// CLASSES
// ════════════════════════════════════════════════════════════
class UserRepository                    // Interface
class UserRepositoryImpl                // Implementation
class GetUserProfileUseCase             // UseCase
class ProfileViewModel                  // ViewModel
class ProfileScreen                     // Composable Screen
data class ProfileUiState(...)          // State
sealed interface ProfileEvent           // Events

// ════════════════════════════════════════════════════════════
// FUNCTIONS
// ════════════════════════════════════════════════════════════
fun getUserById(id: String): User       // Action
fun isUserValid(user: User): Boolean    // Boolean check
fun createUser(name: String): User      // Factory
fun onUserSelected(user: User)          // Callback
fun UserDto.toDomain(): User            // Converter

// ════════════════════════════════════════════════════════════
// VARIABLES
// ════════════════════════════════════════════════════════════
val userName: String                    // Property
val isLoading: Boolean                  // Boolean property
private val _state = MutableStateFlow() // Backing property (underscore)
val state: StateFlow<UiState>           // Public property

// ════════════════════════════════════════════════════════════
// CONSTANTS
// ════════════════════════════════════════════════════════════
const val MAX_RETRY_COUNT = 3
const val DEFAULT_PAGE_SIZE = 20
private const val TAG = "ProfileViewModel"

companion object {
    const val KEY_USER_ID = "user_id"
    val DEFAULT_TIMEOUT = 30.seconds
}
```

### ❌ DON'T: Inconsistent Naming
```kotlin
// ❌ BAD: Inconsistent casing and prefixes
interface IUserRepository { }           // ❌ Don't use I prefix
class user_repository { }               // ❌ Wrong casing
class ProfileVM { }                     // ❌ Don't abbreviate
class ProfileViewmodel { }              // ❌ Inconsistent casing

// ❌ BAD: Unclear function names
fun process(data: Any) { }              // ❌ Too vague
fun doStuff() { }                       // ❌ Meaningless
fun getUserDataFromServerAndSaveToDatabase() { } // ❌ Too long

// ❌ BAD: Variable naming
val User_Name: String                   // ❌ Wrong casing
val x: Int                              // ❌ Single letter (except loops)
val temp: String                        // ❌ Unclear purpose
val mUserName: String                   // ❌ Hungarian notation
```

---

## 2. File Organization

### Standard File Structure
```kotlin
// ════════════════════════════════════════════════════════════
// 1. PACKAGE DECLARATION
// ════════════════════════════════════════════════════════════
package com.example.feature.profile

// ════════════════════════════════════════════════════════════
// 2. IMPORTS (Grouped and sorted)
// ════════════════════════════════════════════════════════════
// Android/AndroidX imports
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

// Third-party imports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Project imports
import com.example.core.common.Result
import com.example.feature.profile.domain.GetUserUseCase

// ════════════════════════════════════════════════════════════
// 3. TOP-LEVEL CONSTANTS (if any)
// ════════════════════════════════════════════════════════════
private const val TAG = "ProfileViewModel"
private const val DEFAULT_PAGE_SIZE = 20

// ════════════════════════════════════════════════════════════
// 4. MAIN CLASS (Using StateStoreHolder pattern)
// ════════════════════════════════════════════════════════════
/**
 * ViewModel for user profile management.
 *
 * Extends MviViewModel to avoid state/event boilerplate.
 * Handles loading, updating, and observing user profile data.
 *
 * @param getUserUseCase Use case for fetching user data
 * @param updateUserUseCase Use case for updating user data
 * @see MviViewModel for base implementation
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserUseCase,
) : MviViewModel<ProfileUiState, ProfileEvent>(ProfileUiState()) {
    // ✅ No boilerplate! state, events, updateState(), sendEvent() inherited
    
    // ════════════════════════════════════════════════════════
    // PUBLIC METHODS (User actions)
    // ════════════════════════════════════════════════════════
    fun loadProfile(userId: String) {
        viewModelScope.launch {
            loadProfileInternal(userId)
        }
    }
    
    fun onNameChanged(name: String) {
        updateState { it.copy(name = name) }  // ✅ Clean API
    }
    
    fun onSaveClick() {
        viewModelScope.launch {
            saveProfile()
        }
    }
    
    // ════════════════════════════════════════════════════════
    // PRIVATE METHODS (Internal logic)
    // ════════════════════════════════════════════════════════
    private suspend fun loadProfileInternal(userId: String) {
        updateState { it.copy(isLoading = true) }
        getUserUseCase(userId)
            .onSuccess { user -> updateState { it.copy(user = user, isLoading = false) } }
            .onFailure { error -> 
                updateState { it.copy(error = error.message, isLoading = false) }
                sendEvent(ProfileEvent.ShowMessage(error.message ?: "Error"))
            }
    }
    
    private suspend fun saveProfile() {
        // Implementation
    }
}

// ════════════════════════════════════════════════════════════
// 5. EXTENSION FUNCTIONS (if any)
// ════════════════════════════════════════════════════════════
private fun User.toUiModel(): UserUiModel = UserUiModel(
    id = id,
    displayName = "$firstName $lastName",
)

// ════════════════════════════════════════════════════════════
// 6. NESTED/RELATED CLASSES (UiState, Events, etc.)
// ════════════════════════════════════════════════════════════
/**
 * UI state for Profile screen.
 */
@Immutable
data class ProfileUiState(
    val user: User? = null,
    val name: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    // Derived properties
    val isValid: Boolean get() = name.isNotBlank() && email.contains("@")
    val hasChanges: Boolean get() = user?.name != name || user?.email != email
}

/**
 * One-time events for Profile screen.
 */
sealed interface ProfileEvent {
    data object NavigateBack : ProfileEvent
    data class ShowMessage(val message: String) : ProfileEvent
}
```

---

## 3. Documentation Standards

### KDoc Requirements

| Element | KDoc Required | Content |
|---------|---------------|---------|
| Public class | ✅ Required | Purpose, usage, related classes |
| Public function | ✅ Required | What it does, params, returns, throws |
| Public property | ⚠️ If non-obvious | Purpose if name isn't self-explanatory |
| Private | ❌ Optional | Only if complex |

### ✅ DO: Comprehensive KDoc
```kotlin
/**
 * Repository for managing user data across local and remote sources.
 *
 * Implements offline-first strategy: reads from cache first, then syncs with remote.
 *
 * ## Usage
 * ```kotlin
 * val user = userRepository.getUser("user123")
 * userRepository.observeUser("user123").collect { user -> ... }
 * ```
 *
 * @see UserApi for remote data source
 * @see UserDao for local data source
 */
interface UserRepository {
    
    /**
     * Retrieves a user by their unique identifier.
     *
     * Fetches from local cache first, then attempts remote sync if stale.
     *
     * @param userId Unique identifier of the user. Must not be blank.
     * @return [Result.success] with [User] if found, [Result.failure] with:
     *  - [IllegalArgumentException] if userId is blank
     *  - [UserNotFoundException] if user doesn't exist
     *  - [NetworkException] if remote fetch fails and cache is empty
     *
     * @throws CancellationException if coroutine is cancelled (always re-thrown)
     */
    suspend fun getUser(userId: String): Result<User>
    
    /**
     * Observes real-time changes to a user's data.
     *
     * Emits immediately with cached data, then updates when remote changes detected.
     *
     * @param userId Unique identifier of the user
     * @return Flow emitting [User] updates, completes when user is deleted
     */
    fun observeUser(userId: String): Flow<User>
}
```

### Comments: Explain WHY, Not WHAT

```kotlin
// ════════════════════════════════════════════════════════════
// ✅ DO: Explain WHY
// ════════════════════════════════════════════════════════════

// Delay ensures animation completes before navigation
delay(300)
navigateToNext()

// Using distinctUntilChanged to prevent unnecessary recomposition
// when user rapidly types (debounce handled separately)
val searchQuery = searchFlow
    .distinctUntilChanged()
    .flatMapLatest { query -> searchRepository.search(query) }

// Server returns dates in ISO-8601 but without timezone,
// so we assume UTC for consistency
val parsedDate = LocalDateTime.parse(dateString)
    .atZone(ZoneOffset.UTC)
    .toInstant()

// ════════════════════════════════════════════════════════════
// ❌ DON'T: Explain WHAT (code already shows this)
// ════════════════════════════════════════════════════════════

// Get the user ❌ Obvious from code
val user = getUser(id)

// Increment counter ❌ Obvious from code
counter++

// Check if list is empty ❌ Obvious from code
if (list.isEmpty()) { }
```

### TODO Format
```kotlin
// ✅ DO: Structured TODOs with tracking
// TODO(JIRA-123): Implement retry logic with exponential backoff
// TODO(github.com/user/repo/issues/456): Add support for pagination
// FIXME(URGENT): Memory leak when screen rotates during upload

// ❌ DON'T: Vague TODOs
// TODO: fix this later
// TODO
// HACK
```

---

## 4. Kotlin Idioms

### Data Classes
```kotlin
// ✅ DO: Data class with defaults and derived properties
@Immutable
data class UserUiState(
    val id: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    // Derived properties (computed, not stored)
    val fullName: String get() = "$firstName $lastName".trim()
    val initials: String get() = "${firstName.firstOrNull() ?: ""}${lastName.firstOrNull() ?: ""}"
    val hasError: Boolean get() = error != null
    val isValid: Boolean get() = firstName.isNotBlank() && email.contains("@")
}

// ✅ DO: Trailing commas for easier diffs
data class Product(
    val id: String,
    val name: String,
    val price: Double,
    val category: String,  // ← Trailing comma
)
```

### Sealed Classes/Interfaces
```kotlin
// ✅ DO: Sealed interface for exhaustive when
sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class Error(val exception: Throwable) : NetworkResult<Nothing>
    data object Loading : NetworkResult<Nothing>
}

// Usage with exhaustive when (no else needed)
fun handleResult(result: NetworkResult<User>) {
    when (result) {
        is NetworkResult.Success -> showUser(result.data)
        is NetworkResult.Error -> showError(result.exception)
        NetworkResult.Loading -> showLoading()
        // No else needed - compiler enforces all cases
    }
}

// ✅ DO: Sealed interface for UI events
sealed interface LoginEvent {
    data object NavigateToHome : LoginEvent
    data object NavigateToForgotPassword : LoginEvent
    data class ShowError(val message: String) : LoginEvent
    data class ShowToast(val message: String, val duration: Int = Toast.LENGTH_SHORT) : LoginEvent
}
```

### Scope Functions
```kotlin
// ════════════════════════════════════════════════════════════
// let - Transform nullable, limit scope
// ════════════════════════════════════════════════════════════
user?.let { nonNullUser ->
    displayUser(nonNullUser)
    trackUserView(nonNullUser.id)
}

// ════════════════════════════════════════════════════════════
// apply - Configure object, returns this
// ════════════════════════════════════════════════════════════
val intent = Intent(context, DetailActivity::class.java).apply {
    putExtra("USER_ID", userId)
    putExtra("SOURCE", "profile")
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// ════════════════════════════════════════════════════════════
// also - Side effects, returns this
// ════════════════════════════════════════════════════════════
val user = repository.getUser(id).also { user ->
    analytics.trackUserLoaded(user.id)
    logger.debug("User loaded: ${user.name}")
}

// ════════════════════════════════════════════════════════════
// run - Transform with receiver, returns result
// ════════════════════════════════════════════════════════════
val displayText = user.run {
    "$firstName $lastName ($email)"
}

// ════════════════════════════════════════════════════════════
// with - Multiple operations on object
// ════════════════════════════════════════════════════════════
with(binding) {
    nameText.text = user.name
    emailText.text = user.email
    avatarImage.load(user.avatarUrl)
}

// ════════════════════════════════════════════════════════════
// ❌ DON'T: Nested scope functions
// ════════════════════════════════════════════════════════════
user?.let { u ->
    u.profile?.let { p ->  // ❌ Hard to read
        p.address?.let { a ->  // ❌ Too nested
            displayAddress(a)
        }
    }
}

// ✅ DO: Use safe calls instead
user?.profile?.address?.let { address ->
    displayAddress(address)
}
```

---

## 5. Formatting Rules

### Line Length & Wrapping
```kotlin
// ════════════════════════════════════════════════════════════
// Max line length: 120 characters
// ════════════════════════════════════════════════════════════

// ✅ DO: Wrap long function signatures
fun processUserProfile(
    userId: String,
    includeDetails: Boolean = true,
    forceRefresh: Boolean = false,
    callback: (Result<User>) -> Unit,
): Job {
    // Implementation
}

// ✅ DO: Wrap long function calls
userRepository.updateUser(
    userId = currentUser.id,
    name = newName,
    email = newEmail,
    preferences = updatedPreferences,
)

// ✅ DO: Wrap long when expressions
val message = when (error) {
    is NetworkError.NoConnection -> 
        "Please check your internet connection and try again"
    is NetworkError.Timeout -> 
        "The server took too long to respond. Please try again"
    is NetworkError.ServerError -> 
        "Something went wrong on our end. We're working on it"
    else -> 
        "An unexpected error occurred"
}
```

### Blank Lines
```kotlin
class ProfileViewModel : ViewModel() {
    
    // One blank line after class opening
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    
    // One blank line between property groups
    private val _events = Channel<ProfileEvent>()
    val events = _events.receiveAsFlow()
    
    // One blank line before functions
    fun loadProfile(userId: String) {
        viewModelScope.launch {
            // ...
        }
    }
    
    // One blank line between functions
    fun updateProfile(name: String) {
        _state.update { it.copy(name = name) }
    }
    
    // No blank line before closing brace
}
```

---

## 6. Verification Checklist

### Naming
- [ ] Classes use PascalCase
- [ ] Functions/variables use camelCase
- [ ] Constants use SCREAMING_SNAKE_CASE
- [ ] No I prefix on interfaces
- [ ] Implementations suffixed with Impl

### Documentation
- [ ] All public APIs have KDoc
- [ ] Comments explain WHY, not WHAT
- [ ] TODOs have tracking (JIRA-XXX)

### Formatting
- [ ] Trailing commas on multi-line parameters
- [ ] Proper blank lines between sections
- [ ] Lines under 120 characters
- [ ] Imports organized by group

### Kotlin Idioms
- [ ] Data classes for models
- [ ] Sealed interfaces for states
- [ ] Scope functions used appropriately
- [ ] No nested scope functions (max 1 level)

### ViewModel Pattern
- [ ] ViewModels implement `StateStoreHolder<State, Events, Intents>`
- [ ] Use `reduceState { copy(...) }` for state updates (reducer pattern)
- [ ] Use `sendEvent()` for one-time events
- [ ] Use `processIntent()` to handle user actions
- [ ] See [07-state-management.md](./07-state-management.md) for full pattern
