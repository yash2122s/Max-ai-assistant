---
name: Navigation (Type-Safe)
description: Scalable, type-safe navigation with loose coupling for multi-module projects.
compliance_level: MANDATORY
tags: [navigation, compose, type-safe, deep-links, navigator, multi-module]
version: 2.4.0
---

# Navigation (Type-Safe)

## Context
Type safety prevents runtime crashes. This guide provides **scalable** patterns that support multi-module architecture with loose coupling between features.

**Related Guides:**
- [05-jetpack-compose.md](./05-jetpack-compose.md) - Route vs Screen pattern
- [07-state-management.md](./07-state-management.md) - ViewModel scoping

**Code Templates:**
- [NavigationExamples.kt](../templates/examples/NavigationExamples.kt) - Type-safe navigation patterns

---

## 🎯 AI Quick Reference

```
SCALABILITY PATTERNS:
• Routes: Per-module, not centralized
• Navigation: Command pattern (extensible)
• Events: Feature-local, not global sealed class

STRUCTURE:
• :core:navigation - Base interfaces only
• :feature:xxx - Own routes + navigator extension
• :app - Assembles all graphs

KEY INTERFACES:
• NavigationCommand - Base for all nav actions
• AppNavigator - Core navigation API
• NavGraphBuilder extensions - Per feature
```

---

## 1. Architecture Overview

### Scalable Multi-Module Structure
```
project/
├── core/
│   └── navigation/                    # Base interfaces ONLY
│       ├── NavigationCommand.kt       # Sealed interface base
│       ├── AppNavigator.kt            # Core navigator interface
│       └── NavGraphBuilderExt.kt      # Common extensions
│
├── feature/
│   ├── auth/
│   │   ├── navigation/
│   │   │   ├── AuthRoutes.kt          # Auth routes only
│   │   │   ├── AuthCommands.kt        # Auth navigation commands
│   │   │   └── AuthNavGraph.kt        # Auth graph builder
│   │   └── ui/...
│   │
│   ├── profile/
│   │   ├── navigation/
│   │   │   ├── ProfileRoutes.kt
│   │   │   ├── ProfileCommands.kt
│   │   │   └── ProfileNavGraph.kt
│   │   └── ui/...
│   │
│   └── product/
│       └── navigation/...
│
└── app/
    └── navigation/
        ├── AppNavHost.kt              # Assembles all graphs
        └── NavigationCommandHandler.kt # Handles all commands
```

### Why This Structure?
| Concern | Solution | Benefit |
|---------|----------|---------|
| Adding new feature | Create feature module | No changes to existing code |
| Feature removal | Delete module | No dangling references |
| Team isolation | Each team owns module | No merge conflicts |
| Testing | Test module in isolation | Faster CI |

---

## 2. Core Navigation Module

### NavigationCommand - Extensible Base
```kotlin
// :core:navigation/NavigationCommand.kt

/**
 * Base interface for all navigation commands.
 * Features extend this with their own commands.
 * 
 * Using interface (not sealed) allows extension across modules.
 */
interface NavigationCommand {
    /**
     * Common commands that any feature might need
     */
    companion object {
        fun back() = Back
        fun <T : Any> to(route: T) = ToRoute(route)
    }
}

// ════════════════════════════════════════════════════════════════
// Built-in commands (in core module)
// ════════════════════════════════════════════════════════════════
data object Back : NavigationCommand

data class ToRoute<T : Any>(
    val route: T,
    val popUpTo: Any? = null,
    val inclusive: Boolean = false,
    val singleTop: Boolean = false,
) : NavigationCommand

data class PopUpTo<T : Any>(
    val route: T,
    val inclusive: Boolean = false,
) : NavigationCommand
```

### AppNavigator - Core Interface
```kotlin
// :core:navigation/AppNavigator.kt

interface AppNavigator {
    /**
     * Execute any navigation command.
     * Commands are handled by the command handler in :app module.
     */
    fun execute(command: NavigationCommand)
    
    /**
     * Convenience methods (delegate to execute)
     */
    fun back() = execute(Back)
    
    fun <T : Any> navigateTo(
        route: T,
        popUpTo: Any? = null,
        inclusive: Boolean = false,
        singleTop: Boolean = false,
    ) = execute(ToRoute(route, popUpTo, inclusive, singleTop))
    
    // ════════════════════════════════════════════════════════════
    // Result passing (optional)
    // ════════════════════════════════════════════════════════════
    fun <T> setResult(key: String, value: T)
    fun <T> getResultFlow(key: String, defaultValue: T): StateFlow<T>?
    fun clearResult(key: String)
}

// ════════════════════════════════════════════════════════════════
// Composition Local
// ════════════════════════════════════════════════════════════════
val LocalAppNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator not provided. Wrap your content with NavigatorProvider.")
}
```

