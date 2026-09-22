---
name: Offline-First Architecture
description: Patterns for robust offline support and data synchronization.
compliance_level: RECOMMENDED
tags: [offline, database, caching, sync, workmanager]
version: 2.2.0
---

# Offline-First Architecture

## Context
Apps should work reliably without internet. The local database is the Single Source of Truth (SSOT). Network calls update the database, and UI observes the database.

**Related Guides:**
- [01-architecture.md](./01-architecture.md) - Clean Architecture
- [10-error-handling.md](./10-error-handling.md) - Error handling patterns

---

## 🎯 AI Quick Reference

```
PATTERN:
UI → observes → Database ← updates ← Network
                 (SSOT)

REPOSITORY:
• Expose Flow<List<T>> from DAO
• suspend fun refresh() fetches from API → saves to DB
• Never return API response directly

SYNC:
• WorkManager for background sync
• Constraints: NetworkType.CONNECTED
• ExistingWorkPolicy.KEEP for deduplication
```

---

## 1. Repository Pattern

### ✅ DO: Database as Single Source of Truth
```kotlin
// ════════════════════════════════════════════════════════════════
// Domain interface
// ════════════════════════════════════════════════════════════════
interface PostRepository {
    fun observePosts(): Flow<List<Post>>
    fun observePost(id: String): Flow<Post?>
    suspend fun refresh(): Result<Unit>
    suspend fun createPost(title: String, content: String): Result<Post>
}

// ════════════════════════════════════════════════════════════════
// Data implementation
// ════════════════════════════════════════════════════════════════
class PostRepositoryImpl @Inject constructor(
    private val api: PostApi,
    private val dao: PostDao,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PostRepository {
    
    // ✅ UI observes database, not network
    override fun observePosts(): Flow<List<Post>> {
        return dao.observeAllPosts()
            .map { entities -> entities.map { it.toDomain() } }
            .flowOn(ioDispatcher)
    }
    
    override fun observePost(id: String): Flow<Post?> {
        return dao.observePostById(id)
            .map { it?.toDomain() }
            .flowOn(ioDispatcher)
    }
    
    // ✅ Refresh updates database
    override suspend fun refresh(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val posts = api.getPosts()
            dao.upsertAll(posts.map { it.toEntity() })
        }
    }
    
    // ✅ Optimistic update with sync
    override suspend fun createPost(
        title: String,
        content: String,
    ): Result<Post> = withContext(ioDispatcher) {
        runCatching {
            // Create local entity first (optimistic)
            val localId = UUID.randomUUID().toString()
            val entity = PostEntity(
                id = localId,
                title = title,
                content = content,
                createdAt = Clock.System.now(),
                syncStatus = SyncStatus.PENDING,
            )
            dao.insert(entity)
            
            // Try to sync if online
            if (networkMonitor.isOnline()) {
                syncPost(entity)
            }
            
            entity.toDomain()
        }
    }
    
    private suspend fun syncPost(entity: PostEntity) {
        try {
            val response = api.createPost(
                CreatePostRequest(entity.title, entity.content)
            )
            // Update with server ID
            dao.update(entity.copy(
                id = response.id,
                syncStatus = SyncStatus.SYNCED,
            ))
            // Delete local-only record
            dao.deleteById(entity.id)
        } catch (e: Exception) {
            // Keep pending status, will retry in background
            Timber.w(e, "Failed to sync post, will retry later")
        }
    }
}
```

### ❌ DON'T: Return Network Directly
```kotlin
// ❌ BAD: No offline support
class BadRepository(private val api: PostApi) {
    
    // ❌ Breaks when offline
    suspend fun getPosts(): List<Post> {
        return api.getPosts().map { it.toDomain() }
    }
}
```

---

## 2. Room Database Setup

### ✅ DO: Entity with Sync Status
```kotlin
// ════════════════════════════════════════════════════════════════
// Sync status enum
// ════════════════════════════════════════════════════════════════
enum class SyncStatus {
    SYNCED,     // Synced with server
    PENDING,    // Created/modified locally, pending sync
    CONFLICT,   // Sync conflict detected
}

// ════════════════════════════════════════════════════════════════
// Entity with sync metadata
// ════════════════════════════════════════════════════════════════
@Entity(tableName = "posts")
data class PostEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant? = null,
    
    // Sync metadata
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED,
    @ColumnInfo(name = "local_updated_at")
    val localUpdatedAt: Instant = Clock.System.now(),
)

// ════════════════════════════════════════════════════════════════
// DAO with observe and sync queries
// ════════════════════════════════════════════════════════════════
@Dao
interface PostDao {
    
    // Observe all posts (reactive)
    @Query("SELECT * FROM posts ORDER BY createdAt DESC")
    fun observeAllPosts(): Flow<List<PostEntity>>
    
    // Observe single post
    @Query("SELECT * FROM posts WHERE id = :id")
    fun observePostById(id: String): Flow<PostEntity?>
    
    // Get pending posts for sync
    @Query("SELECT * FROM posts WHERE sync_status = 'PENDING'")
    suspend fun getPendingPosts(): List<PostEntity>
    
    // Upsert (insert or update)
    @Upsert
    suspend fun upsertAll(posts: List<PostEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(post: PostEntity)
    
    @Update
    suspend fun update(post: PostEntity)
    
    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun deleteById(id: String)
    
    // Clear all for full refresh
    @Query("DELETE FROM posts")
    suspend fun deleteAll()
}
```

