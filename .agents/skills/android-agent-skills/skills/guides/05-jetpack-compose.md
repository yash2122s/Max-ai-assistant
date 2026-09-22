---
name: Jetpack Compose Best Practices
description: Guidelines for building efficient, maintainable, and stable Compose UIs.
compliance_level: MANDATORY
tags: [compose, ui, state, modifiers, navigation]
version: 2.2.0
---

# Jetpack Compose Best Practices

## Context
Jetpack Compose is the modern toolkit for building native UI. This guide ensures consistency, performance, and maintainability across Composable functions.

**Related Guides:**
- [06-compose-performance.md](./06-compose-performance.md) - Performance optimization
- [07-state-management.md](./07-state-management.md) - State patterns
- [13-navigation.md](./13-navigation.md) - Navigation patterns

**Code Templates:**
- [ComposeExample.kt](../templates/compose/ComposeExample.kt) - Compose patterns
- [AgentDemoViewModel.kt](../templates/compose/AgentDemoViewModel.kt) - ViewModel + Compose integration

---

## 🎯 AI Quick Reference

```
PARAMETER ORDER:
Required → State → Modifier → Callbacks

PATTERNS:
• Route = Stateful (ViewModel injection)
• Screen = Stateless (state + callbacks)
• Always add: modifier: Modifier = Modifier

STABILITY:
• @Immutable for data classes in state
• Method references: viewModel::onAction
• ImmutableList instead of List

SIDE EFFECTS:
• LaunchedEffect → One-time suspend calls
• rememberUpdatedState → Capture latest value
• DisposableEffect → Cleanup on dispose

REMEMBER:
• remember → Survives recomposition
• rememberSaveable → Survives process death
• derivedStateOf → Computed state
```

---

## 1. Jetpack Compose Fundamental Rules

### Core Principles

```kotlin
// ════════════════════════════════════════════════════════════
// RULE 1: Composables are PURE functions
// ════════════════════════════════════════════════════════════
// ✅ Same inputs → Same output
// ✅ No side effects in body
// ✅ Can be called in any order, any number of times

// ✅ GOOD: Pure composable
@Composable
fun Greeting(name: String) {
    Text("Hello, $name") // ✅ Same name → Same UI
}

// ❌ BAD: Side effects in body
@Composable
fun BadGreeting(name: String) {
    viewModel.logEvent("greeting_shown") // ❌ Side effect!
    Text("Hello, $name")
}

// ✅ GOOD: Side effects in LaunchedEffect
@Composable
fun GoodGreeting(name: String) {
    LaunchedEffect(Unit) {
        viewModel.logEvent("greeting_shown") // ✅ Controlled side effect
    }
    Text("Hello, $name")
}

// ════════════════════════════════════════════════════════════
// RULE 2: Recomposition can happen ANYTIME
// ════════════════════════════════════════════════════════════
// ✅ Expect composables to be called frequently
// ✅ Keep composables fast and lightweight
// ❌ Don't do expensive work in composable body

// ❌ BAD: Expensive work in body
@Composable
fun BadList(items: List<Item>) {
    val sorted = items.sortedBy { it.name } // ❌ Sorted on EVERY recomposition!
    LazyColumn {
        items(sorted) { ItemRow(it) }
    }
}

// ✅ GOOD: Cache expensive computation
@Composable
fun GoodList(items: List<Item>) {
    val sorted = remember(items) { // ✅ Only recompute when items change
        items.sortedBy { it.name }
    }
    LazyColumn {
        items(sorted) { ItemRow(it) }
    }
}

// ════════════════════════════════════════════════════════════
// RULE 3: Recomposition is OPTIMISTIC
// ════════════════════════════════════════════════════════════
// Compose may start recomposition, then abandon it if inputs change again

@Composable
fun SearchResults(query: String) {
    // ✅ LaunchedEffect cancels and restarts when query changes
    LaunchedEffect(query) {
        delay(300) // Debounce
        performSearch(query) // May be cancelled if query changes
    }
}

// ════════════════════════════════════════════════════════════
// RULE 4: Composables can execute in PARALLEL
// ════════════════════════════════════════════════════════════
// ❌ Don't assume execution order
// ❌ Don't share mutable state between composables

// ❌ BAD: Shared mutable state
var sharedCounter = 0 // ❌ Race condition!

@Composable
fun BadCounter() {
    sharedCounter++ // ❌ Unsafe!
    Text("Count: $sharedCounter")
}

// ✅ GOOD: Hoisted state
@Composable
fun GoodCounter(count: Int) {
    Text("Count: $count") // ✅ Stateless, safe
}
```

