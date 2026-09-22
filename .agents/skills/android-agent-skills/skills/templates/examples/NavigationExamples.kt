package com.example.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * Type-Safe Navigation Examples for Android Compose.
 * 
 * FILE NAMING CONVENTIONS:
 *   Routes:        [Feature]Routes.kt    (e.g., AuthRoutes.kt)
 *   NavGraph:      [Feature]NavGraph.kt  (e.g., AuthNavGraph.kt)
 *   Navigator:     AppNavigator.kt       (centralized)
 *   NavHost:       AppNavHost.kt         (assembly point)
 * 
 * @see 13-navigation.md for navigation guide
 * @see 05-jetpack-compose.md for Compose patterns
 * ============================================================================
 */

/**
 * Type-Safe Navigation Examples for Android Compose.
 * 
 * This template demonstrates:
 * - Serializable routes for type safety
 * - Navigation commands (extensible pattern)
 * - Feature-based graph organization
 * - SharedViewModel for multi-screen flows
 * - Result passing between screens
 * 
 * Created & Reviewed by: TrongLB & AI Agents
 */

// ============================================================
// 1. CORE NAVIGATION - Base Interfaces (:core:navigation)
// ============================================================

/**
 * Base interface for all navigation commands.
 * Features extend this with their own commands.
 */
interface NavigationCommand

/**
 * Built-in navigation commands.
 */
data object Back : NavigationCommand

data class ToRoute<T : Any>(
    val route: T,
    val popUpTo: Any? = null,
    val inclusive: Boolean = false,
    val singleTop: Boolean = false,
) : NavigationCommand

/**
 * Core navigator interface.
 * Injected into ViewModels for navigation.
 */
interface AppNavigator {
    fun execute(command: NavigationCommand)
    fun back() = execute(Back)
    fun <T : Any> navigateTo(
        route: T,
        popUpTo: Any? = null,
        inclusive: Boolean = false,
        singleTop: Boolean = false,
    ) = execute(ToRoute(route, popUpTo, inclusive, singleTop))
    
    // Result passing
    fun <T> setResult(key: String, value: T)
    fun <T> getResult(key: String): T?
    fun clearResult(key: String)
}

/**
 * Composition Local for accessing navigator in Composables.
 */
val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator not provided")
}

// ============================================================
// 2. FEATURE ROUTES - Each feature owns its routes
// ============================================================

// --- Auth Feature Routes ---
@Serializable
data object LoginRoute

@Serializable
data object RegisterRoute

@Serializable
data object ForgotPasswordRoute

// --- Profile Feature Routes ---
@Serializable
data class ProfileRoute(val userId: String)

@Serializable
data class EditProfileRoute(val userId: String)

@Serializable
data object ProfileSettingsRoute

// --- Product Feature Routes ---
@Serializable
data class ProductDetailRoute(
    val productId: String,
    val source: String = "home",  // Default value
)

@Serializable
data class ProductListRoute(
    val category: String? = null,  // Optional parameter
)

// --- Multi-Step Flow (SharedViewModel) ---
@Serializable
data object CreateProductGraphRoute  // Graph marker

@Serializable
data object CreateProductStep1Route

@Serializable
data object CreateProductStep2Route

@Serializable
data object CreateProductPreviewRoute

// ============================================================
// 3. FEATURE COMMANDS - Extensible Navigation
// ============================================================

/**
 * Auth feature navigation commands.
 */
sealed interface AuthCommand : NavigationCommand {
    data object ToLogin : AuthCommand
    data object ToRegister : AuthCommand
    data object ToForgotPassword : AuthCommand
    data object ToHome : AuthCommand  // After successful login
}

// Extension functions for cleaner API
fun AppNavigator.toLogin() = execute(AuthCommand.ToLogin)
fun AppNavigator.toRegister() = execute(AuthCommand.ToRegister)
fun AppNavigator.toHome() = execute(AuthCommand.ToHome)

/**
 * Profile feature navigation commands.
 */
sealed interface ProfileCommand : NavigationCommand {
    data class ToProfile(val userId: String) : ProfileCommand
    data class ToEditProfile(val userId: String) : ProfileCommand
    data object ToSettings : ProfileCommand
}

fun AppNavigator.toProfile(userId: String) = execute(ProfileCommand.ToProfile(userId))
fun AppNavigator.toEditProfile(userId: String) = execute(ProfileCommand.ToEditProfile(userId))
fun AppNavigator.toProfileSettings() = execute(ProfileCommand.ToSettings)

/**
 * Product feature navigation commands.
 */
sealed interface ProductCommand : NavigationCommand {
    data class ToProductDetail(val productId: String, val source: String = "home") : ProductCommand
    data class ToProductList(val category: String? = null) : ProductCommand
}

fun AppNavigator.toProductDetail(productId: String, source: String = "home") = 
    execute(ProductCommand.ToProductDetail(productId, source))
