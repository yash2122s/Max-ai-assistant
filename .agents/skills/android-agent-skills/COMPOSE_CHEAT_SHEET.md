# Compose Cheat Sheet
> **Quick Reference**: Jetpack Compose patterns and best practices
> **Version**: 2.2.0

---

## 🎯 Route/Screen Pattern

```kotlin
// Route = Stateful (handles ViewModel)
@Composable
fun FeatureRoute(
    onNavigateBack: () -> Unit,
    viewModel: FeatureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is Event.NavigateBack -> onNavigateBack()
            }
        }
    }
    
    FeatureScreen(
        state = state,
        onAction = viewModel::onAction,
    )
}

// Screen = Stateless (pure UI)
// Parameter order: Required → State → Modifier → Callbacks
@Composable
fun FeatureScreen(
    state: FeatureUiState,
    modifier: Modifier = Modifier,
    onAction: (Action) -> Unit,
) {
    // UI only, no business logic
}
```

---

## 1. State Collection

```kotlin
// ✅ CORRECT: Lifecycle-aware
val state by viewModel.state.collectAsStateWithLifecycle()

// ❌ WRONG: Not lifecycle-aware
val state by viewModel.state.collectAsState()

// For non-StateFlow
val state by remember { mutableStateOf(initialValue) }

// Derived state
val isValid by remember(email, password) {
    derivedStateOf { email.isNotBlank() && password.length >= 8 }
}
```

---

## 2. State Class

```kotlin
// Always use @Immutable
@Immutable
data class FeatureUiState(
    val items: List<Item> = emptyList(),      // Use immutable list
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedId: String? = null,
) {
    // Derived properties
    val hasItems: Boolean get() = items.isNotEmpty()
    val selectedItem: Item? get() = items.find { it.id == selectedId }
}
```

---

## 3. Modifier Best Practices

```kotlin
// ✅ CORRECT: modifier before callbacks
// Order: Required → State → Modifier → Callbacks
@Composable
fun MyComponent(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier  // Apply to root
            .fillMaxWidth()
            .padding(16.dp)
    ) { }
}

// ❌ WRONG: modifier in the middle
@Composable
fun BadComponent(
    modifier: Modifier,  // No default!
    title: String,
)
```

---

## 4. Modifier Cheat Sheet

```kotlin
// Layout
Modifier
    .fillMaxSize()
    .fillMaxWidth()
    .fillMaxHeight()
    .size(48.dp)
    .width(100.dp)
    .height(50.dp)
    .wrapContentSize()
    .weight(1f)  // Only in Row/Column scope

// Spacing
    .padding(16.dp)
    .padding(horizontal = 16.dp, vertical = 8.dp)
    .padding(start = 16.dp, top = 8.dp)

// Background & Border
    .background(Color.White)
    .background(Color.Blue, RoundedCornerShape(8.dp))
    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
    .clip(RoundedCornerShape(8.dp))

// Interaction
    .clickable { onClick() }
    .clickable(enabled = isEnabled) { onClick() }
    .selectable(selected = isSelected) { onSelect() }

// Scrolling
    .verticalScroll(rememberScrollState())
    .horizontalScroll(rememberScrollState())

// Drawing
    .alpha(0.5f)
    .shadow(4.dp, RoundedCornerShape(8.dp))

// Semantics (Accessibility)
    .semantics { contentDescription = "Button" }
    .testTag("my_component")
```

---

## 5. Side Effects

```kotlin
// Run once when composable enters composition
LaunchedEffect(Unit) {
    viewModel.loadData()
}

// Run when key changes
LaunchedEffect(userId) {
    viewModel.loadUser(userId)
}

// Collect events
LaunchedEffect(Unit) {
    viewModel.events.collect { event ->
        when (event) { }
    }
}

// Cleanup when leaving composition
DisposableEffect(key) {
    val listener = addListener()
    onDispose {
        removeListener(listener)
    }
}

// Derived state with remember
val isValid = remember(email) {
    email.contains("@")
}

// Side effect that doesn't suspend
SideEffect {
    analytics.trackScreen("FeatureScreen")
}

// Launch in response to state change
rememberCoroutineScope().let { scope ->
    Button(onClick = { scope.launch { doWork() } })
}
```

---

## 6. Common Patterns

### Loading/Content/Error
```kotlin
@Composable
fun ContentScreen(state: UiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingIndicator()
            state.error != null -> ErrorContent(state.error)
            state.items.isEmpty() -> EmptyContent()
            else -> ContentList(state.items)
        }
    }
}
```

### Pull-to-Refresh
```kotlin
@Composable
fun RefreshableList(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = onRefresh,
    )
    
    Box(Modifier.pullRefresh(pullRefreshState)) {
        content()
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
```

### LazyColumn with Key
```kotlin
LazyColumn {
    items(
        items = state.items,
        key = { item -> item.id },  // Important for animation/performance
    ) { item ->
        ItemRow(item = item)
    }
}
```

---

## 7. Preview Functions

```kotlin
// Always add 3+ previews
@Preview(showBackground = true)
@Composable
private fun FeatureScreenPreview() {
    MaterialTheme {
        FeatureScreen(
            state = FeatureUiState(items = sampleItems),
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureScreenLoadingPreview() {
    MaterialTheme {
        FeatureScreen(
            state = FeatureUiState(isLoading = true),
            onAction = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeatureScreenErrorPreview() {
    MaterialTheme {
        FeatureScreen(
            state = FeatureUiState(error = "Network error"),
            onAction = {},
        )
    }
}

// Dark mode preview
@Preview(showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun FeatureScreenDarkPreview() { }
```

---

## 8. Performance Tips

```kotlin
// ✅ Use remember for expensive calculations
val sortedItems = remember(items) {
    items.sortedBy { it.name }
}

// ✅ Use derivedStateOf for derived values
val isButtonEnabled by remember {
    derivedStateOf { name.isNotBlank() && email.isNotBlank() }
}

// ✅ Use key() for conditional content
key(item.id) {
    ItemContent(item)
}

// ✅ Use Immutable collections
@Immutable
data class UiState(
    val items: ImmutableList<Item> = persistentListOf()
)

// ❌ WRONG: Lambda in parameter causes recomposition
items.forEach { item ->
    Button(onClick = { onItemClick(item) })  // Creates new lambda!
}

// ✅ CORRECT: Use remember for stable lambdas
val onClickHandler = remember(item.id) { { onItemClick(item) } }
```

---

## 9. ❌ Anti-Patterns

```kotlin
// ❌ WRONG: Business logic in composable
@Composable
fun BadScreen() {
    val result = calculateDiscount(price)  // Move to ViewModel!
}

// ❌ WRONG: ViewModel created in composable
@Composable  
fun BadScreen() {
    val viewModel = remember { MyViewModel() }  // Use hiltViewModel()!
}

// ❌ WRONG: Stateful Screen accepting callbacks
@Composable
fun BadScreen(
    viewModel: ViewModel,  // Screen should be stateless!
)

// ❌ WRONG: No modifier parameter
@Composable
fun BadComponent(title: String) { }  // Missing modifier!

// ❌ WRONG: collectAsState without lifecycle
val state by viewModel.state.collectAsState()  // Use collectAsStateWithLifecycle!
```

---

**Reference**: `skills/guides/05-jetpack-compose.md` for full patterns
