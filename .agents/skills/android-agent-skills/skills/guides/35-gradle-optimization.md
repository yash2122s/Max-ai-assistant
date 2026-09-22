---
name: Gradle & Build Optimization
description: Build performance optimization, convention plugins, dependency management, and CI/CD build strategies for Android projects.
compliance_level: RECOMMENDED
tags: [gradle, build, optimization, convention-plugins, kts, performance]
version: 1.0.0
---

# Gradle & Build Optimization

## Context

Build time directly impacts developer productivity. This guide covers:

1. **Build performance** optimization techniques
2. **Convention plugins** for consistent configuration
3. **Dependency management** with version catalogs
4. **Build cache** and incremental builds
5. **CI/CD** build strategies

**Related Guides:**
- [09-version-catalog.md](./09-version-catalog.md) - Version catalog basics
- [17-build-configuration.md](./17-build-configuration.md) - Build variants, ProGuard
- [18-ci-cd.md](./18-ci-cd.md) - CI/CD pipelines

---

## 🎯 AI Quick Reference

```
BUILD OPTIMIZATION PRIORITIES:
1. Enable build cache (local + remote)
2. Use convention plugins (DRY config)
3. Minimize dynamic dependencies
4. Configure parallel execution
5. Use configuration cache
6. Optimize test execution

GRADLE BEST PRACTICES:
• Use Kotlin DSL (.kts) for type safety
• Version catalog for dependencies
• Convention plugins for shared config
• Avoid buildSrc for large projects
• Enable file-system watching
• Configure JVM memory properly

COMMON BOTTLENECKS:
• Annotation processors (kapt → KSP)
• Dynamic version resolution
• Large buildSrc changes
• Missing build cache
• Sync-heavy plugins
• Excessive task creation

NEVER:
• Use + or latest.release versions
• Put logic in buildSrc that changes often
• Disable incremental compilation
• Skip configuration cache opportunities
• Use deprecated APIs
```

---

## 1. Gradle Configuration Basics

### 1.1 gradle.properties Optimization

```properties
# gradle.properties - Optimized settings

# ============================================
# JVM & Memory Configuration
# ============================================
# Allocate sufficient heap for builds
org.gradle.jvmargs=-Xmx4g -XX:+HeapDumpOnOutOfMemoryError -XX:+UseParallelGC -Dfile.encoding=UTF-8

# ============================================
# Build Performance
# ============================================
# Enable parallel project execution
org.gradle.parallel=true

# Enable build caching
org.gradle.caching=true

# Enable configuration cache (Gradle 8.1+)
org.gradle.configuration-cache=true
org.gradle.configuration-cache.problems=warn

# Enable file-system watching for faster incremental builds
org.gradle.vfs.watch=true

# Kotlin incremental compilation
kotlin.incremental=true

# Use K2 compiler (Kotlin 2.0+)
kotlin.experimental.tryK2=true

# ============================================
# Android Specific
# ============================================
# Enable non-transitive R classes
android.nonTransitiveRClass=true

# Use AndroidX
android.useAndroidX=true

# Enable build features only when needed (reduces APK analyzer overhead)
android.defaults.buildfeatures.buildconfig=false
android.defaults.buildfeatures.aidl=false
android.defaults.buildfeatures.renderscript=false

# ============================================
# Kapt / KSP
# ============================================
# Use KSP instead of kapt when possible
# kapt.incremental.apt=true (if still using kapt)

# ============================================
# CI-Specific (set via command line or CI env)
# ============================================
# org.gradle.daemon=false  # Disable daemon on CI
# org.gradle.workers.max=4 # Limit workers on CI
```

### 1.2 settings.gradle.kts

```kotlin
// settings.gradle.kts
pluginManagement {
    // Include build-logic for convention plugins
    includeBuild("build-logic")
    
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Enable type-safe project accessors
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Root project name
rootProject.name = "MyApp"

// Include modules
include(":app")
include(":core:common")
include(":core:network")
include(":core:database")
include(":core:ui")
include(":feature:home")
include(":feature:profile")
include(":feature:settings")
```

---

## 2. Convention Plugins

### 2.1 Project Structure

