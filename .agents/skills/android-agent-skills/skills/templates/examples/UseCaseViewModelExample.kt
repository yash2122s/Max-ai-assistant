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
// For Hilt:
// import dagger.hilt.android.lifecycle.HiltViewModel
// import javax.inject.Inject
// For Koin: (no imports needed, just constructor)

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * This is an EXAMPLE showing the Clean Architecture pattern:
 * ViewModel → UseCase → Repository
 * 
 * FILE NAMING CONVENTIONS:
 *   ViewModel:   [Feature]ViewModel.kt       (e.g., UserProfileViewModel.kt)
 *   UseCase:     [Action][Entity]UseCase.kt  (e.g., GetUserProfileUseCase.kt)
 *   UiState:     [Feature]UiState            (inner data class or separate file)
 *   Event:       [Feature]Event              (sealed interface)
 * 
 * @see UserRepositoryImpl.kt for Repository implementation pattern
 * @see StateStoreHolderExample.kt for reducing ViewModel boilerplate
 * ============================================================================
 */

// ============================================================
// CLEAN ARCHITECTURE PATTERN: ViewModel → UseCase → Repository
// ============================================================
// 
// This is the RECOMMENDED pattern for production apps.
// 
// Layer Separation:
// - Presentation Layer: ViewModel (this file)
// - Domain Layer: UseCase (business logic)
// - Data Layer: Repository (data access)

// ============================================================
// DOMAIN LAYER: USE CASES
// ============================================================

/**
 * UseCase for detecting user intent from text input.
 * 
 * Single Responsibility: One use case = one business action.
 * 
 * Benefits:
 * - Testable in isolation
 * - Reusable across multiple ViewModels
 * - Business logic separated from presentation
 */
class DetectIntentUseCase /* @Inject constructor */ (
    private val intentRepository: IntentRepository,
) {
    /**
     * operator invoke allows calling use case as function:
     * val result = detectIntentUseCase(text)
     */
    suspend operator fun invoke(text: String): Result<IntentResult> {
        // Input validation
        if (text.isBlank()) {
            return Result.failure(IllegalArgumentException("Text cannot be blank"))
        }
        
        // Business logic
        return try {
            val intent = intentRepository.detectIntent(text)
            
            // Post-processing
            if (intent.confidence < 0.5f) {
                Result.failure(Exception("Low confidence: ${intent.confidence}"))
            } else {
                Result.success(intent)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // ALWAYS rethrow CancellationException
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * UseCase for processing queries with context.
 */
class ProcessQueryUseCase /* @Inject constructor */ (
    private val queryRepository: QueryRepository,
    private val contextRepository: ContextRepository,
) {
    suspend operator fun invoke(
        query: String,
        userId: String,
    ): Result<QueryResponse> {
        // Get user context
        val context = contextRepository.getUserContext(userId)
            .getOrElse { return Result.failure(it) }
        
        // Process query with context
        return queryRepository.processQuery(query, context)
    }
}

// ============================================================
// DATA LAYER: REPOSITORIES (Interfaces)
// ============================================================

interface IntentRepository {
    suspend fun detectIntent(text: String): Result<IntentResult>
}

interface QueryRepository {
    suspend fun processQuery(query: String, context: UserContext): Result<QueryResponse>
}

interface ContextRepository {
    suspend fun getUserContext(userId: String): Result<UserContext>
}

// ============================================================
// DOMAIN MODELS
// ============================================================

data class IntentResult(
    val intent: String,
    val confidence: Float,
    val entities: Map<String, String> = emptyMap(),
)

data class QueryResponse(
    val answer: String,
    val sources: List<String> = emptyList(),
)

data class UserContext(
    val userId: String,
    val preferences: Map<String, String> = emptyMap(),
)

// ============================================================
// PRESENTATION LAYER: UI STATE & EVENTS
// ============================================================

@Immutable
data class QueryUiState(
    val query: String = "",
    val isProcessing: Boolean = false,
    val result: QueryResponse? = null,
    val error: String? = null,
) {
    // Derived properties
    val canSubmit: Boolean get() = query.isNotBlank() && !isProcessing
    val showResult: Boolean get() = result != null && !isProcessing
    val showError: Boolean get() = error != null
}

sealed interface QueryEvent {
    data class ShowSnackbar(val message: String) : QueryEvent
    data object NavigateToHistory : QueryEvent
}

// ============================================================
// PRESENTATION LAYER: VIEWMODEL WITH HILT
// ============================================================

/**
 * ViewModel following Clean Architecture with UseCase injection.
 * 
 * Responsibilities:
 * - Manage UI state
 * - Handle user actions
 * - Coordinate between UseCases
 * - Emit one-time events
 * 
 * DOES NOT:
 * - Access Repository directly (use UseCase)
 * - Contain business logic (delegate to UseCase)
 * - Access Android framework (except ViewModel APIs)
 */
// @HiltViewModel
class QueryViewModel /* @Inject constructor */ (
    private val detectIntent: DetectIntentUseCase,
    private val processQuery: ProcessQueryUseCase,
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(QueryUiState())
    val uiState: StateFlow<QueryUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<QueryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    // ============================================================
    // PUBLIC ACTIONS (Called by UI)
    // ============================================================
    
    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
    }
    
    fun onSubmit(userId: String) {
        val query = _uiState.value.query
        if (!_uiState.value.canSubmit) return
        
        viewModelScope.launch {
            processQueryWithIntent(query, userId)
        }
    }
    
    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun onClearResult() {
        _uiState.update { it.copy(result = null, query = "") }
    }
    
    // ============================================================
    // PRIVATE BUSINESS LOGIC
    // ============================================================
    
    private suspend fun processQueryWithIntent(query: String, userId: String) {
        _uiState.update { it.copy(isProcessing = true, error = null) }
        
        // Step 1: Detect intent
        val intentResult = detectIntent(query)
            .onFailure { error ->
                handleError("Failed to detect intent: ${error.message}")
                return
            }
            .getOrNull() ?: return
        
        // Step 2: Process query
        val response = processQuery(query, userId)
            .onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        result = result,
                    )
                }
                _events.send(QueryEvent.ShowSnackbar("Query processed successfully"))
            }
            .onFailure { error ->
                handleError("Failed to process query: ${error.message}")
            }
    }
    
    private suspend fun handleError(message: String) {
        _uiState.update {
            it.copy(
                isProcessing = false,
                error = message,
            )
        }
        _events.send(QueryEvent.ShowSnackbar(message))
    }
}