### Common Extensions
```kotlin
// :core:navigation/NavGraphBuilderExt.kt

/**
 * Simplified composable registration
 */
inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(content = content)
}

inline fun <reified T : Any> NavGraphBuilder.screen(
    deepLinks: List<String> = emptyList(),
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(
        deepLinks = deepLinks.map { navDeepLink<T>(basePath = it) },
        content = content,
    )
}

/**
 * Extract route with type safety
 */
inline fun <reified T : Any> NavBackStackEntry.route(): T = toRoute<T>()
```

---

## 3. Feature Module Pattern

### Feature Routes (Self-Contained)
```kotlin
// :feature:profile/navigation/ProfileRoutes.kt
package com.example.feature.profile.navigation

import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════
// Routes owned by this feature only
// ════════════════════════════════════════════════════════════════
@Serializable
data class ProfileRoute(val userId: String)

@Serializable
data class EditProfileRoute(val userId: String)

@Serializable
data object ProfileSettingsRoute
```

### Feature Commands (Extensible)
```kotlin
// :feature:profile/navigation/ProfileCommands.kt
package com.example.feature.profile.navigation

import com.example.core.navigation.NavigationCommand

/**
 * Navigation commands specific to Profile feature.
 * These extend NavigationCommand so they can be handled centrally.
 */
sealed interface ProfileCommand : NavigationCommand {
    data class ToProfile(val userId: String) : ProfileCommand
    data class ToEditProfile(val userId: String) : ProfileCommand
    data object ToProfileSettings : ProfileCommand
}

// ════════════════════════════════════════════════════════════════
// Extension function for cleaner API
// ════════════════════════════════════════════════════════════════
fun AppNavigator.toProfile(userId: String) = execute(ProfileCommand.ToProfile(userId))
fun AppNavigator.toEditProfile(userId: String) = execute(ProfileCommand.ToEditProfile(userId))
fun AppNavigator.toProfileSettings() = execute(ProfileCommand.ToProfileSettings)
```

### Feature NavGraph
```kotlin
// :feature:profile/navigation/ProfileNavGraph.kt
package com.example.feature.profile.navigation

import androidx.navigation.NavGraphBuilder
import com.example.core.navigation.screen
import com.example.core.navigation.route

/**
 * Profile feature's navigation graph.
 * Called from :app module's AppNavHost.
 */
fun NavGraphBuilder.profileGraph() {
    screen<ProfileRoute> { entry ->
        val route = entry.route<ProfileRoute>()
        ProfileRoute(userId = route.userId)
    }
    
    screen<EditProfileRoute> { entry ->
        val route = entry.route<EditProfileRoute>()
        EditProfileRoute(userId = route.userId)
    }
    
    screen<ProfileSettingsRoute> {
        ProfileSettingsRoute()
    }
}
```

### Feature ViewModel Usage
```kotlin
// :feature:profile/ui/ProfileViewModel.kt

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
    private val navigator: AppNavigator, // Injected!
) : ViewModel(), StateStoreHolder<ProfileUiState, ProfileEvent, ProfileIntent> {
    
    override val stateStore = StateStore(viewModelScope, ProfileUiState())
    
    override fun processIntent(intent: ProfileIntent) {
        when (intent) {
            is ProfileIntent.EditClicked -> {
                // ✅ Use feature-specific extension
                navigator.toEditProfile(state.value.userId)
            }
            ProfileIntent.SettingsClicked -> {
                navigator.toProfileSettings()
            }
            ProfileIntent.BackClicked -> {
                navigator.back()
            }
        }
    }
}
```

---

## 4. App Module Assembly

### AppNavigator Implementation
```kotlin
// :app/navigation/AppNavigatorImpl.kt

class AppNavigatorImpl(
    private val navController: NavHostController,
    private val commandHandler: NavigationCommandHandler,
) : AppNavigator {
    
    override fun execute(command: NavigationCommand) {
        commandHandler.handle(command, navController)
    }
    
    override fun <T> setResult(key: String, value: T) {
        navController.previousBackStackEntry?.savedStateHandle?.set(key, value)
    }
    
    override fun <T> getResultFlow(key: String, defaultValue: T): StateFlow<T>? {
        return navController.currentBackStackEntry?.savedStateHandle?.getStateFlow(key, defaultValue)
    }
    
    override fun clearResult(key: String) {
        navController.currentBackStackEntry?.savedStateHandle?.remove<Any>(key)
    }
}
```

