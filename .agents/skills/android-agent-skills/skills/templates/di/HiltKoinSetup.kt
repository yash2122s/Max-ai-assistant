package com.agentcore.di

// ============================================================
// LAST UPDATED: 2026-01-24
// Synced with: skills/guides/08-dependency-injection.md
// ============================================================

// ============================================================
// HILT SETUP EXAMPLE
// ============================================================
// Uncomment and adapt for your project when using Hilt
// 
// Best Practices:
// - Use @Binds instead of @Provides for interfaces (better performance)
// - Use custom @Qualifiers instead of @Named
// - Prefer constructor injection over field injection
// - Use singleOf/factoryOf DSL for cleaner syntax

/*
import com.agentcore.controller.AgentController
import com.agentcore.controller.AgentControllerImpl
import com.agentcore.memory.InMemoryStore
import com.agentcore.memory.MemoryStore
import com.agentcore.skill.registry.SkillRegistry
import com.agentcore.skill.registry.SkillRegistryImpl
import com.agentcore.skills.fallback.FallbackSkill
import com.agentcore.skills.intent.IntentDetectSkill
import com.agentcore.skills.logging.Logger
import com.agentcore.skills.logging.LoggingSkill
import com.agentcore.skills.memory.MemoryReadSkill
import com.agentcore.skills.memory.MemoryWriteSkill
import com.agentcore.skills.network.NetworkMonitor
import com.agentcore.skills.network.NetworkStatusCheckSkill
import com.agentcore.util.Clock
import com.agentcore.util.CoroutineDispatchers
import com.agentcore.util.DefaultCoroutineDispatchers
import com.agentcore.util.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

// ============================================================
// QUALIFIER ANNOTATIONS (Use custom qualifiers, not @Named)
// ============================================================

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

// ============================================================
// DISPATCHERS MODULE (Provides CoroutineDispatchers)
// ============================================================

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
    fun provideCoroutineDispatchers(): CoroutineDispatchers = DefaultCoroutineDispatchers()
    
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

// ============================================================
// AGENT CORE MODULE (Use @Binds for interfaces)
// ============================================================

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentCoreBindingsModule {
    
    // Use @Binds instead of @Provides for better performance
    // @Binds avoids reflection and generates less code
    
    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
    
    @Binds
    @Singleton
    abstract fun bindMemoryStore(impl: InMemoryStore): MemoryStore
    
    @Binds
    @Singleton
    abstract fun bindSkillRegistry(impl: SkillRegistryImpl): SkillRegistry
    
    @Binds
    @Singleton
    abstract fun bindAgentController(impl: AgentControllerImpl): AgentController
}

// ============================================================
// AGENT CORE PROVIDES MODULE (For complex construction)
// ============================================================

@Module
@InstallIn(SingletonComponent::class)
object AgentCoreProvidesModule {
    
    // Use @Provides only when:
    // 1. The class is not owned by you (3rd party)
    // 2. Complex construction logic is needed
    // 3. Multiple instances with different configurations
    
    @Provides
    @Singleton
    fun provideSkillRegistryImpl(
        logger: Logger,
        networkMonitor: NetworkMonitor,
    ): SkillRegistryImpl {
        return SkillRegistryImpl().apply {
            // Register core skills
            register(MemoryReadSkill())
            register(MemoryWriteSkill())
            register(IntentDetectSkill())
            register(FallbackSkill())
            register(NetworkStatusCheckSkill(networkMonitor))
            register(LoggingSkill(logger))
        }
    }
}

// ============================================================
// PLATFORM BINDINGS MODULE
// ============================================================

@Module
@InstallIn(SingletonComponent::class)
abstract class PlatformBindingsModule {
    
    // Platform-specific implementations must be provided by app module
    // NetworkMonitor requires Android Context
    // Logger may use Timber or other platform-specific implementation
    
    // Example:
    // @Binds
    // @Singleton
    // abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor
    
    // @Binds
    // @Singleton
    // abstract fun bindLogger(impl: TimberLogger): Logger
}
*/

// ============================================================
// KOIN SETUP EXAMPLE
// ============================================================
// Uncomment and adapt for your project when using Koin
//
// Best Practices:
// - Use singleOf/factoryOf/viewModelOf DSL for cleaner syntax
// - Use named() for qualifiers instead of string literals
// - Prefer constructor injection (let Koin resolve dependencies)
// - Use bind<Interface>() for interface implementations

