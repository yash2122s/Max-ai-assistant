package com.agentcore.compose

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcore.controller.AgentController
import com.agentcore.controller.AgentEvent
import com.agentcore.controller.AgentState
import com.agentcore.skill.base.SkillResult
import com.agentcore.skills.intent.IntentDetectInput
import com.agentcore.skills.intent.IntentDetectOutput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
// import dagger.hilt.android.lifecycle.HiltViewModel
// import javax.inject.Inject

// ============================================================
// LAST UPDATED: 2026-01-24
// Synced with: skills/guides/07-state-management.md
// ============================================================
//
// ⚠️  IMPORTANT: This is an AGENT FRAMEWORK DEMO ViewModel
//
// This file demonstrates Pattern 2 (AgentController) for agent-specific features.
// For standard app features, see:
//   - skills/templates/examples/UseCaseViewModelExample.kt (Pattern 1)
//   - skills/guides/07-state-management.md (Clean Architecture)
//
// WHY AgentController here?
// - This is a demo UI for showing Agent Framework capabilities
// - Needs direct access to agent state, events, and skill execution
// - Used for debugging/monitoring agent behavior
//
// For YOUR production features → Use Pattern 1 (UseCase injection)
// ============================================================

// ============================================================
// UI STATE
// ============================================================

/**
 * UI State for the Agent Demo screen.
 * Following MVI/MVVM pattern with @Immutable data class.
 * 
 * @see skills/guides/07-state-management.md for state management patterns
 */
@Immutable
data class AgentDemoUiState(
    val userInput: String = "",
    val isProcessing: Boolean = false,
    val results: List<AgentResultItem> = emptyList(),
    val agentState: AgentState = AgentState.Initial,
    val error: String? = null,
) {
    // Derived properties for UI logic
    val canSubmit: Boolean get() = userInput.isNotBlank() && !isProcessing
    val showError: Boolean get() = error != null
    val hasResults: Boolean get() = results.isNotEmpty()
}

/**
 * Represents a result item to display in the UI.
 */
@Immutable
data class AgentResultItem(
    val id: String,
    val type: ResultType,
    val title: String,
    val content: String,
    val timestamp: Long,
    val metadata: Map<String, Any> = emptyMap(),
)

@Immutable
enum class ResultType {
    USER_INPUT,
    INTENT_DETECTED,
    SKILL_RESULT,
    ERROR,
    AGENT_EVENT,
}

// ============================================================
// UI EVENTS (One-time events)
// ============================================================

/**
 * One-time UI events following Channel pattern.
 * Use with EventsEffect in Compose for lifecycle-aware handling.
 * 
 * @see skills/guides/07-state-management.md for event handling patterns
 */
sealed interface AgentDemoEvent {
    data class ShowSnackbar(val message: String) : AgentDemoEvent
    data class NavigateToSkillDetails(val skillId: String) : AgentDemoEvent
    data object ClearInput : AgentDemoEvent
}

// ============================================================
// VIEWMODEL VARIANTS
// ============================================================
// 
// TWO PATTERNS:
// 1. Clean Architecture Pattern: ViewModel → UseCase → Repository
// 2. Agent Framework Pattern: ViewModel → AgentController (for agent-specific features)
//
// Choose based on your use case:
// - Use Pattern 1 for standard app features (RECOMMENDED)
// - Use Pattern 2 when leveraging agent framework capabilities

// ============================================================
// PATTERN 1: CLEAN ARCHITECTURE (RECOMMENDED)
// ============================================================

/**
 * ViewModel following Clean Architecture with UseCase injection.
 * 
 * This is the RECOMMENDED pattern for most app features.
 * 
 * Architecture:
 * UI → ViewModel → UseCase → Repository
 * 
 * HILT VERSION:
 */
// @HiltViewModel
// class AgentDemoViewModel @Inject constructor(
//     private val detectIntentUseCase: DetectIntentUseCase,
//     private val processQueryUseCase: ProcessQueryUseCase,
// ) : ViewModel() {
//     // Same state/events pattern as below
// }

/**
 * KOIN VERSION:
 */
// class AgentDemoViewModel(
//     private val detectIntentUseCase: DetectIntentUseCase,
//     private val processQueryUseCase: ProcessQueryUseCase,
// ) : ViewModel() {
//     // Same state/events pattern as below
// }
// 
// // In Koin module:
// viewModelOf(::AgentDemoViewModel)

// ============================================================
// PATTERN 2: AGENT FRAMEWORK (FOR AGENT FEATURES ONLY)
// ============================================================

