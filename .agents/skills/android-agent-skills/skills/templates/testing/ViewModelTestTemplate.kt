package com.example.app.feature.template

import androidx.compose.runtime.Immutable
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*

/**
 * Template for testing ViewModels.
 * 
 * This template demonstrates best practices for ViewModel testing:
 * - State transitions testing with Turbine
 * - Event/Effect testing
 * - Error handling scenarios
 * - Concurrent operations
 * 
 * Test Coverage Goals:
 * - State transitions: 40% (loading → success/error)
 * - User actions: 30% (each action method)
 * - Error handling: 20% (network, validation, etc.)
 * - Edge cases: 10% (empty, boundary conditions)
 * 
 * @see 11-testing.md for testing guidelines
 * @see 07-state-management.md for ViewModel patterns
 * 
 * Created & Reviewed by: TrongLB & AI Agents
 */

// ============================================================
// EXAMPLE VIEWMODEL TO TEST
// ============================================================

// Domain model
data class User(
    val id: String,
    val name: String,
    val email: String,
)

// UI State (✅ ALWAYS mark with @Immutable for Compose stability)
@Immutable
data class ProfileUiState(
    val user: User? = null,
    val name: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
) {
    val isValid: Boolean get() = name.isNotBlank() && email.contains("@")
    val hasChanges: Boolean get() = user?.let { it.name != name || it.email != email } ?: false
}

@Immutable
data class UiError(
    val message: String,
    val isRetryable: Boolean = false,
)

// Events (one-time effects)
sealed interface ProfileEvent {
    data object NavigateToLogin : ProfileEvent
    data object ProfileUpdated : ProfileEvent
    data class ShowToast(val message: String) : ProfileEvent
}

// UseCases (interfaces for mocking)
interface GetUserUseCase {
    suspend operator fun invoke(userId: String): Result<User>
}

interface UpdateUserProfileUseCase {
    suspend operator fun invoke(userId: String, name: String, email: String): Result<User>
}