/*
import com.agentcore.controller.AgentController
import com.agentcore.controller.AgentControllerImpl
import com.agentcore.memory.InMemoryStore
import com.agentcore.memory.MemoryStore
import com.agentcore.skill.registry.SkillRegistry
import com.agentcore.skill.registry.SkillRegistryImpl
import com.agentcore.skills.fallback.FallbackSkill
import com.agentcore.skills.intent.IntentDetectSkill
import com.agentcore.skills.logging.ConsoleLogger
import com.agentcore.skills.logging.Logger
import com.agentcore.skills.logging.LoggingSkill
import com.agentcore.skills.memory.MemoryReadSkill
import com.agentcore.skills.memory.MemoryWriteSkill
import com.agentcore.skills.network.NetworkMonitor
import com.agentcore.skills.network.NetworkStatusCheckSkill
import com.agentcore.skills.network.StubNetworkMonitor
import com.agentcore.util.Clock
import com.agentcore.util.CoroutineDispatchers
import com.agentcore.util.DefaultCoroutineDispatchers
import com.agentcore.util.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.core.logger.Level

// ============================================================
// KOIN QUALIFIER NAMES (Use named() for type-safe qualifiers)
// ============================================================
object KoinQualifiers {
    val IO_DISPATCHER = named("IoDispatcher")
    val DEFAULT_DISPATCHER = named("DefaultDispatcher")
    val MAIN_DISPATCHER = named("MainDispatcher")
    val MAIN_IMMEDIATE_DISPATCHER = named("MainImmediateDispatcher")
    val APPLICATION_SCOPE = named("ApplicationScope")
}

// ============================================================
// DISPATCHERS MODULE
// ============================================================
val dispatchersModule = module {
    
    // Use named qualifiers for dispatchers
    single(KoinQualifiers.IO_DISPATCHER) { Dispatchers.IO }
    single(KoinQualifiers.DEFAULT_DISPATCHER) { Dispatchers.Default }
    single(KoinQualifiers.MAIN_DISPATCHER) { Dispatchers.Main }
    single(KoinQualifiers.MAIN_IMMEDIATE_DISPATCHER) { Dispatchers.Main.immediate }

    // CoroutineDispatchers wrapper
    singleOf(::DefaultCoroutineDispatchers) { bind<CoroutineDispatchers>() }

    // Application-scoped CoroutineScope
    single(KoinQualifiers.APPLICATION_SCOPE) {
        CoroutineScope(SupervisorJob() + get<CoroutineDispatcher>(KoinQualifiers.DEFAULT_DISPATCHER))
    }
}

// ============================================================
// AGENT CORE MODULE (Use singleOf DSL for cleaner syntax)
// ============================================================
val agentCoreModule = module {
    
    // Core utilities (using singleOf for constructor injection)
    singleOf(::SystemClock) { bind<Clock>() }
    singleOf(::ConsoleLogger) { bind<Logger>() }
    
    // Memory store
    singleOf(::InMemoryStore) { bind<MemoryStore>() }
    
    // Network monitor (can be overridden by app module with platform implementation)
    singleOf(::StubNetworkMonitor) { bind<NetworkMonitor>() }
    
    // Skill registry with core skills
    single<SkillRegistry> {
        SkillRegistryImpl().apply {
            // Register core skills
            register(MemoryReadSkill())
            register(MemoryWriteSkill())
            register(IntentDetectSkill())
            register(FallbackSkill())
            register(NetworkStatusCheckSkill(get()))
            register(LoggingSkill(get()))
        }
    }
    
    // Agent controller (using singleOf with constructor injection)
    singleOf(::AgentControllerImpl) { bind<AgentController>() }
}

// ============================================================
// VIEWMODEL MODULE (Use viewModelOf for cleaner syntax)
// ============================================================
val viewModelModule = module {
    
    // Example ViewModels using viewModelOf DSL
    // viewModelOf(::ProfileViewModel)
    // viewModelOf(::HomeViewModel)
    // viewModelOf(::AgentDemoViewModel)
}

// ============================================================
// APPLICATION SETUP
// ============================================================

// In your Application class:
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            // Log Koin into Android logger
            androidLogger(Level.DEBUG)
            
            // Reference Android context
            androidContext(this@MyApplication)
            
            // Load modules
            modules(
                dispatchersModule,
                agentCoreModule,
                viewModelModule,
                // Add your app modules here
            )
        }
    }
}

// ============================================================
// USAGE IN COMPOSABLES
// ============================================================

// With Koin:
import org.koin.androidx.compose.koinViewModel

@Composable
fun MyRoute(
    viewModel: MyViewModel = koinViewModel()
) {
    // ...
}

// Inject dependencies in regular classes:
class MyRepository(
    private val api: MyApi,
    private val dispatcher: CoroutineDispatcher = get(KoinQualifiers.IO_DISPATCHER)
) {
    // ...
}
*/

// ============================================================
// TESTING SETUP
// ============================================================

/*
// For Hilt testing:
@HiltAndroidTest
class MyTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    @Test
    fun myTest() {
        // Test with Hilt injection
    }
}

// For Koin testing:
class MyTest : KoinTest {
    @get:Rule
    val koinTestRule = KoinTestRule.create {
        modules(testModule)
    }
    
    @Test
    fun myTest() {
        val dependency: MyDependency by inject()
        // Test with Koin injection
    }
}
*/

// ============================================================
// MIGRATION GUIDE: Hilt ↔ Koin
// ============================================================

/*
Hilt → Koin Migration:
1. @HiltViewModel → viewModelOf(::MyViewModel)
2. @Inject constructor → constructor injection (Koin resolves automatically)
3. @Binds → singleOf(::Impl) { bind<Interface>() }
4. @Provides → single { /* construction */ }
5. @Qualifier → named("qualifier")
6. hiltViewModel() → koinViewModel()

