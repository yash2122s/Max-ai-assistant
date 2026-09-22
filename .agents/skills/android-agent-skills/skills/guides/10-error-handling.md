---
name: Error Handling
description: Unified error handling strategy using Result and Domain Errors.
compliance_level: MANDATORY
tags: [error-handling, result, exceptions, domain-errors]
version: 2.2.0
---

# Error Handling

## Context
Consistent error handling prevents crashes and provides a better user experience. We use `kotlin.Result` and typed errors instead of unchecked exceptions.

**Related Guides:**
- [01-architecture.md](./01-architecture.md) - Repository error mapping
- [03-coroutines-concurrency.md](./03-coroutines-concurrency.md) - CancellationException
- [07-state-management.md](./07-state-management.md) - Error state in UI

---

## 🎯 AI Quick Reference

```
PATTERNS:
• Return Result<T> from repositories/use cases
• NEVER throw from data/domain layers
• Map exceptions at boundaries (Data layer)
• Handle errors in ViewModel

DOMAIN ERRORS:
• Sealed interface hierarchy
• Network: NoConnection, Timeout
• Server: Unauthorized, NotFound, ServerError
• Validation: InvalidInput, BusinessRule

RESULT EXTENSIONS:
• .onSuccess { value -> }
• .onFailure { error -> }
• .getOrNull()
• .getOrDefault(default)
• .map { } / .mapCatching { }
• .recover { } / .recoverCatching { }
```

---

## 1. Domain Error Hierarchy

### Error Definition
```kotlin
// ════════════════════════════════════════════════════════════
// DOMAIN ERROR - Sealed hierarchy for typed errors
// ════════════════════════════════════════════════════════════
sealed interface DomainError {
    val message: String
    val cause: Throwable?
        get() = null
    
    // ────────────────────────────────────────────────────────
    // Network Errors
    // ────────────────────────────────────────────────────────
    sealed interface Network : DomainError {
        data object NoConnection : Network {
            override val message = "No internet connection"
        }
        
        data object Timeout : Network {
            override val message = "Connection timed out"
        }
        
        data class Unknown(
            override val message: String,
            override val cause: Throwable? = null,
        ) : Network
    }
    
    // ────────────────────────────────────────────────────────
    // Server Errors
    // ────────────────────────────────────────────────────────
    sealed interface Server : DomainError {
        data object Unauthorized : Server {
            override val message = "Authentication required"
        }
        
        data object Forbidden : Server {
            override val message = "Access denied"
        }
        
        data object NotFound : Server {
            override val message = "Resource not found"
        }
        
        data object RateLimited : Server {
            override val message = "Too many requests, please try again later"
        }
        
        data class ServerError(
            override val message: String = "Server error occurred",
            val statusCode: Int = 500,
        ) : Server
    }
    
    // ────────────────────────────────────────────────────────
    // Data/Parsing Errors
    // ────────────────────────────────────────────────────────
    sealed interface Data : DomainError {
        data class ParseError(
            override val message: String,
            override val cause: Throwable? = null,
        ) : Data
        
        data class DatabaseError(
            override val message: String,
            override val cause: Throwable? = null,
        ) : Data
        
        data object EmptyResult : Data {
            override val message = "No data available"
        }
    }
    
    // ────────────────────────────────────────────────────────
    // Validation Errors
    // ────────────────────────────────────────────────────────
    sealed interface Validation : DomainError {
        data class InvalidInput(
            val field: String,
            override val message: String,
        ) : Validation
        
        data class BusinessRule(
            val rule: String,
            override val message: String,
        ) : Validation
    }
    
    // ────────────────────────────────────────────────────────
    // Generic Unknown Error
    // ────────────────────────────────────────────────────────
    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null,
    ) : DomainError
}

// ════════════════════════════════════════════════════════════
// ERROR MAPPING - At data layer boundaries
// ════════════════════════════════════════════════════════════
fun Throwable.toDomainError(): DomainError = when (this) {
    // Network errors
    is UnknownHostException -> DomainError.Network.NoConnection
    is SocketTimeoutException -> DomainError.Network.Timeout
    is ConnectException -> DomainError.Network.NoConnection
    is SSLException -> DomainError.Network.Unknown("SSL error: ${message}")
    
    // HTTP errors
    is HttpException -> when (code()) {
        401 -> DomainError.Server.Unauthorized
        403 -> DomainError.Server.Forbidden
        404 -> DomainError.Server.NotFound
        429 -> DomainError.Server.RateLimited
        in 500..599 -> DomainError.Server.ServerError(
            message = message() ?: "Server error",
            statusCode = code(),
        )
        else -> DomainError.Unknown("HTTP ${code()}: ${message()}")
    }
    
    // Parsing errors
    is JsonDecodingException -> DomainError.Data.ParseError(
        message = "Failed to parse response",
        cause = this,
    )
    is SerializationException -> DomainError.Data.ParseError(
        message = "Serialization error: ${message}",
        cause = this,
    )
    
    // Database errors
    is SQLiteException -> DomainError.Data.DatabaseError(
        message = "Database error: ${message}",
        cause = this,
    )
    
    // Cancellation - ALWAYS re-throw!
    is CancellationException -> throw this
    
    // Unknown
    else -> DomainError.Unknown(
        message = message ?: "Unknown error occurred",
        cause = this,
    )
}

// Convert DomainError to Throwable for Result.failure()
fun DomainError.toException(): Exception = DomainException(this)

class DomainException(val error: DomainError) : Exception(error.message, error.cause)

// Extension to get DomainError from Result
fun <T> Result<T>.domainError(): DomainError? = 
    exceptionOrNull()?.let { 
        (it as? DomainException)?.error ?: it.toDomainError()
    }
```