fun AppNavigator.toProductList(category: String? = null) = 
    execute(ProductCommand.ToProductList(category))

// ============================================================
// 4. FEATURE NAV GRAPHS - Modular graph builders
// ============================================================

/**
 * Auth feature navigation graph.
 */
fun NavGraphBuilder.authGraph() {
    composable<LoginRoute> {
        LoginScreen()
    }
    composable<RegisterRoute> {
        RegisterScreen()
    }
    composable<ForgotPasswordRoute> {
        ForgotPasswordScreen()
    }
}

/**
 * Profile feature navigation graph.
 */
fun NavGraphBuilder.profileGraph() {
    composable<ProfileRoute> { entry ->
        val route = entry.toRoute<ProfileRoute>()
        ProfileScreen(userId = route.userId)
    }
    composable<EditProfileRoute> { entry ->
        val route = entry.toRoute<EditProfileRoute>()
        EditProfileScreen(userId = route.userId)
    }
    composable<ProfileSettingsRoute> {
        ProfileSettingsScreen()
    }
}

/**
 * Product feature navigation graph.
 */
fun NavGraphBuilder.productGraph() {
    composable<ProductDetailRoute> { entry ->
        val route = entry.toRoute<ProductDetailRoute>()
        ProductDetailScreen(
            productId = route.productId,
            source = route.source,
        )
    }
    composable<ProductListRoute> { entry ->
        val route = entry.toRoute<ProductListRoute>()
        ProductListScreen(category = route.category)
    }
}

/**
 * Multi-step create product flow with SharedViewModel.
 */
