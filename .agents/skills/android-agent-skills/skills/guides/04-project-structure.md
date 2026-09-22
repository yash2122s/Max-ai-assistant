---
name: Project Structure (Package-by-Feature)
description: Standardized directory layout for scalable Android apps.
compliance_level: MANDATORY
tags: [structure, package-by-feature, modularization]
version: 2.2.0
---

# Project Structure

## Context
A clear project structure reduces cognitive load. We use **Package-by-Feature** to keep related code together, with **Multi-Module** support for larger projects.

**Related Guides:**
- [01-architecture.md](./01-architecture.md) - Clean Architecture layers
- [17-build-configuration.md](./17-build-configuration.md) - Build setup

---

## 🎯 AI Quick Reference

```
SINGLE MODULE:
com.example.app/
├── App.kt                  # Application
├── MainActivity.kt         # Entry point
├── core/                   # Shared utilities
│   ├── network/            # API setup
│   ├── database/           # Room setup
│   └── ui/                 # Theme, Components
└── feature/                # Features by domain
    ├── auth/               # Login/Register
    ├── home/               # Main screen
    └── profile/            # User profile

MULTI-MODULE:
:app                        # Main app module
:core:network               # Networking
:core:database              # Database
:core:ui                    # Design system
:feature:auth               # Auth feature
:feature:home               # Home feature
```

---

## 1. Single-Module Structure

### ✅ DO: Package-by-Feature
```text
com.example.myapp/
├── App.kt                      # Application class (@HiltAndroidApp)
├── MainActivity.kt             # Single Activity
├── AppNavigation.kt            # NavHost configuration
│
├── core/                       # Shared across features
│   ├── network/
│   │   ├── ApiClient.kt        # Retrofit setup
│   │   ├── AuthInterceptor.kt  # Token handling
│   │   └── NetworkModule.kt    # Hilt module
│   │
│   ├── database/
│   │   ├── AppDatabase.kt      # Room database
│   │   ├── DatabaseModule.kt   # Hilt module
│   │   └── converters/         # Type converters
│   │
│   ├── ui/                     # Design system
│   │   ├── theme/
│   │   │   ├── Color.kt
│   │   │   ├── Theme.kt
│   │   │   └── Type.kt
│   │   └── components/         # Reusable composables
│   │       ├── LoadingIndicator.kt
│   │       ├── ErrorMessage.kt
│   │       └── PrimaryButton.kt
│   │
│   └── common/                 # Utilities
│       ├── Result.kt           # Result wrapper
│       ├── Extensions.kt       # Common extensions
│       └── Constants.kt
│
└── feature/                    # Domain features
    ├── auth/
    │   ├── presentation/       # UI layer
    │   │   ├── login/
    │   │   │   ├── LoginRoute.kt
    │   │   │   ├── LoginScreen.kt
    │   │   │   ├── LoginViewModel.kt
    │   │   │   └── LoginUiState.kt
    │   │   └── register/
    │   │       ├── RegisterRoute.kt
    │   │       └── ...
    │   ├── domain/             # Business logic
    │   │   ├── model/
    │   │   │   └── User.kt
    │   │   ├── repository/
    │   │   │   └── AuthRepository.kt  # Interface
    │   │   └── usecase/
    │   │       ├── LoginUseCase.kt
    │   │       └── ValidateEmailUseCase.kt
    │   └── data/               # Data layer
    │       ├── remote/
    │       │   ├── AuthApi.kt
    │       │   └── dto/
    │       │       └── LoginRequestDto.kt
    │       ├── local/
    │       │   └── AuthDao.kt
    │       └── repository/
    │           └── AuthRepositoryImpl.kt
    │
    ├── home/
    │   ├── presentation/
    │   ├── domain/
    │   └── data/
    │
    └── profile/
        ├── presentation/
        ├── domain/
        └── data/
```

### ❌ DON'T: Package-by-Layer (Legacy)
```text
// ❌ BAD: Hard to navigate, scattered feature code
com.example.myapp/
├── data/
│   ├── AuthRepositoryImpl.kt
│   ├── HomeRepositoryImpl.kt
│   └── ProfileRepositoryImpl.kt
├── domain/
│   ├── AuthRepository.kt
│   └── LoginUseCase.kt
└── presentation/
    ├── LoginScreen.kt
    └── HomeScreen.kt
```

---

## 2. Multi-Module Structure