### Recomposition Triggers

```kotlin
// ════════════════════════════════════════════════════════════
// What causes recomposition?
// ════════════════════════════════════════════════════════════

@Composable
fun RecompositionExample() {
    // 1. State change
    var count by remember { mutableStateOf(0) }
    
    // 2. StateFlow/Flow emission
    val user by viewModel.user.collectAsStateWithLifecycle()
    
    // 3. Parent recomposition (if not skipped)
    ParentComposable {
        ChildComposable() // Recomposes when parent recomposes
    }
    
    // 4. Key change in remember/LaunchedEffect
    LaunchedEffect(userId) { // Restarts when userId changes
        loadUser(userId)
    }
}

// ════════════════════════════════════════════════════════════
// Minimize recomposition scope
// ════════════════════════════════════════════════════════════

// ❌ BAD: Entire screen recomposes on counter change
@Composable
fun BadScreen() {
    var counter by remember { mutableStateOf(0) }
    
    Column {
        ExpensiveHeader() // ❌ Recomposes unnecessarily
        ExpensiveContent() // ❌ Recomposes unnecessarily
        Button(onClick = { counter++ }) { Text("Count: $counter") }
    }
}

// ✅ GOOD: Only button recomposes
@Composable
fun GoodScreen() {
    Column {
        ExpensiveHeader() // ✅ Won't recompose
        ExpensiveContent() // ✅ Won't recompose
        CounterButton() // ✅ Only this recomposes
    }
}

@Composable
fun CounterButton() {
    var counter by remember { mutableStateOf(0) }
    Button(onClick = { counter++ }) {
        Text("Count: $counter")
    }
}
```

### State Hoisting Rules

```kotlin
// ════════════════════════════════════════════════════════════
// State Hoisting Pattern
// ════════════════════════════════════════════════════════════

// RULE: Hoist state to the LOWEST COMMON ANCESTOR that needs it

// ❌ BAD: State too high (App level for single screen state)
@Composable
fun App() {
    var searchQuery by remember { mutableStateOf("") } // ❌ Too high!
    
    NavHost {
        composable("home") { HomeScreen() }
        composable("search") { SearchScreen(searchQuery) } // Only used here
    }
}

// ✅ GOOD: State at appropriate level
@Composable
fun App() {
    NavHost {
        composable("home") { HomeScreen() }
        composable("search") { SearchRoute() } // State owned by route
    }
}

@Composable
fun SearchRoute() {
    var searchQuery by remember { mutableStateOf("") } // ✅ Right level
    SearchScreen(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
    )
}

// ════════════════════════════════════════════════════════════
// State Hoisting Best Practices
// ════════════════════════════════════════════════════════════

// 1. STATELESS component (receives state + callbacks)
@Composable
fun SearchBar(
    query: String,                        // ✅ State from parent
    onQueryChange: (String) -> Unit,      // ✅ Event to parent
    modifier: Modifier = Modifier,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
    )
}

// 2. STATEFUL component (owns state)
@Composable
fun SearchBarStateful(
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit,           // Only expose final event
) {
    var query by remember { mutableStateOf("") }
    
    SearchBar(
        query = query,
        onQueryChange = { query = it },
        modifier = modifier,
    )
    
    LaunchedEffect(query) {
        delay(300) // Debounce
        onSearch(query)
    }
}

// 3. When to hoist state?
/*
✅ HOIST when:
- Multiple composables need to read the state
- Parent needs to control child state
- State needs to survive configuration changes
- Testing requires injecting state

❌ DON'T HOIST when:
- Only single composable uses the state
- State is purely internal (e.g., expanded/collapsed)
- Performance: hoisting causes parent recomposition
*/

// Example: Internal state (don't hoist)
@Composable
fun ExpandableCard(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) } // ✅ Internal state
    
    Card(onClick = { expanded = !expanded }) {
        Text(title)
        if (expanded) {
            content()
        }
    }
}
```

---

## 2. Composable Architecture

### Route vs Screen Pattern

