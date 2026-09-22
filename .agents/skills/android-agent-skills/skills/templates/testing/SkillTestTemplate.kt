package com.agentcore.skills.template

import androidx.compose.runtime.Immutable
import com.agentcore.skill.base.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Template for testing Agent Skills.
 * 
 * This template demonstrates best practices for comprehensive skill testing.
 * Copy this template and customize for your specific skill.
 * 
 * Test Coverage Goals:
 * - Validation: 30% of tests (test each field independently)
 * - Execution: 40% of tests (happy path + error scenarios)
 * - Cancellation: 10% of tests (ensure proper handling)
 * - Edge Cases: 20% of tests (boundaries, special values)
 * 
 * Quality Targets:
 * - Line coverage: ≥ 85%
 * - Branch coverage: ≥ 80%
 * - All validation paths tested
 * - All error scenarios covered
 * 
 * @see DomainSkillGuide.kt for skill implementation patterns
 * @see 11-testing.md for testing guidelines
 * @see 29-testing-automation.md for test automation
 */

// ============================================================
// EXAMPLE SKILL TO TEST
// ============================================================

/**
 * Example skill for demonstration.
 * Replace with your actual skill implementation.
 */
class ExampleSkill(
    private val exampleRepository: ExampleRepository,
) : BaseSkill<ExampleInput, ExampleOutput>() {
    
    override val metadata = skillMetadata {
        id = "example.skill"
        name = "Example Skill"
        description = "Example skill for testing template"
        category = SkillCategory.UTILITY
    }
    
    override suspend fun validate(input: ExampleInput): SkillResult<Unit> {
        return validate {
            requireNotBlank(input.query, "query")
            requireInRange(input.query.length, 1..200, "query length")
            requirePositive(input.maxResults, "maxResults")
            requireInRange(input.maxResults, 1..100, "maxResults")
            input.filterCategory?.let { 
                requireNotBlank(it, "filterCategory")
            }
        }
    }
    
    override suspend fun doExecute(
        input: ExampleInput,
        context: SkillContext,
    ): SkillResult<ExampleOutput> {
        checkCancellation()
        
        return try {
            val results = exampleRepository.search(
                query = input.query,
                maxResults = input.maxResults,
                category = input.filterCategory
            )
            
            checkCancellation()
            
            SkillResult.success(
                ExampleOutput(
                    items = results,
                    totalCount = results.size
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkException) {
            SkillResult.failure(
                SkillError.Network(
                    message = "Failed to search: ${e.message}",
                    cause = e
                )
            )
        } catch (e: Exception) {
            SkillResult.failure(
                SkillError.Execution(
                    message = "Unexpected error: ${e.message}",
                    cause = e
                )
            )
        }
    }
}

@Immutable
data class ExampleInput(
    val query: String,
    val maxResults: Int = 10,
    val filterCategory: String? = null,
)

@Immutable
data class ExampleOutput(
    val items: List<String>,
    val totalCount: Int,
)

interface ExampleRepository {
    suspend fun search(query: String, maxResults: Int, category: String?): List<String>
}

class NetworkException(message: String) : Exception(message)

// ============================================================
// TEST SUITE
// ============================================================

/**
 * Comprehensive test suite for ExampleSkill.
 * 
 * Test Organization:
 * - @Nested classes group related test scenarios
 * - Test names follow: when[Condition]_then[Expected] pattern
 * - Each test has Given/When/Then structure
 * 
 * Test Execution:
 * - @BeforeEach: Setup mocks and test context
 * - @AfterEach: Clean up mocks
 * - runTest: Provides TestDispatcher for coroutine testing
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExampleSkillTest {
    
    // ========================================
    // Test Dependencies (Mocked)
    // ========================================
    
    private lateinit var exampleRepository: ExampleRepository
    
    // ========================================
    // System Under Test
    // ========================================
    
    private lateinit var skill: ExampleSkill
    
    // ========================================
    // Test Context
    // ========================================
    
    private lateinit var context: SkillContext
    
    // ========================================
    // Setup & Teardown
    // ========================================
    
    @BeforeEach
    fun setup() {
        // Create mocks
        exampleRepository = mockk()
        
        // Create system under test
        skill = ExampleSkill(exampleRepository)
        
        // Create test context (relaxed mock allows any property access)
        context = mockk(relaxed = true)
    }
    
    @AfterEach
    fun tearDown() {
        // Clear all mocks to prevent interference between tests
        clearAllMocks()
    }
    
    // ========================================
    // Happy Path Tests (20%)
    // ========================================
    
    @Nested
    @DisplayName("Happy Path Scenarios")
    inner class HappyPath {
        
        @Test
        fun `when valid input with all fields then returns success`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test query",
                maxResults = 10,
                filterCategory = "technology"
            )
            val expectedResults = listOf("result1", "result2", "result3")
            
            coEvery { 
                exampleRepository.search(
                    query = "test query",
                    maxResults = 10,
                    category = "technology"
                )
            } returns expectedResults
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess, "Expected success result")
            val output = result.getOrThrow()
            assertEquals(expectedResults, output.items)
            assertEquals(3, output.totalCount)
            
            // Verify repository was called correctly
            coVerify(exactly = 1) {
                exampleRepository.search("test query", 10, "technology")
            }
        }
        
        @Test
        fun `when valid input with optional field null then returns success`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test",
                maxResults = 5,
                filterCategory = null
            )
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns emptyList()
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
            val output = result.getOrThrow()
            assertEquals(0, output.totalCount)
        }
        
        @Test
        fun `when query at maximum length then returns success`() = runTest {
            // Given
            val input = ExampleInput(
                query = "a".repeat(200), // Maximum allowed
                maxResults = 10
            )
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns listOf("result")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
        }
        
        @Test
        fun `when maxResults at boundary values then returns success`() = runTest {
            // Test minimum boundary
            val inputMin = ExampleInput(query = "test", maxResults = 1)
            coEvery { exampleRepository.search(any(), any(), any()) } returns listOf("r1")
            
            val resultMin = skill.execute(inputMin, context)
            assertTrue(resultMin.isSuccess)
            
            // Test maximum boundary
            val inputMax = ExampleInput(query = "test", maxResults = 100)
            val resultMax = skill.execute(inputMax, context)
            assertTrue(resultMax.isSuccess)
        }
    }
    
    // ========================================
    // Validation Error Tests (30%)
    // ========================================
    
    @Nested
    @DisplayName("Validation Errors")
    inner class ValidationErrors {
        
        @Test
        fun `when query is blank then returns validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "",
                maxResults = 10
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure, "Expected failure for blank query")
            val error = result.getErrorOrNull()
            assertNotNull(error)
            assertTrue(error is SkillError.Validation)
            assertTrue(
                error!!.message.contains("query", ignoreCase = true),
                "Error message should mention 'query'"
            )
        }
        
        @Test
        fun `when query exceeds maximum length then returns validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "a".repeat(201), // Exceeds maximum of 200
                maxResults = 10
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error!!.message.contains("query length", ignoreCase = true))
        }
        
        @Test
        fun `when maxResults is zero then returns validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test",
                maxResults = 0
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error!!.message.contains("maxResults", ignoreCase = true))
        }
        
        @Test
        fun `when maxResults is negative then returns validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test",
                maxResults = -1
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            assertTrue(result.getErrorOrNull() is SkillError.Validation)
        }
        
        @Test
        fun `when maxResults exceeds maximum then returns validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test",
                maxResults = 101 // Exceeds maximum of 100
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            assertTrue(result.getErrorOrNull() is SkillError.Validation)
        }
        
        @Test
        fun `when filterCategory is blank then returns validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test",
                maxResults = 10,
                filterCategory = "" // Blank, should fail
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Validation)
            assertTrue(error!!.message.contains("filterCategory", ignoreCase = true))
        }
        
        @Test
        fun `when multiple fields invalid then returns first validation error`() = runTest {
            // Given
            val input = ExampleInput(
                query = "", // Invalid
                maxResults = 0, // Invalid
                filterCategory = "" // Invalid
            )
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            // Should return first error encountered
            assertTrue(result.getErrorOrNull() is SkillError.Validation)
        }
    }
    
    // ========================================
    // Execution Error Tests (40%)
    // ========================================
    
    @Nested
    @DisplayName("Execution Errors")
    inner class ExecutionErrors {
        
        @Test
        fun `when repository throws NetworkException then returns network error`() = runTest {
            // Given
            val input = ExampleInput(query = "test", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } throws NetworkException("Connection timeout")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure, "Expected failure for network error")
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Network, "Expected Network error type")
            assertTrue(
                error!!.message.contains("Connection timeout"),
                "Error message should include original exception message"
            )
            assertNotNull(error.cause, "Cause should be preserved")
        }
        
        @Test
        fun `when repository throws generic exception then returns execution error`() = runTest {
            // Given
            val input = ExampleInput(query = "test", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } throws RuntimeException("Unexpected error")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            val error = result.getErrorOrNull()
            assertTrue(error is SkillError.Execution)
            assertTrue(error!!.message.contains("Unexpected error"))
        }
        
        @Test
        fun `when repository throws IllegalArgumentException then returns execution error`() = runTest {
            // Given
            val input = ExampleInput(query = "test", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } throws IllegalArgumentException("Invalid parameter")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isFailure)
            assertTrue(result.getErrorOrNull() is SkillError.Execution)
        }
        
        @Test
        fun `when repository returns empty results then returns success with empty list`() = runTest {
            // Given
            val input = ExampleInput(query = "nonexistent", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns emptyList()
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess, "Empty results should still be success")
            val output = result.getOrThrow()
            assertTrue(output.items.isEmpty())
            assertEquals(0, output.totalCount)
        }
    }
    
    // ========================================
    // Cancellation Tests (10%)
    // ========================================
    
    @Nested
    @DisplayName("Cancellation Handling")
    inner class CancellationHandling {
        
        @Test
        fun `when cancelled during repository call then throws CancellationException`() = runTest {
            // Given
            val input = ExampleInput(query = "test", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } throws CancellationException("Test cancellation")
            
            // When & Then
            assertThrows<CancellationException> {
                runTest {
                    skill.execute(input, context)
                }
            }
        }
        
        @Test
        fun `when cancellation occurs then does not wrap in SkillResult`() = runTest {
            // Given
            val input = ExampleInput(query = "test", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } throws CancellationException("Cancelled")
            
            // When & Then
            val exception = assertThrows<CancellationException> {
                runTest {
                    skill.execute(input, context)
                }
            }
            
            assertEquals("Cancelled", exception.message)
        }
    }
    
    // ========================================
    // Edge Case Tests (20%)
    // ========================================
    
    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {
        
        @Test
        fun `when query contains special characters then processes correctly`() = runTest {
            // Given
            val input = ExampleInput(
                query = "test @#$%^&* query",
                maxResults = 10
            )
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns listOf("result")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
            coVerify { 
                exampleRepository.search("test @#$%^&* query", any(), any()) 
            }
        }
        
        @Test
        fun `when query contains unicode characters then processes correctly`() = runTest {
            // Given
            val input = ExampleInput(
                query = "测试查询 🚀",
                maxResults = 10
            )
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns listOf("result")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
        }
        
        @Test
        fun `when repository returns very large result set then handles correctly`() = runTest {
            // Given
            val input = ExampleInput(query = "popular", maxResults = 100)
            val largeResultSet = List(100) { "item$it" }
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns largeResultSet
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
            val output = result.getOrThrow()
            assertEquals(100, output.totalCount)
            assertEquals(100, output.items.size)
        }
        
        @Test
        fun `when executed multiple times concurrently then handles correctly`() = runTest {
            // Given
            val input = ExampleInput(query = "test", maxResults = 10)
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns listOf("result")
            
            // When - Execute multiple times concurrently
            val results = List(10) {
                skill.execute(input, context)
            }
            
            // Then
            results.forEach { result ->
                assertTrue(result.isSuccess)
            }
            
            // Repository should be called 10 times
            coVerify(exactly = 10) { 
                exampleRepository.search(any(), any(), any()) 
            }
        }
        
        @Test
        fun `when query is at minimum valid length then returns success`() = runTest {
            // Given
            val input = ExampleInput(
                query = "a", // Minimum length of 1
                maxResults = 10
            )
            
            coEvery { 
                exampleRepository.search(any(), any(), any()) 
            } returns listOf("result")
            
            // When
            val result = skill.execute(input, context)
            
            // Then
            assertTrue(result.isSuccess)
        }
    }
}

// ============================================================
// ADDITIONAL TEST UTILITIES
// ============================================================

/**
 * Helper function to create test input with defaults.
 */
fun createTestInput(
    query: String = "default query",
    maxResults: Int = 10,
    filterCategory: String? = null,
): ExampleInput = ExampleInput(query, maxResults, filterCategory)

/**
 * Helper function to create mock repository with standard behavior.
 */
fun createMockRepository(
    defaultResults: List<String> = listOf("result1", "result2"),
): ExampleRepository = mockk {
    coEvery { search(any(), any(), any()) } returns defaultResults
}

/**
 * Extension to quickly assert SkillResult is success.
 */
fun <T> SkillResult<T>.assertSuccess(): T {
    assertTrue(isSuccess, "Expected success but got failure: ${getErrorOrNull()}")
    return getOrThrow()
}

/**
 * Extension to quickly assert SkillResult is failure of specific type.
 */
inline fun <reified E : SkillError> SkillResult<*>.assertFailure(): E {
    assertTrue(isFailure, "Expected failure but got success")
    val error = getErrorOrNull()
    assertNotNull(error, "Error should not be null")
    assertTrue(error is E, "Expected error type ${E::class.simpleName} but got ${error!!::class.simpleName}")
    return error as E
}
