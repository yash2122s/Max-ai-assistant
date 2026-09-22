---
name: Kotlin Multiplatform (KMP) Readiness
description: Guidelines for ensuring code is ready for KMP migration.
compliance_level: RECOMMENDED
tags: [kmp, multiplatform, architecture, clean-architecture]
version: 2.2.0
---

# Kotlin Multiplatform (KMP) Readiness

## Context
Future-proofing for iOS/Desktop support requires separating pure Kotlin logic from Android dependencies. This guide ensures your codebase can migrate to KMP with minimal effort.

**Related Guides:**
- [01-architecture.md](./01-architecture.md) - Clean Architecture
- [16-compose-multiplatform.md](./16-compose-multiplatform.md) - Compose Multiplatform

---

## 🎯 AI Quick Reference

```
KMP-READY:
• Domain layer: Pure Kotlin only
• kotlinx-datetime instead of java.time
• kotlinx-coroutines (Flow, suspend)
• Koin for DI (or expect/actual for Hilt)
• Ktor/SQLDelight for networking/database

AVOID:
• java.* imports in shared code
• android.* imports in domain
• Platform-specific singletons
• Context in business logic
```

---

## 1. Layer Guidelines

### KMP Compatibility Matrix

| Layer | KMP Ready? | Allowed Dependencies |
|-------|-----------|---------------------|
| Domain (models, use cases) | ✅ MUST | kotlinx-* only |
| Data (repository interfaces) | ✅ MUST | kotlinx-*, expect/actual |
| Data (implementations) | ⚠️ Platform-specific | Ktor, SQLDelight, or expect/actual |
| Presentation (ViewModel) | ⚠️ Partial | Compose Multiplatform ViewModels |
| UI (Compose) | ⚠️ Partial | Compose Multiplatform |

### ✅ DO: Pure Kotlin Domain
```kotlin
// ════════════════════════════════════════════════════════════════
// Domain models - Pure Kotlin, KMP ready
// ════════════════════════════════════════════════════════════════
package com.example.domain.model

import kotlinx.datetime.Instant
import kotlinx.datetime.Clock

// ✅ All types are from kotlinx-*
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Product(
    val id: String,
    val name: String,
    val price: Double,
    val currency: String,
)

// ════════════════════════════════════════════════════════════════
// Repository interface - Pure Kotlin
// ════════════════════════════════════════════════════════════════
package com.example.domain.repository

import com.example.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun observeUser(id: String): Flow<User?>
    suspend fun getUser(id: String): Result<User>
    suspend fun updateUser(user: User): Result<Unit>
}

// ════════════════════════════════════════════════════════════════
// Use Case - Pure Kotlin
// ════════════════════════════════════════════════════════════════
package com.example.domain.usecase

class GetUserProfileUseCase(
    private val userRepository: UserRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(userId: String): Result<UserProfile> {
        return userRepository.getUser(userId).map { user ->
            UserProfile(
                user = user,
                memberSince = calculateMemberDuration(user.createdAt),
            )
        }
    }
    
    private fun calculateMemberDuration(createdAt: Instant): String {
        val now = clock.now()
        val days = (now - createdAt).inWholeDays
        return when {
            days < 30 -> "New member"
            days < 365 -> "${days / 30} months"
            else -> "${days / 365} years"
        }
    }
}
```

### ❌ DON'T: Java/Android Dependencies in Domain
```kotlin
// ❌ BAD: Java imports in domain
package com.example.domain.model

import java.util.Date          // ❌ Java
import java.util.UUID          // ❌ Java (use expect/actual instead)
import android.os.Parcelable   // ❌ Android

data class BadUser(
    val id: UUID,              // ❌ java.util.UUID
    val createdAt: Date,       // ❌ java.util.Date
) : Parcelable                 // ❌ Android Parcelable
```

---

## 2. Date/Time Handling

### ✅ DO: kotlinx-datetime
```kotlin
// ════════════════════════════════════════════════════════════════
// Common date/time utilities (KMP ready)
// ════════════════════════════════════════════════════════════════
package com.example.common.time

import kotlinx.datetime.*

object DateTimeUtils {
    
    fun now(): Instant = Clock.System.now()
    
    fun today(): LocalDate = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
    
    fun formatRelative(instant: Instant): String {
        val now = Clock.System.now()
        val duration = now - instant
        
        return when {
            duration.inWholeMinutes < 1 -> "Just now"
            duration.inWholeHours < 1 -> "${duration.inWholeMinutes}m ago"
            duration.inWholeDays < 1 -> "${duration.inWholeHours}h ago"
            duration.inWholeDays < 7 -> "${duration.inWholeDays}d ago"
            else -> {
                val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
                "${localDate.dayOfMonth}/${localDate.monthNumber}/${localDate.year}"
            }
        }
    }
    
    fun parseIso(isoString: String): Instant {
        return Instant.parse(isoString)
    }
    
    fun toIso(instant: Instant): String {
        return instant.toString()
    }
}

// ════════════════════════════════════════════════════════════════
// Usage in data layer
// ════════════════════════════════════════════════════════════════
@Serializable
data class UserDto(
    val id: String,
    val email: String,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
)

// Custom serializer for kotlinx.datetime.Instant
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = Instant.parse(decoder.decodeString())
}
```

