---
name: Build Configuration
description: build.gradle.kts and proguard setup for production apps.
compliance_level: MANDATORY
tags: [build-config, gradle, flavors, proguard, r8]
version: 2.2.0
---

# Build Configuration

## Context
Proper build configuration ensures security, performance (minification), and correct environment handling for different deployment targets.

**Related Guides:**
- [09-version-catalog.md](./09-version-catalog.md) - Dependency management
- [12-security.md](./12-security.md) - Security configuration
- [18-ci-cd.md](./18-ci-cd.md) - CI/CD integration

---

## 🎯 AI Quick Reference

```
BUILD TYPES:
debug    → debuggable, no minify, logging enabled
release  → minify, shrink, no logging

FLAVORS:
dev      → staging API, debug tools
prod     → production API, analytics

SIGNING:
• Credentials in local.properties or CI env
• NEVER commit keystores or passwords

R8:
• -assumenosideeffects for log stripping
• Keep rules for serialization
• Optimize for size
```

---

## 1. Build Types

### ✅ DO: Proper Build Type Configuration
```kotlin
// app/build.gradle.kts
android {
    namespace = "com.example.myapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    
    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Room schema export for migrations
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
    
    buildTypes {
        // ════════════════════════════════════════════════════════
        // Debug build
        // ════════════════════════════════════════════════════════
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            
            // Debug-specific BuildConfig fields
            buildConfigField("String", "API_BASE_URL", "\"https://api-staging.example.com\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "true")
        }
        
        // ════════════════════════════════════════════════════════
        // Release build
        // ════════════════════════════════════════════════════════
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.com\"")
            buildConfigField("Boolean", "ENABLE_LOGGING", "false")
            
            // Signing (configured below)
            signingConfig = signingConfigs.getByName("release")
        }
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}
```

---

## 2. Product Flavors

### ✅ DO: Environment-based Flavors
```kotlin
android {
    flavorDimensions += "environment"
    
    productFlavors {
        // ════════════════════════════════════════════════════════
        // Development flavor
        // ════════════════════════════════════════════════════════
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            
            buildConfigField("String", "API_BASE_URL", "\"https://api-dev.example.com\"")
            buildConfigField("Boolean", "SHOW_DEBUG_TOOLS", "true")
            
            // Different app name for dev
            resValue("string", "app_name", "MyApp Dev")
        }
        
        // ════════════════════════════════════════════════════════
        // Staging flavor
        // ════════════════════════════════════════════════════════
        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            
            buildConfigField("String", "API_BASE_URL", "\"https://api-staging.example.com\"")
            buildConfigField("Boolean", "SHOW_DEBUG_TOOLS", "true")
            
            resValue("string", "app_name", "MyApp Staging")
        }
        
        // ════════════════════════════════════════════════════════
        // Production flavor
        // ════════════════════════════════════════════════════════
        create("prod") {
            dimension = "environment"
            // No suffix for production
            
            buildConfigField("String", "API_BASE_URL", "\"https://api.example.com\"")
            buildConfigField("Boolean", "SHOW_DEBUG_TOOLS", "false")
            
            resValue("string", "app_name", "MyApp")
        }
    }
}
```

---

## 3. Signing Configuration

### ✅ DO: Externalized Signing
```kotlin
// app/build.gradle.kts
import java.util.Properties

// Load from local.properties (not committed)
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        load(file.inputStream())
    }
}

android {
    signingConfigs {
        // ════════════════════════════════════════════════════════
        // Release signing config
        // ════════════════════════════════════════════════════════
        create("release") {
            // Try local.properties first, then environment variables (CI)
            storeFile = file(
                localProperties.getProperty("RELEASE_STORE_FILE")
                    ?: System.getenv("RELEASE_STORE_FILE")
                    ?: "keystore/release.keystore"
            )
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("RELEASE_STORE_PASSWORD")
                ?: ""
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("RELEASE_KEY_ALIAS")
                ?: ""
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("RELEASE_KEY_PASSWORD")
                ?: ""
        }
    }
    
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### local.properties (NOT committed)
```properties
# local.properties (add to .gitignore)
RELEASE_STORE_FILE=/path/to/release.keystore
RELEASE_STORE_PASSWORD=your-store-password
RELEASE_KEY_ALIAS=your-key-alias
RELEASE_KEY_PASSWORD=your-key-password
```

---

## 4. ProGuard / R8 Rules

### ✅ DO: Comprehensive ProGuard Rules
```proguard
# proguard-rules.pro

# ════════════════════════════════════════════════════════════════
# Log stripping
# ════════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
}

# ════════════════════════════════════════════════════════════════
# Kotlinx Serialization
# ════════════════════════════════════════════════════════════════
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}

-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep @Serializable annotated classes
-keep @kotlinx.serialization.Serializable class * {
    static **$Companion Companion;
}

# ════════════════════════════════════════════════════════════════
# Navigation (Type-safe routes)
# ════════════════════════════════════════════════════════════════
-keep @kotlinx.serialization.Serializable class * { *; }

# ════════════════════════════════════════════════════════════════
# Retrofit / OkHttp
# ════════════════════════════════════════════════════════════════
-keepattributes Signature
-keepattributes Exceptions

-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

-dontwarn okhttp3.**
-dontwarn okio.**

# ════════════════════════════════════════════════════════════════
# Room
# ════════════════════════════════════════════════════════════════
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ════════════════════════════════════════════════════════════════
# Hilt
# ════════════════════════════════════════════════════════════════
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory

# ════════════════════════════════════════════════════════════════
# Compose
# ════════════════════════════════════════════════════════════════
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Compose @Stable and @Immutable classes
-keep @androidx.compose.runtime.Stable class *
-keep @androidx.compose.runtime.Immutable class *

# ════════════════════════════════════════════════════════════════
# Debugging: Uncomment to get mapping file
# ════════════════════════════════════════════════════════════════
# -printmapping build/outputs/mapping/release/mapping.txt
```

---

## 5. Gradle Optimizations

### ✅ DO: gradle.properties Optimizations
```properties
# gradle.properties

# ════════════════════════════════════════════════════════════════
# Memory settings
# ════════════════════════════════════════════════════════════════
org.gradle.jvmargs=-Xmx4096m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8

# ════════════════════════════════════════════════════════════════
# Build performance
# ════════════════════════════════════════════════════════════════
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true

# ════════════════════════════════════════════════════════════════
# Kotlin
# ════════════════════════════════════════════════════════════════
kotlin.code.style=official
kotlin.incremental=true

# ════════════════════════════════════════════════════════════════
# Android
# ════════════════════════════════════════════════════════════════
android.useAndroidX=true
android.nonTransitiveRClass=true
android.defaults.buildfeatures.buildconfig=true
```

---

## 6. Verification Checklist

### Build Types
- [ ] Debug: `isDebuggable = true`, `isMinifyEnabled = false`
- [ ] Release: `isDebuggable = false`, `isMinifyEnabled = true`
- [ ] `isShrinkResources = true` for release
- [ ] BuildConfig fields for API URLs

### Signing
- [ ] Credentials in local.properties (gitignored)
- [ ] CI/CD uses environment variables
- [ ] NO keystores in version control

### ProGuard
- [ ] Log stripping rules
- [ ] Kotlinx Serialization keep rules
- [ ] Navigation routes kept
- [ ] Room/Hilt rules included

### Gradle
- [ ] Parallel builds enabled
- [ ] Build caching enabled
- [ ] Proper JVM memory allocation
