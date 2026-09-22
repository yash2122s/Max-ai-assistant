package com.agentcore.skill.base

import kotlin.time.Duration

/**
 * Represents the result of a skill execution.
 * 
 * @param T The type of successful output data
 */
sealed interface SkillResult<out T> {
    
    /**
     * Represents a successful skill execution.
     * 
     * @param data The output data from the skill
     * @param metadata Optional metadata about the execution
     */
    data class Success<T>(
        val data: T,
        val metadata: ExecutionMetadata = ExecutionMetadata(),
    ) : SkillResult<T>
    
    /**
     * Represents a failed skill execution.
     * 
     * @param error The error that occurred
     * @param metadata Optional metadata about the execution
     */
    data class Failure(
        val error: SkillError,
        val metadata: ExecutionMetadata = ExecutionMetadata(),
    ) : SkillResult<Nothing>
    
    /**
     * Checks if this result is a success.
     */
    val isSuccess: Boolean
        get() = this is Success
    
    /**
     * Checks if this result is a failure.
     */
    val isFailure: Boolean
        get() = this is Failure
    
    /**
     * Returns the data if success, or null if failure.
     */
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }
    
    /**
     * Returns the data if success, or the default value if failure.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> default
    }
    
    /**
     * Returns the data if success, or computes a default value if failure.
     */
    inline fun getOrElse(onFailure: (SkillError) -> @UnsafeVariance T): T = when (this) {
        is Success -> data
        is Failure -> onFailure(error)
    }
    
    /**
     * Returns the error if failure, or null if success.
     */
    fun errorOrNull(): SkillError? = when (this) {
        is Success -> null
        is Failure -> error
    }
    
    /**
     * Transforms the success data using the given mapper.
     */
    inline fun <R> map(transform: (T) -> R): SkillResult<R> = when (this) {
        is Success -> Success(transform(data), metadata)
        is Failure -> this
    }
    
    /**
     * Transforms the success data using a mapper that returns a SkillResult.
     */
    inline fun <R> flatMap(transform: (T) -> SkillResult<R>): SkillResult<R> = when (this) {
        is Success -> transform(data)
        is Failure -> this
    }
    
    /**
     * Handles the result using the provided handlers.
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onFailure: (SkillError) -> R,
    ): R = when (this) {
        is Success -> onSuccess(data)
        is Failure -> onFailure(error)
    }
    
    /**
     * Performs an action if this is a success.
     */
    inline fun onSuccess(action: (T) -> Unit): SkillResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    /**
     * Performs an action if this is a failure.
     */
    inline fun onFailure(action: (SkillError) -> Unit): SkillResult<T> {
        if (this is Failure) action(error)
        return this
    }
    
    companion object {
        /**
         * Creates a success result.
         */
        fun <T> success(data: T, metadata: ExecutionMetadata = ExecutionMetadata()): SkillResult<T> =
            Success(data, metadata)
        
        /**
         * Creates a failure result.
         */
        fun failure(error: SkillError, metadata: ExecutionMetadata = ExecutionMetadata()): SkillResult<Nothing> =
            Failure(error, metadata)
        
        /**
         * Creates a failure result from an exception.
         */
        fun failure(
            exception: Throwable,
            code: String = ErrorCode.UNKNOWN,
            metadata: ExecutionMetadata = ExecutionMetadata(),
        ): SkillResult<Nothing> = Failure(
            error = SkillError(
                code = code,
                message = exception.message ?: "Unknown error",
                cause = exception,
            ),
            metadata = metadata,
        )
        
        /**
         * Wraps a block execution in a SkillResult.
         */
        inline fun <T> runCatching(block: () -> T): SkillResult<T> = try {
            Success(block())
        } catch (e: Exception) {
            failure(e)
        }
    }
}

/**
 * Metadata about skill execution.
 */
data class ExecutionMetadata(
    val executionTime: Duration = Duration.ZERO,
    val retryCount: Int = 0,
    val skillName: String = "",
    val traceId: String = "",
    val additionalInfo: Map<String, Any> = emptyMap(),
)

/**
 * Represents an error that occurred during skill execution.
 */
