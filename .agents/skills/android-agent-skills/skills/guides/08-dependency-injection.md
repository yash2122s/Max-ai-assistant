---
name: Dependency Injection (Hilt & Koin)
description: Configuration and usage of DI frameworks for modular Android apps.
compliance_level: MANDATORY
tags: [di, hilt, koin, modules, testing]
version: 2.2.0
---

# Dependency Injection (Hilt & Koin)

## Context
Dependency Injection decouples components, improves testability, and manages scoping. We primarily use **Hilt** for Android, but **Koin** is supported for KMP projects.

**Related Guides:**
- [01-architecture.md](./01-architecture.md) - Architecture patterns
- [03-coroutines-concurrency.md](./03-coroutines-concurrency.md) - Dispatcher injection
- [11-testing.md](./11-testing.md) - Test modules

**Code Templates:**
- [KoinSkillExamples.kt](../templates/examples/KoinSkillExamples.kt) - Koin DI patterns for Skills
- [AgentCoreModule.kt](../templates/di/AgentCoreModule.kt) - Core Koin modules
- [HiltKoinSetup.kt](../templates/di/HiltKoinSetup.kt) - Hilt + Koin setup
- [Qualifiers.kt](../templates/di/Qualifiers.kt) - DI qualifiers

---

## 🎯 AI Quick Reference

```
HILT:
• @Binds for interfaces (no code gen overhead)
• @Provides for third-party or complex objects
• Custom @Qualifier instead of @Named
• @ViewModelScoped or no scope (avoid @Singleton)

KOIN:
• singleOf(::Impl) { bind<Interface>() }
• factoryOf(::Class) for new instances
• viewModelOf(::VM) for ViewModels
• named("qualifier") for qualifiers

INJECTION:
• Constructor injection ALWAYS
• Field injection ONLY for Android entry points
```

---

## 1. Hilt Configuration

### Module Organization
```kotlin
// ════════════════════════════════════════════════════════════
// QUALIFIERS - Custom annotations instead of @Named
// ════════════════════════════════════════════════════════════
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

// ════════════════════════════════════════════════════════════
// DISPATCHERS MODULE
// ════════════════════════════════════════════════════════════
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    
    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
    
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

// ════════════════════════════════════════════════════════════
// REPOSITORY MODULE - Use @Binds for interfaces
// ════════════════════════════════════════════════════════════
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    
    @Binds
    @Singleton
    abstract fun bindUserRepository(
        impl: UserRepositoryImpl,
    ): UserRepository
    
    @Binds
    @Singleton
    abstract fun bindProductRepository(
        impl: ProductRepositoryImpl,
    ): ProductRepository
    
    @Binds
    @Singleton
    abstract fun bindOrderRepository(
        impl: OrderRepositoryImpl,
    ): OrderRepository
}

// ════════════════════════════════════════════════════════════
// NETWORK MODULE - Use @Provides for third-party libs
// ════════════════════════════════════════════════════════════
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        })
        .build()
    
    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
    
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = 
        retrofit.create(UserApi::class.java)
    
    @Provides
    @Singleton
    fun provideProductApi(retrofit: Retrofit): ProductApi = 
        retrofit.create(ProductApi::class.java)
}

// ════════════════════════════════════════════════════════════
// DATABASE MODULE
// ════════════════════════════════════════════════════════════
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "app_database",
    )
        .fallbackToDestructiveMigration()
        .build()
    
    @Provides
    fun provideUserDao(database: AppDatabase): UserDao = 
        database.userDao()
    
    @Provides
    fun provideProductDao(database: AppDatabase): ProductDao = 
        database.productDao()
}

// ════════════════════════════════════════════════════════════
// USE CASE MODULE - No scope (new instance each time)
// ════════════════════════════════════════════════════════════
@Module
@InstallIn(ViewModelComponent::class)
abstract class UseCaseModule {
    
    // UseCases typically don't need @Singleton
    // New instance per ViewModel is fine
    @Binds
    abstract fun bindGetUserUseCase(
        impl: GetUserUseCaseImpl,
    ): GetUserUseCase
    
    @Binds
    abstract fun bindUpdateUserUseCase(
        impl: UpdateUserUseCaseImpl,
    ): UpdateUserUseCase
}
```