```
project-root/
├── build-logic/
│   ├── convention/
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/
│   │       ├── AndroidApplicationConventionPlugin.kt
│   │       ├── AndroidLibraryConventionPlugin.kt
│   │       ├── AndroidComposeConventionPlugin.kt
│   │       ├── AndroidHiltConventionPlugin.kt
│   │       ├── AndroidTestConventionPlugin.kt
│   │       ├── KotlinLibraryConventionPlugin.kt
│   │       └── extensions/
│   │           └── ProjectExtensions.kt
│   └── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
├── app/
│   └── build.gradle.kts
└── settings.gradle.kts
```

### 2.2 Build-Logic Setup

```kotlin
// build-logic/settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
```

```kotlin
// build-logic/convention/build.gradle.kts
plugins {
    `kotlin-dsl`
}

group = "com.example.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "myapp.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "myapp.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "myapp.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "myapp.android.hilt"
            implementationClass = "AndroidHiltConventionPlugin"
        }
        register("androidTest") {
            id = "myapp.android.test"
            implementationClass = "AndroidTestConventionPlugin"
        }
        register("kotlinLibrary") {
            id = "myapp.kotlin.library"
            implementationClass = "KotlinLibraryConventionPlugin"
        }
    }
}
```

### 2.3 Android Application Plugin

```kotlin
// build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }
            
            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
                
                defaultConfig {
                    targetSdk = libs.findVersion("targetSdk").get().toString().toInt()
                    
                    // Vector drawable support
                    vectorDrawables {
                        useSupportLibrary = true
                    }
                }
                
                buildTypes {
                    release {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                    debug {
                        isMinifyEnabled = false
                        applicationIdSuffix = ".debug"
                        versionNameSuffix = "-debug"
                    }
                }
                
                // Packaging options
                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                        excludes += "/META-INF/versions/9/previous-compilation-data.bin"
                    }
                }
            }
        }
    }
}
```

### 2.4 Android Library Plugin

```kotlin
// build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt
import com.android.build.gradle.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }
            
            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                
                defaultConfig {
                    consumerProguardFiles("consumer-rules.pro")
                }
                
                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }
            }
            
            // Common library dependencies
            dependencies {
                add("implementation", libs.findLibrary("timber").get())
            }
        }
    }
}
```

### 2.5 Compose Convention Plugin

```kotlin
// build-logic/convention/src/main/kotlin/AndroidComposeConventionPlugin.kt
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Apply Compose compiler plugin (Kotlin 2.0+)
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            
            val extension = extensions.findByType(CommonExtension::class.java)
                ?: error("Android extension not found. Apply Android plugin first.")
            
            extension.apply {
                buildFeatures {
                    compose = true
                }
            }
            
            // Configure Compose compiler
            extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
                // Enable strong skipping mode for better performance
                enableStrongSkippingMode.set(true)
                
                // Generate stability reports for debugging
                if (project.findProperty("composeCompilerReports") == "true") {
                    val reportsDir = layout.buildDirectory.dir("compose_compiler")
                    reportsDestination.set(reportsDir)
                    metricsDestination.set(reportsDir)
                }
            }
            
            dependencies {
                val bom = libs.findLibrary("compose-bom").get()
                add("implementation", platform(bom))
                add("androidTestImplementation", platform(bom))
                
                add("implementation", libs.findLibrary("compose-ui").get())
                add("implementation", libs.findLibrary("compose-ui-graphics").get())
                add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
                add("implementation", libs.findLibrary("compose-material3").get())
                
                add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
                add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())
            }
        }
    }
}
```

### 2.6 Hilt Convention Plugin

```kotlin
// build-logic/convention/src/main/kotlin/AndroidHiltConventionPlugin.kt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("dagger.hilt.android.plugin")
            }
            
            dependencies {
                add("implementation", libs.findLibrary("hilt-android").get())
                add("ksp", libs.findLibrary("hilt-compiler").get())
                
                // Hilt + Compose integration
                add("implementation", libs.findLibrary("hilt-navigation-compose").get())
            }
        }
    }
}
```

### 2.7 Common Kotlin Configuration