// ============================================================
// TEST SUITE
// ============================================================

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileViewModelTest {
    
    // ========================================
    // Test Setup
    // ========================================
    
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var getUserUseCase: GetUserUseCase
    private lateinit var updateUserUseCase: UpdateUserProfileUseCase
    private lateinit var viewModel: ProfileViewModel
    
    @BeforeEach
    fun setup() {
        // Set main dispatcher for ViewModel coroutines
        Dispatchers.setMain(testDispatcher)
        
        // Create mocks
        getUserUseCase = mockk()
        updateUserUseCase = mockk()
        
        // Create ViewModel (do NOT call methods that trigger coroutines in init{})
        viewModel = ProfileViewModel(
            getUserUseCase = getUserUseCase,
            updateUserUseCase = updateUserUseCase,
        )
    }
    
    @AfterEach
    fun tearDown() {
        // Reset main dispatcher
        Dispatchers.resetMain()
        
        // Clear all mocks
        clearAllMocks()
    }
    
    // ========================================
    // State Transition Tests (40%)
    // ========================================
    
    @Nested
    @DisplayName("Load Profile - State Transitions")
    inner class LoadProfileTests {
        
        @Test
        fun `when loadProfile succeeds then state shows loading then success`() = runTest {
            // Given
            val user = User(id = "1", name = "John Doe", email = "john@test.com")
            coEvery { getUserUseCase("1") } returns Result.success(user)
            
            // When & Then - Use Turbine for state testing
            viewModel.state.test {
                // Initial state
                val initial = awaitItem()
                assertThat(initial.isLoading).isFalse()
                assertThat(initial.user).isNull()
                
                // Trigger action
                viewModel.loadProfile("1")
                
                // Loading state
                val loading = awaitItem()
                assertThat(loading.isLoading).isTrue()
                
                // Success state
                val success = awaitItem()
                assertThat(success.isLoading).isFalse()
                assertThat(success.user).isEqualTo(user)
                assertThat(success.name).isEqualTo("John Doe")
                assertThat(success.email).isEqualTo("john@test.com")
                assertThat(success.error).isNull()
                
                cancelAndIgnoreRemainingEvents()
            }
            
            // Verify use case called
            coVerify(exactly = 1) { getUserUseCase("1") }
        }
        
        @Test
        fun `when loadProfile fails then state shows error`() = runTest {
            // Given
            val exception = RuntimeException("Network error")
            coEvery { getUserUseCase("1") } returns Result.failure(exception)
            
            // When & Then
            viewModel.state.test {
                awaitItem() // Initial
                
                viewModel.loadProfile("1")
                
                awaitItem() // Loading
                
                val error = awaitItem()
                assertThat(error.isLoading).isFalse()
                assertThat(error.error).isNotNull()
                assertThat(error.error?.message).contains("Network error")
                assertThat(error.error?.isRetryable).isTrue()
                
                cancelAndIgnoreRemainingEvents()
            }
        }
        
        @Test
        fun `when loadProfile fails with auth error then navigates to login`() = runTest {
            // Given
            val authError = SecurityException("Unauthorized")
            coEvery { getUserUseCase("1") } returns Result.failure(authError)
            
            // When & Then - Test events
            viewModel.events.test {
                viewModel.loadProfile("1")
                advanceUntilIdle()
                
                val event = awaitItem()
                assertThat(event).isEqualTo(ProfileEvent.NavigateToLogin)
                
                cancelAndIgnoreRemainingEvents()
            }
        }
    }
    
    // ========================================
    // User Action Tests (30%)
    // ========================================
    
    @Nested
    @DisplayName("User Actions")
    inner class UserActionTests {
        
        @Test
        fun `when onNameChange then state updates name`() = runTest {
            // When
            viewModel.onNameChange("New Name")
            advanceUntilIdle()
            
            // Then
            assertThat(viewModel.state.value.name).isEqualTo("New Name")
        }
        
        @Test
        fun `when onEmailChange then state updates email`() = runTest {
            // When
            viewModel.onEmailChange("new@email.com")
            advanceUntilIdle()
            
            // Then
            assertThat(viewModel.state.value.email).isEqualTo("new@email.com")
        }
        
        @Test
        fun `when updateProfile succeeds then emits ProfileUpdated event`() = runTest {
            // Given - Load user first
            val user = User(id = "1", name = "John", email = "john@test.com")
            coEvery { getUserUseCase("1") } returns Result.success(user)
            coEvery { 
                updateUserUseCase("1", "Jane", "jane@test.com") 
            } returns Result.success(user.copy(name = "Jane", email = "jane@test.com"))
            
            viewModel.loadProfile("1")
            advanceUntilIdle()
            
            // Make changes
            viewModel.onNameChange("Jane")
            viewModel.onEmailChange("jane@test.com")
            
            // When & Then
            viewModel.events.test {
                viewModel.updateProfile()
                advanceUntilIdle()
                
                val event = awaitItem()
                assertThat(event).isEqualTo(ProfileEvent.ProfileUpdated)
                
                cancelAndIgnoreRemainingEvents()
            }
        }
        
        @Test
        fun `when onDismissError then clears error`() = runTest {
            // Given - State has error
            val exception = RuntimeException("Error")
            coEvery { getUserUseCase("1") } returns Result.failure(exception)
            
            viewModel.loadProfile("1")
            advanceUntilIdle()
            assertThat(viewModel.state.value.error).isNotNull()
            
            // When
            viewModel.onDismissError()
            advanceUntilIdle()
            
            // Then
            assertThat(viewModel.state.value.error).isNull()
        }
    }
    
    // ========================================
    // Error Handling Tests (20%)
    // ========================================
    
    @Nested
    @DisplayName("Error Handling")
    inner class ErrorHandlingTests {
        
        @Test
        fun `when updateProfile fails with validation error then shows field error`() = runTest {
            // Given
            val user = User(id = "1", name = "John", email = "john@test.com")
            coEvery { getUserUseCase("1") } returns Result.success(user)
            coEvery { 
                updateUserUseCase(any(), any(), any()) 
            } returns Result.failure(IllegalArgumentException("Invalid email format"))
            
            viewModel.loadProfile("1")
            advanceUntilIdle()
            viewModel.onEmailChange("invalid")
            
            // When
            viewModel.updateProfile()
            advanceUntilIdle()
            
            // Then
            val state = viewModel.state.value
            assertThat(state.fieldErrors).containsKey("email")
        }
        
        @Test
        fun `when network error occurs then shows retryable error`() = runTest {
            // Given
            coEvery { getUserUseCase("1") } returns Result.failure(java.net.UnknownHostException())
            
            // When
            viewModel.loadProfile("1")
            advanceUntilIdle()
            
            // Then
            val state = viewModel.state.value
            assertThat(state.error?.isRetryable).isTrue()
        }
    }
    
    // ========================================
    // Edge Case Tests (10%)
    // ========================================
    
    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {
        
        @Test
        fun `when name is blank then isValid is false`() = runTest {
            // When
            viewModel.onNameChange("")
            viewModel.onEmailChange("valid@email.com")
            advanceUntilIdle()
            
            // Then
            assertThat(viewModel.state.value.isValid).isFalse()
        }
        
        @Test
        fun `when email has no @ then isValid is false`() = runTest {
            // When
            viewModel.onNameChange("John")
            viewModel.onEmailChange("invalidemail")
            advanceUntilIdle()
            
            // Then
            assertThat(viewModel.state.value.isValid).isFalse()
        }
        
        @Test
        fun `when values unchanged then hasChanges is false`() = runTest {
            // Given
            val user = User(id = "1", name = "John", email = "john@test.com")
            coEvery { getUserUseCase("1") } returns Result.success(user)
            
            viewModel.loadProfile("1")
            advanceUntilIdle()
            
            // Then - No changes made
            assertThat(viewModel.state.value.hasChanges).isFalse()
        }
        
        @Test
        fun `when loadProfile called multiple times then only processes latest`() = runTest {
            // Given
            val user1 = User(id = "1", name = "User1", email = "user1@test.com")
            val user2 = User(id = "2", name = "User2", email = "user2@test.com")
            
            coEvery { getUserUseCase("1") } coAnswers {
                kotlinx.coroutines.delay(100)
                Result.success(user1)
            }
            coEvery { getUserUseCase("2") } returns Result.success(user2)
            
            // When - Call twice quickly
            viewModel.loadProfile("1")
            viewModel.loadProfile("2")
            advanceUntilIdle()
            
            // Then - Should have user2 (latest)
            assertThat(viewModel.state.value.user?.id).isEqualTo("2")
        }
    }
}

