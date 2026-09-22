# Error Handling Cheat Sheet
> **Quick Reference**: Error patterns for Android/Kotlin and Agent Skills
> **Version**: 2.2.0

---

## 🎯 Quick Decision

```
What layer am I in?
    │
    ├─► Domain (UseCase)
    │   └─► Return Result<T>
    │       └─► Never throw, use Result.failure()
    │
    ├─► Skill (BaseSkill)
    │   └─► Return SkillResult<T>
    │       └─► Never throw, use SkillResult.failure()
    │
    └─► Data (Repository)
        └─► Return Result<T>
            └─► Map exceptions to DomainError
```

---

## 1. Result<T> Pattern (Kotlin stdlib)

```kotlin
// Create Result
val success = Result.success(data)
val failure = Result.failure(exception)

// Handle Result
result.onSuccess { data -> /* use data */ }
      .onFailure { error -> /* handle error */ }

// Transform
result.map { data -> transform(data) }
result.getOrDefault(defaultValue)
result.getOrElse { error -> fallback }
result.getOrThrow()  // ⚠️ Can throw!

// Combine
result.andThen { nextOperation(it) }  // Custom extension
```

---

## 2. SkillResult<T> Pattern (Agent Skills)

```kotlin
// Create
SkillResult.success(output)
SkillResult.failure(error)
TypedSkillError.Network("message", cause).toFailure()

// Handle
result.onSuccess { output -> /* use */ }
      .onFailure { error -> /* handle */ }

// Transform
result.map { transform(it) }
result.andThen { nextSkill(it) }
result.recover { error -> fallbackValue }

// Combine
result1.zip(result2) { a, b -> Combined(a, b) }
```

---

## 3. Exception → Error Type Mapping

### SkillError Types
| Exception | TypedSkillError | Code |
|-----------|-----------------|------|
| `IOException` | `Network` | `TypedSkillError.Network(msg, cause)` |
| `SocketTimeoutException` | `Timeout` | `TypedSkillError.Timeout(msg, duration, cause)` |
| `UnknownHostException` | `Network` | `TypedSkillError.Network(msg, cause)` |
| `SSLException` | `Network` | `TypedSkillError.Network.sslError()` |
| `HttpException(401)` | `Unauthorized` | `TypedSkillError.Unauthorized.invalidCredentials()` |
| `HttpException(403)` | `Unauthorized` | `TypedSkillError.Unauthorized(msg)` |
| `HttpException(404)` | `NotFound` | `TypedSkillError.NotFound.resource(type, id)` |
| `HttpException(409)` | `Conflict` | `TypedSkillError.Conflict(msg)` |
| `HttpException(429)` | `RateLimited` | `TypedSkillError.RateLimited.limitExceeded(limit, period)` |
| `HttpException(5xx)` | `Execution` | `TypedSkillError.Execution(msg, cause)` |
| `JsonParseException` | `Serialization` | `TypedSkillError.Serialization(msg, cause)` |
| `SQLiteException` | `Database` | `TypedSkillError.Database(msg, cause)` |
| `IllegalArgumentException` | `InvalidInput` | `TypedSkillError.InvalidInput(field, msg)` |
| `CancellationException` | **RETHROW** | `throw e` ← NEVER CATCH! |

### DomainError Types
| Exception | DomainError | Usage |
|-----------|-------------|-------|
| `UnknownHostException` | `DomainError.Network.NoConnection` | No network |
| `SocketTimeoutException` | `DomainError.Network.Timeout` | Timeout |
| `HttpException(401)` | `DomainError.Server.Unauthorized` | Auth required |
| `HttpException(403)` | `DomainError.Server.Forbidden` | Access denied |
| `HttpException(404)` | `DomainError.Server.NotFound` | Not found |
| `HttpException(5xx)` | `DomainError.Server.InternalError` | Server error |

---

## 4. Correct Error Handling Pattern

### In Skills (doExecute)
```kotlin
override suspend fun doExecute(
    input: I,
    context: SkillContext,
): SkillResult<O> {
    checkCancellation()
    
    return try {
        val result = doWork(input)
        checkCancellation()
        SkillResult.success(result)
    } catch (e: CancellationException) {
        throw e  // ⚠️ ALWAYS RETHROW
    } catch (e: IOException) {
        TypedSkillError.Network(
            message = "Network failed: ${e.message}",
            cause = e
        ).toFailure()
    } catch (e: HttpException) {
        when (e.code()) {
            401, 403 -> TypedSkillError.Unauthorized.invalidCredentials().toFailure()
            404 -> TypedSkillError.NotFound.resource("Resource", "id").toFailure()
            429 -> TypedSkillError.RateLimited.limitExceeded(100, "minute").toFailure()
            else -> TypedSkillError.Execution("HTTP ${e.code()}", e).toFailure()
        }
    } catch (e: Exception) {
        TypedSkillError.Execution(
            message = "Unexpected: ${e.message}",
            cause = e
        ).toFailure()
    }
}
```

### In Repository
```kotlin
override suspend fun getData(id: String): Result<Data> = withContext(dispatcher) {
    try {
        val dto = api.getData(id)
        dao.insert(dto.toEntity())
        Result.success(dto.toDomain())
    } catch (e: CancellationException) {
        throw e  // ⚠️ ALWAYS RETHROW
    } catch (e: Exception) {
        // Fallback to cache
        dao.getData(id)?.let { Result.success(it.toDomain()) }
            ?: Result.failure(e.toDomainError())
    }
}
```

### In UseCase
```kotlin
suspend operator fun invoke(input: Input): Result<Output> {
    // 1. Validate first
    if (input.value.isBlank()) {
        return Result.failure(ValidationError("value", "cannot be blank"))
    }
    
    // 2. Execute
    return repository.getData(input.id)
        .map { data -> transform(data) }
}
```

---

## 5. ❌ Anti-Patterns

```kotlin
// ❌ WRONG: Swallowing CancellationException
try {
    doWork()
} catch (e: Exception) {  // Catches CancellationException!
    handleError(e)
}

// ❌ WRONG: Throwing from skill
override suspend fun doExecute(...): SkillResult<O> {
    throw RuntimeException("Error")  // NEVER throw!
}

// ❌ WRONG: Using getOrThrow() without handling
val data = result.getOrThrow()  // Can crash!

// ❌ WRONG: Ignoring errors
repository.save(data)  // Result ignored!

// ❌ WRONG: Generic error messages
SkillResult.failure(SkillError.Execution("Error"))  // Too vague!
```

---

## 6. ✅ Quick Helpers

```kotlin
// Extension for safe try-catch
inline fun <T> safeCall(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

// Extension for Result logging
fun <T> Result<T>.logOnFailure(tag: String): Result<T> = onFailure {
    Timber.tag(tag).e(it, "Operation failed")
}

// Extension for SkillResult to Result
fun <T> SkillResult<T>.toResult(): Result<T> = when (this) {
    is SkillResult.Success -> Result.success(data)
    is SkillResult.Failure -> Result.failure(error.toException())
}
```

---

**Reference**: `skills/guides/10-error-handling.md` for full patterns