### Command Handler (Central Router)
```kotlin
// :app/navigation/NavigationCommandHandler.kt

/**
 * Handles all navigation commands from all features.
 * This is the ONLY place that needs updating when adding new features.
 */
class NavigationCommandHandler {
    
    fun handle(command: NavigationCommand, navController: NavHostController) {
        when (command) {
            // ════════════════════════════════════════════════════════
            // Core commands
            // ════════════════════════════════════════════════════════
            is Back -> navController.popBackStack()
            
            is ToRoute<*> -> navController.navigate(command.route) {
                command.popUpTo?.let { popUpTo(it::class) { inclusive = command.inclusive } }
                launchSingleTop = command.singleTop
            }
            
            is PopUpTo<*> -> navController.popBackStack(
                route = command.route,
                inclusive = command.inclusive,
            )
            
            // ════════════════════════════════════════════════════════
            // Auth commands
            // ════════════════════════════════════════════════════════
            is AuthCommand.ToLogin -> navController.navigate(LoginRoute) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
            
            is AuthCommand.ToRegister -> navController.navigate(RegisterRoute)
            
            is AuthCommand.ToHome -> navController.navigate(HomeRoute) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
            
            // ════════════════════════════════════════════════════════
            // Profile commands
            // ════════════════════════════════════════════════════════
            is ProfileCommand.ToProfile -> navController.navigate(ProfileRoute(command.userId))
            is ProfileCommand.ToEditProfile -> navController.navigate(EditProfileRoute(command.userId))
            is ProfileCommand.ToProfileSettings -> navController.navigate(ProfileSettingsRoute)
            
            // ════════════════════════════════════════════════════════
            // Product commands
            // ════════════════════════════════════════════════════════
            is ProductCommand.ToProductDetail -> navController.navigate(
                ProductDetailRoute(command.productId, command.source)
            )
            is ProductCommand.ToProductList -> navController.navigate(ProductListRoute(command.category))
            
            // ════════════════════════════════════════════════════════
            // Unknown command (for debugging)
            // ════════════════════════════════════════════════════════
            else -> error("Unknown navigation command: $command")
        }
    }
}
```

### AppNavHost (Assembly Point)
```kotlin
// :app/navigation/AppNavHost.kt

@Composable
fun AppNavHost(
    isLoggedIn: Boolean,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val commandHandler = remember { NavigationCommandHandler() }
    val navigator = remember(navController) { 
        AppNavigatorImpl(navController, commandHandler) 
    }
    
    // Provide navigator to all composables
    CompositionLocalProvider(LocalAppNavigator provides navigator) {
        NavHost(
            navController = navController,
            startDestination = if (isLoggedIn) HomeRoute else LoginRoute,
            modifier = modifier,
        ) {
            // ════════════════════════════════════════════════════════
            // Assemble all feature graphs
            // ════════════════════════════════════════════════════════
            authGraph()      // from :feature:auth
            homeGraph()      // from :feature:home
            profileGraph()   // from :feature:profile
            productGraph()   // from :feature:product
            settingsGraph()  // from :feature:settings
        }
    }
}
```

---

## 5. Adding New Feature (Step-by-Step)

### Step 1: Create Routes
```kotlin
// :feature:orders/navigation/OrderRoutes.kt
@Serializable data class OrderListRoute(val status: String? = null)
@Serializable data class OrderDetailRoute(val orderId: String)
```

### Step 2: Create Commands
```kotlin
// :feature:orders/navigation/OrderCommands.kt
sealed interface OrderCommand : NavigationCommand {
    data class ToOrderList(val status: String? = null) : OrderCommand
    data class ToOrderDetail(val orderId: String) : OrderCommand
}

fun AppNavigator.toOrderList(status: String? = null) = execute(OrderCommand.ToOrderList(status))
fun AppNavigator.toOrderDetail(orderId: String) = execute(OrderCommand.ToOrderDetail(orderId))
```

### Step 3: Create NavGraph
```kotlin
// :feature:orders/navigation/OrderNavGraph.kt
fun NavGraphBuilder.orderGraph() {
    screen<OrderListRoute> { entry ->
        val route = entry.route<OrderListRoute>()
        OrderListRoute(statusFilter = route.status)
    }
    screen<OrderDetailRoute> { entry ->
        val route = entry.route<OrderDetailRoute>()
        OrderDetailRoute(orderId = route.orderId)
    }
}
```