data class SkillError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
    val details: Map<String, Any> = emptyMap(),
) {
    fun toException(): SkillExecutionException = SkillExecutionException(this)
}

/**
 * Exception wrapper for SkillError.
 */
class SkillExecutionException(
    val error: SkillError,
) : Exception(error.message, error.cause)

/**
 * Common error codes.
 */
object ErrorCode {
    const val UNKNOWN = "UNKNOWN_ERROR"
    const val TIMEOUT = "TIMEOUT_ERROR"
    const val CANCELLED = "CANCELLED_ERROR"
    const val VALIDATION = "VALIDATION_ERROR"
    const val NOT_FOUND = "NOT_FOUND_ERROR"
    const val NETWORK = "NETWORK_ERROR"
    const val SKILL_NOT_FOUND = "SKILL_NOT_FOUND"
    const val EXECUTION_FAILED = "EXECUTION_FAILED"
    const val CHAIN_FAILED = "CHAIN_FAILED"
    const val MEMORY_ERROR = "MEMORY_ERROR"
    const val INTENT_NOT_DETECTED = "INTENT_NOT_DETECTED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val RATE_LIMITED = "RATE_LIMITED"
    const val INVALID_STATE = "INVALID_STATE"
    const val UNAUTHORIZED = "UNAUTHORIZED_ERROR"
    const val CONFLICT = "CONFLICT_ERROR"
    const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE_ERROR"
    const val DATABASE = "DATABASE_ERROR"
    const val INVALID_INPUT = "INVALID_INPUT_ERROR"
    const val SERIALIZATION = "SERIALIZATION_ERROR"
    const val CONFIGURATION = "CONFIGURATION_ERROR"
}

/**
 * Sealed class hierarchy for typed skill errors.
 * 
 * Use these typed errors for clearer error handling and pattern matching.
 * Each error type provides specific context for its error scenario.
 * 
 * Usage:
 * ```kotlin
 * when (error) {
 *     is SkillError.Network -> handleNetworkError(error)
 *     is SkillError.Validation -> showValidationMessage(error)
 *     is SkillError.NotFound -> showNotFoundScreen(error)
 *     is SkillError.RateLimited -> scheduleRetry(error.retryAfterMs)
 *     // etc.
 * }
 * ```
 */
