---
name: Compose Performance & Metrics
description: Techniques for measuring and optimizing Compose performance.
compliance_level: MANDATORY
tags: [performance, compose, properties, stability, benchmark]
version: 2.2.0
---

# Compose Performance & Metrics

## Context
Compose performance relies heavily on **Stability**. Unstable parameters cause unnecessary recompositions. This guide ensures UI smoothness and efficiency.

**Related Guides:**
- [05-jetpack-compose.md](./05-jetpack-compose.md) - Compose patterns
- [23-memory-performance.md](./23-memory-performance.md) - Memory management
- [25-performance-benchmarks.md](./25-performance-benchmarks.md) - Performance targets

---

## 🎯 AI Quick Reference

```
STABILITY RULES:
• @Immutable for data classes with only val
• @Stable for mutable classes with stable equals/hashCode
• ImmutableList/ImmutableSet for collections
• Method references for callbacks

OPTIMIZATION:
• LazyColumn: key = { item.id }, contentType
• derivedStateOf for computed values
• Defer state reads (lambda modifiers)
• remember { } for expensive calculations

METRICS:
• Enable compiler reports
• Layout Inspector recomposition highlights
• Benchmark with Macrobenchmark
```

---

## 1. Stability Fundamentals

### What Makes a Class Stable?

| Annotation | Requirements | Use Case |
|------------|--------------|----------|
| `@Immutable` | All properties `val`, no mutable refs | Data classes, UiState |
| `@Stable` | Can mutate, but equals/hashCode stable | Wrapper classes |
| None (unstable) | Mutable or unknown equality | Causes recomposition |

### ✅ DO: Stable Classes
```kotlin
// ════════════════════════════════════════════════════════════════
// @Immutable - All val, immutable collections
// ════════════════════════════════════════════════════════════════
@Immutable
data class ProductUiState(
    val products: ImmutableList<Product> = persistentListOf(),
    val selectedIds: ImmutableSet<String> = persistentSetOf(),
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    // Derived properties are fine
    val isEmpty: Boolean get() = products.isEmpty()
    val selectedCount: Int get() = selectedIds.size
}

@Immutable
data class Product(
    val id: String,
    val name: String,
    val price: Double,
    val imageUrl: String?,
)

// ════════════════════════════════════════════════════════════════
// @Stable - Mutable but stable equality
// ════════════════════════════════════════════════════════════════
@Stable
class CartManager {
    var itemCount by mutableStateOf(0)
        private set
    
    fun addItem() { itemCount++ }
    
    // equals/hashCode based on identity (object reference)
}

// ════════════════════════════════════════════════════════════════
// Collection wrappers for external models
// ════════════════════════════════════════════════════════════════
@Immutable
data class StableList<T>(
    val items: List<T>,
) {
    override fun equals(other: Any?) = other is StableList<*> && items === other.items
    override fun hashCode() = System.identityHashCode(items)
}
```

### ❌ DON'T: Unstable Patterns
```kotlin
// ❌ BAD: var property
data class BadState(
    var selectedId: String = "", // ❌ var makes entire class unstable
)

// ❌ BAD: Standard List (unstable interface)
data class BadState(
    val items: List<Product>, // ❌ List is not stable
)

// ❌ BAD: Mutable collection
data class BadState(
    val items: MutableList<Product>, // ❌ Mutable
)

// ❌ BAD: No annotation on complex class
data class UnknownStability(
    val config: ExternalLibraryConfig, // ❌ Unknown stability
)
```

---

## 2. Lambda Stability

### ✅ DO: Stable Lambdas
```kotlin
@Composable
fun ProductListRoute(
    viewModel: ProductViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    ProductListScreen(
        state = state,
        // ✅ Method references - always stable
        onProductClick = viewModel::onProductClick,
        onFavoriteClick = viewModel::onFavoriteClick,
        onRefresh = viewModel::refresh,
    )
}

@Composable
fun ProductListScreen(
    state: ProductUiState,
    modifier: Modifier = Modifier,
    onProductClick: (String) -> Unit = {},
    onFavoriteClick: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    // ✅ remember lambda when transformation needed
    val handleProductClick: (Product) -> Unit = remember(onProductClick) {
        { product -> onProductClick(product.id) }
    }
    
    LazyColumn(modifier = modifier) {
        items(
            items = state.products,
            key = { it.id },
        ) { product ->
            ProductCard(
                product = product,
                onClick = { handleProductClick(product) },
            )
        }
    }
}
```

### ❌ DON'T: Unstable Lambdas
```kotlin
@Composable
fun BadScreen(viewModel: MyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    // ❌ BAD: New lambda on every recomposition
    ProductList(
        items = state.products,
        onClick = { id -> viewModel.onProductClick(id) }, // ❌ New lambda
    )
    
    // ❌ BAD: Capturing changing state in lambda
    Button(
        onClick = {
            viewModel.submit(state.name, state.email) // ❌ Captures state
        },
    ) {
        Text("Submit")
    }
}

// ✅ GOOD: Use method reference or remember
@Composable
fun GoodScreen(viewModel: MyViewModel) {
    ProductList(
        onClick = viewModel::onProductClick, // ✅ Method reference
    )
    
    // ✅ If transformation needed, use remember
    val submitCallback = remember(viewModel) {
        { viewModel.submitCurrentState() }
    }
    Button(onClick = submitCallback) {
        Text("Submit")
    }
}
```

---

## 3. LazyList Optimization

