package com.agentcore.examples

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * STATESTOREHOLDER PATTERN - Reduce ViewModel Boilerplate
 * 
 * FILE NAMING CONVENTIONS:
 *   Base ViewModel:     MviViewModel.kt (in /presentation/base/)
 *   StateStoreHolder:   StateStoreHolder.kt (interface)
 *   Feature ViewModel:  [Feature]ViewModel.kt (extends MviViewModel)
 * 
 * @see UseCaseViewModelExample.kt for standard ViewModel pattern
 * @see 07-state-management.md for state management guide
 * ============================================================================
 */

// ============================================================
// STATESTOREHOLDER PATTERN - AVOID CODE DUPLICATION
// ============================================================
//
// Problem: Every ViewModel needs same boilerplate:
//   private val _uiState = MutableStateFlow(...)
//   val uiState = _uiState.asStateFlow()
//   _uiState.update { ... }
//
// Solution: Extract common pattern into reusable interface/base class

// ============================================================
// PATTERN 1: INTERFACE (Recommended - More Flexible)
// ============================================================

/**
 * StateStoreHolder interface for ViewModels.
 * 
 * Benefits:
 * - Eliminates boilerplate in every ViewModel
 * - Consistent state management pattern
 * - Easy to test
 * - Type-safe
 * 
 * Usage: Implement this interface in your ViewModel
 */
interface StateStoreHolder<S> {
    val state: StateFlow<S>
    fun updateState(transform: (S) -> S)
}

/**
 * Base ViewModel with StateStoreHolder implementation.
 * 
 * Inherit from this to get state management for free.
 */
abstract class BaseStateViewModel<S>(
    initialState: S,
) : ViewModel(), StateStoreHolder<S> {
    
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state.asStateFlow()
    
    override fun updateState(transform: (S) -> S) {
        _state.update(transform)
    }
}

// ============================================================
// PATTERN 2: DELEGATE (Alternative - Composition)
// ============================================================

/**
 * StateStore delegate for composition-based approach.
 */
class StateStore<S>(initialState: S) : StateStoreHolder<S> {
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state.asStateFlow()
    
    override fun updateState(transform: (S) -> S) {
        _state.update(transform)
    }
}

// ============================================================
// EVENT HOLDER PATTERN (Similar concept for events)
// ============================================================

/**
 * EventHolder interface for one-time events.
 */
interface EventHolder<E> {
    val events: kotlinx.coroutines.flow.Flow<E>
    fun sendEvent(event: E)
}

/**
 * Base implementation for event handling.
 */
class EventChannel<E> : EventHolder<E> {
    private val _events = Channel<E>(Channel.BUFFERED)
    override val events = _events.receiveAsFlow()
    
    override fun sendEvent(event: E) {
        _events.trySend(event)
    }
}

// ============================================================
// COMPLETE BASE VIEWMODEL (State + Events)
// ============================================================

/**
 * Complete base ViewModel with both state and event management.
 * 
 * This is the RECOMMENDED pattern for production apps.
 */
abstract class MviViewModel<S, E>(
    initialState: S,
) : ViewModel(),
    StateStoreHolder<S>,
    EventHolder<E> {
    
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state.asStateFlow()
    
    private val _events = Channel<E>(Channel.BUFFERED)
    override val events = _events.receiveAsFlow()
    
    override fun updateState(transform: (S) -> S) {
        _state.update(transform)
    }
    
    override fun sendEvent(event: E) {
        viewModelScope.launch {
            _events.send(event)
        }
    }
    
    // Helper for common pattern: update state and send event
    protected fun updateStateAndSendEvent(
        stateTransform: (S) -> S,
        event: E,
    ) {
        updateState(stateTransform)
        sendEvent(event)
    }
}

// ============================================================
// USAGE EXAMPLES
// ============================================================