sealed class TypedSkillError(
    open val code: String,
    open val message: String,
    open val cause: Throwable? = null,
    open val details: Map<String, Any> = emptyMap(),
) {
    /**
     * Converts to basic SkillError for compatibility.
     */
    fun toSkillError(): SkillError = SkillError(code, message, cause, details)
    
    /**
     * Network-related errors (connection, DNS, SSL, etc.)
     * 
     * @param message Human-readable error description
     * @param cause The underlying IOException or network exception
     * @param isRetryable Whether the operation can be retried
     * @param httpStatusCode Optional HTTP status code if available
     */
    data class Network(
        override val message: String,
        override val cause: Throwable? = null,
        val isRetryable: Boolean = true,
        val httpStatusCode: Int? = null,
    ) : TypedSkillError(ErrorCode.NETWORK, message, cause) {
        companion object {
            fun connectionFailed(host: String, cause: Throwable? = null) = Network(
                message = "Failed to connect to $host",
                cause = cause,
                isRetryable = true
            )
            
            fun timeout(operation: String, timeoutMs: Long, cause: Throwable? = null) = Network(
                message = "$operation timed out after ${timeoutMs}ms",
                cause = cause,
                isRetryable = true
            )
            
            fun sslError(message: String, cause: Throwable? = null) = Network(
                message = "SSL/TLS error: $message",
                cause = cause,
                isRetryable = false
            )
        }
    }
    
    /**
     * Input validation errors (format, range, constraints)
     * 
     * @param message Human-readable error description
     * @param field The field that failed validation
     * @param rejectedValue The value that was rejected (for debugging)
     */
    data class Validation(
        override val message: String,
        val field: String = "",
        val rejectedValue: Any? = null,
    ) : TypedSkillError(
        code = ErrorCode.VALIDATION,
        message = message,
        details = buildMap {
            if (field.isNotEmpty()) put("field", field)
            rejectedValue?.let { put("rejectedValue", it.toString()) }
        }
    ) {
        companion object {
            fun required(field: String) = Validation(
                message = "$field is required",
                field = field
            )
            
            fun invalidFormat(field: String, expected: String, actual: Any?) = Validation(
                message = "$field has invalid format. Expected: $expected",
                field = field,
                rejectedValue = actual
            )
            
            fun outOfRange(field: String, range: String, actual: Any?) = Validation(
                message = "$field is out of range. Expected: $range",
                field = field,
                rejectedValue = actual
            )
        }
    }
    
    /**
     * Resource not found errors
     * 
     * @param message Human-readable error description
     * @param resourceType The type of resource that was not found
     * @param resourceId The identifier used to look up the resource
     */
    data class NotFound(
        override val message: String,
        val resourceType: String = "",
        val resourceId: String = "",
    ) : TypedSkillError(
        code = ErrorCode.NOT_FOUND,
        message = message,
        details = buildMap {
            if (resourceType.isNotEmpty()) put("resourceType", resourceType)
            if (resourceId.isNotEmpty()) put("resourceId", resourceId)
        }
    ) {
        companion object {
            fun resource(type: String, id: String) = NotFound(
                message = "$type with id '$id' not found",
                resourceType = type,
                resourceId = id
            )
            
            fun skill(skillId: String) = NotFound(
                message = "Skill '$skillId' not found in registry",
                resourceType = "Skill",
                resourceId = skillId
            )
        }
    }
    
    /**
     * Authorization/Authentication errors
     * 
     * @param message Human-readable error description
     * @param requiredPermission The permission that was required
     * @param requiredRole The role that was required (if applicable)
     */
    data class Unauthorized(
        override val message: String,
        val requiredPermission: String? = null,
        val requiredRole: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.UNAUTHORIZED,
        message = message,
        details = buildMap {
            requiredPermission?.let { put("requiredPermission", it) }
            requiredRole?.let { put("requiredRole", it) }
        }
    ) {
        companion object {
            fun missingPermission(permission: String) = Unauthorized(
                message = "Missing required permission: $permission",
                requiredPermission = permission
            )
            
            fun tokenExpired() = Unauthorized(
                message = "Authentication token has expired"
            )
            
            fun invalidCredentials() = Unauthorized(
                message = "Invalid credentials provided"
            )
        }
    }
    
    /**
     * Rate limiting errors
     * 
     * @param message Human-readable error description
     * @param retryAfterMs Milliseconds to wait before retrying
     * @param limit The rate limit that was exceeded
     * @param window The time window for the rate limit
     */
    data class RateLimited(
        override val message: String,
        val retryAfterMs: Long? = null,
        val limit: Int? = null,
        val window: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.RATE_LIMITED,
        message = message,
        details = buildMap {
            retryAfterMs?.let { put("retryAfterMs", it) }
            limit?.let { put("limit", it) }
            window?.let { put("window", it) }
        }
    ) {
        companion object {
            fun limitExceeded(limit: Int, window: String, retryAfterMs: Long? = null) = RateLimited(
                message = "Rate limit exceeded: $limit requests per $window",
                retryAfterMs = retryAfterMs,
                limit = limit,
                window = window
            )
        }
    }
    
    /**
     * Resource conflict errors (concurrent modification, duplicate, etc.)
     * 
     * @param message Human-readable error description
     * @param conflictingResource The resource that caused the conflict
     * @param conflictType Type of conflict (e.g., "duplicate", "version_mismatch")
     */
    data class Conflict(
        override val message: String,
        val conflictingResource: String? = null,
        val conflictType: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.CONFLICT,
        message = message,
        details = buildMap {
            conflictingResource?.let { put("conflictingResource", it) }
            conflictType?.let { put("conflictType", it) }
        }
    ) {
        companion object {
            fun duplicate(resourceType: String, identifier: String) = Conflict(
                message = "$resourceType with '$identifier' already exists",
                conflictingResource = identifier,
                conflictType = "duplicate"
            )
            
            fun versionMismatch(resourceType: String, expected: String, actual: String) = Conflict(
                message = "$resourceType version mismatch. Expected: $expected, Actual: $actual",
                conflictType = "version_mismatch"
            )
        }
    }
    
    /**
     * Service unavailable errors (maintenance, overload, dependency failure)
     * 
     * @param message Human-readable error description
     * @param serviceName The name of the unavailable service
     * @param estimatedRecoveryMs Estimated recovery time in milliseconds
     */
    data class ServiceUnavailable(
        override val message: String,
        val serviceName: String,
        val estimatedRecoveryMs: Long? = null,
    ) : TypedSkillError(
        code = ErrorCode.SERVICE_UNAVAILABLE,
        message = message,
        details = buildMap {
            put("serviceName", serviceName)
            estimatedRecoveryMs?.let { put("estimatedRecoveryMs", it) }
        }
    ) {
        companion object {
            fun maintenance(serviceName: String, estimatedRecoveryMs: Long? = null) = ServiceUnavailable(
                message = "$serviceName is currently under maintenance",
                serviceName = serviceName,
                estimatedRecoveryMs = estimatedRecoveryMs
            )
            
            fun overloaded(serviceName: String) = ServiceUnavailable(
                message = "$serviceName is currently overloaded. Please try again later.",
                serviceName = serviceName
            )
        }
    }
    
    /**
     * Database operation errors
     * 
     * @param message Human-readable error description
     * @param operation The database operation that failed
     * @param cause The underlying database exception
     */
    data class Database(
        override val message: String,
        val operation: String? = null,
        override val cause: Throwable? = null,
    ) : TypedSkillError(
        code = ErrorCode.DATABASE,
        message = message,
        cause = cause,
        details = buildMap {
            operation?.let { put("operation", it) }
        }
    ) {
        companion object {
            fun queryFailed(query: String, cause: Throwable? = null) = Database(
                message = "Database query failed: ${cause?.message ?: "Unknown error"}",
                operation = "query",
                cause = cause
            )
            
            fun constraintViolation(constraint: String, cause: Throwable? = null) = Database(
                message = "Database constraint violation: $constraint",
                operation = "constraint",
                cause = cause
            )
            
            fun connectionFailed(cause: Throwable? = null) = Database(
                message = "Failed to connect to database: ${cause?.message ?: "Unknown error"}",
                operation = "connect",
                cause = cause
            )
        }
    }
    
    /**
     * Timeout errors
     * 
     * @param message Human-readable error description
     * @param operation The operation that timed out
     * @param timeoutMs The timeout value in milliseconds
     */
    data class Timeout(
        override val message: String,
        val operation: String = "",
        val timeoutMs: Long = 0,
    ) : TypedSkillError(
        code = ErrorCode.TIMEOUT,
        message = message,
        details = buildMap {
            if (operation.isNotEmpty()) put("operation", operation)
            if (timeoutMs > 0) put("timeoutMs", timeoutMs)
        }
    ) {
        companion object {
            fun operationTimeout(operation: String, timeoutMs: Long) = Timeout(
                message = "$operation timed out after ${timeoutMs}ms",
                operation = operation,
                timeoutMs = timeoutMs
            )
        }
    }
    
    /**
     * Execution/Runtime errors (catch-all for unexpected errors)
     * 
     * @param message Human-readable error description
     * @param cause The underlying exception
     * @param operation The operation that failed
     */
    data class Execution(
        override val message: String,
        override val cause: Throwable? = null,
        val operation: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.EXECUTION_FAILED,
        message = message,
        cause = cause,
        details = buildMap {
            operation?.let { put("operation", it) }
            cause?.let { put("exceptionType", it::class.simpleName ?: "Unknown") }
        }
    )
    
    /**
     * Cancellation error (for tracking cancelled operations)
     * Note: CancellationException should typically be rethrown, not caught.
     * Use this only when you need to track that an operation was cancelled.
     */
    data class Cancelled(
        override val message: String = "Operation was cancelled",
        val reason: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.CANCELLED,
        message = message,
        details = buildMap {
            reason?.let { put("reason", it) }
        }
    )
    
    /**
     * Invalid input errors (similar to Validation but for programmatic errors)
     * 
     * @param message Human-readable error description
     * @param parameterName The parameter that was invalid
     * @param expectedType The expected type or format
     */
    data class InvalidInput(
        override val message: String,
        val parameterName: String? = null,
        val expectedType: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.INVALID_INPUT,
        message = message,
        details = buildMap {
            parameterName?.let { put("parameterName", it) }
            expectedType?.let { put("expectedType", it) }
        }
    )
    
    /**
     * Serialization/Deserialization errors
     * 
     * @param message Human-readable error description
     * @param format The serialization format (JSON, XML, etc.)
     * @param cause The underlying exception
     */
    data class Serialization(
        override val message: String,
        val format: String = "JSON",
        override val cause: Throwable? = null,
    ) : TypedSkillError(
        code = ErrorCode.SERIALIZATION,
        message = message,
        cause = cause,
        details = mapOf("format" to format)
    ) {
        companion object {
            fun parseFailed(format: String, cause: Throwable? = null) = Serialization(
                message = "Failed to parse $format: ${cause?.message ?: "Unknown error"}",
                format = format,
                cause = cause
            )
            
            fun serializeFailed(format: String, cause: Throwable? = null) = Serialization(
                message = "Failed to serialize to $format: ${cause?.message ?: "Unknown error"}",
                format = format,
                cause = cause
            )
        }
    }
    
    /**
     * Configuration errors
     * 
     * @param message Human-readable error description
     * @param configKey The configuration key that was invalid or missing
     */
    data class Configuration(
        override val message: String,
        val configKey: String? = null,
    ) : TypedSkillError(
        code = ErrorCode.CONFIGURATION,
        message = message,
        details = buildMap {
            configKey?.let { put("configKey", it) }
        }
    ) {
        companion object {
            fun missing(key: String) = Configuration(
                message = "Required configuration '$key' is missing",
                configKey = key
            )
            
            fun invalid(key: String, reason: String) = Configuration(
                message = "Configuration '$key' is invalid: $reason",
                configKey = key
            )
        }
    }
}