---

## 3. Background Sync with WorkManager

### ✅ DO: SyncManager
```kotlin
// ════════════════════════════════════════════════════════════════
// Sync Manager interface
// ════════════════════════════════════════════════════════════════
interface SyncManager {
    fun scheduleSyncPosts()
    fun schedulePeriodicSync()
    fun cancelSync()
}

// ════════════════════════════════════════════════════════════════
// Implementation with WorkManager
// ════════════════════════════════════════════════════════════════
class SyncManagerImpl @Inject constructor(
    private val workManager: WorkManager,
) : SyncManager {
    
    override fun scheduleSyncPosts() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        
        val request = OneTimeWorkRequestBuilder<SyncPostsWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        
        workManager.enqueueUniqueWork(
            SYNC_POSTS_WORK,
            ExistingWorkPolicy.KEEP, // Don't duplicate if already running
            request,
        )
    }
    
    override fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        
        val request = PeriodicWorkRequestBuilder<SyncPostsWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES,
            flexTimeInterval = 5,
            flexTimeIntervalUnit = TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
    
    override fun cancelSync() {
        workManager.cancelUniqueWork(SYNC_POSTS_WORK)
    }
    
    companion object {
        private const val SYNC_POSTS_WORK = "sync_posts"
        private const val PERIODIC_SYNC_WORK = "periodic_sync"
    }
}

// ════════════════════════════════════════════════════════════════
// Worker implementation
// ════════════════════════════════════════════════════════════════
@HiltWorker
class SyncPostsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val postDao: PostDao,
    private val api: PostApi,
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        return try {
            // Sync pending local changes to server
            val pendingPosts = postDao.getPendingPosts()
            pendingPosts.forEach { post ->
                syncPostToServer(post)
            }
            
            // Fetch latest from server
            val serverPosts = api.getPosts()
            postDao.upsertAll(serverPosts.map { it.toEntity() })
            
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Sync failed")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    private suspend fun syncPostToServer(post: PostEntity) {
        val response = api.createPost(
            CreatePostRequest(post.title, post.content)
        )
        // Update with server ID and mark as synced
        postDao.update(post.copy(
            id = response.id,
            syncStatus = SyncStatus.SYNCED,
        ))
    }
}
```

---

## 4. Network Monitor

### ✅ DO: Reactive Network Status
```kotlin
interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
    fun isOnline(): Boolean
}

class NetworkMonitorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {
    
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    
    private val _isOnline = MutableStateFlow(checkNetworkStatus())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = true
        }
        
        override fun onLost(network: Network) {
            _isOnline.value = false
        }
    }
    
    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }
    
    override fun isOnline(): Boolean = _isOnline.value
    
    private fun checkNetworkStatus(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
```

---

## 5. ViewModel Integration

### ✅ DO: Handle Offline States
```kotlin
@HiltViewModel
class PostsViewModel @Inject constructor(
    private val repository: PostRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncManager: SyncManager,
) : ViewModel() {
    
    private val _state = MutableStateFlow(PostsUiState())
    val state: StateFlow<PostsUiState> = _state.asStateFlow()
    
    init {
        // Observe posts from database
        viewModelScope.launch {
            repository.observePosts()
                .collect { posts ->
                    _state.update { it.copy(
                        posts = posts.toImmutableList(),
                        isLoading = false,
                    ) }
                }
        }
        
        // Observe network status
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(isOnline = online) }
                if (online) {
                    // Auto-refresh when back online
                    refresh()
                }
            }
        }
        
        // Initial refresh
        refresh()
    }
    
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            
            repository.refresh()
                .onFailure { error ->
                    _state.update { it.copy(
                        error = if (!networkMonitor.isOnline()) {
                            "You're offline. Showing cached data."
                        } else {
                            "Failed to refresh: ${error.message}"
                        },
                    ) }
                }
            
            _state.update { it.copy(isRefreshing = false) }
        }
    }
}

@Immutable
data class PostsUiState(
    val posts: ImmutableList<Post> = persistentListOf(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    val error: String? = null,
)
```

---

## 6. Verification Checklist

### Repository
- [ ] `Flow<List<T>>` exposed from DAO
- [ ] `suspend fun refresh()` fetches API → saves to DB
- [ ] Network responses never returned directly

### Database
- [ ] Entities have sync status field
- [ ] DAO has observe and pending queries
- [ ] Upsert used for server responses

### WorkManager
- [ ] Constraints require network
- [ ] Exponential backoff configured
- [ ] ExistingWorkPolicy.KEEP for deduplication

### Network Monitor
- [ ] Reactive `StateFlow<Boolean>`
- [ ] NetworkCallback registered
- [ ] Auto-refresh when online