### Step 4: Register in App Module
```kotlin
// :app/navigation/NavigationCommandHandler.kt
// Add to handle():
is OrderCommand.ToOrderList -> navController.navigate(OrderListRoute(command.status))
is OrderCommand.ToOrderDetail -> navController.navigate(OrderDetailRoute(command.orderId))

// :app/navigation/AppNavHost.kt
// Add to NavHost:
orderGraph()
```

**Total changes to existing code: 2 files, ~4 lines each**

---

## 6. Data Passing Strategies

### Decision Matrix: How to Pass Data?

| Data Type | Size | Lifetime | Recommended Approach |
|-----------|------|----------|---------------------|
| ID only | Small | Screen | ✅ **Route params** → Fetch in ViewModel |
| Simple model | < 2KB | Screen | ⚠️ Route params (serialized) |
| Complex model | > 2KB | Screen | ❌ Use ID + fetch |
| Shared state | Any | Graph | ✅ **SharedViewModel** (graph-scoped) |
| Edit → Preview | Any | Multi-screen | ✅ **SharedViewModel** |
| Result to parent | Any | One-time | ✅ **SavedStateHandle** |
| Global state | Any | App | ✅ **Repository/DataStore** |

### ✅ Strategy 1: Pass ID, Fetch in ViewModel (RECOMMENDED)
```kotlin
// ════════════════════════════════════════════════════════════════
// Best Practice: Pass ID only, fetch data in destination
// ════════════════════════════════════════════════════════════════

// Route with ID only
@Serializable
data class ProductDetailRoute(val productId: String)

// ViewModel fetches full data
@HiltViewModel
class ProductDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getProductUseCase: GetProductUseCase,
) : ViewModel() {
    
    // Extract route params
    private val productId: String = savedStateHandle.toRoute<ProductDetailRoute>().productId
    
    private val _state = MutableStateFlow(ProductDetailUiState())
    val state = _state.asStateFlow()
    
    init {
        loadProduct()
    }
    
    private fun loadProduct() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            getProductUseCase(productId)
                .onSuccess { product ->
                    _state.update { it.copy(product = product, isLoading = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message, isLoading = false) }
                }
        }
    }
}
```

**Why pass ID instead of full model?**
- ✅ Survives process death (ID can be restored)
- ✅ Deep links work naturally
- ✅ Always fresh data (not stale cached object)
- ✅ No serialization size limits
- ✅ Single source of truth (Repository)

### ✅ Strategy 2: SharedViewModel (Graph-Scoped)
```kotlin
// ════════════════════════════════════════════════════════════════
// Use when: Multiple screens need to share/edit same state
// Example: Product Create → Product Preview → Product Confirm
// ════════════════════════════════════════════════════════════════

// Graph marker for scoping
@Serializable object CreateProductGraph

// Shared state
@Immutable
data class CreateProductState(
    val name: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val images: ImmutableList<Uri> = persistentListOf(),
    val category: Category? = null,
)

// SharedViewModel - scoped to navigation graph
@HiltViewModel
class CreateProductSharedViewModel @Inject constructor() : ViewModel() {
    
    private val _state = MutableStateFlow(CreateProductState())
    val state = _state.asStateFlow()
    
    fun updateName(name: String) = _state.update { it.copy(name = name) }
    fun updateDescription(desc: String) = _state.update { it.copy(description = desc) }
    fun updatePrice(price: Double) = _state.update { it.copy(price = price) }
    fun addImage(uri: Uri) = _state.update { it.copy(images = it.images + uri) }
    fun setCategory(category: Category) = _state.update { it.copy(category = category) }
    
    fun reset() = _state.value = CreateProductState()
}

// ════════════════════════════════════════════════════════════════
// NavGraph setup with SharedViewModel scoping
// ════════════════════════════════════════════════════════════════
fun NavGraphBuilder.createProductGraph(navController: NavHostController) {
    navigation<CreateProductGraph>(startDestination = CreateProductStep1Route) {
        
        composable<CreateProductStep1Route> { entry ->
            // Get SharedViewModel scoped to the graph
            val parentEntry = remember(entry) {
                navController.getBackStackEntry(CreateProductGraph)
            }
            val sharedViewModel: CreateProductSharedViewModel = hiltViewModel(parentEntry)
            
            CreateProductStep1Screen(
                sharedViewModel = sharedViewModel,
                onNext = { navController.navigate(CreateProductStep2Route) },
            )
        }
        
        composable<CreateProductStep2Route> { entry ->
            val parentEntry = remember(entry) {
                navController.getBackStackEntry(CreateProductGraph)
            }
            val sharedViewModel: CreateProductSharedViewModel = hiltViewModel(parentEntry)
            
            CreateProductStep2Screen(
                sharedViewModel = sharedViewModel,
                onNext = { navController.navigate(CreateProductPreviewRoute) },
                onBack = { navController.popBackStack() },
            )
        }
        
        composable<CreateProductPreviewRoute> { entry ->
            val parentEntry = remember(entry) {
                navController.getBackStackEntry(CreateProductGraph)
            }
            val sharedViewModel: CreateProductSharedViewModel = hiltViewModel(parentEntry)
            
            CreateProductPreviewScreen(
                sharedViewModel = sharedViewModel,
                onConfirm = { 
                    // Submit product
                    navController.popBackStack(CreateProductGraph, inclusive = true)
                },
                onEdit = { navController.popBackStack() },
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Extension for cleaner SharedViewModel access
// ════════════════════════════════════════════════════════════════
@Composable
inline fun <reified VM : ViewModel, reified G : Any> graphScopedViewModel(
    navController: NavHostController,
): VM {
    val parentEntry = remember {
        navController.getBackStackEntry(G::class)
    }
    return hiltViewModel(parentEntry)
}

// Usage:
@Composable
fun CreateProductStep1Route(navController: NavHostController) {
    val sharedViewModel: CreateProductSharedViewModel = 
        graphScopedViewModel<CreateProductSharedViewModel, CreateProductGraph>(navController)
}
```

