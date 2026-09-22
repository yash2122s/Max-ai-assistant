---
name: Compose Multiplatform (CMP)
description: Guidelines for sharing UI code across Android, iOS, Desktop, and Web using CMP.
compliance_level: RECOMMENDED
tags: [kmp, cmp, compose, multiplatform, shared-ui]
version: 2.2.0
---

# Compose Multiplatform

## Context
Compose Multiplatform enables UI sharing across Android, iOS, Desktop, and Web. This guide covers project structure, shared composables, and platform-specific implementations.

**Related Guides:**
- [15-kmp-readiness.md](./15-kmp-readiness.md) - KMP preparation
- [08-dependency-injection.md](./08-dependency-injection.md) - Koin for KMP

---

## 🎯 AI Quick Reference

```
PROJECT STRUCTURE:
composeApp/
  src/
    commonMain/   → Shared UI (Composables)
    androidMain/  → Android specifics
    iosMain/      → iOS specifics
    desktopMain/  → Desktop specifics

PATTERNS:
• expect/actual for platform APIs
• composeResources for shared resources
• Koin for DI (cross-platform)
• ViewModel alternatives for iOS

TESTING:
• commonTest/ for shared tests
• Preview in commonMain with @Preview
```

---

## 1. Project Structure

### ✅ DO: Standard CMP Structure
```
project/
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/
│       │   └── kotlin/
│       │       └── com/example/
│       │           ├── App.kt              # Root composable
│       │           ├── di/
│       │           │   └── AppModule.kt    # Koin modules
│       │           ├── ui/
│       │           │   ├── theme/
│       │           │   │   └── Theme.kt
│       │           │   └── screens/
│       │           │       └── HomeScreen.kt
│       │           └── data/
│       │               └── Repository.kt
│       │   └── composeResources/
│       │       ├── drawable/
│       │       ├── values/
│       │       │   └── strings.xml
│       │       └── font/
│       ├── androidMain/
│       │   └── kotlin/
│       │       └── com/example/
│       │           ├── MainActivity.kt
│       │           └── Platform.android.kt
│       ├── iosMain/
│       │   └── kotlin/
│       │       └── com/example/
│       │           └── Platform.ios.kt
│       └── desktopMain/
│           └── kotlin/
│               └── com/example/
│                   ├── Main.kt
│                   └── Platform.desktop.kt
├── shared/                                 # Optional: shared domain/data
│   └── src/
│       ├── commonMain/
│       └── androidMain/
└── build.gradle.kts
```

---

## 2. Shared Composables (commonMain)

### ✅ DO: Platform-Agnostic UI
```kotlin
// ════════════════════════════════════════════════════════════════
// commonMain/kotlin/com/example/App.kt
// ════════════════════════════════════════════════════════════════
@Composable
fun App() {
    MaterialTheme {
        val navigator = rememberNavigator()
        
        NavHost(
            navigator = navigator,
            initialRoute = "/home",
        ) {
            scene("/home") { HomeScreen(navigator) }
            scene("/detail/{id}") { DetailScreen(it.path("id")) }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// commonMain/kotlin/com/example/ui/screens/HomeScreen.kt
// ════════════════════════════════════════════════════════════════
@Composable
fun HomeScreen(
    navigator: Navigator,
    viewModel: HomeViewModel = koinViewModel(), // Koin DI
) {
    val state by viewModel.state.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Home") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            items(state.items, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    onClick = { navigator.navigate("/detail/${item.id}") },
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Reusable component
// ════════════════════════════════════════════════════════════════
@Composable
fun ItemCard(
    item: Item,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
```

---

## 3. expect/actual Pattern

### ✅ DO: Platform Abstractions
```kotlin
// ════════════════════════════════════════════════════════════════
// commonMain: expect declaration
// ════════════════════════════════════════════════════════════════
// Platform.kt
expect class Platform {
    val name: String
    val version: String
}

expect fun getPlatform(): Platform

// ════════════════════════════════════════════════════════════════
// androidMain: actual implementation
// ════════════════════════════════════════════════════════════════
// Platform.android.kt
actual class Platform {
    actual val name: String = "Android"
    actual val version: String = "${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = Platform()

// ════════════════════════════════════════════════════════════════
// iosMain: actual implementation
// ════════════════════════════════════════════════════════════════
// Platform.ios.kt
actual class Platform {
    actual val name: String = "iOS"
    actual val version: String = UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = Platform()

// ════════════════════════════════════════════════════════════════
// desktopMain: actual implementation
// ════════════════════════════════════════════════════════════════
// Platform.desktop.kt
actual class Platform {
    actual val name: String = "Desktop"
    actual val version: String = System.getProperty("os.version") ?: "Unknown"
}

actual fun getPlatform(): Platform = Platform()
```

### ✅ DO: Platform-Specific UI Components
```kotlin
// ════════════════════════════════════════════════════════════════
// commonMain: expect composable
// ════════════════════════════════════════════════════════════════
@Composable
expect fun VideoPlayer(
    url: String,
    modifier: Modifier = Modifier,
)

// ════════════════════════════════════════════════════════════════
// androidMain: ExoPlayer
// ════════════════════════════════════════════════════════════════
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = modifier,
    )
}

// ════════════════════════════════════════════════════════════════
// iosMain: AVPlayer
// ════════════════════════════════════════════════════════════════
@Composable
actual fun VideoPlayer(
    url: String,
    modifier: Modifier,
) {
    UIKitView(
        factory = {
            val player = AVPlayer(uRL = NSURL.URLWithString(url)!!)
            AVPlayerViewController().apply {
                this.player = player
            }.view
        },
        modifier = modifier,
    )
}
```

---

## 4. CMP Resources