Koin → Hilt Migration:
1. viewModelOf(::MyViewModel) → @HiltViewModel class MyViewModel @Inject constructor()
2. singleOf(::Impl) { bind<Interface>() } → @Binds abstract fun bindInterface(impl: Impl): Interface
3. single { /* construction */ } → @Provides fun provideInstance(): Instance
4. named("qualifier") → @Qualifier annotation class CustomQualifier
5. koinViewModel() → hiltViewModel()
*/
// val viewModelKoinModule = module {
//     // Simple ViewModel injection
//     viewModelOf(::MainViewModel)
//     viewModelOf(::UserViewModel)
//
//     // ViewModel with parameters (SavedStateHandle is auto-injected)
//     viewModel { parameters ->
//         UserDetailViewModel(
//             userId = parameters.get(),
//             getUserUseCase = get(),
//         )
//     }
// }

// ============================================================
// KOIN APPLICATION SETUP
// ============================================================
// class MyKoinApplication : Application() {
//     override fun onCreate() {
//         super.onCreate()
//
//         startKoin {
//             // Logger for debug builds
//             if (BuildConfig.DEBUG) {
//                 androidLogger(Level.DEBUG)
//             }
//
//             // Android context
//             androidContext(this@MyKoinApplication)
//
//             // Modules
//             modules(
//                 dispatchersModule,
//                 agentCoreKoinModule,
//                 networkKoinModule,
//                 // repositoryKoinModule,
//                 // viewModelKoinModule,
//             )
//         }
//     }
// }

// ============================================================
// KOIN TESTING SETUP
// ============================================================
// @OptIn(ExperimentalCoroutinesApi::class)
// class UserRepositoryTest : KoinTest {
//
//     private val testDispatcher = StandardTestDispatcher()
//
//     @get:Rule
//     val koinTestRule = KoinTestRule.create {
//         modules(
//             module {
//                 single<CoroutineDispatcher>(named(KoinQualifiers.IO_DISPATCHER)) {
//                     testDispatcher
//                 }
//                 single<UserApiService> { mockk() }
//                 singleOf(::UserRepositoryImpl) { bind<UserRepository>() }
//             }
//         )
//     }
//
//     @get:Rule
//     val mockProvider = MockProviderRule.create { clazz ->
//         mockkClass(clazz)
//     }
//
//     private val repository: UserRepository by inject()
//     private val mockApi: UserApiService by inject()
//
//     @Test
//     fun `getUser returns success`() = runTest(testDispatcher) {
//         // Given
//         coEvery { mockApi.getUser("123") } returns UserDto(id = "123", name = "John")
//
//         // When
//         val result = repository.getUser("123")
//
//         // Then
//         assertTrue(result.isSuccess)
//     }
// }

// ============================================================
// KOIN COMPOSE INTEGRATION
// ============================================================
// @Composable
// fun UserScreen(
//     // Koin ViewModel injection in Compose
//     viewModel: UserViewModel = koinViewModel(),
// ) {
//     val uiState by viewModel.uiState.collectAsStateWithLifecycle()
//
//     UserContent(
//         uiState = uiState,
//         onAction = viewModel::onAction,
//     )
// }
//
// // For ViewModel with parameters
// @Composable
// fun UserDetailScreen(
//     userId: String,
//     viewModel: UserDetailViewModel = koinViewModel { parametersOf(userId) },
// ) {
//     // ...
// }
*/

/**
 * Documentation for DI setup.
 * 
 * This file contains commented examples for both Hilt and Koin setup.
 * 
 * To use with Hilt:
 * 1. Uncomment the Hilt section
 * 2. Add Hilt dependencies to build.gradle
 * 3. Provide platform-specific implementations (NetworkMonitor, Logger)
 * 4. Add @HiltAndroidApp to your Application class
 * 
 * To use with Koin:
 * 1. Uncomment the Koin section
 * 2. Add Koin dependencies to build.gradle
 * 3. Call startKoin in your Application class
 * 4. Optionally override platform-specific implementations in app module
 * 
 * For manual DI:
 * Use AgentCoreModule.createDefault() or AgentCoreModuleBuilder
 */
object DiSetupGuide {
    const val DOCUMENTATION = """
    Agent Core DI Setup Guide
    =========================
    
    1. MANUAL DI (Simplest, no framework needed)
    -------------------------------------------
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val module = AgentCoreModule.createDefault(scope)
    val controller = module.agentController
    
    2. HILT (Recommended for large apps)
    ------------------------------------
    - Uncomment the Hilt code in this file
    - Add dependencies: hilt-android, hilt-compiler
    - Provide NetworkMonitor binding in your app module
    
    3. KOIN (Simpler DI framework)
    ------------------------------
    - Uncomment the Koin code in this file
    - Add dependencies: koin-android, koin-androidx-compose
    - Call startKoin with agentCoreKoinModule
    """
}
