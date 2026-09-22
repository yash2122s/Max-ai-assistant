package com.agentcore.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
// import dagger.hilt.android.lifecycle.HiltViewModel // Uncomment if using Hilt
// import javax.inject.Inject // Uncomment if using Hilt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ============================================================
// LAST UPDATED: 2026-01-24
// Synced with: skills/guides/05-jetpack-compose.md
//              skills/guides/07-state-management.md
// ============================================================

// ============================================================
// COMPOSE UI EXAMPLE
// ============================================================
// 
// This file provides an example Compose UI implementation
// following the latest best practices from guides.
// 
// Copy this to your app's presentation layer and adapt as needed.
// 
// IMPORTANT: Ensure you have these dependencies:
// - androidx.compose.material3:material3
// - androidx.compose.foundation:foundation
// - androidx.lifecycle:lifecycle-runtime-compose
// - androidx.hilt:hilt-navigation-compose (if using Hilt)
// - org.koin:koin-androidx-compose (if using Koin)

// ============================================================
// TEMPLATE: ROUTE & SCREEN PATTERN
// ============================================================

// 1. Define UI State (@Immutable for Compose stability)
@Immutable
data class TemplateUiState(
    val isLoading: Boolean = false,
    val data: String? = null,
    val error: String? = null,
) {
    // Derived properties for UI logic
    val showContent: Boolean get() = !isLoading && data != null
    val showError: Boolean get() = error != null
}

// 2. Define UI Events (one-time events)
sealed interface TemplateEvent {
    data class ShowSnackbar(val message: String) : TemplateEvent
    data object NavigateBack : TemplateEvent
}

// 3. ViewModel following MVVM + MVI patterns
// @HiltViewModel
class TemplateViewModel /* @Inject constructor() */ : ViewModel() {
    
    private val _uiState = MutableStateFlow(TemplateUiState())
    val uiState: StateFlow<TemplateUiState> = _uiState.asStateFlow()
    
    private val _events = Channel<TemplateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            try {
                // Simulate data loading
                // val result = useCase()
                _uiState.update { it.copy(isLoading = false, data = "Loaded data") }
                _events.send(TemplateEvent.ShowSnackbar("Data loaded successfully"))
            } catch (e: CancellationException) {
                throw e  // ALWAYS rethrow CancellationException
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
                _events.send(TemplateEvent.ShowSnackbar("Error: ${e.message}"))
            }
        }
    }
    
    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }
}

// 4. Route (Stateful) - Handles ViewModel, navigation, and events
// Parameter order: Required → State → Modifier → Callbacks
@Composable
fun TemplateRoute(
    modifier: Modifier = Modifier,
    viewModel: TemplateViewModel = hiltViewModel(),
    onNavigateUp: () -> Unit,
    onShowSnackbar: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // Handle one-time events (lifecycle-aware)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TemplateEvent.ShowSnackbar -> onShowSnackbar(event.message)
                is TemplateEvent.NavigateBack -> onNavigateUp()
            }
        }
    }

    TemplateScreen(
        uiState = uiState,
        modifier = modifier,
        onLoadData = viewModel::loadData,
        onDismissError = viewModel::onDismissError,
        onNavigateUp = onNavigateUp,
    )
}

// 5. Screen (Stateless) - Pure UI, only state and callbacks
// Parameter order: Required → State → Modifier → Callbacks
@Composable
fun TemplateScreen(
    uiState: TemplateUiState,
    modifier: Modifier = Modifier,
    onLoadData: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateUp: () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            uiState.isLoading -> CircularProgressIndicator()
            uiState.showError -> Text(text = "Error: ${uiState.error}")
            uiState.showContent -> Text(text = uiState.data ?: "No Data")
            else -> Text(text = "No Data")
        }
    }
}

// 6. Previews (Multiple states for comprehensive preview)
@Preview(name = "Loading State", showBackground = true)
@Composable
private fun TemplateScreenLoadingPreview() {
    TemplateScreen(
        uiState = TemplateUiState(isLoading = true),
        onLoadData = {},
        onDismissError = {},
        onNavigateUp = {}
    )
}

@Preview(name = "Success State", showBackground = true)
@Composable
private fun TemplateScreenSuccessPreview() {
    TemplateScreen(
        uiState = TemplateUiState(data = "Preview Data"),
        onLoadData = {},
        onDismissError = {},
        onNavigateUp = {}
    )
}

@Preview(name = "Error State", showBackground = true)
@Composable
private fun TemplateScreenErrorPreview() {
    TemplateScreen(
        uiState = TemplateUiState(error = "Something went wrong"),
        onLoadData = {},
        onDismissError = {},
        onNavigateUp = {}
    )
}

// ============================================================
// COMPOSE INTEGRATION GUIDE
// ============================================================

/**
 * Best Practices Summary:
 * 
 * 1. STATE MANAGEMENT (@see skills/guides/07-state-management.md)
 *    - Use @Immutable data classes for UI state
 *    - Expose StateFlow (read-only) from ViewModel
 *    - Use Channel for one-time events
 *    - Atomic state updates with update { }
 * 
 * 2. COMPOSE PATTERNS (@see skills/guides/05-jetpack-compose.md)
 *    - Separate Route (stateful) and Screen (stateless)
 *    - Parameter order: Required → State → Modifier → Callbacks
 *    - Always add modifier: Modifier = Modifier
 *    - Use method references for callbacks (viewModel::onAction)
 *    - Collect state with collectAsStateWithLifecycle()
 * 
 * 3. EVENT HANDLING
 *    - Use LaunchedEffect(Unit) for event collection
 *    - Handle navigation and snackbars in Route
 *    - Keep Screen pure (no side effects)
 * 
 * 4. DEPENDENCY INJECTION (@see skills/guides/08-dependency-injection.md)
 *    
 *    Hilt:
 *    ```kotlin
 *    @HiltViewModel
 *    class MyViewModel @Inject constructor(
 *        private val useCase: MyUseCase
 *    ) : ViewModel()
 *    
 *    // In Compose
 *    val viewModel: MyViewModel = hiltViewModel()
 *    ```
 *    
 *    Koin:
 *    ```kotlin
 *    // In module
 *    viewModelOf(::MyViewModel)
 *    
 *    // In Compose
 *    val viewModel: MyViewModel = koinViewModel()
 *    ```
 * 
 * 5. TESTING (@see skills/guides/11-testing.md)
 *    - Screen is easy to test (pure function)
 *    - ViewModel tested with runTest + Turbine
 *    - Use Paparazzi/Roborazzi for screenshot tests
 * 
 * 6. PERFORMANCE (@see skills/guides/06-compose-performance.md)
 *    - Use @Immutable/@Stable annotations
 *    - Avoid creating lambdas in Composable body
 *    - Use remember for expensive computations
 *    - Enable Strong Skipping Mode
 */