### ✅ Strategy 3: SavedStateHandle for Results
```kotlin
// ════════════════════════════════════════════════════════════════
// Use when: Child screen returns result to parent
// Example: Select Address → return selected address
// ════════════════════════════════════════════════════════════════

// Result keys (centralized)
object NavResultKeys {
    const val SELECTED_ADDRESS = "selected_address"
    const val SELECTED_PAYMENT = "selected_payment"
    const val EDITED_PROFILE = "edited_profile"
}

// Child screen: Set result before navigating back
@Composable
fun SelectAddressScreen(
    addresses: List<Address>,
    navController: NavHostController,
) {
    AddressList(
        addresses = addresses,
        onSelect = { address ->
            // Set result for parent
            navController.previousBackStackEntry
                ?.savedStateHandle
                ?.set(NavResultKeys.SELECTED_ADDRESS, address.id)
            navController.popBackStack()
        },
    )
}

// Parent screen: Observe result
@Composable
fun CheckoutScreen(
    viewModel: CheckoutViewModel = hiltViewModel(),
    navController: NavHostController,
) {
    // Observe address selection result
    val addressResult = navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow(NavResultKeys.SELECTED_ADDRESS, "")
        ?.collectAsStateWithLifecycle()
    
    LaunchedEffect(addressResult?.value) {
        addressResult?.value?.takeIf { it.isNotBlank() }?.let { addressId ->
            viewModel.processIntent(CheckoutIntent.AddressSelected(addressId))
            // Clear result after handling
            navController.currentBackStackEntry
                ?.savedStateHandle
                ?.remove<String>(NavResultKeys.SELECTED_ADDRESS)
        }
    }
}

// ════════════════════════════════════════════════════════════════
// For complex objects: Serialize to JSON string
// ════════════════════════════════════════════════════════════════
fun <T> NavBackStackEntry.setSerializableResult(key: String, value: T) {
    savedStateHandle[key] = Json.encodeToString(value)
}

inline fun <reified T> NavBackStackEntry.getSerializableResult(key: String): T? {
    return savedStateHandle.get<String>(key)?.let { Json.decodeFromString(it) }
}
```

### ❌ DON'T: Pass Large Objects via Route
```kotlin
// ❌ BAD: Full model in route params
@Serializable
data class ProductDetailRoute(
    val product: Product,  // ❌ Large object, may exceed limit
)

// ❌ BAD: List of objects in route
@Serializable
data class CartRoute(
    val items: List<CartItem>,  // ❌ Can be very large
)

// ❌ BAD: Passing cached data that may be stale
val cachedProduct = productCache.get(productId)
navController.navigate(ProductDetailRoute(cachedProduct))  // ❌ May be outdated
```

### Comparison: SharedViewModel vs Route Params vs SavedStateHandle

