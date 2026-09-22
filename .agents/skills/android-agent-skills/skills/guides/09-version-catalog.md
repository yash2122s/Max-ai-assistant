---
name: Gradle Version Catalog
description: Standardized dependency management using libs.versions.toml.
compliance_level: MANDATORY
tags: [gradle, version-catalog, dependencies, build]
version: 2.2.0
---

# Gradle Version Catalog

## Context
Centralized dependency management reduces conflicts and simplifies updates. We use `libs.versions.toml` for all dependency declarations.

**Related Guides:**
- [17-build-configuration.md](./17-build-configuration.md) - Build setup
- [08-dependency-injection.md](./08-dependency-injection.md) - DI configuration

---

## 🎯 AI Quick Reference

```
STRUCTURE:
[versions] - Version numbers
[libraries] - Individual dependencies  
[bundles] - Grouped dependencies
[plugins] - Gradle plugins

USAGE:
libs.library.name           # Single library
libs.bundles.name           # Bundle
libs.plugins.name           # Plugin

NAMING:
kebab-case in TOML → dot notation in Kotlin
androidx-core-ktx → libs.androidx.core.ktx
```

---

## 1. Version Catalog Structure

### ✅ DO: Complete libs.versions.toml
```toml
# gradle/libs.versions.toml

# ════════════════════════════════════════════════════════════════
# [versions] - Single source for all version numbers
# ════════════════════════════════════════════════════════════════
[versions]
# SDK
android-compileSdk = "34"
android-minSdk = "24"
android-targetSdk = "34"

# Kotlin & Core
kotlin = "2.0.0"
ksp = "2.0.0-1.0.21"
coroutines = "1.8.1"

# AndroidX
androidx-core = "1.13.1"
androidx-lifecycle = "2.8.2"
androidx-activity = "1.9.0"
androidx-navigation = "2.8.0"

# Compose
compose-bom = "2024.06.00"
compose-compiler = "1.5.14"

# Networking
retrofit = "2.11.0"
okhttp = "4.12.0"
kotlinx-serialization = "1.6.3"

# Database
room = "2.6.1"

# DI
hilt = "2.51.1"
hilt-navigation-compose = "1.2.0"

# Testing
junit5 = "5.10.2"
mockk = "1.13.11"
turbine = "1.1.0"
robolectric = "4.12.2"

# ════════════════════════════════════════════════════════════════
# [libraries] - Artifact declarations
# ════════════════════════════════════════════════════════════════
[libraries]
# Kotlin
kotlin-stdlib = { group = "org.jetbrains.kotlin", name = "kotlin-stdlib", version.ref = "kotlin" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-collections-immutable = { group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable", version = "0.3.7" }

# AndroidX Core
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "androidx-core" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "androidx-activity" }

# Lifecycle
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "androidx-lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "androidx-lifecycle" }
androidx-lifecycle-viewmodel-ktx = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-ktx", version.ref = "androidx-lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "androidx-lifecycle" }

# Compose BOM
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }

# Navigation
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "androidx-navigation" }

# Room
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }

# Networking
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hilt-navigation-compose" }
hilt-android-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt" }

# Testing
junit5 = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit5" }
junit5-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version.ref = "junit5" }
junit5-engine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit5" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
mockk-android = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }

# ════════════════════════════════════════════════════════════════
# [bundles] - Grouped dependencies
# ════════════════════════════════════════════════════════════════
[bundles]
# Compose core
compose = [
    "compose-ui",
    "compose-ui-graphics",
    "compose-ui-tooling-preview",
    "compose-material3",
]

# Compose debug tools
compose-debug = [
    "compose-ui-tooling",
    "compose-ui-test-manifest",
]

# Lifecycle
lifecycle = [
    "androidx-lifecycle-runtime-ktx",
    "androidx-lifecycle-runtime-compose",
    "androidx-lifecycle-viewmodel-ktx",
    "androidx-lifecycle-viewmodel-compose",
]

# Coroutines
coroutines = [
    "kotlinx-coroutines-core",
    "kotlinx-coroutines-android",
]

# Networking
networking = [
    "retrofit",
    "retrofit-kotlinx-serialization",
    "okhttp",
    "okhttp-logging",
    "kotlinx-serialization-json",
]

# Room
room = [
    "room-runtime",
    "room-ktx",
]

# Unit testing
testing = [
    "junit5",
    "mockk",
    "kotlinx-coroutines-test",
    "turbine",
]

# ════════════════════════════════════════════════════════════════
# [plugins] - Gradle plugins
# ════════════════════════════════════════════════════════════════
[plugins]
android-application = { id = "com.android.application", version = "8.4.1" }
android-library = { id = "com.android.library", version = "8.4.1" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
room = { id = "androidx.room", version.ref = "room" }
```

---

## 2. Build Configuration

### ✅ DO: Root build.gradle.kts
```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
```

### ✅ DO: App build.gradle.kts
```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    
    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.collections.immutable)
    
    // Lifecycle
    implementation(libs.bundles.lifecycle)
    
    // Coroutines
    implementation(libs.bundles.coroutines)
    
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.bundles.compose.debug)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // Networking
    implementation(libs.bundles.networking)
    
    // Room
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)
    
    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    
    // Testing
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
}
```

### ❌ DON'T: Hardcoded Versions
```kotlin
// ❌ BAD: Hardcoded versions in build.gradle.kts
dependencies {
    implementation("androidx.core:core-ktx:1.13.1") // ❌
    implementation("com.squareup.retrofit2:retrofit:2.11.0") // ❌
}
```

---

## 3. Verification Checklist

### Structure
- [ ] All versions in `[versions]` section
- [ ] Libraries use `version.ref` references
- [ ] SDK versions stored as strings for `get().toInt()`

### Bundles
- [ ] Related libraries grouped (compose, networking, testing)
- [ ] Debug tools in separate bundle
- [ ] Test dependencies bundled together

### Plugins
- [ ] All plugins declared in `[plugins]`
- [ ] Root build.gradle.kts uses `alias()` with `apply false`
- [ ] Module build.gradle.kts uses `alias()` directly

### Build Files
- [ ] No hardcoded version strings
- [ ] BOM used for Compose dependencies
- [ ] KSP used instead of KAPT for Room/Hilt
