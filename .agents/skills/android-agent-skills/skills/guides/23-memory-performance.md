---
name: Memory Management & Performance
description: Strategies for preventing memory leaks, optimizing lists, and efficient networking.
compliance_level: MANDATORY
tags: [memory, performance, leaks, compose, list, image-loading]
version: 2.2.0
---

# Memory Management & Performance

## Context
Efficient memory usage prevents crashes (OOM) and ensures smooth UI (60fps). This guide covers mandatory practices for memory safety and performance optimization.

**Related Guides:**
- [06-compose-performance.md](./06-compose-performance.md) - Compose optimization
- [25-performance-benchmarks.md](./25-performance-benchmarks.md) - Performance metrics

---

## 🎯 AI Quick Reference

```
MEMORY LEAKS:
• collectAsStateWithLifecycle() in Compose
• viewModelScope/lifecycleScope for coroutines
• NEVER store Activity context in singleton
• WeakReference for callbacks

LIST OPTIMIZATION:
• LazyColumn: key = { item.id }
• contentType for different item types
• Avoid nested scrolling same direction

IMAGE LOADING:
• Coil with disk/memory cache
• Downsample to view size
• Cancel on dispose
```

---

## 1. Memory Leak Prevention

### ✅ DO: Safe Patterns
```kotlin
// ════════════════════════════════════════════════════════════════
// Compose: collectAsStateWithLifecycle
// ════════════════════════════════════════════════════════════════
@Composable
fun UserScreen(viewModel: UserViewModel = hiltViewModel()) {
    // ✅ Automatically stops collection when lifecycle is below STARTED
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    UserContent(state = state)
}

// ════════════════════════════════════════════════════════════════
// ViewModel: viewModelScope auto-cancels
// ════════════════════════════════════════════════════════════════
@HiltViewModel
class UserViewModel @Inject constructor(
    private val repository: UserRepository,
) : ViewModel() {
    
    init {
        // ✅ viewModelScope cancels when ViewModel is cleared
        viewModelScope.launch {
            repository.observeUser()
                .collect { user ->
                    _state.update { it.copy(user = user) }
                }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Singleton: Application context only
// ════════════════════════════════════════════════════════════════
class NetworkClient @Inject constructor(
    @ApplicationContext private val context: Context, // ✅ Application context
) {
    // Safe to hold reference
}

// ════════════════════════════════════════════════════════════════
// Callbacks: Use WeakReference or lambdas
// ════════════════════════════════════════════════════════════════
class ImageLoader {
    private var callback: WeakReference<Callback>? = null // ✅ Weak
    
    fun setCallback(cb: Callback) {
        callback = WeakReference(cb)
    }
    
    private fun onComplete(bitmap: Bitmap) {
        callback?.get()?.onImageLoaded(bitmap)
    }
}

// ════════════════════════════════════════════════════════════════
// Compose: DisposableEffect for cleanup
// ════════════════════════════════════════════════════════════════
@Composable
fun LocationTracker(onLocationUpdate: (Location) -> Unit) {
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        val locationManager = context.getSystemService<LocationManager>()
        val listener = LocationListener { location ->
            onLocationUpdate(location)
        }
        
        locationManager?.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            10f,
            listener,
        )
        
        onDispose {
            // ✅ Clean up when composable leaves composition
            locationManager?.removeUpdates(listener)
        }
    }
}
```

### ❌ DON'T: Memory Leak Patterns
```kotlin
// ❌ BAD: Activity context in singleton
object UserManager {
    var context: Context? = null // ❌ LEAK! Holds Activity forever
    
    fun init(context: Context) {
        this.context = context // ❌ If Activity, it leaks
    }
}

// ❌ BAD: Anonymous class holding Activity reference
class MyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ❌ Anonymous Runnable holds reference to Activity
        handler.postDelayed({
            updateUI() // ❌ 'this' is captured
        }, 10_000)
    }
}

// ❌ BAD: Collecting flow without lifecycle awareness
@Composable
fun BadScreen(viewModel: MyViewModel) {
    // ❌ Continues collecting even when screen is in background
    val state by viewModel.state.collectAsState()
}

// ❌ BAD: Custom scope without cancellation
class BadRepository {
    private val scope = CoroutineScope(Dispatchers.IO) // ❌ Never cancelled!
    
    fun fetchData() {
        scope.launch { /* ... */ } // ❌ Leaks if Repository outlives usage
    }
}
```

---

## 2. List Performance

### ✅ DO: Optimized LazyColumn
```kotlin
@Composable
fun OptimizedList(
    items: ImmutableList<Item>,
    modifier: Modifier = Modifier,
    onItemClick: (String) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ✅ Header with unique key and contentType
        item(key = "header", contentType = "header") {
            ListHeader()
        }
        
        // ✅ Items with stable key and contentType
        items(
            items = items,
            key = { it.id }, // ✅ Stable unique key
            contentType = { "item" }, // ✅ Same type for recycling
        ) { item ->
            // ✅ Item composable is stable
            ItemCard(
                item = item,
                onClick = { onItemClick(item.id) },
            )
        }
        
        // ✅ Footer
        item(key = "footer", contentType = "footer") {
            ListFooter()
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Paging with Paging3
// ════════════════════════════════════════════════════════════════
@Composable
fun PaginatedList(
    items: LazyPagingItems<Item>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.id }, // ✅ Stable key
            contentType = items.itemContentType { "item" },
        ) { index ->
            val item = items[index]
            if (item != null) {
                ItemCard(item = item)
            } else {
                ItemPlaceholder()
            }
        }
        
        // Loading state
        when (items.loadState.append) {
            is LoadState.Loading -> {
                item { LoadingIndicator() }
            }
            is LoadState.Error -> {
                item { RetryButton(onClick = { items.retry() }) }
            }
            else -> {}
        }
    }
}
```