// ============================================================
// STUB VIEWMODEL (Replace with your actual implementation)
// ============================================================

class ProfileViewModel(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserProfileUseCase,
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ProfileUiState())
    val state = _state.asStateFlow()
    
    private val _events = kotlinx.coroutines.channels.Channel<ProfileEvent>()
    val events = _events.receiveAsFlow()
    
    private var currentJob: kotlinx.coroutines.Job? = null
    
    fun loadProfile(userId: String) {
        currentJob?.cancel()
        currentJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            _state.update { it.copy(isLoading = true, error = null) }
            
            getUserUseCase(userId)
                .onSuccess { user ->
                    _state.update { 
                        it.copy(
                            user = user, 
                            name = user.name, 
                            email = user.email,
                            isLoading = false,
                        ) 
                    }
                }
                .onFailure { error ->
                    when (error) {
                        is SecurityException -> {
                            _events.send(ProfileEvent.NavigateToLogin)
                        }
                        else -> {
                            _state.update { 
                                it.copy(
                                    isLoading = false, 
                                    error = UiError(
                                        message = error.message ?: "Unknown error",
                                        isRetryable = error is java.net.UnknownHostException,
                                    ),
                                ) 
                            }
                        }
                    }
                }
        }
    }
    
    fun onNameChange(name: String) {
        _state.update { it.copy(name = name) }
    }
    
    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email) }
    }
    
    fun updateProfile() {
        val currentState = _state.value
        val userId = currentState.user?.id ?: return
        
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            updateUserUseCase(userId, currentState.name, currentState.email)
                .onSuccess { 
                    _events.send(ProfileEvent.ProfileUpdated)
                }
                .onFailure { error ->
                    if (error is IllegalArgumentException) {
                        _state.update { 
                            it.copy(fieldErrors = mapOf("email" to error.message.orEmpty())) 
                        }
                    }
                }
        }
    }
    
    fun onDismissError() {
        _state.update { it.copy(error = null) }
    }
}

private fun <T> kotlinx.coroutines.flow.MutableStateFlow<T>.asStateFlow() = this as kotlinx.coroutines.flow.StateFlow<T>
private fun <T> kotlinx.coroutines.channels.Channel<T>.receiveAsFlow() = kotlinx.coroutines.flow.flow { 
    for (item in this@receiveAsFlow) emit(item) 
}