### ❌ DON'T: java.time in shared code
```kotlin
// ❌ BAD: java.time (not KMP compatible)
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun formatDate(instant: java.time.Instant): String {
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy") // ❌ Java
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}
```

---

## 3. Dependency Injection

### ✅ DO: Koin (KMP Native)
```kotlin
// ════════════════════════════════════════════════════════════════
// Shared Koin module (works on all platforms)
// ════════════════════════════════════════════════════════════════
// commonMain/kotlin/com/example/di/SharedModule.kt

val sharedModule = module {
    // Domain
    factoryOf(::GetUserProfileUseCase)
    factoryOf(::LoginUseCase)
    factoryOf(::ValidateEmailUseCase)
    
    // Repository interfaces bound in platform-specific modules
}

// ════════════════════════════════════════════════════════════════
// Android-specific module
// ════════════════════════════════════════════════════════════════
// androidMain/kotlin/com/example/di/AndroidModule.kt

val androidModule = module {
    // Android-specific implementations
    single<UserRepository> { UserRepositoryImpl(get(), get()) }
    single { Room.databaseBuilder(...).build() }
    single { createRetrofit() }
}

// ════════════════════════════════════════════════════════════════
// iOS-specific module
// ════════════════════════════════════════════════════════════════
// iosMain/kotlin/com/example/di/IosModule.kt

val iosModule = module {
    // iOS-specific implementations
    single<UserRepository> { UserRepositoryImpl(get()) }
    single { createKtorClient() }
}
```

### expect/actual Pattern for Hilt Migration
```kotlin
// ════════════════════════════════════════════════════════════════
// Common expect declaration
// ════════════════════════════════════════════════════════════════
// commonMain/kotlin/com/example/di/Inject.kt

@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.RUNTIME)
expect annotation class Inject()

// ════════════════════════════════════════════════════════════════
// Android actual (maps to javax.inject.Inject)
// ════════════════════════════════════════════════════════════════
// androidMain/kotlin/com/example/di/Inject.kt

actual typealias Inject = javax.inject.Inject

// ════════════════════════════════════════════════════════════════
// iOS actual (no-op or Koin-based)
// ════════════════════════════════════════════════════════════════
// iosMain/kotlin/com/example/di/Inject.kt

@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR)
@Retention(AnnotationRetention.RUNTIME)
actual annotation class Inject()
```

---

## 4. Networking

### ✅ DO: Ktor (KMP Native)
```kotlin
// ════════════════════════════════════════════════════════════════
// Shared HttpClient configuration
// ════════════════════════════════════════════════════════════════
// commonMain/kotlin/com/example/network/HttpClientFactory.kt

expect fun createPlatformEngine(): HttpClientEngine

fun createHttpClient(): HttpClient {
    return HttpClient(createPlatformEngine()) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
        install(Logging) {
            level = LogLevel.BODY
        }
        defaultRequest {
            url("https://api.example.com")
            contentType(ContentType.Application.Json)
        }
    }
}

// ════════════════════════════════════════════════════════════════
// API Service
// ════════════════════════════════════════════════════════════════
class UserApiService(private val client: HttpClient) {
    
    suspend fun getUser(id: String): UserDto {
        return client.get("/users/$id").body()
    }
    
    suspend fun updateUser(user: UserDto): UserDto {
        return client.put("/users/${user.id}") {
            setBody(user)
        }.body()
    }
}

// ════════════════════════════════════════════════════════════════
// Platform-specific engines
// ════════════════════════════════════════════════════════════════
// androidMain
actual fun createPlatformEngine(): HttpClientEngine = OkHttp.create()

// iosMain
actual fun createPlatformEngine(): HttpClientEngine = Darwin.create()
```

---

## 5. Database

### ✅ DO: SQLDelight (KMP Native)
```sql
-- commonMain/sqldelight/com/example/db/User.sq

CREATE TABLE user (
    id TEXT NOT NULL PRIMARY KEY,
    email TEXT NOT NULL,
    display_name TEXT,
    created_at INTEGER NOT NULL
);

selectById:
SELECT * FROM user WHERE id = ?;

selectAll:
SELECT * FROM user ORDER BY display_name;

insert:
INSERT OR REPLACE INTO user(id, email, display_name, created_at)
VALUES (?, ?, ?, ?);

deleteById:
DELETE FROM user WHERE id = ?;
```

```kotlin
// Repository implementation
class UserRepositoryImpl(
    private val database: AppDatabase,
    private val api: UserApiService,
) : UserRepository {
    
    override fun observeUser(id: String): Flow<User?> {
        return database.userQueries.selectById(id)
            .asFlow()
            .mapToOneOrNull()
            .map { it?.toDomain() }
    }
}
```

---

## 6. Verification Checklist

### Domain Layer
- [ ] No `java.*` imports
- [ ] No `android.*` imports
- [ ] Using `kotlinx-datetime`
- [ ] Using `kotlinx-coroutines` Flow

### Data Layer
- [ ] Repository interfaces in common code
- [ ] Platform-specific implementations use expect/actual
- [ ] DTOs use kotlinx-serialization

### Dependencies
- [ ] Ktor instead of Retrofit (or abstracted)
- [ ] SQLDelight instead of Room (or abstracted)
- [ ] Koin or expect/actual for DI

### Testing
- [ ] Domain tests are platform-agnostic
- [ ] Use `runTest` from kotlinx-coroutines-test