/**
 * Extension to create SkillResult.Failure from TypedSkillError.
 */
fun <T> TypedSkillError.toFailure(): SkillResult<T> = SkillResult.failure(toSkillError())

/**
 * Helper functions for creating typed error results.
 */
object SkillErrors {
    fun <T> network(message: String, cause: Throwable? = null): SkillResult<T> =
        TypedSkillError.Network(message, cause).toFailure()
    
    fun <T> validation(message: String, field: String = ""): SkillResult<T> =
        TypedSkillError.Validation(message, field).toFailure()
    
    fun <T> notFound(resourceType: String, resourceId: String): SkillResult<T> =
        TypedSkillError.NotFound.resource(resourceType, resourceId).toFailure()
    
    fun <T> unauthorized(message: String): SkillResult<T> =
        TypedSkillError.Unauthorized(message).toFailure()
    
    fun <T> rateLimited(message: String, retryAfterMs: Long? = null): SkillResult<T> =
        TypedSkillError.RateLimited(message, retryAfterMs).toFailure()
    
    fun <T> conflict(message: String): SkillResult<T> =
        TypedSkillError.Conflict(message).toFailure()
    
    fun <T> serviceUnavailable(serviceName: String, message: String): SkillResult<T> =
        TypedSkillError.ServiceUnavailable(message, serviceName).toFailure()
    