| Aspect | Route Params | SharedViewModel | SavedStateHandle |
|--------|-------------|-----------------|------------------|
| **Data size** | Small (< 2KB) | Any | Medium (serializable) |
| **Scope** | Single screen | Nav graph | Parent-child |
| **Process death** | ✅ Survives | ❌ Lost | ✅ Survives |
| **Deep link** | ✅ Works | ❌ N/A | ❌ N/A |
| **Multi-screen edit** | ❌ Poor | ✅ Perfect | ❌ Poor |
| **Return result** | ❌ N/A | ⚠️ Overkill | ✅ Perfect |
| **Fresh data** | ✅ Fetch new | ⚠️ Cached | ⚠️ Cached |

### Best Practices Summary
```kotlin
// ════════════════════════════════════════════════════════════════
// ✅ RULE 1: Pass ID, fetch in ViewModel
// ════════════════════════════════════════════════════════════════
// Navigation:
navigator.navigateTo(ProductDetailRoute(productId = "123"))

// ViewModel:
class ProductDetailViewModel(savedStateHandle: SavedStateHandle) {
    private val productId = savedStateHandle.toRoute<ProductDetailRoute>().productId
    // Fetch product from repository
}

// ════════════════════════════════════════════════════════════════
// ✅ RULE 2: SharedViewModel for multi-step flows
// ════════════════════════════════════════════════════════════════
// Create → Preview → Confirm flow
navigation<CreateOrderGraph>(startDestination = Step1Route) {
    // All screens share CreateOrderSharedViewModel
}

// ════════════════════════════════════════════════════════════════
// ✅ RULE 3: SavedStateHandle for returning results
// ════════════════════════════════════════════════════════════════
// Select screen → Parent screen
navController.previousBackStackEntry?.savedStateHandle?.set(KEY, value)
navController.popBackStack()

// ════════════════════════════════════════════════════════════════
// ✅ RULE 4: Repository/Cache for global state
// ════════════════════════════════════════════════════════════════
// Current user, cart, settings → Access via Repository, not navigation
```

---

## 7. Parameter Types & Serialization

### Supported Parameter Types
| Type | Support | Example |
|------|---------|---------|
| Primitives | ✅ Native | `String`, `Int`, `Long`, `Boolean`, `Float` |
| Nullable | ✅ Native | `String?`, `Int?` |
| Default values | ✅ Native | `source: String = "home"` |
| Enums | ✅ With `@Serializable` | `OrderStatus.PENDING` |
| Lists | ⚠️ Custom NavType | `List<String>` |
| Complex objects | ⚠️ Custom NavType | `FilterOptions` |

### ✅ Basic Parameters (Native Support)
```kotlin
// :feature:product/navigation/ProductRoutes.kt

@Serializable
data class ProductDetailRoute(
    // Required param
    val productId: String,
    
    // Optional with default
    val source: String = "home",
    
    // Nullable optional
    val highlightColor: String? = null,
    
    // Primitive types
    val showReviews: Boolean = true,
    val initialTab: Int = 0,
)

// Usage:
navigator.navigateTo(ProductDetailRoute(
    productId = "123",
    source = "search",
    showReviews = false,
))

// Deep link: myapp://product/123?source=search&showReviews=false
```

### ✅ Enum Parameters
```kotlin
// :core:model/OrderStatus.kt
@Serializable
enum class OrderStatus {
    PENDING, PROCESSING, SHIPPED, DELIVERED, CANCELLED
}

// :feature:orders/navigation/OrderRoutes.kt
@Serializable
data class OrderListRoute(
    val status: OrderStatus? = null,  // Filter by status
    val sortBy: SortOrder = SortOrder.NEWEST,
)

@Serializable
enum class SortOrder { NEWEST, OLDEST, PRICE_ASC, PRICE_DESC }

// Usage:
navigator.navigateTo(OrderListRoute(
    status = OrderStatus.PENDING,
    sortBy = SortOrder.NEWEST,
))

// Deep link: myapp://orders?status=PENDING&sortBy=NEWEST
```