### ✅ DO: Proper Hilt Usage
```kotlin
// ════════════════════════════════════════════════════════════
// ViewModel with Constructor Injection
// ════════════════════════════════════════════════════════════
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle, // Hilt provides this automatically
) : ViewModel() {
    
    private val userId: String = savedStateHandle.get<String>("userId") ?: ""
    
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    
    init {
        if (userId.isNotBlank()) {
            loadProfile()
        }
    }
    
    private fun loadProfile() {
        viewModelScope.launch {
            getUserUseCase(userId)
                .onSuccess { user ->
                    _state.update { it.copy(user = user) }
                }
        }
    }
}

// ════════════════════════════════════════════════════════════
// Repository with Constructor Injection
// ════════════════════════════════════════════════════════════
class UserRepositoryImpl @Inject constructor(
    private val api: UserApi,
    private val dao: UserDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UserRepository {
    
    override suspend fun getUser(id: String): Result<User> = 
        withContext(ioDispatcher) {
            try {
                val response = api.getUser(id)
                dao.insert(response.toEntity())
                Result.success(response.toDomain())
            } catch (e: Exception) {
                val cached = dao.getUser(id)
                cached?.let { Result.success(it.toDomain()) }
                    ?: Result.failure(e.toDomainError())
            }
        }
}

// ════════════════════════════════════════════════════════════
// UseCase with Constructor Injection (no scope)
// ════════════════════════════════════════════════════════════
class GetUserUseCaseImpl @Inject constructor(
    private val userRepository: UserRepository,
) : GetUserUseCase {
    
    override suspend operator fun invoke(userId: String): Result<User> {
        if (userId.isBlank()) {
            return Result.failure(IllegalArgumentException("userId cannot be blank"))
        }
        return userRepository.getUser(userId)
    }
}
```

### ❌ DON'T: Hilt Anti-Patterns
```kotlin
// ❌ BAD: @Provides when @Binds works
@Module
@InstallIn(SingletonComponent::class)
object BadModule {
    @Provides
    @Singleton
    fun provideUserRepository(impl: UserRepositoryImpl): UserRepository = impl // ❌
}

// ✅ GOOD: Use @Binds
@Module
@InstallIn(SingletonComponent::class)
abstract class GoodModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository
}

// ❌ BAD: @Named instead of custom qualifier
class BadViewModel @Inject constructor(
    @Named("io") private val dispatcher: CoroutineDispatcher, // ❌
)

// ✅ GOOD: Custom qualifier
class GoodViewModel @Inject constructor(
    @IoDispatcher private val dispatcher: CoroutineDispatcher, // ✅
)

// ❌ BAD: Field injection in non-Android class
class BadRepository {
    @Inject lateinit var api: UserApi // ❌ Only for Android entry points
}

// ❌ BAD: Manual instantiation
class BadViewModel : ViewModel() {
    private val repository = UserRepositoryImpl() // ❌ Tight coupling
}

// ❌ BAD: Overusing @Singleton
@Module
@InstallIn(SingletonComponent::class)
abstract class BadModule {
    @Binds
    @Singleton // ❌ UseCases don't need to be singletons
    abstract fun bindGetUserUseCase(impl: GetUserUseCaseImpl): GetUserUseCase
}
```

---

## 2. Koin Configuration