/**
 * ⚠️  SPECIAL CASE: ViewModel using AgentController directly
 * 
 * WHY THIS FILE USES AgentController:
 * This is NOT a standard app feature - it's a demo/debugging UI for the Agent Framework.
 * 
 * Valid use cases for AgentController:
 * ✅ Agent debugging/monitoring UI (like this)
 * ✅ Agent developer tools
 * ✅ Framework testing/demo screens
 * ✅ Rapid prototyping of agent features
 * 
 * ❌ NEVER use AgentController for:
 * ❌ Standard app features (login, profile, settings, etc.)
 * ❌ Business logic flows
 * ❌ Production user-facing features
 * 
 * For 99% of your app features → Use Pattern 1 (UseCase injection)
 * See: skills/templates/examples/UseCaseViewModelExample.kt
 * 
 * Best Practices:
 * - Extends AndroidX ViewModel for lifecycle management
 * - Uses viewModelScope for coroutine lifecycle
 * - Exposes StateFlow for UI state (read-only)
 * - Uses Channel for one-time events
 * - All business logic in ViewModel, UI only dispatches actions
 * 
 * HILT VERSION (uncomment to use):
 * @HiltViewModel
 * class AgentDemoViewModel @Inject constructor(
 *     private val agentController: AgentController,
 * ) : ViewModel()
 * 
 * KOIN VERSION (uncomment to use):
 */
class AgentDemoViewModel(
    private val agentController: AgentController,
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(AgentDemoUiState())
    val uiState: StateFlow<AgentDemoUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<AgentDemoEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    init {
        // Observe agent state changes
        agentController.state
            .onEach { agentState ->
                _uiState.update { it.copy(agentState = agentState) }
            }
            .launchIn(viewModelScope)
        
        // Observe agent events
        agentController.events
            .onEach { event ->
                handleAgentEvent(event)
            }
            .launchIn(viewModelScope)
    }
    
    // ============================================================
    // PUBLIC ACTIONS (Called by UI)
    // ============================================================
    
    /**
     * Updates the user input.
     */
    fun onInputChanged(input: String) {
        _uiState.update { it.copy(userInput = input) }
    }
    
    /**
     * Submits the current input for processing.
     */
    fun onSubmit() {
        val input = _uiState.value.userInput.trim()
        if (input.isBlank()) return
        
        viewModelScope.launch {
            processInput(input)
        }
    }
    
    /**
     * Clears all results.
     */
    fun onClearResults() {
        _uiState.update { it.copy(results = emptyList()) }
    }
    
    /**
     * Clears the current error.
     */
    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }
    
    // ============================================================
    // PRIVATE BUSINESS LOGIC
    // ============================================================
    
    /**
     * Processes user input through the agent.
     */
    private suspend fun processInput(input: String) {
        _uiState.update { 
            it.copy(
                isProcessing = true,
                error = null,
            )
        }
        
        // Add user input to results
        addResult(
            AgentResultItem(
                id = "input_${System.currentTimeMillis()}",
                type = ResultType.USER_INPUT,
                title = "You",
                content = input,
                timestamp = System.currentTimeMillis(),
            )
        )
        
        // Execute intent detection
        val result = agentController.executeSkill<IntentDetectInput, IntentDetectOutput>(
            skillId = "core.intent.detect",
            input = IntentDetectInput(
                text = input,
                maxResults = 3,
                minConfidence = 0.3f,
            ),
        )
        
        // Handle result
        when (result) {
            is SkillResult.Success -> {
                val output = result.data
                
                if (output.hasIntent) {
                    addResult(
                        AgentResultItem(
                            id = "intent_${System.currentTimeMillis()}",
                            type = ResultType.INTENT_DETECTED,
                            title = "Intent Detected",
                            content = buildString {
                                append("Intent: ${output.primaryIntent?.intent}\n")
                                append("Confidence: ${String.format("%.2f", output.confidence * 100)}%\n")
                                if (output.intents.size > 1) {
                                    append("Other intents: ${output.intents.drop(1).joinToString { it.intent }}")
                                }
                            },
                            timestamp = System.currentTimeMillis(),
                            metadata = mapOf(
                                "intent" to (output.primaryIntent?.intent ?: ""),
                                "confidence" to output.confidence,
                            ),
                        )
                    )
                } else {
                    addResult(
                        AgentResultItem(
                            id = "no_intent_${System.currentTimeMillis()}",
                            type = ResultType.SKILL_RESULT,
                            title = "No Intent Detected",
                            content = "Could not determine intent from: \"$input\"",
                            timestamp = System.currentTimeMillis(),
                        )
                    )
                }
                
                _events.send(AgentDemoEvent.ClearInput)
            }
            
            is SkillResult.Failure -> {
                val error = result.error
                addResult(
                    AgentResultItem(
                        id = "error_${System.currentTimeMillis()}",
                        type = ResultType.ERROR,
                        title = "Error",
                        content = error.message,
                        timestamp = System.currentTimeMillis(),
                        metadata = mapOf("errorCode" to error.code),
                    )
                )
                
                _uiState.update { it.copy(error = error.message) }
                _events.send(AgentDemoEvent.ShowSnackbar("Error: ${error.message}"))
            }
        }
        
        _uiState.update { 
            it.copy(
                isProcessing = false,
                userInput = "", // Clear input after processing
            )
        }
    }
    
    /**
     * Handles agent events.
     */
    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.SkillStarted -> {
                // Optionally show skill started
            }
            is AgentEvent.SkillCompleted -> {
                addResult(
                    AgentResultItem(
                        id = "skill_${System.currentTimeMillis()}",
                        type = ResultType.AGENT_EVENT,
                        title = "Skill Completed",
                        content = "${event.skillName} - ${if (event.success) "Success" else "Failed"} (${event.durationMs}ms)",
                        timestamp = event.timestamp,
                    )
                )
            }
            is AgentEvent.Error -> {
                _uiState.update { it.copy(error = event.message) }
            }
            else -> {
                // Handle other events as needed
            }
        }
    }
    
    /**
     * Adds a result to the list.
     */
    private fun addResult(item: AgentResultItem) {
        _uiState.update { state ->
            state.copy(results = state.results + item)
        }
    }
}

