package com.example.app.feature.template

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * Template for testing UseCases.
 * 
 * This template demonstrates best practices for UseCase testing:
 * - Validation testing (every field, every rule)
 * - Success path testing
 * - Error propagation testing
 * - Parameterized tests for multiple inputs
 * 
 * Test Coverage Goals:
 * - Validation: 50% (test EVERY validation rule)
 * - Success path: 25% (correct data flow)
 * - Error handling: 25% (repository errors, edge cases)
 * 
 * @see 11-testing.md for testing guidelines
 * @see 24-validation-rules.md for validation patterns
 * 
 * Created & Reviewed by: TrongLB & AI Agents
 */

// ============================================================
// EXAMPLE USECASE TO TEST
// ============================================================

// Domain model
data class User(
    val id: String,
    val name: String,
    val email: String,
)

// Domain error
sealed interface DomainError {
    sealed interface Validation : DomainError {
        data class InvalidInput(val field: String, val message: String) : Validation
    }
    sealed interface Network : DomainError {
        data object NoConnection : Network
        data object Timeout : Network
    }
    sealed interface Server : DomainError {
        data object Unauthorized : Server
        data object NotFound : Server
    }
}

// Repository interface
interface UserRepository {
    suspend fun updateUser(user: User): Result<User>
}

// UseCase under test
class UpdateUserProfileUseCase(
    private val userRepository: UserRepository,
) {
    suspend operator fun invoke(
        userId: String,
        name: String,
        email: String,
    ): Result<User> {
        // Validation
        if (userId.isBlank()) {
            return Result.failure(
                ValidationException("userId", "User ID is required")
            )
        }
        
        if (name.isBlank()) {
            return Result.failure(
                ValidationException("name", "Name is required")
            )
        }
        
        if (name.length < 2) {
            return Result.failure(
                ValidationException("name", "Name must be at least 2 characters")
            )
        }
        
        if (name.length > 50) {
            return Result.failure(
                ValidationException("name", "Name must not exceed 50 characters")
            )
        }
        
        if (!email.matches(Regex("^[A-Za-z0-9+_.-]+@(.+)$"))) {
            return Result.failure(
                ValidationException("email", "Invalid email format")
            )
        }
        
        // Business logic - normalize email
        val normalizedEmail = email.lowercase().trim()
        
        // Call repository
        val user = User(
            id = userId,
            name = name.trim(),
            email = normalizedEmail,
        )
        
        return userRepository.updateUser(user)
    }
}

class ValidationException(
    val field: String,
    override val message: String,
) : Exception(message)