```kotlin
// ════════════════════════════════════════════════════════════
// ROUTE - Stateful, handles ViewModel, navigation
// ════════════════════════════════════════════════════════════
@Composable
fun ProfileRoute(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    // Collect state with lifecycle awareness
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    // Handle one-time events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ProfileEvent.NavigateBack -> onNavigateBack()
                ProfileEvent.NavigateToSettings -> onNavigateToSettings()
                is ProfileEvent.ShowMessage -> { /* show snackbar */ }
            }
        }
    }
    
    // Delegate to stateless Screen
    ProfileScreen(
        state = state,
        onNameChange = viewModel::onNameChange,
        onEmailChange = viewModel::onEmailChange,
        onSaveClick = viewModel::onSaveClick,
        onBackClick = viewModel::onBackClick,
    )
}

// ════════════════════════════════════════════════════════════
// SCREEN - Stateless, pure UI, easy to preview/test
// ════════════════════════════════════════════════════════════
@Composable
fun ProfileScreen(
    state: ProfileUiState,                    // Required: State
    modifier: Modifier = Modifier,            // Modifier
    onNameChange: (String) -> Unit = {},      // Callbacks
    onEmailChange: (String) -> Unit = {},
    onSaveClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            } else {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmailChange,
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = onSaveClick,
                    enabled = state.isValid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save")
                }
                
                state.error?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// PREVIEW - Always for stateless components
// ════════════════════════════════════════════════════════════
@Preview(showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    MaterialTheme {
        ProfileScreen(
            state = ProfileUiState(
                name = "John Doe",
                email = "john@example.com",
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileScreenLoadingPreview() {
    MaterialTheme {
        ProfileScreen(
            state = ProfileUiState(isLoading = true),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProfileScreenErrorPreview() {
    MaterialTheme {
        ProfileScreen(
            state = ProfileUiState(
                name = "John Doe",
                email = "invalid",
                error = "Please enter a valid email address",
            ),
        )
    }
}
```

---

## 2. Parameter Conventions

### Parameter Order
```kotlin
@Composable
fun ComponentName(
    // 1. REQUIRED parameters (no defaults)
    data: DataType,
    
    // 2. STATE parameters (with defaults)
    isEnabled: Boolean = true,
    isSelected: Boolean = false,
    
    // 3. MODIFIER (always with default)
    modifier: Modifier = Modifier,
    
    // 4. CALLBACKS (always with empty default)
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onValueChange: (String) -> Unit = {},
)
```

### ✅ DO: Proper Parameter Patterns
```kotlin
// ════════════════════════════════════════════════════════════
// Card Component with proper order
// ════════════════════════════════════════════════════════════
@Composable
fun ProductCard(
    product: Product,                         // Required
    isFavorite: Boolean = false,              // State
    modifier: Modifier = Modifier,            // Modifier
    onProductClick: () -> Unit = {},          // Callbacks
    onFavoriteClick: () -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onProductClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = product.imageUrl,
                contentDescription = product.name,
                modifier = Modifier.size(64.dp),
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = product.formattedPrice,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) Color.Red else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════
// List Component with items
// ════════════════════════════════════════════════════════════
@Composable
fun ProductList(
    products: ImmutableList<Product>,         // Required (use ImmutableList!)
    favorites: ImmutableSet<String>,          // Required
    modifier: Modifier = Modifier,            // Modifier
    onProductClick: (Product) -> Unit = {},   // Callbacks
    onFavoriteClick: (Product) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = products,
            key = { it.id }, // ✅ Always provide key for performance
        ) { product ->
            ProductCard(
                product = product,
                isFavorite = product.id in favorites,
                onProductClick = { onProductClick(product) },
                onFavoriteClick = { onFavoriteClick(product) },
            )
        }
    }
}
```

### ❌ DON'T: Bad Parameter Patterns
```kotlin
// ❌ BAD: Wrong parameter order
@Composable
fun BadComponent(
    modifier: Modifier = Modifier,     // ❌ Modifier before required
    onClick: () -> Unit,               // ❌ Callback without default
    data: Data,                        // ❌ Required after optional
) { }

// ❌ BAD: ViewModel in stateless component
@Composable
fun BadScreen(
    viewModel: MyViewModel,            // ❌ Don't pass ViewModel
) {
    val state = viewModel.state.value  // ❌ Don't collect here
}

// ❌ BAD: No modifier parameter
@Composable
fun BadCard(
    product: Product,
    // ❌ Missing modifier parameter!
) {
    Card { }
}
```

---

## 3. Modifier Best Practices