### ❌ DON'T: Unoptimized Lists
```kotlin
// ❌ BAD: No key - items get recreated on reorder
LazyColumn {
    items(items) { item -> // ❌ No key!
        ItemCard(item)
    }
}

// ❌ BAD: Index as key - breaks on reorder/insert
LazyColumn {
    itemsIndexed(items) { index, item ->
        key(index) { // ❌ Index changes!
            ItemCard(item)
        }
    }
}

// ❌ BAD: Nested scrolling in same direction
LazyColumn { // Vertical
    item {
        LazyColumn { // ❌ Nested vertical scroll!
            items(nestedItems) { /* ... */ }
        }
    }
}

// ✅ FIX: Use LazyColumn with combined items
LazyColumn {
    items(mainItems, key = { it.id }) { /* ... */ }
    items(nestedItems, key = { "nested_${it.id}" }) { /* ... */ }
}
```

---

## 3. Image Loading with Coil

### ✅ DO: Efficient Image Loading
```kotlin
// ════════════════════════════════════════════════════════════════
// Basic AsyncImage with downsampling
// ════════════════════════════════════════════════════════════════
@Composable
fun ProductImage(
    url: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .size(Size.ORIGINAL) // Or specific size
            .scale(Scale.FILL)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build(),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = painterResource(R.drawable.placeholder),
        error = painterResource(R.drawable.error),
    )
}

// ════════════════════════════════════════════════════════════════
// Thumbnail with specific size (saves memory)
// ════════════════════════════════════════════════════════════════
@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .size(200, 200) // ✅ Downsample to 200x200
            .transformations(CircleCropTransformation()) // Optional
            .build(),
        contentDescription = null,
        modifier = modifier.size(100.dp),
    )
}

// ════════════════════════════════════════════════════════════════
// SubcomposeAsyncImage for loading states
// ════════════════════════════════════════════════════════════════
@Composable
fun ImageWithLoading(url: String?) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            is AsyncImagePainter.State.Error -> {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = "Error",
                )
            }
            else -> {
                SubcomposeAsyncImageContent()
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Coil setup in Application
// ════════════════════════════════════════════════════════════════
@HiltAndroidApp
class MyApp : Application(), ImageLoaderFactory {
    
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of app memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // 2% of disk
                    .build()
            }
            .crossfade(true)
            .respectCacheHeaders(false) // Cache even without headers
            .build()
    }
}
```

---

## 4. General Optimization

### ✅ DO: Efficient Patterns
```kotlin
// ════════════════════════════════════════════════════════════════
// Sequence for large collections
// ════════════════════════════════════════════════════════════════
fun processLargeList(items: List<Item>): List<ProcessedItem> {
    // ✅ Sequence: lazy evaluation, single pass
    return items.asSequence()
        .filter { it.isValid }
        .map { processItem(it) }
        .take(100)
        .toList()
}

// ❌ BAD: Multiple intermediate lists
fun badProcessing(items: List<Item>): List<ProcessedItem> {
    return items
        .filter { it.isValid } // ❌ Creates new list
        .map { processItem(it) } // ❌ Creates another list
        .take(100) // ❌ Creates third list
}

// ════════════════════════════════════════════════════════════════
// Lazy initialization
// ════════════════════════════════════════════════════════════════
class ExpensiveClass {
    // ✅ Only computed when first accessed
    val expensiveValue: ExpensiveObject by lazy {
        computeExpensiveValue()
    }
    
    // ✅ Lazy with thread safety mode
    val threadSafeValue by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        computeValue()
    }
}

// ════════════════════════════════════════════════════════════════
// Object pooling for frequent allocations
// ════════════════════════════════════════════════════════════════
class StringBuilder {
    private val pool = ArrayDeque<StringBuilder>()
    
    fun acquire(): StringBuilder {
        return pool.removeFirstOrNull() ?: StringBuilder()
    }
    
    fun release(builder: StringBuilder) {
        builder.clear()
        pool.addLast(builder)
    }
}

// ════════════════════════════════════════════════════════════════
// Avoid allocations in hot paths
// ════════════════════════════════════════════════════════════════
// ❌ BAD: Creates new Pair on every call
fun getPosition(): Pair<Float, Float> = Pair(x, y)

// ✅ GOOD: Reuse or use primitives
data class Position(var x: Float, var y: Float)
private val position = Position(0f, 0f)

fun getPosition(): Position {
    position.x = x
    position.y = y
    return position
}
```

---

## 5. Verification Checklist

### Memory Safety
- [ ] `collectAsStateWithLifecycle()` in Compose
- [ ] `viewModelScope`/`lifecycleScope` for coroutines
- [ ] No Activity context in singletons
- [ ] `DisposableEffect` for cleanup
- [ ] No anonymous classes holding Activity

### List Performance
- [ ] `key = { item.id }` in LazyColumn
- [ ] `contentType` for different item types
- [ ] No nested same-direction scrolling
- [ ] Paging for large datasets

### Images
- [ ] Coil with memory/disk cache
- [ ] Downsampling to view size
- [ ] Placeholder and error states
- [ ] Cache policy configured

### General
- [ ] `Sequence` for large collection ops
- [ ] `by lazy` for expensive initialization
- [ ] No allocations in hot loops