### ✅ DO: Module-per-Feature
```text
project/
├── app/                        # Application module
│   ├── src/main/kotlin/.../
│   │   ├── App.kt
│   │   ├── MainActivity.kt
│   │   └── AppNavigation.kt
│   └── build.gradle.kts
│
├── core/
│   ├── network/                # :core:network
│   │   ├── src/main/kotlin/
│   │   │   ├── ApiClient.kt
│   │   │   ├── NetworkModule.kt
│   │   │   └── interceptors/
│   │   └── build.gradle.kts
│   │
│   ├── database/               # :core:database
│   │   ├── src/main/kotlin/
│   │   │   ├── AppDatabase.kt
│   │   │   └── DatabaseModule.kt
│   │   └── build.gradle.kts
│   │
│   ├── ui/                     # :core:ui (design system)
│   │   ├── src/main/kotlin/
│   │   │   ├── theme/
│   │   │   └── components/
│   │   └── build.gradle.kts
│   │
│   ├── common/                 # :core:common
│   │   ├── src/main/kotlin/
│   │   │   ├── Result.kt
│   │   │   └── Extensions.kt
│   │   └── build.gradle.kts
│   │
│   └── testing/                # :core:testing
│       ├── src/main/kotlin/
│       │   ├── FakeRepository.kt
│       │   └── TestDispatcherRule.kt
│       └── build.gradle.kts
│
└── feature/
    ├── auth/                   # :feature:auth
    │   ├── src/main/kotlin/
    │   │   ├── presentation/
    │   │   ├── domain/
    │   │   └── data/
    │   └── build.gradle.kts
    │
    └── home/                   # :feature:home
        ├── src/main/kotlin/
        └── build.gradle.kts
```

### Module Dependencies
```kotlin
// feature/auth/build.gradle.kts
dependencies {
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(project(":core:common"))
    
    testImplementation(project(":core:testing"))
}

// app/build.gradle.kts
dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:profile"))
}
```

---

## 3. File Naming Conventions

### Presentation Layer
| Type | Pattern | Example |
|------|---------|---------|
| Route | `{Feature}Route.kt` | `LoginRoute.kt` |
| Screen | `{Feature}Screen.kt` | `LoginScreen.kt` |
| ViewModel | `{Feature}ViewModel.kt` | `LoginViewModel.kt` |
| UiState | `{Feature}UiState.kt` | `LoginUiState.kt` |
| Event | `{Feature}Event.kt` | `LoginEvent.kt` |

### Domain Layer
| Type | Pattern | Example |
|------|---------|---------|
| Entity | `{Name}.kt` | `User.kt` |
| Repository (Interface) | `{Name}Repository.kt` | `AuthRepository.kt` |
| UseCase | `{Action}{Entity}UseCase.kt` | `LoginUserUseCase.kt` |

### Data Layer
| Type | Pattern | Example |
|------|---------|---------|
| Repository (Impl) | `{Name}RepositoryImpl.kt` | `AuthRepositoryImpl.kt` |
| API Service | `{Name}Api.kt` | `AuthApi.kt` |
| DTO | `{Name}Dto.kt` | `LoginRequestDto.kt` |
| DAO | `{Name}Dao.kt` | `UserDao.kt` |
| Entity (DB) | `{Name}Entity.kt` | `UserEntity.kt` |

---

## 4. Navigation File Organization

### ✅ DO: Centralized Routes
```kotlin
// com/example/app/navigation/Routes.kt
package com.example.app.navigation

import kotlinx.serialization.Serializable

// ════════════════════════════════════════════════════════════════
// Graph markers
// ════════════════════════════════════════════════════════════════
@Serializable object AuthGraph
@Serializable object MainGraph

// ════════════════════════════════════════════════════════════════
// Auth routes
// ════════════════════════════════════════════════════════════════
@Serializable object Login
@Serializable object Register
@Serializable data class ForgotPassword(val email: String? = null)

// ════════════════════════════════════════════════════════════════
// Main routes
// ════════════════════════════════════════════════════════════════
@Serializable object Home
@Serializable data class Profile(val userId: String)
@Serializable data class ProductDetail(val productId: String)
@Serializable object Settings

// com/example/app/navigation/AppNavigation.kt
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    isLoggedIn: Boolean,
) {
    NavHost(
        navController = navController,
        startDestination = if (isLoggedIn) MainGraph else AuthGraph,
    ) {
        authNavGraph(navController)
        mainNavGraph(navController)
    }
}

// Separate extension functions for each graph
private fun NavGraphBuilder.authNavGraph(navController: NavController) {
    navigation<AuthGraph>(startDestination = Login) {
        composable<Login> { /* ... */ }
        composable<Register> { /* ... */ }
    }
}

private fun NavGraphBuilder.mainNavGraph(navController: NavController) {
    navigation<MainGraph>(startDestination = Home) {
        composable<Home> { /* ... */ }
        composable<Profile> { /* ... */ }
    }
}
```

---

## 5. Verification Checklist

### Structure
- [ ] Features have dedicated packages/modules
- [ ] Shared code in `core/` directory
- [ ] No circular dependencies between features
- [ ] Clear separation: presentation/domain/data

### Naming
- [ ] Consistent file naming conventions
- [ ] Route/Screen/ViewModel pattern followed
- [ ] DTO suffix for network models

### Navigation
- [ ] Routes centralized in one file
- [ ] NavGraphBuilder extensions for graphs
- [ ] Type-safe routes with @Serializable

### Multi-Module (if applicable)
- [ ] Feature modules don't depend on each other
- [ ] `:core:testing` module for test utilities
- [ ] `:core:ui` for design system
