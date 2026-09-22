---
name: Performance Benchmarks & Optimization
description: Performance metrics, benchmarks, and optimization strategies for Android apps
compliance_level: RECOMMENDED
tags: [performance, benchmarks, optimization, profiling, metrics]
version: 2.2.0
last_updated: 2026-01-24
---

# Performance Benchmarks & Optimization

## Context
Quantifiable performance targets ensure consistent user experience. This guide provides specific benchmarks and optimization strategies for Android apps with Jetpack Compose.

## Performance Budget

### App Startup
| Metric | Target | Measurement |
|--------|--------|-------------|
| Cold Start | < 2s | Time to first frame |
| Warm Start | < 1s | Activity recreation |
| Hot Start | < 500ms | Activity bring to foreground |

**Measurement:**
```kotlin
// In Application.onCreate()
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val startTime = System.currentTimeMillis()
        
        // App initialization
        
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val duration = System.currentTimeMillis() - startTime
                Log.d("Startup", "Time to first activity: ${duration}ms")
            }
            // ... other callbacks
        })
    }
}
```

### Compose Performance
| Metric | Target | Measurement |
|--------|--------|-------------|
| Recomposition | < 16ms (60 FPS) | Frame rendering time |
| Initial Composition | < 100ms | First render |
| Screen Navigation | < 300ms | Transition duration |

**Measurement with Compose Metrics:**
```bash
# Enable compose compiler metrics
./gradlew assembleRelease -Pandroidx.compose.compiler.reportsDestination=build/compose_metrics
```

### Memory
| Metric | Target | Measurement |
|--------|--------|-------------|
| Per Screen | < 5MB | Heap allocation |
| Leak-Free | 0 leaks | LeakCanary |
| Bitmap Memory | < 10MB total | Memory Profiler |

**Measurement:**
```kotlin
val runtime = Runtime.getRuntime()
val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
Log.d("Memory", "Used: ${usedMemory}MB")
```

### Network
| Metric | Target | Measurement |
|--------|--------|-------------|
| API Response | < 1s | Request to response |
| Image Load | < 500ms | Coil/Glide metrics |
| Retry Strategy | Exponential backoff | 3 retries max |

### Database
| Metric | Target | Measurement |
|--------|--------|-------------|
| Query Time | < 50ms | Room query profiler |
| Write Time | < 100ms | Transaction duration |
| Index Coverage | 100% | All frequent queries |

## Optimization Strategies

### 1. App Startup Optimization

#### ✅ DO: Lazy Initialization
```kotlin
// Use lazy delegates for expensive objects
class MyApplication : Application() {
    val database by lazy { AppDatabase.getInstance(this) }
    val imageLoader by lazy { ImageLoader.Builder(this).build() }
    
    override fun onCreate() {
        super.onCreate()
        // Only initialize critical components
        initializeCrashReporting()
        initializeAnalytics()
    }
}
```

#### ✅ DO: Content Provider for Background Init
```kotlin
class WorkManagerInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        WorkManager.initialize(context!!, Configuration.Builder().build())
        return true
    }
    // ... other overrides
}
```

#### ✅ DO: Startup Library
```kotlin
dependencies {
    implementation "androidx.startup:startup-runtime:1.1.1"
}

class WorkManagerInitializer : Initializer<WorkManager> {
    override fun create(context: Context): WorkManager {
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
        WorkManager.initialize(context, configuration)
        return WorkManager.getInstance(context)
    }
    
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
```

#### ❌ AVOID: Blocking Main Thread
```kotlin
// ❌ DON'T block main thread during startup
override fun onCreate() {
    super.onCreate()
    database.query()  // ❌ Blocks
    networkClient.warmUp()  // ❌ Blocks
}

// ✅ DO: Initialize asynchronously
override fun onCreate() {
    super.onCreate()
    lifecycleScope.launch {
        database.warmUp()
        networkClient.initialize()
    }
}
```

### 2. Compose Performance Optimization

#### ✅ DO: Stability Annotations
```kotlin
@Immutable
data class User(val id: String, val name: String)

@Stable
interface UserRepository {
    fun getUser(id: String): Flow<User>
}
```

#### ✅ DO: Remember Expensive Calculations
```kotlin
@Composable
fun ExpensiveList(items: List<Item>) {
    val processedItems = remember(items) {
        items.map { process(it) }  // Only recompute when items change
    }
    
    LazyColumn {
        items(processedItems) { item ->
            ItemCard(item)
        }
    }
}
```

