---
name: State Management (MVI/MVVM)
description: Standardized patterns for managing UI state and events using MVI/MVVM.
compliance_level: MANDATORY
tags: [state-management, mvi, mvvm, viewmodel, events, stateflow]
version: 2.2.0
last_updated: 2026-01-24
---

# State Management (MVI/MVVM)

## Context
Clear state management ensures predictable UI behavior. We use a unidirectional data flow (UDF) approach combining MVVM with MVI concepts.

## Rules

### 1. ViewModels
*   Must extend AndroidX `ViewModel` class.
*   Inject UseCases (not Repositories or Controllers) following Clean Architecture.
*   RECOMMENDED: Implement `StateStoreHolder` interface to avoid code duplication.
*   Expose `uiState` as `StateFlow<UiState>`.
*   Expose `events` as `Flow<Event>` from `Channel`.
*   Handle all business logic; UI only dispatches actions.

### 2. UI State
*   **Immutable**: Must be `@Immutable` data class.
*   **Comprehensive**: Include all fields (loading, data, error) in one class.
*   **Derived**: Use properties for computed values (e.g., `val showEmpty get() = ...`).

### 3. Events
*   Use `Sealed Interface` for one-time events (Navigation, Snackbar).
*   Use `Channel` or `SharedFlow` (replay=0) for dispatching.
*   Collect with `EventsEffect` (lifecycle-aware) in Compose.

### 4. Updates
*   State updates must be atomic (`update { ... }`).
*   Never expose MutableStateFlow to the UI.

## Examples

### ✅ Correct: ViewModel Structure
```kotlin
@HiltViewModel
class FeatureViewModel @Inject constructor(
    private val useCase: MyUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeatureUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<FeatureEvent>()
    val events = _events.receiveAsFlow()

    fun onAction() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            // ...
        }
    }
}
```

### ✅ Correct: UI State
```kotlin
@Immutable
data class UserUiState(
    val user: User? = null,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    val showContent get() = !isLoading && user != null
}
```

### ❌ Incorrect: Mutable State in UI
```kotlin
// ❌ Don't expose MutableStateFlow
val uiState = MutableStateFlow(UiState()) 

// ❌ Don't use var in State
data class UiState(var isLoading: Boolean) 
```

## StateStoreHolder Pattern

### Why Use StateStoreHolder?

Every ViewModel needs the same boilerplate:
```kotlin
// ❌ Repeated in every ViewModel
private val _uiState = MutableStateFlow(UiState())
val uiState: StateFlow<UiState> = _uiState.asStateFlow()
```

**Solution**: Use `StateStore` + `StateStoreHolder` pattern:
```kotlin
// ✅ StateStore: Single source of truth for state/events
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
    
    /** Reducer pattern: receives current state, returns new state */
    fun reduceState(reducer: State.() -> State) {
        _state.update(reducer)  // Thread-safe atomic update
    }
}

// ✅ StateStoreHolder: Interface for ViewModels
interface StateStoreHolder<State, Events, Intents> {
    val stateStore: StateStore<State, Events>
    val events: Flow<Events> get() = stateStore.events
    val state: StateFlow<State> get() = stateStore.state
    
    fun reduceState(reducer: State.() -> State) = stateStore.reduceState(reducer)
    fun sendEvent(event: Events) = stateStore.sendEvent(event)
    fun processIntent(intent: Intents)  // Handle user actions
}

// ✅ Use everywhere - no boilerplate!
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
) : ViewModel(), StateStoreHolder<ProfileUiState, ProfileEvent, ProfileIntent> {
    
    override val stateStore = StateStore(viewModelScope, ProfileUiState())
    
    override fun processIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.Load -> loadProfile()
        }
    }
    
    private fun loadProfile() {
        reduceState { copy(isLoading = true) }  // ✅ Clean reducer pattern
    }
}
```

**Benefits**:
- Eliminates 5-7 lines per ViewModel
- Flexible (implement only what you need)
- Testable (test each interface separately)
- Reusable (use outside ViewModel if needed)
- Consistent pattern across app

See `skills/templates/examples/StateStoreHolderExample.kt` for complete implementation.

## Verification Checklist
- [ ] Does ViewModel extend base class or implement StateStoreHolder?
- [ ] Does ViewModel expose read-only `StateFlow`?
- [ ] Is UI State `@Immutable`?
- [ ] Are one-time events handled via `Channel`?
- [ ] Are atomic updates used (`update {}`)?