// ============================================================
// USAGE EXAMPLES
// ============================================================

/**
 * PATTERN 1: UseCase Injection (Clean Architecture)
 * 
 * 1. Define UseCase:
 * ```kotlin
 * class DetectIntentUseCase @Inject constructor(
 *     private val intentRepository: IntentRepository
 * ) {
 *     suspend operator fun invoke(text: String): Result<Intent> {
 *         return intentRepository.detectIntent(text)
 *     }
 * }
 * ```
 * 
 * 2. Inject into ViewModel:
 * ```kotlin
 * @HiltViewModel
 * class MyViewModel @Inject constructor(
 *     private val detectIntent: DetectIntentUseCase
 * ) : ViewModel() {
 *     fun onSubmit(text: String) {
 *         viewModelScope.launch {
 *             detectIntent(text)
 *                 .onSuccess { intent -> /* handle */ }
 *                 .onFailure { error -> /* handle */ }
 *         }
 *     }
 * }
 * ```
 * 
 * 3. Use in Compose:
 * ```kotlin
 * @Composable
 * fun MyRoute(viewModel: MyViewModel = hiltViewModel()) {
 *     // or koinViewModel() for Koin
 * }
 * ```
 */

/**
 * PATTERN 2: AgentController (Agent Framework)
 * 
 * ⚠️  THIS PATTERN IS ONLY FOR AGENT FRAMEWORK TOOLING
 * 
 * This AgentDemoViewModel uses AgentController because it's a:
 * - Demo screen showing agent capabilities
 * - Developer tool for testing agent features
 * - Debugging UI for agent state/events
 * 
 * For YOUR app features (login, profile, cart, etc.):
 * ❌ DON'T copy this pattern
 * ✅ Use Pattern 1 (UseCase) instead
 * 
 * Example of when to use each:
 * - Login screen → Pattern 1 (LoginUseCase)
 * - Profile screen → Pattern 1 (GetUserUseCase)
 * - Agent debugger → Pattern 2 (AgentController) ← Only this!
 * 
 * Compose usage:
 * ```kotlin
 * @Composable
 * fun AgentDemoRoute(
 *     viewModel: AgentDemoViewModel = hiltViewModel() // or koinViewModel()
 * )
 * ```
 */

// ============================================================
// DI SETUP
// ============================================================

/**
 * HILT SETUP:
 * 
 * No additional setup needed - just use @HiltViewModel and @Inject
 * 
 * ```kotlin
 * @HiltViewModel
 * class MyViewModel @Inject constructor(
 *     private val useCase: MyUseCase
 * ) : ViewModel()
 * ```
 */

/**
 * KOIN SETUP:
 * 
 * In your Koin module:
 * ```kotlin
 * val viewModelModule = module {
 *     // Pattern 1: UseCase injection
 *     viewModelOf(::MyViewModel)
 *     
 *     // Pattern 2: AgentController injection
 *     viewModelOf(::AgentDemoViewModel)
 * }
 * ```
 * 
 * In Compose:
 * ```kotlin
 * @Composable
 * fun MyRoute(viewModel: MyViewModel = koinViewModel())
 * ```
 */

// ============================================================
// WHICH PATTERN SHOULD YOU USE? (DECISION GUIDE)
// ============================================================

/**
 * DECISION FLOWCHART:
 * 
 * Are you building an agent debugging/demo UI?
 *   YES → Use Pattern 2 (AgentController)
 *         Example: AgentDemoViewModel.kt (this file)
 *   
 *   NO  → Use Pattern 1 (UseCase injection)
 *         Example: UseCaseViewModelExample.kt
 * 
 * CONCRETE EXAMPLES:
 * 
 * ✅ Pattern 1 (UseCase) - 99% of your app:
 *   - LoginViewModel(loginUseCase: LoginUseCase)
 *   - ProfileViewModel(getUserUseCase: GetUserUseCase)
 *   - CartViewModel(addToCartUseCase: AddToCartUseCase)
 *   - SettingsViewModel(updateSettingsUseCase: UpdateSettingsUseCase)
 * 
 * ✅ Pattern 2 (AgentController) - Only agent tools:
 *   - AgentDemoViewModel(agentController: AgentController) ← This file
 *   - AgentDebuggerViewModel(agentController: AgentController)
 *   - SkillMonitorViewModel(agentController: AgentController)
 * 
 * ARCHITECTURE LAYERS:
 * 
 * Pattern 1 (Clean Architecture):
 *   UI → ViewModel → UseCase → Repository → Data Source
 *   └── Proper layer separation
 *   └── Testable
 *   └── Maintainable
 * 
 * Pattern 2 (Agent Framework):
 *   UI → ViewModel → AgentController → Skills
 *   └── Direct agent access
 *   └── Only for agent tooling
 *   └── Not for business features
 */

// ============================================================
// STATESTOREHOLDER PATTERN (RECOMMENDED TO AVOID DUPLICATION)
// ============================================================

/**
 * StateStoreHolder pattern helps avoid code duplication across ViewModels.
 * 
 * Problem: Every ViewModel needs this boilerplate:
 * ```kotlin
 * private val _uiState = MutableStateFlow(...)
 * val uiState = _uiState.asStateFlow()
 * _uiState.update { ... }
 * ```
 * 
 * Solution: Create base ViewModel with StateStoreHolder:
 * StateStoreHolder pattern tách biệt concerns:
 * 
 * ```kotlin
 * // Separate interfaces - flexible composition
 * interface StateStoreHolder<S> {
 *     val state: StateFlow<S>
 *     fun updateState(transform: (S) -> S)
 * }
 * 
 * interface EventHolder<E> {
 *     val events: Flow<E>
 *     fun sendEvent(event: E)
 * }
 * 
 * // Base ViewModel implements both
 * abstract class MviViewModel<S, E>(initialState: S) : ViewModel(),
 *     StateStoreHolder<S>, EventHolder<E> {
 *     private val _state = MutableStateFlow(initialState)
 *     override val state: StateFlow<S> = _state.asStateFlow()
 *     
 *     private val _events = Channel<E>(Channel.BUFFERED)
 *     override val events = _events.receiveAsFlow()
 *     
 *     override fun updateState(transform: (S) -> S) = _state.update(transform)
 *     override fun sendEvent(event: E) { _events.trySend(event) }
 * }
 * 
 * // Then in your ViewModels:
 * class ProfileViewModel : MviViewModel<ProfileUiState, ProfileEvent>(ProfileUiState()) {
 *     fun loadProfile() {
 *         updateState { it.copy(isLoading = true) }  // ✅ No boilerplate!
 *     }
 * }
 * ```
 * 
 * Benefits:
 * - ✅ Flexible: Implement only what you need
 * - ✅ Testable: Test each interface separately
 * - ✅ Reusable: Can use outside ViewModel
 * - ✅ DRY: 10 ViewModels = 50-70 lines saved!
 * 
 * @see skills/templates/examples/StateStoreHolderExample.kt for complete implementation
 * @see skills/guides/07-state-management.md for pattern details
 */