// Example 1: Using BaseStateViewModel
@Immutable
data class ProfileUiState(
    val name: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ProfileViewModel(
    private val getUserUseCase: GetUserUseCase,
) : BaseStateViewModel<ProfileUiState>(ProfileUiState()) {
    
    fun loadProfile(userId: String) {
        viewModelScope.launch {
            // ✅ Use updateState() - no boilerplate!
            updateState { it.copy(isLoading = true) }
            
            getUserUseCase(userId)
                .onSuccess { user ->
                    updateState {
                        it.copy(
                            name = user.name,
                            email = user.email,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    updateState {
                        it.copy(
                            isLoading = false,
                            error = error.message,
                        )
                    }
                }
        }
    }
}

// Example 2: Using MviViewModel (State + Events)
@Immutable
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface LoginEvent {
    data object NavigateToHome : LoginEvent
    data class ShowError(val message: String) : LoginEvent
}

class LoginViewModel(
    private val loginUseCase: LoginUseCase,
) : MviViewModel<LoginUiState, LoginEvent>(LoginUiState()) {
    
    fun onEmailChanged(email: String) {
        // ✅ Clean and concise
        updateState { it.copy(email = email) }
    }
    
    fun onPasswordChanged(password: String) {
        updateState { it.copy(password = password) }
    }
    
    fun onLoginClick() {
        viewModelScope.launch {
            updateState { it.copy(isLoading = true) }
            
            loginUseCase(state.value.email, state.value.password)
                .onSuccess {
                    updateState { it.copy(isLoading = false) }
                    sendEvent(LoginEvent.NavigateToHome)
                }
                .onFailure { error ->
                    updateStateAndSendEvent(
                        stateTransform = { it.copy(isLoading = false, error = error.message) },
                        event = LoginEvent.ShowError(error.message ?: "Login failed"),
                    )
                }
        }
    }
}

// Example 3: Using StateStore delegate (Composition)
class CartViewModel(
    private val cartRepository: CartRepository,
) : ViewModel() {
    
    // ✅ Delegate to StateStore
    private val stateStore = StateStore(CartUiState())
    val state: StateFlow<CartUiState> = stateStore.state
    
    private val eventChannel = EventChannel<CartEvent>()
    val events = eventChannel.events
    
    fun addToCart(productId: String) {
        viewModelScope.launch {
            stateStore.updateState { it.copy(isLoading = true) }
            
            cartRepository.addItem(productId)
                .onSuccess {
                    stateStore.updateState { it.copy(isLoading = false) }
                    eventChannel.sendEvent(CartEvent.ItemAdded)
                }
        }
    }
}

@Immutable
data class CartUiState(
    val items: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

sealed interface CartEvent {
    data object ItemAdded : CartEvent
}

// ============================================================
// COMPARISON: WITH vs WITHOUT StateStoreHolder
// ============================================================

/**
 * ❌ WITHOUT StateStoreHolder (Repetitive boilerplate)
 */
class ProductViewModelOld(
    private val useCase: GetProductUseCase,
) : ViewModel() {
    // ❌ Same code in every ViewModel
    private val _uiState = MutableStateFlow(ProductUiState())
    val uiState: StateFlow<ProductUiState> = _uiState.asStateFlow()
    
    fun loadProduct() {
        viewModelScope.launch {
            // ❌ Verbose update
            _uiState.update { it.copy(isLoading = true) }
            // ...
        }
    }
}

/**
 * ✅ WITH StateStoreHolder (Clean, no boilerplate)
 */
class ProductViewModel(
    private val useCase: GetProductUseCase,
) : BaseStateViewModel<ProductUiState>(ProductUiState()) {
    // ✅ No boilerplate needed!
    
    fun loadProduct() {
        viewModelScope.launch {
            // ✅ Clean update
            updateState { it.copy(isLoading = true) }
            // ...
        }
    }
}

// ============================================================
// TESTING BENEFITS
// ============================================================

/**
 * Testing with StateStoreHolder is easier:
 */
class ProfileViewModelTest {
    // ✅ Can test state updates through interface
    @Test
    fun `loadProfile updates state correctly`() = runTest {
        val viewModel = ProfileViewModel(fakeUseCase)
        
        // State is exposed through StateStoreHolder interface
        viewModel.state.test {
            viewModel.loadProfile("123")
            
            val loadingState = awaitItem()
            assertEquals(true, loadingState.isLoading)
            
            val successState = awaitItem()
            assertEquals(false, successState.isLoading)
            assertEquals("John", successState.name)
        }
    }
}

// ============================================================
// DEPENDENCY INJECTION SETUP
// ============================================================

/**
 * HILT:
 */
// @HiltViewModel
// class MyViewModel @Inject constructor(
//     useCase: MyUseCase
// ) : MviViewModel<UiState, Event>(UiState())

/**
 * KOIN:
 */
// val viewModelModule = module {
//     viewModelOf(::ProfileViewModel)
//     viewModelOf(::LoginViewModel)
// }

// ============================================================
// KEY TAKEAWAYS
// ============================================================

/**
 * 1. StateStoreHolder Pattern Benefits:
 *    ✅ Eliminates duplicate code across ViewModels
 *    ✅ Consistent state management pattern
 *    ✅ Easier to test
 *    ✅ Type-safe
 *    ✅ Less error-prone
 * 
 * 2. Choose Your Pattern:
 *    - MviViewModel: Most complete (state + events)
 *    - BaseStateViewModel: Only state management
 *    - StateStore delegate: Composition over inheritance
 * 
 * 3. When to Use:
 *    ✅ Always for production apps (avoid boilerplate)
 *    ✅ When you have multiple ViewModels (99% of apps)
 *    ✅ When you want consistent patterns
 *    ❌ Only skip for POC/prototype with 1 ViewModel
 * 
 * 4. Code Reduction:
 *    - Without: ~5-7 lines per ViewModel
 *    - With: ~0 lines (inherit from base)
 *    - 10 ViewModels = 50-70 lines saved!
 * 
 * 5. Best Practice:
 *    Create your own MviViewModel base in your project
 *    and have all ViewModels inherit from it.
 */

// ============================================================
// RECOMMENDED PROJECT STRUCTURE
// ============================================================

/**
 * /presentation
 *   /base
 *     - MviViewModel.kt          ← Your base ViewModel
 *     - StateStoreHolder.kt      ← Interfaces
 *   /profile
 *     - ProfileViewModel.kt      ← Extends MviViewModel
 *     - ProfileScreen.kt
 *   /login
 *     - LoginViewModel.kt        ← Extends MviViewModel
 *     - LoginScreen.kt
 */

// Mock classes for examples
interface GetUserUseCase { suspend operator fun invoke(userId: String): Result<User> }
interface LoginUseCase { suspend operator fun invoke(email: String, password: String): Result<Unit> }
interface GetProductUseCase { suspend operator fun invoke(): Result<Unit> }
interface CartRepository { suspend fun addItem(productId: String): Result<Unit> }
data class User(val name: String, val email: String)