    fun <T> database(message: String, cause: Throwable? = null): SkillResult<T> =
        TypedSkillError.Database(message, cause = cause).toFailure()
    
    fun <T> timeout(operation: String, timeoutMs: Long): SkillResult<T> =
        TypedSkillError.Timeout.operationTimeout(operation, timeoutMs).toFailure()
    
    fun <T> execution(message: String, cause: Throwable? = null): SkillResult<T> =
        TypedSkillError.Execution(message, cause).toFailure()
}

// ============================================================
// EXTENSION FUNCTIONS FOR INTEROPERABILITY
// ============================================================

/**
 * Converts kotlin.Result to SkillResult.
 * 
 * Usage:
 * ```kotlin
 * val kotlinResult: Result<String> = runCatching { "hello" }
 * val skillResult: SkillResult<String> = kotlinResult.toSkillResult()
 * ```
 */
fun <T> Result<T>.toSkillResult(): SkillResult<T> = fold(
    onSuccess = { SkillResult.success(it) },
    onFailure = { SkillResult.failure(it) }
)

/**
 * Converts SkillResult to kotlin.Result.
 * 
 * Usage:
 * ```kotlin
 * val skillResult: SkillResult<String> = SkillResult.success("hello")
 * val kotlinResult: Result<String> = skillResult.toKotlinResult()
 * ```
 */