### Module Organization
```kotlin
// ════════════════════════════════════════════════════════════
// DISPATCHERS MODULE
// ════════════════════════════════════════════════════════════
val dispatchersModule = module {
    single(named("IoDispatcher")) { Dispatchers.IO }
    single(named("DefaultDispatcher")) { Dispatchers.Default }
    single(named("MainDispatcher")) { Dispatchers.Main }
    
    single(named("ApplicationScope")) {
        CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(named("DefaultDispatcher")))
    }
}

// ════════════════════════════════════════════════════════════
// NETWORK MODULE
// ════════════════════════════════════════════════════════════
val networkModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    single {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
    
    single {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(get())
            .addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
            .build()
    }
    
    single { get<Retrofit>().create(UserApi::class.java) }
    single { get<Retrofit>().create(ProductApi::class.java) }
}

// ════════════════════════════════════════════════════════════
// DATABASE MODULE
// ════════════════════════════════════════════════════════════
val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "app_database",
        )
            .fallbackToDestructiveMigration()
            .build()
    }
    
    single { get<AppDatabase>().userDao() }
    single { get<AppDatabase>().productDao() }
}

// ════════════════════════════════════════════════════════════
// REPOSITORY MODULE - singleOf with bind
// ════════════════════════════════════════════════════════════
val repositoryModule = module {
    // Modern Koin DSL: singleOf + bind
    singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
    singleOf(::ProductRepositoryImpl) { bind<ProductRepository>() }
    singleOf(::OrderRepositoryImpl) { bind<OrderRepository>() }
}

// ════════════════════════════════════════════════════════════
// USE CASE MODULE - factoryOf (new instance each time)
// ════════════════════════════════════════════════════════════
val useCaseModule = module {
    factoryOf(::GetUserUseCaseImpl) { bind<GetUserUseCase>() }
    factoryOf(::UpdateUserUseCaseImpl) { bind<UpdateUserUseCase>() }
    factoryOf(::GetProductsUseCaseImpl) { bind<GetProductsUseCase>() }
}

// ════════════════════════════════════════════════════════════
// VIEWMODEL MODULE
// ════════════════════════════════════════════════════════════
val viewModelModule = module {
    viewModelOf(::ProfileViewModel)
    viewModelOf(::ProductListViewModel)
    viewModelOf(::CartViewModel)
    
    // With parameters
    viewModel { parameters ->
        OrderDetailViewModel(
            orderId = parameters.get(),
            getOrderUseCase = get(),
        )
    }
}

// ════════════════════════════════════════════════════════════
// APPLICATION SETUP
// ════════════════════════════════════════════════════════════
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.ERROR)
            androidContext(this@MyApplication)
            modules(
                dispatchersModule,
                networkModule,
                databaseModule,
                repositoryModule,
                useCaseModule,
                viewModelModule,
            )
        }
    }
}
```

### ✅ DO: Proper Koin Usage
```kotlin
// ════════════════════════════════════════════════════════════
// ViewModel with injected dependencies
// ════════════════════════════════════════════════════════════
class ProfileViewModel(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserUseCase,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
}

// ════════════════════════════════════════════════════════════
// Repository with named dispatcher
// ════════════════════════════════════════════════════════════
class UserRepositoryImpl(
    private val api: UserApi,
    private val dao: UserDao,
    private val ioDispatcher: CoroutineDispatcher,
) : UserRepository {
    // Implementation
}

// Module definition
val repositoryModule = module {
    singleOf(::UserRepositoryImpl) {
        bind<UserRepository>()
        // Explicitly pass named dispatcher
        // Parameters are resolved by type, so we need parametersOf for named
    }
    
    // Or with explicit parameters
    single<UserRepository> {
        UserRepositoryImpl(
            api = get(),
            dao = get(),
            ioDispatcher = get(named("IoDispatcher")),
        )
    }
}

// ════════════════════════════════════════════════════════════
// Compose integration
// ════════════════════════════════════════════════════════════
@Composable
fun ProfileRoute(
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    
    ProfileScreen(
        state = state,
        onSaveClick = viewModel::onSaveClick,
    )
}

// With parameters
@Composable
fun OrderDetailRoute(
    orderId: String,
    viewModel: OrderDetailViewModel = koinViewModel { parametersOf(orderId) },
) {
    // ...
}
```

### ❌ DON'T: Koin Anti-Patterns
```kotlin
// ❌ BAD: Using get() directly in composables
@Composable
fun BadScreen() {
    val viewModel = get<ProfileViewModel>() // ❌ Not lifecycle-aware
}

// ✅ GOOD: Use koinViewModel()
@Composable
fun GoodScreen() {
    val viewModel = koinViewModel<ProfileViewModel>() // ✅
}

// ❌ BAD: single for ViewModels
val badModule = module {
    single { ProfileViewModel(get(), get()) } // ❌ VM should be scoped to screen
}

// ✅ GOOD: viewModelOf
val goodModule = module {
    viewModelOf(::ProfileViewModel) // ✅
}

// ❌ BAD: Not binding interface
val badModule = module {
    single { UserRepositoryImpl(get(), get(), get()) } // ❌ No interface binding
}

// ✅ GOOD: Bind to interface
val goodModule = module {
    singleOf(::UserRepositoryImpl) { bind<UserRepository>() } // ✅
}
```