### ⚠️ Complex Types (Custom NavType Required)
```kotlin
// :core:navigation/CustomNavTypes.kt

/**
 * NavType for List<String> parameters
 */
object StringListNavType : NavType<List<String>>(isNullableAllowed = true) {
    override fun get(bundle: Bundle, key: String): List<String>? {
        return bundle.getStringArrayList(key)
    }
    
    override fun parseValue(value: String): List<String> {
        return value.split(",").filter { it.isNotBlank() }
    }
    
    override fun serializeAsValue(value: List<String>): String {
        return value.joinToString(",")
    }
    
    override fun put(bundle: Bundle, key: String, value: List<String>) {
        bundle.putStringArrayList(key, ArrayList(value))
    }
}

/**
 * NavType for any @Serializable object using JSON
 */
inline fun <reified T : Any> jsonNavType(): NavType<T> = object : NavType<T>(
    isNullableAllowed = true,
) {
    override fun get(bundle: Bundle, key: String): T? {
        return bundle.getString(key)?.let { Json.decodeFromString<T>(it) }
    }
    
    override fun parseValue(value: String): T {
        return Json.decodeFromString(Uri.decode(value))
    }
    
    override fun serializeAsValue(value: T): String {
        return Uri.encode(Json.encodeToString(value))
    }
    
    override fun put(bundle: Bundle, key: String, value: T) {
        bundle.putString(key, Json.encodeToString(value))
    }
}

// ════════════════════════════════════════════════════════════════
// Usage with complex filter object
// ════════════════════════════════════════════════════════════════
@Serializable
data class ProductFilter(
    val categories: List<String> = emptyList(),
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val inStock: Boolean = true,
)

@Serializable
data class ProductListRoute(
    val filter: ProductFilter = ProductFilter(),
)

// Register custom type in NavGraph
fun NavGraphBuilder.productGraph() {
    composable<ProductListRoute>(
        typeMap = mapOf(
            typeOf<ProductFilter>() to jsonNavType<ProductFilter>(),
        ),
    ) { entry ->
        val route = entry.route<ProductListRoute>()
        ProductListScreen(filter = route.filter)
    }
}
```

---

## 7. Deep Links (Comprehensive)

### Basic Deep Link Setup
```kotlin
// :feature:product/navigation/ProductNavGraph.kt

fun NavGraphBuilder.productGraph() {
    // ════════════════════════════════════════════════════════════
    // Product Detail with multiple deep link patterns
    // ════════════════════════════════════════════════════════════
    composable<ProductDetailRoute>(
        deepLinks = listOf(
            // HTTPS deep link
            navDeepLink<ProductDetailRoute>(
                basePath = "https://myapp.com/product",
            ),
            // App scheme deep link
            navDeepLink<ProductDetailRoute>(
                basePath = "myapp://product",
            ),
            // Legacy path support
            navDeepLink<ProductDetailRoute>(
                basePath = "https://myapp.com/p",
            ),
        ),
    ) { entry ->
        val route = entry.route<ProductDetailRoute>()
        ProductDetailScreen(
            productId = route.productId,
            source = route.source,
        )
    }
}

// Deep link examples:
// https://myapp.com/product/123              → productId="123"
// https://myapp.com/product/123?source=email → productId="123", source="email"
// myapp://product/123?source=push            → productId="123", source="push"
```

### Deep Link with Query Parameters
```kotlin
@Serializable
data class SearchRoute(
    val query: String,
    val category: String? = null,
    val sortBy: SortOrder = SortOrder.RELEVANCE,
    val page: Int = 1,
)

fun NavGraphBuilder.searchGraph() {
    composable<SearchRoute>(
        deepLinks = listOf(
            navDeepLink<SearchRoute>(
                basePath = "https://myapp.com/search",
            ),
        ),
    ) { entry ->
        val route = entry.route<SearchRoute>()
        SearchScreen(
            query = route.query,
            category = route.category,
            sortBy = route.sortBy,
            page = route.page,
        )
    }
}

// Deep link examples:
// https://myapp.com/search/phone                    → query="phone"
// https://myapp.com/search/phone?category=electronics&sortBy=PRICE_ASC&page=2
```

### Deep Link with Path Segments
```kotlin
@Serializable
data class UserProfileRoute(
    val userId: String,
    val tab: ProfileTab = ProfileTab.POSTS,
)

@Serializable
enum class ProfileTab { POSTS, FOLLOWERS, FOLLOWING, ABOUT }

// Deep link pattern: /user/{userId}/{tab}
// https://myapp.com/user/john123/followers
```

### AndroidManifest Configuration
```xml
<!-- AndroidManifest.xml -->
<activity android:name=".MainActivity"
    android:exported="true">
    
    <!-- HTTPS Deep Links -->
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="https" 
              android:host="myapp.com" />
    </intent-filter>
    
    <!-- App Scheme Deep Links -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="myapp" />
    </intent-filter>
    
</activity>
```