---

## 2. Repository Error Handling

### ✅ DO: Proper Error Handling in Repository
```kotlin
class UserRepositoryImpl @Inject constructor(
    private val api: UserApi,
    private val dao: UserDao,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : UserRepository {
    
    override suspend fun getUser(id: String): Result<User> = withContext(dispatcher) {
        try {
            // 1. Try network first
            val response = api.getUser(id)
            
            // 2. Cache locally
            dao.insert(response.toEntity())
            
            // 3. Return success
            Result.success(response.toDomain())
            
        } catch (e: CancellationException) {
            // ALWAYS re-throw CancellationException
            throw e
            
        } catch (e: Exception) {
            // 4. Try cache fallback
            val cached = dao.getUser(id)
            
            if (cached != null) {
                // Return cached with warning (could log staleness)
                Result.success(cached.toDomain())
            } else {
                // No cache, return error
                Result.failure(e.toDomainError().toException())
            }
        }
    }
    
    override suspend fun updateUser(user: User): Result<User> = withContext(dispatcher) {
        try {
            val response = api.updateUser(user.id, user.toDto())
            dao.update(response.toEntity())
            Result.success(response.toDomain())
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e.toDomainError().toException())
        }
    }
    
    override fun observeUser(id: String): Flow<User> = dao.observeUser(id)
        .map { it.toDomain() }
        .catch { e ->
            if (e is CancellationException) throw e
            // Emit error or empty based on use case
            throw e.toDomainError().toException()
        }
        .flowOn(dispatcher)
}

// ════════════════════════════════════════════════════════════
// EXTENSION: Safe API call wrapper
// ════════════════════════════════════════════════════════════
suspend fun <T> safeApiCall(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    apiCall: suspend () -> T,
): Result<T> = withContext(dispatcher) {
    try {
        Result.success(apiCall())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toDomainError().toException())
    }
}

// Usage
override suspend fun getProducts(): Result<List<Product>> = safeApiCall {
    api.getProducts().map { it.toDomain() }
}
```

---

## 3. UseCase Error Handling