### Modifier Order
```kotlin
Modifier
    // 1. SIZE & LAYOUT (how big, where)
    .fillMaxWidth()
    .height(200.dp)
    .padding(16.dp)               // Outer padding (margin)
    
    // 2. DRAWING (how it looks)
    .clip(RoundedCornerShape(8.dp))
    .background(Color.White)
    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
    .shadow(4.dp, RoundedCornerShape(8.dp))
    
    // 3. INTERACTION (what it does)
    .clickable { }
    .draggable(...)
    .scrollable(...)
    
    // 4. INNER PADDING (content spacing)
    .padding(16.dp)               // Inner padding
```

### ✅ DO: Modifier Patterns
```kotlin
// ════════════════════════════════════════════════════════════
// Correct order: Layout → Drawing → Interaction → Inner padding
// ════════════════════════════════════════════════════════════
@Composable
fun StyledButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

// ════════════════════════════════════════════════════════════
// Reusable modifier extensions
// ════════════════════════════════════════════════════════════
fun Modifier.cardStyle(): Modifier = this
    .fillMaxWidth()
    .clip(RoundedCornerShape(12.dp))
    .background(MaterialTheme.colorScheme.surface)
    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))

// Usage
Card(
    modifier = Modifier
        .cardStyle()
        .padding(16.dp),
)

// ════════════════════════════════════════════════════════════
// Conditional modifiers
// ════════════════════════════════════════════════════════════
fun Modifier.conditionalBorder(
    showBorder: Boolean,
    color: Color = Color.Red,
): Modifier = if (showBorder) {
    this.border(2.dp, color, RoundedCornerShape(8.dp))
} else {
    this
}

// Better: Use Modifier.then()
fun Modifier.selectedBorder(isSelected: Boolean): Modifier = this.then(
    if (isSelected) {
        Modifier.border(2.dp, Color.Blue, RoundedCornerShape(8.dp))
    } else {
        Modifier
    }
)
```

### ❌ DON'T: Modifier Anti-Patterns
```kotlin
// ❌ BAD: Wrong order - click before clip = click outside visible area
Modifier
    .clickable { }                 // ❌ Click area includes corners
    .clip(RoundedCornerShape(8.dp))

// ❌ BAD: Not passing modifier to root
@Composable
fun BadComponent(modifier: Modifier = Modifier) {
    Column {                       // ❌ modifier not applied!
        Text("Hello")
    }
}

// ✅ GOOD: Apply modifier to root
@Composable
fun GoodComponent(modifier: Modifier = Modifier) {
    Column(modifier = modifier) { // ✅
        Text("Hello")
    }
}

// ❌ BAD: Creating new modifier inside composable
@Composable
fun BadComponent() {
    val myModifier = Modifier.padding(16.dp) // ❌ Created on every recomposition
    Text("Hello", modifier = myModifier)
}
```

---

## 4. Side Effects

### Side Effect Guide

| Effect | Use Case | When Triggered |
|--------|----------|----------------|
| `LaunchedEffect(key)` | One-time suspend operation | When key changes |
| `LaunchedEffect(Unit)` | Once on first composition | Only once |
| `rememberUpdatedState` | Capture latest callback | Never restarts |
| `DisposableEffect` | Setup + cleanup | When key changes |
| `SideEffect` | Non-suspend side effect | Every recomposition |
| `derivedStateOf` | Computed state | When dependencies change |
| `produceState` | Convert non-Compose to State | When key changes |

### ✅ DO: Proper Side Effects
```kotlin
@Composable
fun ProfileRoute(
    userId: String,
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    // ════════════════════════════════════════════════════════
    // LaunchedEffect(key) - Runs when userId changes
    // ════════════════════════════════════════════════════════
    LaunchedEffect(userId) {
        viewModel.loadProfile(userId)
    }
    
    // ════════════════════════════════════════════════════════
    // LaunchedEffect(Unit) - Runs once for event collection
    // ════════════════════════════════════════════════════════
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ProfileEvent.NavigateBack -> onNavigateBack()
                is ProfileEvent.ShowMessage -> { /* snackbar */ }
            }
        }
    }
    
    // ════════════════════════════════════════════════════════
    // rememberUpdatedState - Capture latest callback
    // ════════════════════════════════════════════════════════
    val currentOnNavigateBack by rememberUpdatedState(onNavigateBack)
    
    LaunchedEffect(Unit) {
        delay(5000)
        // Uses latest onNavigateBack even if it changed during delay
        currentOnNavigateBack()
    }
    
    ProfileScreen(state = state)
}

@Composable
fun TimerComponent(
    onTick: (Int) -> Unit,
) {
    // ════════════════════════════════════════════════════════
    // DisposableEffect - Setup + cleanup
    // ════════════════════════════════════════════════════════
    DisposableEffect(Unit) {
        val timer = Timer()
        var count = 0
        
        timer.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    onTick(++count)
                }
            },
            0L,
            1000L,
        )
        
        onDispose {
            timer.cancel() // ✅ Cleanup when leaving composition
        }
    }
}

@Composable
fun SearchScreen(
    items: List<Item>,
    searchQuery: String,
) {
    // ════════════════════════════════════════════════════════
    // derivedStateOf - Computed state, minimizes recomposition
    // ════════════════════════════════════════════════════════
    val filteredItems by remember(items) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                items
            } else {
                items.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    }
    
    LazyColumn {
        items(filteredItems) { item ->
            ItemRow(item = item)
        }
    }
}
```