#### ✅ DO: Defer Read with derivedStateOf
```kotlin
@Composable
fun SearchResults(query: String, items: List<Item>) {
    val filteredItems by remember {
        derivedStateOf {
            items.filter { it.name.contains(query, ignoreCase = true) }
        }
    }
    
    LazyColumn {
        items(filteredItems) { item ->
            ItemCard(item)
        }
    }
}
```

#### ✅ DO: Key for LazyColumn Stability
```kotlin
LazyColumn {
    items(
        items = users,
        key = { user -> user.id }  // Stable key for efficient recomposition
    ) { user ->
        UserCard(user)
    }
}
```

#### ❌ AVOID: Lambda Allocations in Loops
```kotlin
// ❌ Creates new lambda each recomposition
@Composable
fun UserList(users: List<User>) {
    LazyColumn {
        items(users) { user ->
            UserCard(
                user = user,
                onClick = { viewModel.onUserClick(user.id) }  // ❌ New lambda
            )
        }
    }
}

// ✅ Extract callback or use key
@Composable
fun UserList(users: List<User>, onUserClick: (String) -> Unit) {
    LazyColumn {
        items(users, key = { it.id }) { user ->
            UserCard(
                user = user,
                onClick = { onUserClick(user.id) }  // ✅ Stable reference
            )
        }
    }
}
```

### 3. Memory Optimization

#### ✅ DO: Bitmap Downsampling
```kotlin
// Coil configuration
ImageLoader.Builder(context)
    .memoryCache {
        MemoryCache.Builder(context)
            .maxSizePercent(0.25)  // 25% of app memory
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("image_cache"))
            .maxSizeBytes(50 * 1024 * 1024)  // 50MB
            .build()
    }
    .build()
```

#### ✅ DO: Pagination
```kotlin
@Composable
fun PaginatedList(viewModel: ListViewModel = hiltViewModel()) {
    val items = viewModel.pager.collectAsLazyPagingItems()
    
    LazyColumn {
        items(items.itemCount) { index ->
            items[index]?.let { item ->
                ItemCard(item)
            }
        }
    }
}
```

#### ❌ AVOID: Memory Leaks
```kotlin
// ❌ DON'T hold Activity/Fragment references
class LeakyRepository {
    private var activity: Activity? = null  // ❌ Leak
    
    fun setActivity(activity: Activity) {
        this.activity = activity
    }
}

// ✅ DO: Use Application Context or WeakReference
class SafeRepository(private val appContext: Context) {
    // Use application context only
}
```

### 4. Network Optimization

#### ✅ DO: Request Batching
```kotlin
class BatchingRepository(private val api: Api) {
    private val requestQueue = Channel<Request>(Channel.UNLIMITED)
    
    init {
        CoroutineScope(Dispatchers.IO).launch {
            requestQueue.consumeAsFlow()
                .chunked(50)
                .collect { batch ->
                    api.batchRequest(batch)
                }
        }
    }
}
```

#### ✅ DO: Caching Strategy
```kotlin
class CachedRepository(
    private val api: Api,
    private val cache: Cache,
    private val clock: Clock,
) {
    suspend fun getData(forceRefresh: Boolean = false): Result<Data> {
        val cached = cache.get()
        
        return when {
            !forceRefresh && cached != null && cached.isValid(clock) -> {
                Result.success(cached.data)
            }
            else -> {
                api.fetchData()
                    .onSuccess { cache.put(it, clock.now()) }
            }
        }
    }
}
```

#### ✅ DO: Image Optimization
```kotlin
AsyncImage(
    model = ImageRequest.Builder(context)
        .data(imageUrl)
        .size(800, 600)  // Target size
        .scale(Scale.FIT)
        .crossfade(true)
        .memoryCacheKey(imageUrl)
        .diskCacheKey(imageUrl)
        .build(),
    contentDescription = null
)
```

### 5. Database Optimization

#### ✅ DO: Proper Indexing
```kotlin
@Entity(
    tableName = "users",
    indices = [
        Index(value = ["email"], unique = true),
        Index(value = ["last_name", "first_name"])  // Composite index
    ]
)
data class UserEntity(
    @PrimaryKey val id: String,
    val email: String,
    @ColumnInfo(name = "first_name") val firstName: String,
    @ColumnInfo(name = "last_name") val lastName: String,
)
```

#### ✅ DO: Batch Operations
```kotlin
@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<UserEntity>)
    
    @Transaction
    suspend fun updateUsers(users: List<UserEntity>) {
        deleteAll()
        insertAll(users)
    }
}
```