---

## 3. Scoping Guidelines

### Scope Comparison

| Scope | Hilt | Koin | Use Case |
|-------|------|------|----------|
| App lifetime | `@Singleton` | `single` | Database, OkHttp |
| ViewModel lifetime | `@ViewModelScoped` | `viewModel` | VM-specific caches |
| Activity lifetime | `@ActivityScoped` | `scope<Activity>` | Activity state |
| No scope | (default) | `factory` | UseCases, Mappers |

### ✅ DO: Appropriate Scoping
```kotlin
// ════════════════════════════════════════════════════════════
// HILT
// ════════════════════════════════════════════════════════════
@Module
@InstallIn(SingletonComponent::class)
object SingletonModule {
    // ✅ Singleton: Expensive to create, stateless, thread-safe
    @Provides
    @Singleton
    fun provideDatabase(...): AppDatabase
    
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient
}

@Module
@InstallIn(ViewModelComponent::class)
abstract class ViewModelModule {
    // ✅ No scope: UseCases are cheap, stateless
    @Binds
    abstract fun bindGetUserUseCase(impl: GetUserUseCaseImpl): GetUserUseCase
}

// ════════════════════════════════════════════════════════════
// KOIN
// ════════════════════════════════════════════════════════════
val appModule = module {
    // ✅ single: App-level singletons
    single { AppDatabase.create(androidContext()) }
    single { OkHttpClient.Builder().build() }
    
    // ✅ singleOf with bind: Repositories
    singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
    
    // ✅ factoryOf: UseCases (new instance each time)
    factoryOf(::GetUserUseCaseImpl) { bind<GetUserUseCase>() }
    
    // ✅ viewModelOf: ViewModels
    viewModelOf(::ProfileViewModel)
}
```

---

## 4. Testing with DI

### Hilt Test Setup
```kotlin
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ProfileViewModelTest {
    
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    @Inject
    lateinit var viewModel: ProfileViewModel
    
    @BindValue
    val mockRepository: UserRepository = mockk()
    
    @Before
    fun setup() {
        hiltRule.inject()
    }
    
    @Test
    fun `loadProfile should update state on success`() = runTest {
        // Given
        val user = User(id = "1", name = "John")
        coEvery { mockRepository.getUser("1") } returns Result.success(user)
        
        // When
        viewModel.loadProfile("1")
        
        // Then
        assertThat(viewModel.state.value.user).isEqualTo(user)
    }
}

// Test module replacement
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class],
)
abstract class TestRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: FakeUserRepository): UserRepository
}
```

### Koin Test Setup
```kotlin
class ProfileViewModelTest : KoinTest {
    
    private val mockRepository: UserRepository = mockk()
    
    @get:Rule
    val koinRule = KoinTestRule.create {
        modules(
            module {
                single<UserRepository> { mockRepository }
                factoryOf(::GetUserUseCaseImpl) { bind<GetUserUseCase>() }
                viewModelOf(::ProfileViewModel)
            }
        )
    }
    
    @Test
    fun `loadProfile should update state on success`() = runTest {
        // Given
        val viewModel: ProfileViewModel by inject()
        val user = User(id = "1", name = "John")
        coEvery { mockRepository.getUser("1") } returns Result.success(user)
        
        // When
        viewModel.loadProfile("1")
        
        // Then
        assertThat(viewModel.state.value.user).isEqualTo(user)
    }
}
```

---

## 5. Verification Checklist

### General
- [ ] Constructor injection used for all logic classes
- [ ] Field injection only for Android entry points
- [ ] No manual instantiation (`= SomeClass()`)

### Hilt
- [ ] `@Binds` used for interfaces
- [ ] `@Provides` only for third-party/complex objects
- [ ] Custom `@Qualifier` instead of `@Named`
- [ ] Appropriate scope (`@Singleton`, none, etc.)

### Koin
- [ ] `singleOf`/`factoryOf` with `bind<Interface>()`
- [ ] `viewModelOf` for ViewModels
- [ ] `koinViewModel()` in Composables
- [ ] Named qualifiers for dispatchers

### Testing
- [ ] Modules can be replaced in tests
- [ ] Mocks/Fakes properly injected
- [ ] No production dependencies in test code