```kotlin
// build-logic/convention/src/main/kotlin/extensions/ProjectExtensions.kt
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// Extension to access version catalog
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Configure common Kotlin/Android settings
internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension<*, *, *, *, *, *>,
) {
    commonExtension.apply {
        compileSdk = libs.findVersion("compileSdk").get().toString().toInt()
        
        defaultConfig {
            minSdk = libs.findVersion("minSdk").get().toString().toInt()
        }
        
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
    
    // Configure Kotlin
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            
            // Treat all Kotlin warnings as errors (for CI)
            allWarningsAsErrors.set(
                project.providers.gradleProperty("warningsAsErrors")
                    .map { it.toBoolean() }
                    .getOrElse(false)
            )
            
            // Enable explicit API mode for libraries
            if (project.path.contains(":core:") || project.path.contains(":domain:")) {
                explicitApi()
            }
            
            // Compiler arguments
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
            )
        }
    }
    
    // Common dependencies
    dependencies {
        add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    }
}
```

### 2.8 Usage in Module build.gradle.kts

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.myapp.android.application)
    alias(libs.plugins.myapp.android.compose)
    alias(libs.plugins.myapp.android.hilt)
}

android {
    namespace = "com.example.myapp"
    
    defaultConfig {
        applicationId = "com.example.myapp"
        versionCode = 1
        versionName = "1.0.0"
    }
}

dependencies {
    // Type-safe project dependencies
    implementation(projects.core.common)
    implementation(projects.core.network)
    implementation(projects.core.ui)
    implementation(projects.feature.home)
    implementation(projects.feature.profile)
}
```

```kotlin
// feature/home/build.gradle.kts
plugins {
    alias(libs.plugins.myapp.android.library)
    alias(libs.plugins.myapp.android.compose)
    alias(libs.plugins.myapp.android.hilt)
}

android {
    namespace = "com.example.feature.home"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ui)
}
```

---

## 3. Version Catalog Best Practices

### 3.1 Organized Version Catalog

```toml
# gradle/libs.versions.toml

[versions]
# SDK Versions
compileSdk = "35"
minSdk = "24"
targetSdk = "35"

# Kotlin & Core
kotlin = "2.0.21"
kotlinxCoroutines = "1.9.0"
kotlinxSerialization = "1.7.3"
ksp = "2.0.21-1.0.27"

# Android
androidGradlePlugin = "8.7.2"
androidxCore = "1.15.0"
androidxLifecycle = "2.8.7"
androidxActivity = "1.9.3"
androidxNavigation = "2.8.4"

# Compose
composeBom = "2024.12.01"
composeCompiler = "1.5.15"  # Only if not using Kotlin 2.0 compose plugin

# DI
hilt = "2.53"
hiltNavigationCompose = "1.2.0"

# Network
retrofit = "2.11.0"
okhttp = "4.12.0"

# Database
room = "2.6.1"

# Testing
junit = "4.13.2"
junit5 = "5.11.3"
mockk = "1.13.13"
turbine = "1.2.0"

# Utilities
timber = "5.0.1"

[libraries]
# Kotlin
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }

# AndroidX Core
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-ktx = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "androidxLifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "androidxLifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "androidxLifecycle" }

# Compose BOM
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-material3 = { module = "androidx.compose.material3:material3" }

# Navigation
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "androidxNavigation" }

# Hilt
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version.ref = "hiltNavigationCompose" }
hilt-android-testing = { module = "com.google.dagger:hilt-android-testing", version.ref = "hilt" }

# Network
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }

# Room
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }

# Testing
junit = { module = "junit:junit", version.ref = "junit" }
junit5-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit5" }
junit5-engine = { module = "org.junit.jupiter:junit-jupiter-engine", version.ref = "junit5" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
mockk-android = { module = "io.mockk:mockk-android", version.ref = "mockk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

# Utilities
timber = { module = "com.jakewharton.timber:timber", version.ref = "timber" }

# Gradle Plugins (for build-logic)
android-gradlePlugin = { module = "com.android.tools.build:gradle", version.ref = "androidGradlePlugin" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
compose-gradlePlugin = { module = "org.jetbrains.kotlin:compose-compiler-gradle-plugin", version.ref = "kotlin" }
ksp-gradlePlugin = { module = "com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin", version.ref = "ksp" }

[bundles]
# Commonly used together
compose = ["compose-ui", "compose-ui-graphics", "compose-ui-tooling-preview", "compose-material3"]
compose-debug = ["compose-ui-tooling", "compose-ui-test-manifest"]
lifecycle = ["androidx-lifecycle-runtime-ktx", "androidx-lifecycle-viewmodel-ktx", "androidx-lifecycle-viewmodel-compose"]
room = ["room-runtime", "room-ktx"]
retrofit = ["retrofit", "retrofit-kotlinx-serialization", "okhttp", "okhttp-logging"]
testing = ["junit", "mockk", "turbine", "kotlinx-coroutines-test"]

[plugins]
# Android & Kotlin (external)
android-application = { id = "com.android.application", version.ref = "androidGradlePlugin" }
android-library = { id = "com.android.library", version.ref = "androidGradlePlugin" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }

# Convention plugins (local)
myapp-android-application = { id = "myapp.android.application", version = "unspecified" }
myapp-android-library = { id = "myapp.android.library", version = "unspecified" }
myapp-android-compose = { id = "myapp.android.compose", version = "unspecified" }
myapp-android-hilt = { id = "myapp.android.hilt", version = "unspecified" }
myapp-android-test = { id = "myapp.android.test", version = "unspecified" }
myapp-kotlin-library = { id = "myapp.kotlin.library", version = "unspecified" }
```

---

## 4. Build Performance Optimization

### 4.1 Kapt → KSP Migration

```kotlin
// BEFORE: Kapt (slower)
plugins {
    kotlin("kapt")
}

dependencies {
    kapt(libs.room.compiler)
    kapt(libs.hilt.compiler)
}

// AFTER: KSP (faster)
plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    ksp(libs.room.compiler)
    ksp(libs.hilt.compiler)
}
```

### 4.2 Build Cache Configuration

```kotlin
// settings.gradle.kts
buildCache {
    local {
        directory = File(rootDir, ".gradle/build-cache")
        removeUnusedEntriesAfterDays = 30
    }
    
    // Remote cache for CI (optional)
    remote<HttpBuildCache> {
        url = uri(System.getenv("GRADLE_CACHE_URL") ?: "")
        isPush = System.getenv("CI") == "true"
        credentials {
            username = System.getenv("GRADLE_CACHE_USER") ?: ""
            password = System.getenv("GRADLE_CACHE_PASSWORD") ?: ""
        }
    }
}
```

### 4.3 Parallel & Workers Configuration

```properties
# gradle.properties

# Maximum workers (default = number of CPU cores)
org.gradle.workers.max=8

# Parallel execution
org.gradle.parallel=true

# JVM max heap (adjust based on project size)
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError
```

### 4.4 Configuration on Demand (Careful!)

```properties
# Only enable if you understand the implications
# Can cause issues with multi-module projects
# org.gradle.configureondemand=true
```

### 4.5 Analyzing Build Performance

```bash
# Generate build scan (requires Gradle Enterprise or Develocity)
./gradlew build --scan

# Profile build locally
./gradlew build --profile
# Results in: build/reports/profile/

# Analyze configuration time
./gradlew --dry-run assembleDebug

# Check task inputs/outputs for cacheability
./gradlew :app:assembleDebug --rerun-tasks -Dorg.gradle.caching.debug=true
```

### 4.6 Task Configuration Avoidance

```kotlin
// BAD: Eager configuration
tasks.register("myTask") {
    // This block runs during configuration
}

// GOOD: Lazy configuration
tasks.register("myTask") {
    doLast {
        // This only runs during execution
    }
}

// BETTER: Full lazy evaluation
tasks.register<Copy>("copyFiles") {
    from(layout.projectDirectory.dir("src"))
    into(layout.buildDirectory.dir("output"))
}
```

---

## 5. Module Structure Best Practices

### 5.1 Recommended Module Graph

```
:app (Android Application)
├── :feature:home (Android Library)
├── :feature:profile (Android Library)
├── :feature:settings (Android Library)
│
├── :core:ui (Android Library - Compose components)
├── :core:network (Android Library - Retrofit, OkHttp)
├── :core:database (Android Library - Room)
├── :core:common (Android Library - Utilities)
│
├── :domain:user (Kotlin Library - Pure)
├── :domain:product (Kotlin Library - Pure)
│
└── :data:user (Android Library - Impl)
└── :data:product (Android Library - Impl)
```

### 5.2 Module Dependency Rules

```kotlin
// Module dependencies follow Clean Architecture
// UI → Domain ← Data

// :feature:home/build.gradle.kts
dependencies {
    // ✅ Feature depends on domain (interfaces)
    implementation(projects.domain.user)
    
    // ✅ Feature depends on core utilities
    implementation(projects.core.ui)
    implementation(projects.core.common)
    
    // ❌ Feature NEVER depends on data layer directly
    // implementation(projects.data.user) // WRONG!
}

// :data:user/build.gradle.kts
dependencies {
    // ✅ Data implements domain interfaces
    implementation(projects.domain.user)
    
    // ✅ Data uses core for networking/database
    implementation(projects.core.network)
    implementation(projects.core.database)
}

// :domain:user/build.gradle.kts (Pure Kotlin)
plugins {
    alias(libs.plugins.myapp.kotlin.library)
}

dependencies {
    // ✅ Domain has minimal dependencies
    implementation(libs.kotlinx.coroutines.core)
    
    // ❌ Domain NEVER depends on Android
    // implementation(libs.androidx.core.ktx) // WRONG!
}
```

### 5.3 api vs implementation

```kotlin
dependencies {
    // ✅ Use 'implementation' by default (encapsulation)
    implementation(libs.retrofit)
    
    // ✅ Use 'api' only when exposing in public API
    api(projects.domain.user)  // If feature exposes domain types
    
    // ❌ Avoid 'api' for internal dependencies
    // api(libs.okhttp) // Leaks dependency to consumers
}
```

---

## 6. CI/CD Build Optimization

### 6.1 CI-Specific Gradle Properties

```bash
# CI build command
./gradlew assembleRelease \
    --no-daemon \
    --parallel \
    --max-workers=4 \
    --build-cache \
    -Dorg.gradle.caching=true \
    -Dorg.gradle.configuration-cache=true
```

### 6.2 GitHub Actions with Caching

```yaml
# .github/workflows/build.yml
name: Build

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
      
      - name: Build with Gradle
        run: ./gradlew build --no-daemon --parallel
      
      - name: Run tests
        run: ./gradlew test --no-daemon --parallel
```

### 6.3 Module-Based Test Splitting

```yaml
# Parallel test execution by module
jobs:
  test:
    strategy:
      matrix:
        module: [":app", ":feature:home", ":feature:profile", ":core:network"]
    
    steps:
      - uses: actions/checkout@v4
      - name: Test ${{ matrix.module }}
        run: ./gradlew ${{ matrix.module }}:test
```

---

## 7. Common Issues & Solutions

### 7.1 Slow Configuration Phase

```kotlin
// Problem: Heavy work in configuration
dependencies {
    // ❌ File operations during configuration
    implementation(files(file("libs").listFiles()?.toList() ?: emptyList()))
}

// Solution: Use lazy evaluation
dependencies {
    // ✅ Defer to execution
    implementation(fileTree("libs") { include("*.jar") })
}
```

### 7.2 Build Scan Analysis

```
Common bottlenecks in build scans:
1. Configuration time > 10s → Too many plugins, buildSrc changes
2. Task dependency resolution → Check circular dependencies  
3. Cache miss rate > 20% → Check task outputs
4. Network requests during build → Dependency resolution issues
```

### 7.3 Memory Issues

```properties
# For large projects (50+ modules)
org.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=2g -XX:+UseParallelGC

# Enable garbage collector logging for debugging
# org.gradle.jvmargs=-Xmx8g -Xlog:gc*:file=gc.log
```

---

## 8. Verification Checklist

- [ ] Using Kotlin DSL (.kts) for build scripts
- [ ] Version catalog (libs.versions.toml) configured
- [ ] Convention plugins for shared configuration
- [ ] KSP instead of kapt where possible
- [ ] Build cache enabled (local + remote for CI)
- [ ] Configuration cache enabled
- [ ] Parallel execution enabled
- [ ] Module graph follows Clean Architecture
- [ ] No dynamic dependency versions
- [ ] CI uses Gradle caching actions
- [ ] Build scans analyzed for bottlenecks

---

## Related Resources

- [09-version-catalog.md](./09-version-catalog.md) - Version catalog details
- [17-build-configuration.md](./17-build-configuration.md) - Build variants
- [18-ci-cd.md](./18-ci-cd.md) - CI/CD pipelines
- [Gradle Performance Guide](https://docs.gradle.org/current/userguide/performance.html)

---

**Created**: January 2026  
**Maintained By**: AI Agent / TrongLB  
**Version**: 1.0.0