### ✅ DO: Validation + Error Propagation
```kotlin
class UpdateUserProfileUseCase @Inject constructor(
    private val userRepository: UserRepository,
) {
    suspend operator fun invoke(
        userId: String,
        name: String,
        email: String,
    ): Result<User> {
        // ────────────────────────────────────────────────────
        // 1. Validate inputs FIRST
        // ────────────────────────────────────────────────────
        if (userId.isBlank()) {
            return Result.failure(
                DomainError.Validation.InvalidInput("userId", "User ID is required")
                    .toException()
            )
        }
        
        if (name.isBlank()) {
            return Result.failure(
                DomainError.Validation.InvalidInput("name", "Name is required")
                    .toException()
            )
        }
        
        if (!email.isValidEmail()) {
            return Result.failure(
                DomainError.Validation.InvalidInput("email", "Invalid email format")
                    .toException()
            )
        }
        
        // ────────────────────────────────────────────────────
        // 2. Business rule validation
        // ────────────────────────────────────────────────────
        if (name.length > 100) {
            return Result.failure(
                DomainError.Validation.BusinessRule(
                    rule = "name_length",
                    message = "Name cannot exceed 100 characters",
                ).toException()
            )
        }
        
        // ────────────────────────────────────────────────────
        // 3. Execute and propagate repository errors
        // ────────────────────────────────────────────────────
        return userRepository.updateUser(
            User(id = userId, name = name, email = email)
        )
    }
    
    private fun String.isValidEmail(): Boolean =
        matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"))
}

// ════════════════════════════════════════════════════════════
// CHAINING RESULTS
// ════════════════════════════════════════════════════════════
class PlaceOrderUseCase @Inject constructor(
    private val validateCartUseCase: ValidateCartUseCase,
    private val calculateTotalUseCase: CalculateTotalUseCase,
    private val orderRepository: OrderRepository,
) {
    suspend operator fun invoke(cart: Cart): Result<Order> {
        // Chain results - short-circuits on first failure
        return validateCartUseCase(cart)
            .mapCatching { calculateTotalUseCase(cart) }
            .mapCatching { total -> orderRepository.createOrder(cart, total) }
            .getOrElse { error ->
                Result.failure(error)
            }
    }
}
```

---

## 4. ViewModel Error Handling

### ✅ DO: Handle Errors and Update UI State
```kotlin
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getUserUseCase: GetUserUseCase,
    private val updateUserUseCase: UpdateUserProfileUseCase,
) : ViewModel() {
    
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()
    
    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    
    fun loadProfile(userId: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            
            getUserUseCase(userId)
                .onSuccess { user ->
                    _state.update { 
                        it.copy(
                            user = user,
                            name = user.name,
                            email = user.email,
                            isLoading = false,
                            error = null,
                        ) 
                    }
                }
                .onFailure { error ->
                    val uiError = error.toUiError()
                    _state.update { it.copy(isLoading = false, error = uiError) }
                    
                    // Handle specific errors
                    when (error.toDomainError()) {
                        DomainError.Server.Unauthorized -> {
                            _events.send(ProfileEvent.NavigateToLogin)
                        }
                        DomainError.Network.NoConnection -> {
                            _events.send(ProfileEvent.ShowRetrySnackbar)
                        }
                        else -> { /* Error shown in UI state */ }
                    }
                }
        }
    }
    
    fun updateProfile() {
        viewModelScope.launch {
            val currentState = _state.value
            
            _state.update { it.copy(isLoading = true, error = null) }
            
            updateUserUseCase(
                userId = currentState.user?.id ?: return@launch,
                name = currentState.name,
                email = currentState.email,
            )
                .onSuccess { user ->
                    _state.update { it.copy(user = user, isLoading = false) }
                    _events.send(ProfileEvent.ShowMessage("Profile updated"))
                }
                .onFailure { error ->
                    val domainError = error.toDomainError()
                    
                    // Handle validation errors specially
                    if (domainError is DomainError.Validation.InvalidInput) {
                        _state.update { 
                            it.copy(
                                isLoading = false,
                                fieldErrors = mapOf(domainError.field to domainError.message),
                            )
                        }
                    } else {
                        _state.update { 
                            it.copy(isLoading = false, error = error.toUiError())
                        }
                    }
                }
        }
    }
    
    fun dismissError() {
        _state.update { it.copy(error = null, fieldErrors = emptyMap()) }
    }
    
    fun retry() {
        _state.value.user?.id?.let { loadProfile(it) }
    }
}

// ════════════════════════════════════════════════════════════
// UI ERROR MAPPING
// ════════════════════════════════════════════════════════════
fun Throwable.toUiError(): UiError {
    val domainError = toDomainError()
    return UiError(
        message = domainError.toUserMessage(),
        isRetryable = domainError.isRetryable(),
        action = domainError.suggestedAction(),
    )
}

fun DomainError.toUserMessage(): String = when (this) {
    DomainError.Network.NoConnection -> "Please check your internet connection"
    DomainError.Network.Timeout -> "Connection timed out. Please try again"
    DomainError.Server.Unauthorized -> "Please log in to continue"
    DomainError.Server.NotFound -> "The requested item was not found"
    DomainError.Server.RateLimited -> "Too many requests. Please wait a moment"
    is DomainError.Server.ServerError -> "Something went wrong. Please try again"
    is DomainError.Validation.InvalidInput -> message
    is DomainError.Validation.BusinessRule -> message
    else -> "An unexpected error occurred"
}

fun DomainError.isRetryable(): Boolean = when (this) {
    DomainError.Network.NoConnection -> true
    DomainError.Network.Timeout -> true
    is DomainError.Server.ServerError -> statusCode in 500..599
    DomainError.Server.RateLimited -> true
    else -> false
}

fun DomainError.suggestedAction(): UiAction? = when (this) {
    DomainError.Server.Unauthorized -> UiAction.NavigateToLogin
    DomainError.Network.NoConnection -> UiAction.ShowRetry
    else -> null
}

// ════════════════════════════════════════════════════════════
// UI STATE WITH ERROR
// ════════════════════════════════════════════════════════════
@Immutable
data class ProfileUiState(
    val user: User? = null,
    val name: String = "",
    val email: String = "",
    val isLoading: Boolean = false,
    val error: UiError? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
) {
    val hasError: Boolean get() = error != null
    val nameError: String? get() = fieldErrors["name"]
    val emailError: String? get() = fieldErrors["email"]
}

@Immutable
data class UiError(
    val message: String,
    val isRetryable: Boolean = false,
    val action: UiAction? = null,
)

enum class UiAction {
    NavigateToLogin,
    ShowRetry,
}
```