### ❌ DON'T: Side Effect Anti-Patterns
```kotlin
// ❌ BAD: Side effect in composable body
@Composable
fun BadComponent(viewModel: MyViewModel) {
    viewModel.loadData() // ❌ Called on EVERY recomposition!
}

// ❌ BAD: LaunchedEffect without proper key
@Composable
fun BadSearch(query: String) {
    LaunchedEffect(Unit) { // ❌ Only runs once, ignores query changes
        search(query)
    }
}

// ❌ BAD: Using state that triggers recomposition in derivedStateOf
@Composable
fun BadDerived() {
    var count by remember { mutableStateOf(0) }
    
    val doubled by remember {
        derivedStateOf { count * 2 } // ⚠️ count is not a State<T> read
    }
}
```

---

## 5. Remember Patterns

### remember vs rememberSaveable vs derivedStateOf

| Function | Survives Recomposition | Survives Process Death | Use Case |
|----------|------------------------|------------------------|----------|
| `remember` | ✅ | ❌ | Computed values, expensive objects |
| `rememberSaveable` | ✅ | ✅ | User input, scroll position |
| `derivedStateOf` | ✅ | ❌ | Computed from other State |
| `produceState` | ✅ | ❌ | Convert non-Compose to State |

### ✅ DO: Proper Remember Usage

```kotlin
// ════════════════════════════════════════════════════════════
// remember - Cache expensive computations
// ════════════════════════════════════════════════════════════
@Composable
fun ProductList(products: List<Product>) {
    // ✅ Sort only when products list changes
    val sortedProducts = remember(products) {
        products.sortedByDescending { it.rating }
    }
    
    // ✅ Create formatter once
    val priceFormatter = remember {
        NumberFormat.getCurrencyInstance(Locale.US)
    }
    
    LazyColumn {
        items(sortedProducts) { product ->
            ProductCard(
                product = product,
                formattedPrice = priceFormatter.format(product.price),
            )
        }
    }
}

// ════════════════════════════════════════════════════════════
// rememberSaveable - Survive process death
// ════════════════════════════════════════════════════════════
@Composable
fun SearchScreen() {
    // ✅ User input persists across process death
    var searchQuery by rememberSaveable { mutableStateOf("") }
    
    // ✅ Scroll position restored after process death
    val listState = rememberLazyListState()
    
    Column {
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
        )
        
        LazyColumn(state = listState) {
            // items...
        }
    }
}

// ════════════════════════════════════════════════════════════
// rememberSaveable with custom Saver
// ════════════════════════════════════════════════════════════
data class City(val id: String, val name: String)

val CitySaver = Saver<City, List<String>>(
    save = { listOf(it.id, it.name) },
    restore = { City(id = it[0], name = it[1]) },
)

@Composable
fun CityPicker() {
    var selectedCity by rememberSaveable(stateSaver = CitySaver) {
        mutableStateOf(City("1", "New York"))
    }
}

// ════════════════════════════════════════════════════════════
// derivedStateOf - Computed state from other State
// ════════════════════════════════════════════════════════════
@Composable
fun FilteredList(
    items: List<Item>,
    filterState: FilterState,
) {
    // ✅ Only recompute when items or filterState change
    val filteredItems by remember {
        derivedStateOf {
            items.filter { item ->
                item.category == filterState.selectedCategory &&
                item.price >= filterState.minPrice &&
                item.price <= filterState.maxPrice
            }
        }
    }
    
    // ✅ Show count without recomputing filter
    Text("Found ${filteredItems.size} items")
    
    LazyColumn {
        items(filteredItems) { ItemCard(it) }
    }
}

// ════════════════════════════════════════════════════════════
// produceState - Convert non-Compose to State
// ════════════════════════════════════════════════════════════
@Composable
fun LoadImageFromUrl(url: String): State<ImageBitmap?> {
    return produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            loadImageFromNetwork(url)
        }
    }
}

@Composable
fun ImageScreen(url: String) {
    val imageBitmap by LoadImageFromUrl(url)
    
    if (imageBitmap != null) {
        Image(bitmap = imageBitmap!!, contentDescription = null)
    } else {
        CircularProgressIndicator()
    }
}
```