// ============================================================
// KOIN SETUP EXAMPLE
// ============================================================

/*
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val domainModule = module {
    // UseCases (factory - new instance each time)
    factoryOf(::DetectIntentUseCase)
    factoryOf(::ProcessQueryUseCase)
}

val dataModule = module {
    // Repositories (singleton)
    singleOf(::IntentRepositoryImpl) { bind<IntentRepository>() }
    singleOf(::QueryRepositoryImpl) { bind<QueryRepository>() }
    singleOf(::ContextRepositoryImpl) { bind<ContextRepository>() }
}

val presentationModule = module {
    // ViewModels
    viewModelOf(::QueryViewModel)
}

// In Application:
startKoin {
    modules(domainModule, dataModule, presentationModule)
}

// In Compose:
@Composable
fun QueryRoute(viewModel: QueryViewModel = koinViewModel()) {
    // ...
}
*/

// ============================================================
// HILT SETUP EXAMPLE
// ============================================================

/*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// UseCases (no annotation needed - Hilt auto-provides)
class DetectIntentUseCase @Inject constructor(
    private val intentRepository: IntentRepository,
)

// ViewModel
@HiltViewModel
class QueryViewModel @Inject constructor(
    private val detectIntent: DetectIntentUseCase,
    private val processQuery: ProcessQueryUseCase,
) : ViewModel()

// Repository binding
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindIntentRepository(
        impl: IntentRepositoryImpl
    ): IntentRepository
}

// In Compose:
@Composable
fun QueryRoute(viewModel: QueryViewModel = hiltViewModel()) {
    // ...
}
*/

// ============================================================
// KEY TAKEAWAYS
// ============================================================

/**
 * 1. Clean Architecture Layers:
 *    - Presentation (ViewModel) → Domain (UseCase) → Data (Repository)
 *    - Each layer depends only on inner layers
 *    - Domain layer is pure Kotlin (no Android dependencies)
 * 
 * 2. UseCase Benefits:
 *    - Single Responsibility (one business action)
 *    - Testable in isolation
 *    - Reusable across ViewModels
 *    - Business logic centralized
 * 
 * 3. ViewModel Responsibilities:
 *    - Manage UI state
 *    - Handle user actions
 *    - Coordinate UseCases
 *    - Emit events
 *    - Does NOT contain business logic
 * 
 * 4. DI Choices:
 *    - Hilt: More boilerplate, compile-time safety, Android-optimized
 *    - Koin: Less boilerplate, runtime DI, KMP-ready
 *    - Both work well - choose based on team preference
 * 
 * 5. When to use:
 *    - ✅ Always for production app features
 *    - ✅ When business logic is complex
 *    - ✅ When logic is reused across screens
 *    - ❌ Not needed for simple UI-only logic (e.g., toggle visibility)
 */