---

## 5. Compose Error UI

### ✅ DO: Display Errors Appropriately
```kotlin
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    modifier: Modifier = Modifier,
    onRetryClick: () -> Unit = {},
    onDismissError: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            
            state.hasError && state.user == null -> {
                // Full-screen error (no data to show)
                ErrorContent(
                    error = state.error!!,
                    onRetryClick = onRetryClick,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            
            else -> {
                // Content with optional error banner
                Column(modifier = Modifier.fillMaxSize()) {
                    // Error banner (data exists but error occurred)
                    state.error?.let { error ->
                        ErrorBanner(
                            error = error,
                            onDismiss = onDismissError,
                            onRetry = if (error.isRetryable) onRetryClick else null,
                        )
                    }
                    
                    // Profile content
                    ProfileContent(
                        state = state,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorContent(
    error: UiError,
    modifier: Modifier = Modifier,
    onRetryClick: () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        
        if (error.isRetryable) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(onClick = onRetryClick) {
                Text("Retry")
            }
        }
    }
}

@Composable
fun ErrorBanner(
    error: UiError,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = error.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            
            onRetry?.let {
                TextButton(onClick = it) {
                    Text("Retry")
                }
            }
            
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Dismiss")
            }
        }
    }
}
```

---

## 6. Verification Checklist

### Error Types
- [ ] Sealed `DomainError` hierarchy defined
- [ ] Network, Server, Data, Validation categories
- [ ] Error mapping at data layer boundaries

### Repository
- [ ] Returns `Result<T>` for all suspend functions
- [ ] `CancellationException` always re-thrown
- [ ] Errors mapped to `DomainError`
- [ ] Cache fallback on network errors

### UseCase
- [ ] Input validation before execution
- [ ] Business rules checked
- [ ] Repository errors propagated

### ViewModel
- [ ] Errors handled with `onFailure`
- [ ] UI state updated with error info
- [ ] Specific error handling (auth, network)
- [ ] Retry capability for retryable errors

### UI
- [ ] Full-screen error when no data
- [ ] Error banner when data exists
- [ ] Retry button for retryable errors
- [ ] Field-level validation errors