### ❌ DON'T: Remember Anti-Patterns

```kotlin
// ❌ BAD: Forgetting to remember expensive objects
@Composable
fun BadFormatter() {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) // ❌ Created every recomposition!
    Text(formatter.format(Date()))
}

// ❌ BAD: Using remember for values that should survive process death
@Composable
fun BadForm() {
    var userInput by remember { mutableStateOf("") } // ❌ Lost on process death!
    TextField(value = userInput, onValueChange = { userInput = it })
}

// ❌ BAD: Wrong dependencies in remember
@Composable
fun BadFilter(items: List<Item>, query: String) {
    val filtered = remember(items) { // ❌ Ignores query changes!
        items.filter { it.name.contains(query) }
    }
}

// ✅ GOOD: Include all dependencies
@Composable
fun GoodFilter(items: List<Item>, query: String) {
    val filtered = remember(items, query) { // ✅ Recomputes when either changes
        items.filter { it.name.contains(query) }
    }
}

// ❌ BAD: Over-using derivedStateOf
@Composable
fun BadDerived(name: String) {
    val uppercase by remember {
        derivedStateOf { name.uppercase() } // ❌ Unnecessary, just use name.uppercase()
    }
}
```

---

## 6. Composition Local

### When to Use CompositionLocal

```kotlin
// ════════════════════════════════════════════════════════════
// Use CompositionLocal for implicit dependencies that are:
// 1. Needed by many composables in the tree
// 2. Conceptually "ambient" (theme, locale, etc.)
// 3. Stable across recompositions
// ════════════════════════════════════════════════════════════

// ✅ GOOD: Theme data
val LocalAppTheme = staticCompositionLocalOf<AppTheme> {
    error("No AppTheme provided")
}

// ✅ GOOD: Current user
val LocalCurrentUser = compositionLocalOf<User?> { null }

// ✅ GOOD: Image loader
val LocalImageLoader = staticCompositionLocalOf<ImageLoader> {
    error("No ImageLoader provided")
}

@Composable
fun App() {
    val appTheme = remember { AppTheme() }
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val imageLoader = remember { ImageLoader(context) }
    
    CompositionLocalProvider(
        LocalAppTheme provides appTheme,
        LocalCurrentUser provides currentUser,
        LocalImageLoader provides imageLoader,
    ) {
        MainContent()
    }
}

@Composable
fun DeepNestedComponent() {
    // ✅ Access without prop drilling
    val theme = LocalAppTheme.current
    val user = LocalCurrentUser.current
    val imageLoader = LocalImageLoader.current
    
    // Use them...
}

// ════════════════════════════════════════════════════════════
// staticCompositionLocalOf vs compositionLocalOf
// ════════════════════════════════════════════════════════════

// ✅ staticCompositionLocalOf - Value rarely/never changes
// More efficient, but changing it recomposes ENTIRE subtree
val LocalConfig = staticCompositionLocalOf<AppConfig> {
    error("No config")
}

// ✅ compositionLocalOf - Value may change
// Less efficient, but only recomposes readers when value changes
val LocalUser = compositionLocalOf<User?> { null }
```

### ❌ DON'T: CompositionLocal Anti-Patterns

```kotlin
// ❌ BAD: Using CompositionLocal for frequently changing state
val LocalCounter = compositionLocalOf { 0 } // ❌ Changes often!

@Composable
fun BadCounter() {
    var count by remember { mutableStateOf(0) }
    
    CompositionLocalProvider(LocalCounter provides count) { // ❌ Recomposes entire tree!
        DeepTree()
    }
}

// ✅ GOOD: Pass as parameter or use ViewModel
@Composable
fun GoodCounter() {
    var count by remember { mutableStateOf(0) }
    DeepTree(count = count) // ✅ Explicit parameter
}

// ❌ BAD: Using for navigation
val LocalNavController = staticCompositionLocalOf<NavController> {
    error("No NavController")
}
// ❌ This makes testing hard and creates implicit dependencies

// ✅ GOOD: Pass navigation callbacks explicitly
@Composable
fun GoodScreen(
    onNavigateToDetail: (String) -> Unit,
) {
    // Explicit dependency, easy to test
}
```