fun <T> SkillResult<T>.toKotlinResult(): Result<T> = when (this) {
    is SkillResult.Success -> Result.success(data)
    is SkillResult.Failure -> Result.failure(error.toException())
}

/**
 * Chains SkillResult operations similar to kotlin.Result.
 */
suspend inline fun <T, R> SkillResult<T>.andThen(
    crossinline transform: suspend (T) -> SkillResult<R>,
): SkillResult<R> = when (this) {
    is SkillResult.Success -> transform(data)
    is SkillResult.Failure -> this
}

/**
 * Recovers from a failure with an alternative value.
 */
inline fun <T> SkillResult<T>.recover(
    recovery: (SkillError) -> T,
): SkillResult<T> = when (this) {
    is SkillResult.Success -> this
    is SkillResult.Failure -> SkillResult.success(recovery(error))
}

/**
 * Recovers from a failure with an alternative SkillResult.
 */
inline fun <T> SkillResult<T>.recoverWith(
    recovery: (SkillError) -> SkillResult<T>,
): SkillResult<T> = when (this) {
    is SkillResult.Success -> this
    is SkillResult.Failure -> recovery(error)
}

/**
 * Maps the error if this is a failure.
 */
inline fun <T> SkillResult<T>.mapError(
    transform: (SkillError) -> SkillError,
): SkillResult<T> = when (this) {
    is SkillResult.Success -> this
    is SkillResult.Failure -> SkillResult.Failure(transform(error), metadata)
}

/**
 * Combines two SkillResults into a Pair.
 */
fun <A, B> SkillResult<A>.zip(
    other: SkillResult<B>,
): SkillResult<Pair<A, B>> = when {
    this is SkillResult.Success && other is SkillResult.Success -> 
        SkillResult.success(data to other.data)
    this is SkillResult.Failure -> this
    other is SkillResult.Failure -> other
    else -> SkillResult.failure(SkillError(ErrorCode.UNKNOWN, "Unexpected state"))
}

/**
 * Combines two SkillResults using a transform function.
 */
inline fun <A, B, R> SkillResult<A>.zipWith(
    other: SkillResult<B>,
    transform: (A, B) -> R,
): SkillResult<R> = when {
    this is SkillResult.Success && other is SkillResult.Success -> 
        SkillResult.success(transform(data, other.data))
    this is SkillResult.Failure -> this
    other is SkillResult.Failure -> other
    else -> SkillResult.failure(SkillError(ErrorCode.UNKNOWN, "Unexpected state"))
}

/**
 * Filters the success value, converting to failure if predicate is false.
 */
inline fun <T> SkillResult<T>.filter(
    errorMessage: String = "Value did not satisfy predicate",
    predicate: (T) -> Boolean,
): SkillResult<T> = when (this) {
    is SkillResult.Success -> if (predicate(data)) this 
        else SkillResult.failure(SkillError(ErrorCode.VALIDATION, errorMessage))
    is SkillResult.Failure -> this
}

/**
 * Converts a nullable value to SkillResult.
 */
fun <T : Any> T?.toSkillResult(
    errorMessage: String = "Value was null",
    errorCode: String = ErrorCode.NOT_FOUND,
): SkillResult<T> = if (this != null) {
    SkillResult.success(this)
} else {
    SkillResult.failure(SkillError(errorCode, errorMessage))
}

/**
 * Ensures the result satisfies a condition, or returns a failure.
 */
inline fun <T> SkillResult<T>.ensure(
    errorCode: String = ErrorCode.VALIDATION,
    errorMessage: () -> String,
    predicate: (T) -> Boolean,
): SkillResult<T> = flatMap { value ->
    if (predicate(value)) {
        SkillResult.success(value)
    } else {
        SkillResult.failure(SkillError(errorCode, errorMessage()))
    }
}