#### ❌ AVOID: N+1 Query Problem
```kotlin
// ❌ N+1 queries
suspend fun getUsersWithPosts(): List<UserWithPosts> {
    val users = userDao.getAll()  // 1 query
    return users.map { user ->
        UserWithPosts(
            user = user,
            posts = postDao.getPostsByUser(user.id)  // N queries
        )
    }
}

// ✅ Single query with JOIN
@Transaction
@Query("""
    SELECT * FROM users
    LEFT JOIN posts ON users.id = posts.user_id
""")
suspend fun getUsersWithPosts(): List<UserWithPosts>
```

## Profiling Tools

### 1. Android Profiler (Android Studio)
```kotlin
// CPU Profiler: Identify slow methods
// Memory Profiler: Detect leaks and allocations
// Network Profiler: Monitor API calls
```

### 2. Macrobenchmark
```kotlin
dependencies {
    androidTestImplementation "androidx.benchmark:benchmark-macro-junit4:1.2.0"
}

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()
    
    @Test
    fun startup() = benchmarkRule.measureRepeated(
        packageName = "com.example.app",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

### 3. Compose Compiler Metrics
```bash
# In gradle.properties
android.enableComposeCompilerMetrics=true
android.enableComposeCompilerReports=true

# Run build
./gradlew assembleRelease

# Check reports
cat app/build/compose_metrics/*-composables.txt
```

### 4. Baseline Profiles
```kotlin
dependencies {
    implementation "androidx.profileinstaller:profileinstaller:1.3.1"
}

// Generate baseline profile
./gradlew :app:generateBaselineProfile
```

### 5. LeakCanary
```kotlin
dependencies {
    debugImplementation "com.squareup.leakcanary:leakcanary-android:2.12"
}
```

## Performance Testing Checklist

### Before Release
- [ ] Startup time < 2s on mid-range devices
- [ ] No memory leaks detected by LeakCanary
- [ ] All screens render < 16ms per frame
- [ ] Network requests cached appropriately
- [ ] Database queries use indexes
- [ ] Images optimized and downsampled
- [ ] Baseline profile generated
- [ ] Compose compiler metrics reviewed

### Monitoring
- [ ] Firebase Performance monitoring enabled
- [ ] Crash reporting configured
- [ ] ANR tracking enabled
- [ ] Frame drop metrics tracked

## Common Performance Issues

### Issue 1: Slow Startup
**Symptoms:** App takes > 3s to show first screen
**Causes:**
- Synchronous initialization in Application.onCreate()
- Too many dependencies injected eagerly
- Large resources loaded at startup

**Solutions:**
- Use lazy initialization
- Move to background threads
- Use Content Providers for library init
- Enable R8 full mode

### Issue 2: Janky Scrolling
**Symptoms:** LazyColumn drops frames during scroll
**Causes:**
- Heavy computation in Composables
- No stable keys in items()
- Non-@Immutable data classes
- Lambda allocations in loops

**Solutions:**
- Use remember() for expensive calculations
- Add key parameter to items()
- Mark data classes @Immutable
- Use derivedStateOf for filtering

### Issue 3: Memory Growth
**Symptoms:** Memory increases continuously
**Causes:**
- Fragment/Activity leaks
- Listener not unregistered
- Bitmap caching issues
- Flow collection leaks

**Solutions:**
- Use LeakCanary to identify leaks
- Use viewModelScope/lifecycleScope
- Configure image loader properly
- Use collectAsStateWithLifecycle()

### Issue 4: Slow Network
**Symptoms:** API calls take > 2s
**Causes:**
- No request timeouts
- No caching strategy
- Large payloads
- Serial requests

**Solutions:**
- Set timeouts (connect: 10s, read: 30s)
- Implement cache-first strategy
- Use pagination and request only needed fields
- Parallelize independent requests

## Advanced Optimization

### 1. R8 Full Mode
```groovy
// build.gradle.kts
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

### 2. Startup Tasks with WorkManager
```kotlin
class DeferredStartupTask : Worker(context, params) {
    override fun doWork(): Result {
        // Non-critical initialization
        return Result.success()
    }
}

// In Application.onCreate()
WorkManager.getInstance(this)
    .enqueueUniqueWork(
        "deferred_startup",
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequest.from(DeferredStartupTask::class.java)
    )
```

### 3. Ahead-of-Time Compilation
```bash
# Enable ART ahead-of-time compilation
adb shell cmd package compile -m speed -f com.example.app
```

## References
- [Android Performance Patterns](https://developer.android.com/topic/performance)
- [Compose Performance](https://developer.android.com/jetpack/compose/performance)
- [Macrobenchmark Guide](https://developer.android.com/studio/profile/macrobenchmark)
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles)

## Related Guides
- [04-memory-performance.md](./04-memory-performance.md) - Memory management
- [06-compose-performance.md](./06-compose-performance.md) - Compose optimization
- [17-build-configuration.md](./17-build-configuration.md) - Build optimization