---

## 7. Stability & Performance

### Stability Rules
```kotlin
// ════════════════════════════════════════════════════════════
// @Immutable - All properties are immutable and stable
// ════════════════════════════════════════════════════════════
@Immutable
data class ProfileUiState(
    val name: String = "",
    val email: String = "",
    val avatarUrl: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

// ════════════════════════════════════════════════════════════
// @Stable - Content may change but equals/hashCode are stable
// ════════════════════════════════════════════════════════════
@Stable
class CartState(
    initialItems: List<CartItem>,
) {
    var items by mutableStateOf(initialItems)
        private set
    
    fun addItem(item: CartItem) {
        items = items + item
    }
}

// ════════════════════════════════════════════════════════════
// Use ImmutableList/ImmutableSet for collections
// ════════════════════════════════════════════════════════════
@Immutable
data class ProductListState(
    val products: ImmutableList<Product> = persistentListOf(),
    val favorites: ImmutableSet<String> = persistentSetOf(),
)

// ════════════════════════════════════════════════════════════
// Method references for stable callbacks
// ════════════════════════════════════════════════════════════
@Composable
fun ProductRoute(viewModel: ProductViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    ProductScreen(
        state = state,
        // ✅ Method references - stable, won't cause recomposition
        onProductClick = viewModel::onProductClick,
        onFavoriteClick = viewModel::onFavoriteClick,
        onRefresh = viewModel::refresh,
    )
}
```

### ❌ DON'T: Unstable Patterns
```kotlin
// ❌ BAD: Lambda created on every recomposition
@Composable
fun BadScreen(viewModel: MyViewModel) {
    Button(
        onClick = { viewModel.doSomething() }, // ❌ New lambda every time
    )
}

// ❌ BAD: List instead of ImmutableList
data class BadState(
    val items: List<Item>, // ❌ List is not stable
)

// ❌ BAD: Passing ViewModel to child composable
@Composable
fun BadScreen(viewModel: MyViewModel) {
    ChildComponent(viewModel = viewModel) // ❌ Don't pass VM
}

// ❌ BAD: Creating objects in composable
@Composable
fun BadComponent() {
    val formatter = SimpleDateFormat("yyyy-MM-dd") // ❌ Created every recomposition
}

// ✅ GOOD: Remember expensive objects
@Composable
fun GoodComponent() {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
}
```

---

## 8. Verification Checklist

### Fundamental Rules
- [ ] Composables are pure functions (no side effects in body)
- [ ] Expensive work cached with `remember`
- [ ] State hoisted to lowest common ancestor
- [ ] No shared mutable state between composables

### Architecture
- [ ] Route (stateful) and Screen (stateless) are separate
- [ ] ViewModel only in Route, not in Screen
- [ ] Events collected in `LaunchedEffect(Unit)`

### Parameters
- [ ] Order: Required → State → Modifier → Callbacks
- [ ] `modifier: Modifier = Modifier` always present
- [ ] Callbacks have empty default `= {}`

### Modifiers
- [ ] Applied to root composable
- [ ] Order: Layout → Drawing → Interaction → Padding

### Remember Patterns
- [ ] `remember` for expensive objects/computations
- [ ] `rememberSaveable` for user input that survives process death
- [ ] `derivedStateOf` for computed state from other State
- [ ] All dependencies included in remember keys

### Stability
- [ ] `@Immutable` on UiState data classes
- [ ] `ImmutableList`/`ImmutableSet` for collections
- [ ] Method references for callbacks
- [ ] `collectAsStateWithLifecycle()` not `collectAsState()`

### Side Effects
- [ ] `LaunchedEffect(key)` with proper key
- [ ] `DisposableEffect` has `onDispose` cleanup
- [ ] No side effects in composable body

### CompositionLocal
- [ ] Used only for ambient/theme data
- [ ] Not used for frequently changing state
- [ ] `staticCompositionLocalOf` for stable values
- [ ] Navigation not passed via CompositionLocal