### ✅ DO: Shared Resources
```kotlin
// ════════════════════════════════════════════════════════════════
// build.gradle.kts setup
// ════════════════════════════════════════════════════════════════
compose.resources {
    publicResClass = true
    packageOfResClass = "com.example.resources"
    generateResClass = always
}

// ════════════════════════════════════════════════════════════════
// composeResources/values/strings.xml
// ════════════════════════════════════════════════════════════════
<?xml version="1.0" encoding="UTF-8"?>
<resources>
    <string name="app_name">My App</string>
    <string name="hello">Hello, %s!</string>
    <string name="items_count">%d items</string>
</resources>

// ════════════════════════════════════════════════════════════════
// Usage in commonMain
// ════════════════════════════════════════════════════════════════
import com.example.resources.Res
import com.example.resources.app_name
import com.example.resources.hello
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun WelcomeScreen(userName: String) {
    Column {
        // String resource
        Text(text = stringResource(Res.string.app_name))
        
        // String with format args
        Text(text = stringResource(Res.string.hello, userName))
        
        // Drawable resource
        Image(
            painter = painterResource(Res.drawable.logo),
            contentDescription = "Logo",
        )
    }
}
```

---

## 5. Dependency Injection with Koin

### ✅ DO: Cross-Platform DI
```kotlin
// ════════════════════════════════════════════════════════════════
// commonMain: Shared modules
// ════════════════════════════════════════════════════════════════
val sharedModule = module {
    singleOf(::UserRepository)
    factoryOf(::GetUserUseCase)
    viewModelOf(::HomeViewModel)
    viewModelOf(::DetailViewModel)
}

// ════════════════════════════════════════════════════════════════
// commonMain: Platform-specific module (expect)
// ════════════════════════════════════════════════════════════════
expect val platformModule: Module

// ════════════════════════════════════════════════════════════════
// androidMain: Android-specific
// ════════════════════════════════════════════════════════════════
actual val platformModule = module {
    single<DatabaseDriver> { AndroidSqliteDriver(AppDatabase.Schema, get(), "app.db") }
    single { androidContext().getSharedPreferences("prefs", Context.MODE_PRIVATE) }
}

// ════════════════════════════════════════════════════════════════
// iosMain: iOS-specific
// ════════════════════════════════════════════════════════════════
actual val platformModule = module {
    single<DatabaseDriver> { NativeSqliteDriver(AppDatabase.Schema, "app.db") }
    single { NSUserDefaults.standardUserDefaults }
}

// ════════════════════════════════════════════════════════════════
// App initialization
// ════════════════════════════════════════════════════════════════
fun initKoin() {
    startKoin {
        modules(sharedModule, platformModule)
    }
}

// Android: Call in Application.onCreate()
// iOS: Call in AppDelegate or Swift main
```

---

## 6. ViewModel Pattern for CMP

### ✅ DO: Cross-Platform ViewModel
```kotlin
// ════════════════════════════════════════════════════════════════
// Using Koin's ViewModel (lifecycle-kmp-viewmodel)
// ════════════════════════════════════════════════════════════════
class HomeViewModel(
    private val getUsersUseCase: GetUsersUseCase,
) : ViewModel() {
    
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    
    init {
        loadUsers()
    }
    
    fun loadUsers() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            
            getUsersUseCase()
                .onSuccess { users ->
                    _state.update { it.copy(users = users, isLoading = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message, isLoading = false) }
                }
        }
    }
}

// ════════════════════════════════════════════════════════════════
// Usage in Composable
// ════════════════════════════════════════════════════════════════
@Composable
fun HomeScreen(viewModel: HomeViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    
    when {
        state.isLoading -> LoadingScreen()
        state.error != null -> ErrorScreen(state.error!!)
        else -> UserList(users = state.users)
    }
}
```

---

## 7. Platform Entry Points

### ✅ DO: Platform Setup
```kotlin
// ════════════════════════════════════════════════════════════════
// androidMain: MainActivity.kt
// ════════════════════════════════════════════════════════════════
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}

// Android Application
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin {
            androidContext(this@MyApplication)
        }
    }
}

// ════════════════════════════════════════════════════════════════
// iosMain: MainViewController.kt
// ════════════════════════════════════════════════════════════════
fun MainViewController() = ComposeUIViewController { App() }

// Swift AppDelegate
@main
struct iOSApp: App {
    init() {
        KoinHelperKt.doInitKoin()
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

// ════════════════════════════════════════════════════════════════
// desktopMain: Main.kt
// ════════════════════════════════════════════════════════════════
fun main() = application {
    initKoin()
    
    Window(
        onCloseRequest = ::exitApplication,
        title = "My App",
    ) {
        App()
    }
}
```

---

## 8. Build Configuration

### ✅ DO: CMP Gradle Setup
```kotlin
// build.gradle.kts (composeApp)
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget()
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    jvm("desktop")
    
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            
            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            
            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            
            // SQLDelight
            implementation(libs.sqldelight.runtime)
        }
        
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
```

---

## 9. Verification Checklist

### Project Structure
- [ ] commonMain for shared UI code
- [ ] Platform-specific modules (androidMain, iosMain, etc.)
- [ ] composeResources for shared assets

### expect/actual
- [ ] Platform abstraction with expect/actual
- [ ] Platform-specific implementations complete
- [ ] No platform imports in commonMain

### Resources
- [ ] Strings in composeResources/values/
- [ ] Images in composeResources/drawable/
- [ ] Using stringResource/painterResource

### DI
- [ ] Koin for cross-platform DI
- [ ] platformModule for platform specifics
- [ ] initKoin() called on all platforms

### Testing
- [ ] commonTest for shared tests
- [ ] Platform tests where needed
- [ ] Preview annotations working