### App Links Verification (assetlinks.json)
```json
// https://myapp.com/.well-known/assetlinks.json
[{
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
        "namespace": "android_app",
        "package_name": "com.example.myapp",
        "sha256_cert_fingerprints": [
            "AA:BB:CC:DD:EE:FF:..."
        ]
    }
}]
```

### Handle Deep Links in MainActivity
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val navController = rememberNavController()
            
            // Handle deep link from intent
            LaunchedEffect(Unit) {
                handleDeepLink(intent, navController)
            }
            
            AppNavHost(navController = navController)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle deep link when app is already running
        // NavController will automatically handle if configured correctly
    }
    
    private fun handleDeepLink(intent: Intent, navController: NavHostController) {
        // NavController handles deep links automatically
        // But you can add custom logic here if needed
        intent.data?.let { uri ->
            Timber.d("Deep link received: $uri")
            // Analytics tracking
            analytics.trackDeepLink(uri.toString())
        }
    }
}
```

---

## 8. Testing Navigation

### Test Navigator Mock
```kotlin
// :core:navigation-testing/FakeAppNavigator.kt

class FakeAppNavigator : AppNavigator {
    private val _commands = mutableListOf<NavigationCommand>()
    val commands: List<NavigationCommand> get() = _commands
    
    private val _results = mutableMapOf<String, Any?>()
    
    override fun execute(command: NavigationCommand) {
        _commands.add(command)
    }
    
    override fun <T> setResult(key: String, value: T) {
        _results[key] = value
    }
    
    override fun <T> getResultFlow(key: String, defaultValue: T): StateFlow<T> {
        return MutableStateFlow(_results[key] as? T ?: defaultValue)
    }
    
    override fun clearResult(key: String) {
        _results.remove(key)
    }
    
    // ════════════════════════════════════════════════════════════
    // Test assertions
    // ════════════════════════════════════════════════════════════
    fun assertNavigatedTo(route: Any) {
        assertTrue(commands.any { 
            it is ToRoute<*> && it.route == route 
        })
    }
    
    fun assertNavigatedBack() {
        assertTrue(commands.any { it is Back })
    }
    
    fun assertLastCommand(expected: NavigationCommand) {
        assertEquals(expected, commands.last())
    }
    
    fun clear() {
        _commands.clear()
        _results.clear()
    }
}
```

### ViewModel Test with Navigation
```kotlin
class ProfileViewModelTest {
    
    private lateinit var viewModel: ProfileViewModel
    private lateinit var fakeNavigator: FakeAppNavigator
    
    @BeforeEach
    fun setup() {
        fakeNavigator = FakeAppNavigator()
        viewModel = ProfileViewModel(
            getUserUseCase = FakeGetUserUseCase(),
            navigator = fakeNavigator,
        )
    }
    
    @Test
    fun `when edit clicked, navigates to edit profile`() {
        // Given
        val userId = "user123"
        viewModel.loadUser(userId)
        
        // When
        viewModel.processIntent(ProfileIntent.EditClicked)
        
        // Then
        fakeNavigator.assertNavigatedTo(EditProfileRoute(userId))
    }
    
    @Test
    fun `when back clicked, navigates back`() {
        // When
        viewModel.processIntent(ProfileIntent.BackClicked)
        
        // Then
        fakeNavigator.assertNavigatedBack()
    }
}
```

---

## 9. Comparison: Before vs After

| Aspect | Centralized (Before) | Modular (After) |
|--------|---------------------|-----------------|
| Add feature | Edit 3+ shared files | Create module, edit 2 lines in app |
| Remove feature | Hunt for all references | Delete module |
| Team work | Merge conflicts on shared files | Independent modules |
| Testing | Need full navigation setup | Test feature in isolation |
| Compile time | Full recompile on nav change | Only affected module |
| Code coupling | High (features know each other) | Low (via commands) |

---

## 10. Verification Checklist

### Architecture
- [ ] Routes defined per feature module
- [ ] Commands extend `NavigationCommand` interface
- [ ] Each feature has own NavGraph builder
- [ ] Only `:app` module assembles graphs

### Parameters
- [ ] Primitives and enums with `@Serializable`
- [ ] Optional params have defaults
- [ ] Complex types use custom `NavType`
- [ ] Type map registered in `composable<>()`

### Deep Links
- [ ] `navDeepLink<Route>` configured per feature
- [ ] AndroidManifest intent filters added
- [ ] App Links verified (assetlinks.json)
- [ ] Both https:// and app:// schemes supported

### Testing
- [ ] `FakeAppNavigator` for unit tests
- [ ] Commands are data classes (easy to assert)
- [ ] Feature graphs testable in isolation