### ✅ DO: Optimized LazyColumn
```kotlin
@Composable
fun OptimizedProductList(
    products: ImmutableList<Product>,
    promotions: ImmutableList<Promotion>,
    modifier: Modifier = Modifier,
    onProductClick: (String) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ✅ Header with unique key
        item(key = "header", contentType = "header") {
            ListHeader(title = "Products")
        }
        
        // ✅ Promotions section with contentType
        items(
            items = promotions,
            key = { "promo_${it.id}" }, // ✅ Unique key
            contentType = { "promotion" }, // ✅ Content type for recycling
        ) { promotion ->
            PromotionBanner(promotion = promotion)
        }
        
        // ✅ Products with stable key
        items(
            items = products,
            key = { it.id }, // ✅ Stable key = item.id
            contentType = { "product" }, // ✅ Content type
        ) { product ->
            ProductCard(
                product = product,
                onClick = { onProductClick(product.id) },
            )
        }
        
        // ✅ Footer
        item(key = "footer", contentType = "footer") {
            ListFooter()
        }
    }
}

// ✅ Stable item composable
@Composable
fun ProductCard(
    product: Product,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        // Content
    }
}
```

### ❌ DON'T: Unoptimized LazyColumn
```kotlin
// ❌ BAD: No keys, no contentType
LazyColumn {
    items(products) { product -> // ❌ No key!
        ProductCard(product)
    }
}

// ❌ BAD: Index as key
LazyColumn {
    itemsIndexed(products) { index, product ->
        key(index) { // ❌ Index changes when list changes!
            ProductCard(product)
        }
    }
}

// ❌ BAD: Complex calculation in composable
LazyColumn {
    items(products, key = { it.id }) { product ->
        val discount = calculateDiscount(product) // ❌ Expensive in composition
        ProductCard(product, discount)
    }
}

// ✅ GOOD: Pre-calculate or remember
LazyColumn {
    items(products, key = { it.id }) { product ->
        val discount = remember(product.price) {
            calculateDiscount(product) // ✅ Cached
        }
        ProductCard(product, discount)
    }
}
```

---

## 4. State Optimization

### derivedStateOf
```kotlin
@Composable
fun SearchableList(
    items: ImmutableList<Product>,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    
    // ✅ derivedStateOf - recalculates only when dependencies change
    val filteredItems by remember(items) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                items
            } else {
                items.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }
    }
    
    Column(modifier = modifier) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
        )
        
        ProductList(products = filteredItems)
    }
}

// ════════════════════════════════════════════════════════════════
// Scroll-based derived state
// ════════════════════════════════════════════════════════════════
@Composable
fun ScrollableContent() {
    val scrollState = rememberLazyListState()
    
    // ✅ Derived state for scroll-dependent UI
    val showScrollToTop by remember {
        derivedStateOf { scrollState.firstVisibleItemIndex > 5 }
    }
    
    Box {
        LazyColumn(state = scrollState) {
            // Content
        }
        
        AnimatedVisibility(
            visible = showScrollToTop,
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            FloatingActionButton(
                onClick = { /* scroll to top */ },
            ) {
                Icon(Icons.Default.KeyboardArrowUp, "Scroll to top")
            }
        }
    }
}
```

### Deferred State Reads
```kotlin
@Composable
fun AnimatedHeader(scrollOffset: () -> Float) { // ✅ Lambda parameter
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            // ✅ State read deferred to layout phase
            .offset { IntOffset(0, scrollOffset().toInt()) }
            .graphicsLayer {
                alpha = 1f - (scrollOffset() / 500f).coerceIn(0f, 1f)
            },
    ) {
        // Content
    }
}

// Usage
@Composable
fun Screen() {
    val scrollState = rememberScrollState()
    
    Column {
        // ✅ Pass lambda, not value
        AnimatedHeader(scrollOffset = { scrollState.value.toFloat() })
        
        Column(
            modifier = Modifier.verticalScroll(scrollState),
        ) {
            // Content
        }
    }
}
```

---

## 5. Compiler Metrics

### Enable Metrics
```kotlin
// build.gradle.kts (app module)
android {
    buildFeatures {
        compose = true
    }
}

// For stability reports
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" +
                project.layout.buildDirectory.dir("compose_reports").get().asFile.absolutePath,
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" +
                project.layout.buildDirectory.dir("compose_metrics").get().asFile.absolutePath,
        )
    }
}
```

### Analyze Reports
```bash
# Generate reports
./gradlew assembleRelease

# Check compose_reports folder for:
# - *-classes.txt: Stability of each class
# - *-composables.txt: Restartability and skippability
# - *-composables.csv: Detailed metrics
```

### Interpreting Results
```
# classes.txt - Look for "unstable"
stable class ProductUiState {
  stable val products: ImmutableList<Product>
  stable val isLoading: Boolean
}

unstable class BadState {  // ❌ Fix this!
  unstable val items: List<Item>  // Problem: List is unstable
}

# composables.txt - Check skippable
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun ProductCard(
  stable product: Product
  stable modifier: Modifier? = @static Companion
  stable onClick: Function0<Unit>
)

restartable scheme("[androidx.compose.ui.UiComposable]") fun BadComponent(  // ❌ Not skippable!
  unstable data: BadData
)
```

---

## 6. Verification Checklist

### Stability
- [ ] All UiState classes have `@Immutable`
- [ ] Collections are `ImmutableList`/`ImmutableSet`
- [ ] No `var` properties in state classes
- [ ] External classes wrapped with stable wrappers

### Lambdas
- [ ] Method references used where possible
- [ ] Complex lambdas use `remember { }`
- [ ] No state captured in lambdas without remember

### LazyList
- [ ] All items have unique stable `key`
- [ ] `contentType` specified for different item types
- [ ] No expensive calculations in item composables

### State
- [ ] `derivedStateOf` for computed values
- [ ] Deferred reads for animation/scroll values
- [ ] `remember` for expensive calculations

### Metrics
- [ ] Compiler reports enabled
- [ ] Unstable classes identified and fixed
- [ ] All composables are skippable