// ============================================================
// TEST SUITE
// ============================================================

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateUserProfileUseCaseTest {
    
    // ========================================
    // Test Setup
    // ========================================
    
    private lateinit var userRepository: UserRepository
    private lateinit var useCase: UpdateUserProfileUseCase
    
    @BeforeEach
    fun setup() {
        userRepository = mockk()
        useCase = UpdateUserProfileUseCase(userRepository)
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    // ========================================
    // Validation Tests (50%) - Test EVERY rule
    // ========================================
    
    @Nested
    @DisplayName("Validation - userId")
    inner class UserIdValidation {
        
        @Test
        fun `when userId is blank then returns validation error`() = runTest {
            // Act
            val result = useCase(
                userId = "",
                name = "John",
                email = "john@test.com",
            )
            
            // Assert
            assertThat(result.isFailure).isTrue()
            val error = result.exceptionOrNull() as ValidationException
            assertThat(error.field).isEqualTo("userId")
            assertThat(error.message).contains("required")
            
            // Verify repository NOT called
            coVerify(exactly = 0) { userRepository.updateUser(any()) }
        }
        
        @Test
        fun `when userId is whitespace only then returns validation error`() = runTest {
            // Act
            val result = useCase(
                userId = "   ",
                name = "John",
                email = "john@test.com",
            )
            
            // Assert
            assertThat(result.isFailure).isTrue()
            val error = result.exceptionOrNull() as ValidationException
            assertThat(error.field).isEqualTo("userId")
        }
    }
    
    @Nested
    @DisplayName("Validation - name")
    inner class NameValidation {
        
        @Test
        fun `when name is blank then returns validation error`() = runTest {
            val result = useCase(
                userId = "1",
                name = "",
                email = "john@test.com",
            )
            
            assertThat(result.isFailure).isTrue()
            val error = result.exceptionOrNull() as ValidationException
            assertThat(error.field).isEqualTo("name")
            assertThat(error.message).contains("required")
        }
        
        @Test
        fun `when name is too short then returns validation error`() = runTest {
            val result = useCase(
                userId = "1",
                name = "A",
                email = "john@test.com",
            )
            
            assertThat(result.isFailure).isTrue()
            val error = result.exceptionOrNull() as ValidationException
            assertThat(error.field).isEqualTo("name")
            assertThat(error.message).contains("at least 2")
        }
        
        @Test
        fun `when name is too long then returns validation error`() = runTest {
            val result = useCase(
                userId = "1",
                name = "A".repeat(51),
                email = "john@test.com",
            )
            
            assertThat(result.isFailure).isTrue()
            val error = result.exceptionOrNull() as ValidationException
            assertThat(error.field).isEqualTo("name")
            assertThat(error.message).contains("50")
        }
        
        @Test
        fun `when name is at minimum length then passes validation`() = runTest {
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", "AB", "john@test.com")
            )
            
            val result = useCase(
                userId = "1",
                name = "AB",  // Exactly 2 characters
                email = "john@test.com",
            )
            
            assertThat(result.isSuccess).isTrue()
        }
        
        @Test
        fun `when name is at maximum length then passes validation`() = runTest {
            val maxName = "A".repeat(50)
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", maxName, "john@test.com")
            )
            
            val result = useCase(
                userId = "1",
                name = maxName,  // Exactly 50 characters
                email = "john@test.com",
            )
            
            assertThat(result.isSuccess).isTrue()
        }
    }
    
    @Nested
    @DisplayName("Validation - email")
    inner class EmailValidation {
        
        @ParameterizedTest
        @ValueSource(strings = [
            "invalid",
            "no-at-sign",
            "@nodomain",
            "spaces in@email.com",
            "",
        ])
        fun `when email is invalid format then returns validation error`(email: String) = runTest {
            val result = useCase(
                userId = "1",
                name = "John",
                email = email,
            )
            
            assertThat(result.isFailure).isTrue()
            val error = result.exceptionOrNull() as? ValidationException
            assertThat(error?.field).isEqualTo("email")
        }
        
        @ParameterizedTest
        @ValueSource(strings = [
            "test@example.com",
            "user.name@domain.org",
            "user+tag@domain.com",
            "a@b.co",
            "USER@EXAMPLE.COM",
        ])
        fun `when email is valid format then passes validation`(email: String) = runTest {
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", "John", email.lowercase())
            )
            
            val result = useCase(
                userId = "1",
                name = "John",
                email = email,
            )
            
            assertThat(result.isSuccess).isTrue()
        }
    }
    
    // ========================================
    // Success Path Tests (25%)
    // ========================================
    
    @Nested
    @DisplayName("Success Path")
    inner class SuccessPath {
        
        @Test
        fun `when all inputs valid then calls repository with correct data`() = runTest {
            // Given
            val expectedUser = User(
                id = "1",
                name = "John Doe",
                email = "john@test.com",
            )
            coEvery { userRepository.updateUser(any()) } returns Result.success(expectedUser)
            
            // When
            val result = useCase(
                userId = "1",
                name = "John Doe",
                email = "JOHN@TEST.COM",  // Will be normalized
            )
            
            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo(expectedUser)
            
            // Verify repository called with normalized email
            coVerify {
                userRepository.updateUser(
                    match { 
                        it.id == "1" && 
                        it.name == "John Doe" && 
                        it.email == "john@test.com"  // Lowercase
                    }
                )
            }
        }
        
        @Test
        fun `when name has leading or trailing whitespace then trims it`() = runTest {
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", "John", "john@test.com")
            )
            
            useCase(
                userId = "1",
                name = "  John  ",  // Has whitespace
                email = "john@test.com",
            )
            
            coVerify {
                userRepository.updateUser(match { it.name == "John" })
            }
        }
        
        @Test
        fun `when email has whitespace then trims and lowercases`() = runTest {
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", "John", "john@test.com")
            )
            
            useCase(
                userId = "1",
                name = "John",
                email = "  JOHN@TEST.COM  ",  // Has whitespace and uppercase
            )
            
            coVerify {
                userRepository.updateUser(match { it.email == "john@test.com" })
            }
        }
    }
    
    // ========================================
    // Error Propagation Tests (25%)
    // ========================================
    
    @Nested
    @DisplayName("Error Propagation")
    inner class ErrorPropagation {
        
        @Test
        fun `when repository throws network error then propagates error`() = runTest {
            // Given
            val networkError = RuntimeException("No internet connection")
            coEvery { userRepository.updateUser(any()) } returns Result.failure(networkError)
            
            // When
            val result = useCase(
                userId = "1",
                name = "John",
                email = "john@test.com",
            )
            
            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isEqualTo(networkError)
        }
        
        @ParameterizedTest
        @MethodSource("repositoryErrorCases")
        fun `when repository returns different errors then propagates correctly`(
            error: Exception,
            expectedMessage: String,
        ) = runTest {
            coEvery { userRepository.updateUser(any()) } returns Result.failure(error)
            
            val result = useCase(
                userId = "1",
                name = "John",
                email = "john@test.com",
            )
            
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()?.message).contains(expectedMessage)
        }
        
        companion object {
            @JvmStatic
            fun repositoryErrorCases() = listOf(
                Arguments.of(RuntimeException("Network error"), "Network"),
                Arguments.of(RuntimeException("Timeout"), "Timeout"),
                Arguments.of(RuntimeException("Server error"), "Server"),
            )
        }
    }
    
    // ========================================
    // Edge Cases
    // ========================================
    
    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {
        
        @Test
        fun `when name contains unicode characters then processes correctly`() = runTest {
            val unicodeName = "Nguyễn Văn A"
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", unicodeName, "john@test.com")
            )
            
            val result = useCase(
                userId = "1",
                name = unicodeName,
                email = "john@test.com",
            )
            
            assertThat(result.isSuccess).isTrue()
            coVerify {
                userRepository.updateUser(match { it.name == unicodeName })
            }
        }
        
        @Test
        fun `when email contains plus sign then accepts it`() = runTest {
            val emailWithPlus = "user+tag@example.com"
            coEvery { userRepository.updateUser(any()) } returns Result.success(
                User("1", "John", emailWithPlus)
            )
            
            val result = useCase(
                userId = "1",
                name = "John",
                email = emailWithPlus,
            )
            
            assertThat(result.isSuccess).isTrue()
        }
    }
}

// ============================================================
// TEST UTILITIES
// ============================================================

/**
 * Extension to assert Result is success and return value.
 */
fun <T> Result<T>.assertSuccess(): T {
    assertThat(isSuccess).isTrue()
    return getOrThrow()
}

/**
 * Extension to assert Result is failure and return exception.
 */
fun <T> Result<T>.assertFailure(): Throwable {
    assertThat(isFailure).isTrue()
    return exceptionOrNull()!!
}

/**
 * Extension to assert validation error for specific field.
 */
fun <T> Result<T>.assertValidationError(field: String) {
    assertThat(isFailure).isTrue()
    val error = exceptionOrNull() as ValidationException
    assertThat(error.field).isEqualTo(field)
}