fun NavGraphBuilder.createProductGraph(navController: NavHostController) {
    navigation<CreateProductGraphRoute>(startDestination = CreateProductStep1Route) {
        
        composable<CreateProductStep1Route> { entry ->
            // Get SharedViewModel scoped to the graph
            val sharedViewModel: CreateProductSharedViewModel = 
                graphScopedViewModel(navController, CreateProductGraphRoute)
            
            CreateProductStep1Screen(
                sharedViewModel = sharedViewModel,
                onNext = { navController.navigate(CreateProductStep2Route) },
            )
        }
        
        composable<CreateProductStep2Route> { entry ->
            val sharedViewModel: CreateProductSharedViewModel = 
                graphScopedViewModel(navController, CreateProductGraphRoute)
            
            CreateProductStep2Screen(
                sharedViewModel = sharedViewModel,
                onNext = { navController.navigate(CreateProductPreviewRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        
        composable<CreateProductPreviewRoute> { entry ->
            val sharedViewModel: CreateProductSharedViewModel = 
                graphScopedViewModel(navController, CreateProductGraphRoute)
            
            CreateProductPreviewScreen(
                sharedViewModel = sharedViewModel,
                onConfirm = { 
                    // Pop entire graph
                    navController.popBackStack(CreateProductGraphRoute, inclusive = true)
                },
                onEdit = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Helper to get ViewModel scoped to navigation graph.
 */
@Composable
inline fun <reified VM : ViewModel, reified G : Any> graphScopedViewModel(
    navController: NavHostController,
    graphRoute: G,
): VM {
    val parentEntry = remember {
        navController.getBackStackEntry(graphRoute)
    }
    return hiltViewModel(parentEntry)
}

// ============================================================
// 5. APP NAVIGATOR IMPLEMENTATION (:app module)
// ============================================================

class AppNavigatorImpl(
    private val navController: NavHostController,
) : AppNavigator {
    
    override fun execute(command: NavigationCommand) {
        when (command) {
            // Core commands
            is Back -> navController.popBackStack()
            
            is ToRoute<*> -> navController.navigate(command.route) {
                command.popUpTo?.let { 
                    popUpTo(it::class) { inclusive = command.inclusive } 
                }
                launchSingleTop = command.singleTop
            }
            
            // Auth commands
            is AuthCommand.ToLogin -> navController.navigate(LoginRoute) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
            is AuthCommand.ToRegister -> navController.navigate(RegisterRoute)
            is AuthCommand.ToForgotPassword -> navController.navigate(ForgotPasswordRoute)
            is AuthCommand.ToHome -> navController.navigate(ProductListRoute()) {
                popUpTo(LoginRoute) { inclusive = true }
            }
            
            // Profile commands
            is ProfileCommand.ToProfile -> navController.navigate(ProfileRoute(command.userId))
            is ProfileCommand.ToEditProfile -> navController.navigate(EditProfileRoute(command.userId))
            is ProfileCommand.ToSettings -> navController.navigate(ProfileSettingsRoute)
            
            // Product commands
            is ProductCommand.ToProductDetail -> navController.navigate(
                ProductDetailRoute(command.productId, command.source)
            )
            is ProductCommand.ToProductList -> navController.navigate(
                ProductListRoute(command.category)
            )
        }
    }
    
    override fun <T> setResult(key: String, value: T) {
        navController.previousBackStackEntry?.savedStateHandle?.set(key, value)
    }
    
    override fun <T> getResult(key: String): T? {
        return navController.currentBackStackEntry?.savedStateHandle?.get(key)
    }
    
    override fun clearResult(key: String) {
        navController.currentBackStackEntry?.savedStateHandle?.remove<Any>(key)
    }
}

// ============================================================
// 6. APP NAV HOST - Assembly Point
// ============================================================

@Composable
fun AppNavHost(
    isLoggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val navigator = remember(navController) { AppNavigatorImpl(navController) }
    
    CompositionLocalProvider(LocalAppNavigator provides navigator) {
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) ProductListRoute() else LoginRoute,
            modifier = modifier,
        ) {
            // Assemble all feature graphs
            authGraph()
            profileGraph()
            productGraph()
            createProductGraph(navController)
        }
    }
}

// ============================================================
// 7. SHAREDVIEWMODEL EXAMPLE - Multi-Step Flow
// ============================================================

/**
 * Shared state for multi-step product creation flow.
 */
data class CreateProductState(
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val category: String = "",
    val step: Int = 1,
)

/**
 * SharedViewModel scoped to navigation graph.
 * All screens in the create product flow share this ViewModel.
 */
@HiltViewModel
class CreateProductSharedViewModel @Inject constructor() : ViewModel() {
    
    private val _state = MutableStateFlow(CreateProductState())
    val state: StateFlow<CreateProductState> = _state.asStateFlow()
    
    fun updateName(name: String) {
        _state.update { it.copy(name = name) }
    }
    
    fun updateDescription(description: String) {
        _state.update { it.copy(description = description) }
    }
    
    fun updatePrice(price: Double) {
        _state.update { it.copy(price = price) }
    }
    
    fun updateCategory(category: String) {
        _state.update { it.copy(category = category) }
    }
    
    fun reset() {
        _state.value = CreateProductState()
    }
}

// ============================================================
// 8. RESULT PASSING EXAMPLE
// ============================================================

/**
 * Keys for navigation results.
 */
object NavResultKeys {
    const val SELECTED_ADDRESS = "selected_address"
    const val SELECTED_PAYMENT = "selected_payment"
}

/**
 * Example: Parent screen observing result from child.
 * Parameter order: Required → State → Modifier → Callbacks
 */
@Composable
fun CheckoutScreenWithResult(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val navigator = LocalAppNavigator.current
    
    // Get result from child screen
    val selectedAddress = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.get<String>(NavResultKeys.SELECTED_ADDRESS)
    
    // Use the result
    if (selectedAddress != null) {
        // Handle selected address
        // Clear after handling
        navController.currentBackStackEntry
            ?.savedStateHandle
            ?.remove<String>(NavResultKeys.SELECTED_ADDRESS)
    }
    
    // ... Screen content
}

/**
 * Example: Child screen setting result before navigating back.
 * Parameter order: Required → State → Modifier → Callbacks
 */
@Composable
fun SelectAddressScreen(
    addresses: List<String>,
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    // When user selects an address
    val onAddressSelected: (String) -> Unit = { addressId ->
        // Set result for parent
        navController.previousBackStackEntry
            ?.savedStateHandle
            ?.set(NavResultKeys.SELECTED_ADDRESS, addressId)
        
        // Navigate back
        navController.popBackStack()
    }
    
    // ... Address list UI
}

// ============================================================
// STUB SCREENS (Replace with actual implementations)
// Note: All composables should have modifier: Modifier = Modifier
// ============================================================

@Composable fun LoginScreen(modifier: Modifier = Modifier) {}
@Composable fun RegisterScreen(modifier: Modifier = Modifier) {}
@Composable fun ForgotPasswordScreen(modifier: Modifier = Modifier) {}
@Composable fun ProfileScreen(userId: String, modifier: Modifier = Modifier) {}
@Composable fun EditProfileScreen(userId: String, modifier: Modifier = Modifier) {}
@Composable fun ProfileSettingsScreen(modifier: Modifier = Modifier) {}
@Composable fun ProductDetailScreen(productId: String, source: String, modifier: Modifier = Modifier) {}
@Composable fun ProductListScreen(category: String?, modifier: Modifier = Modifier) {}
@Composable fun CreateProductStep1Screen(sharedViewModel: CreateProductSharedViewModel, modifier: Modifier = Modifier, onNext: () -> Unit) {}
@Composable fun CreateProductStep2Screen(sharedViewModel: CreateProductSharedViewModel, modifier: Modifier = Modifier, onNext: () -> Unit, onBack: () -> Unit) {}
@Composable fun CreateProductPreviewScreen(sharedViewModel: CreateProductSharedViewModel, modifier: Modifier = Modifier, onConfirm: () -> Unit, onEdit: () -> Unit) {}
