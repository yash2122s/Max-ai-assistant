package com.example.app.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.*
import java.io.IOException

/**
 * Template for testing Repository implementations.
 * 
 * This template demonstrates best practices for Repository testing:
 * - Offline-first patterns (local + remote)
 * - Cache invalidation and refresh
 * - Error handling and fallback
 * - Flow testing for reactive data
 * 
 * Test Coverage Goals:
 * - Happy path: 30% (successful operations)
 * - Offline scenarios: 25% (cache fallback, sync)
 * - Error handling: 25% (network, database, timeout)
 * - Edge cases: 20% (empty, concurrent, cancellation)
 * 
 * @see 01-architecture.md for Repository patterns
 * @see 14-offline-first.md for offline-first strategies
 * @see 11-testing.md for testing guidelines
 * 
 * Created & Reviewed by: TrongLB & AI Agents
 */

// ============================================================
// EXAMPLE MODELS AND INTERFACES
// ============================================================

// Domain Model
data class User(
    val id: String,
    val name: String,
    val email: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

// API Response DTO
data class UserDto(
    val id: String,
    val name: String,
    val email: String,
    val updated_at: Long,
) {
    fun toDomain() = User(
        id = id,
        name = name,
        email = email,
        updatedAt = updated_at,
    )
}

// Database Entity
data class UserEntity(
    val id: String,
    val name: String,
    val email: String,
    val updatedAt: Long,
    val cachedAt: Long = System.currentTimeMillis(),
) {
    fun toDomain() = User(
        id = id,
        name = name,
        email = email,
        updatedAt = updatedAt,
    )
    
    companion object {
        fun fromDomain(user: User, cachedAt: Long = System.currentTimeMillis()) = UserEntity(
            id = user.id,
            name = user.name,
            email = user.email,
            updatedAt = user.updatedAt,
            cachedAt = cachedAt,
        )
    }
}

// Remote API Interface
interface UserApi {
    suspend fun getUser(id: String): UserDto
    suspend fun updateUser(id: String, name: String, email: String): UserDto
    suspend fun getUsers(): List<UserDto>
}

// Local Database DAO
interface UserDao {
    suspend fun getUser(id: String): UserEntity?
    suspend fun insertUser(user: UserEntity)
    suspend fun updateUser(user: UserEntity)
    suspend fun deleteUser(id: String)
    suspend fun getAllUsers(): List<UserEntity>
    fun observeUser(id: String): kotlinx.coroutines.flow.Flow<UserEntity?>
    fun observeAllUsers(): kotlinx.coroutines.flow.Flow<List<UserEntity>>
}

// Repository Interface (Domain layer)
interface UserRepository {
    suspend fun getUser(id: String, forceRefresh: Boolean = false): Result<User>
    suspend fun updateUser(id: String, name: String, email: String): Result<User>
    fun observeUser(id: String): kotlinx.coroutines.flow.Flow<User?>
    suspend fun syncUsers(): Result<Unit>
}

// ============================================================
// REPOSITORY IMPLEMENTATION (for reference)
// ============================================================

/**
 * Offline-first Repository implementation.
 * 
 * Strategy:
 * 1. Check cache first (if not forcing refresh)
 * 2. If cache valid and not stale, return cached data
 * 3. If cache stale or forcing refresh, fetch from network
 * 4. Update cache with fresh data
 * 5. On network error, fallback to cache
 */
class UserRepositoryImpl(
    private val api: UserApi,
    private val dao: UserDao,
    private val cacheValidityMs: Long = 5 * 60 * 1000, // 5 minutes
) : UserRepository {
    
    override suspend fun getUser(id: String, forceRefresh: Boolean): Result<User> {
        // Check cache first
        if (!forceRefresh) {
            val cached = dao.getUser(id)
            if (cached != null && !isCacheStale(cached.cachedAt)) {
                return Result.success(cached.toDomain())
            }
        }
        
        // Fetch from network
        return try {
            val dto = api.getUser(id)
            val entity = UserEntity.fromDomain(dto.toDomain())
            dao.insertUser(entity)
            Result.success(dto.toDomain())
        } catch (e: CancellationException) {
            throw e  // Always rethrow CancellationException
        } catch (e: Exception) {
            // Fallback to cache on error
            dao.getUser(id)?.let { 
                Result.success(it.toDomain()) 
            } ?: Result.failure(e)
        }
    }
    
    override suspend fun updateUser(id: String, name: String, email: String): Result<User> {
        return try {
            val dto = api.updateUser(id, name, email)
            val entity = UserEntity.fromDomain(dto.toDomain())
            dao.updateUser(entity)
            Result.success(dto.toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override fun observeUser(id: String): kotlinx.coroutines.flow.Flow<User?> {
        return dao.observeUser(id).let { flow ->
            kotlinx.coroutines.flow.flow {
                flow.collect { entity ->
                    emit(entity?.toDomain())
                }
            }
        }
    }
    
    override suspend fun syncUsers(): Result<Unit> {
        return try {
            val dtos = api.getUsers()
            dtos.forEach { dto ->
                dao.insertUser(UserEntity.fromDomain(dto.toDomain()))
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun isCacheStale(cachedAt: Long): Boolean {
        return System.currentTimeMillis() - cachedAt > cacheValidityMs
    }
}

// ============================================================
// TEST SUITE
// ============================================================

@OptIn(ExperimentalCoroutinesApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRepositoryImplTest {
    
    // ========================================
    // Test Setup
    // ========================================
    
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var api: UserApi
    private lateinit var dao: UserDao
    private lateinit var repository: UserRepositoryImpl
    
    // Test data
    private val testUser = User(
        id = "user-1",
        name = "John Doe",
        email = "john@example.com",
        updatedAt = 1700000000000L
    )
    
    private val testUserDto = UserDto(
        id = "user-1",
        name = "John Doe",
        email = "john@example.com",
        updated_at = 1700000000000L
    )
    
    private val testUserEntity = UserEntity(
        id = "user-1",
        name = "John Doe",
        email = "john@example.com",
        updatedAt = 1700000000000L,
        cachedAt = System.currentTimeMillis()
    )
    
    @BeforeEach
    fun setup() {
        api = mockk()
        dao = mockk(relaxed = true)  // Relaxed for void functions
        repository = UserRepositoryImpl(
            api = api,
            dao = dao,
            cacheValidityMs = 5 * 60 * 1000  // 5 minutes
        )
    }
    
    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }
    
    // ========================================
    // getUser Tests - Happy Path (30%)
    // ========================================
    
    @Nested
    @DisplayName("getUser - Happy Path")
    inner class GetUserHappyPath {
        
        @Test
        fun `when cache is fresh then returns cached data without network call`() = runTest {
            // Given - Fresh cache (recently cached)
            val freshEntity = testUserEntity.copy(cachedAt = System.currentTimeMillis())
            coEvery { dao.getUser("user-1") } returns freshEntity
            
            // When
            val result = repository.getUser("user-1", forceRefresh = false)
            
            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo(testUser)
            
            // Verify no network call
            coVerify(exactly = 0) { api.getUser(any()) }
        }
        
        @Test
        fun `when forceRefresh is true then fetches from network`() = runTest {
            // Given
            coEvery { api.getUser("user-1") } returns testUserDto
            coEvery { dao.insertUser(any()) } just Runs
            
            // When
            val result = repository.getUser("user-1", forceRefresh = true)
            
            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.name).isEqualTo("John Doe")
            
            // Verify network call and cache update
            coVerify(exactly = 1) { api.getUser("user-1") }
            coVerify(exactly = 1) { dao.insertUser(any()) }
        }
        
        @Test
        fun `when cache is stale then fetches from network`() = runTest {
            // Given - Stale cache (old cachedAt)
            val staleEntity = testUserEntity.copy(
                cachedAt = System.currentTimeMillis() - 10 * 60 * 1000  // 10 min ago
            )
            coEvery { dao.getUser("user-1") } returns staleEntity
            coEvery { api.getUser("user-1") } returns testUserDto
            coEvery { dao.insertUser(any()) } just Runs
            
            // When
            val result = repository.getUser("user-1", forceRefresh = false)
            
            // Then
            assertThat(result.isSuccess).isTrue()
            coVerify(exactly = 1) { api.getUser("user-1") }
        }
    }
    
    // ========================================
    // getUser Tests - Offline Scenarios (25%)
    // ========================================
    
    @Nested
    @DisplayName("getUser - Offline Scenarios")
    inner class GetUserOfflineTests {
        
        @Test
        fun `when network fails and cache exists then returns cached data`() = runTest {
            // Given
            val staleEntity = testUserEntity.copy(
                cachedAt = System.currentTimeMillis() - 10 * 60 * 1000
            )
            coEvery { dao.getUser("user-1") } returns staleEntity
            coEvery { api.getUser("user-1") } throws IOException("No network")
            
            // When
            val result = repository.getUser("user-1", forceRefresh = false)
            
            // Then - Returns cached data as fallback
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.name).isEqualTo("John Doe")
        }
        
        @Test
        fun `when network fails and no cache then returns failure`() = runTest {
            // Given
            coEvery { dao.getUser("user-1") } returns null
            coEvery { api.getUser("user-1") } throws IOException("No network")
            
            // When
            val result = repository.getUser("user-1", forceRefresh = false)
            
            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        }
        
        @Test
        fun `when forceRefresh fails then falls back to cache`() = runTest {
            // Given
            coEvery { dao.getUser("user-1") } returns testUserEntity
            coEvery { api.getUser("user-1") } throws IOException("Network error")
            
            // When
            val result = repository.getUser("user-1", forceRefresh = true)
            
            // Then - Falls back to cache even with forceRefresh
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.name).isEqualTo("John Doe")
        }
    }
    
    // ========================================
    // getUser Tests - Error Handling (25%)
    // ========================================
    
    @Nested
    @DisplayName("getUser - Error Handling")
    inner class GetUserErrorTests {
        
        @Test
        fun `when CancellationException thrown then rethrows`() = runTest {
            // Given
            coEvery { dao.getUser("user-1") } returns null
            coEvery { api.getUser("user-1") } throws CancellationException()
            
            // When & Then
            assertThrows<CancellationException> {
                repository.getUser("user-1")
            }
        }
        
        @Test
        fun `when server returns 404 then returns failure`() = runTest {
            // Given
            coEvery { dao.getUser("user-1") } returns null
            coEvery { api.getUser("user-1") } throws HttpException(404, "Not found")
            
            // When
            val result = repository.getUser("user-1")
            
            // Then
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(HttpException::class.java)
        }
        
        @Test
        fun `when timeout occurs then returns cached data if available`() = runTest {
            // Given
            coEvery { dao.getUser("user-1") } returns testUserEntity
            coEvery { api.getUser("user-1") } throws java.net.SocketTimeoutException("Timeout")
            
            // When
            val result = repository.getUser("user-1")
            
            // Then - Fallback to cache
            assertThat(result.isSuccess).isTrue()
        }
    }
    
    // ========================================
    // updateUser Tests
    // ========================================
    
    @Nested
    @DisplayName("updateUser")
    inner class UpdateUserTests {
        
        @Test
        fun `when update succeeds then updates local cache`() = runTest {
            // Given
            val updatedDto = testUserDto.copy(name = "Jane Doe")
            coEvery { api.updateUser("user-1", "Jane Doe", "john@example.com") } returns updatedDto
            coEvery { dao.updateUser(any()) } just Runs
            
            // When
            val result = repository.updateUser("user-1", "Jane Doe", "john@example.com")
            
            // Then
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()?.name).isEqualTo("Jane Doe")
            
            // Verify cache was updated
            coVerify { dao.updateUser(match { it.name == "Jane Doe" }) }
        }
        
        @Test
        fun `when update fails then does not modify cache`() = runTest {
            // Given
            coEvery { api.updateUser(any(), any(), any()) } throws IOException("Network error")
            
            // When
            val result = repository.updateUser("user-1", "Jane Doe", "john@example.com")
            
            // Then
            assertThat(result.isFailure).isTrue()
            
            // Verify cache was not modified
            coVerify(exactly = 0) { dao.updateUser(any()) }
        }
        
        @Test
        fun `when CancellationException during update then rethrows`() = runTest {
            // Given
            coEvery { api.updateUser(any(), any(), any()) } throws CancellationException()
            
            // When & Then
            assertThrows<CancellationException> {
                repository.updateUser("user-1", "Jane", "jane@test.com")
            }
        }
    }
    
    // ========================================
    // observeUser Tests (Flow)
    // ========================================
    
    @Nested
    @DisplayName("observeUser - Flow Testing")
    inner class ObserveUserTests {
        
        @Test
        fun `when user exists then emits user`() = runTest {
            // Given
            coEvery { dao.observeUser("user-1") } returns flowOf(testUserEntity)
            
            // When
            val result = repository.observeUser("user-1").first()
            
            // Then
            assertThat(result).isNotNull()
            assertThat(result?.name).isEqualTo("John Doe")
        }
        
        @Test
        fun `when user not found then emits null`() = runTest {
            // Given
            coEvery { dao.observeUser("user-1") } returns flowOf(null)
            
            // When
            val result = repository.observeUser("user-1").first()
            
            // Then
            assertThat(result).isNull()
        }
        
        @Test
        fun `when user updates then emits new values`() = runTest {
            // Given
            val updatedEntity = testUserEntity.copy(name = "Updated Name")
            coEvery { dao.observeUser("user-1") } returns flowOf(testUserEntity, updatedEntity)
            
            // When
            val emissions = repository.observeUser("user-1").toList()
            
            // Then
            assertThat(emissions).hasSize(2)
            assertThat(emissions[0]?.name).isEqualTo("John Doe")
            assertThat(emissions[1]?.name).isEqualTo("Updated Name")
        }
    }
    
    // ========================================
    // syncUsers Tests
    // ========================================
    
    @Nested
    @DisplayName("syncUsers")
    inner class SyncUsersTests {
        
        @Test
        fun `when sync succeeds then updates all users in cache`() = runTest {
            // Given
            val users = listOf(
                testUserDto,
                testUserDto.copy(id = "user-2", name = "Jane Doe")
            )
            coEvery { api.getUsers() } returns users
            coEvery { dao.insertUser(any()) } just Runs
            
            // When
            val result = repository.syncUsers()
            
            // Then
            assertThat(result.isSuccess).isTrue()
            coVerify(exactly = 2) { dao.insertUser(any()) }
        }
        
        @Test
        fun `when sync fails then returns failure`() = runTest {
            // Given
            coEvery { api.getUsers() } throws IOException("Network error")
            
            // When
            val result = repository.syncUsers()
            
            // Then
            assertThat(result.isFailure).isTrue()
            coVerify(exactly = 0) { dao.insertUser(any()) }
        }
    }
    
    // ========================================
    // Edge Cases (20%)
    // ========================================
    
    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCaseTests {
        
        @Test
        fun `when cache is exactly at expiry boundary then fetches from network`() = runTest {
            // Given - Cache exactly at expiry time
            val boundaryEntity = testUserEntity.copy(
                cachedAt = System.currentTimeMillis() - 5 * 60 * 1000 - 1  // Just past 5 min
            )
            coEvery { dao.getUser("user-1") } returns boundaryEntity
            coEvery { api.getUser("user-1") } returns testUserDto
            coEvery { dao.insertUser(any()) } just Runs
            
            // When
            val result = repository.getUser("user-1")
            
            // Then - Should fetch from network
            coVerify(exactly = 1) { api.getUser("user-1") }
        }
        
        @Test
        fun `when empty user id then handles gracefully`() = runTest {
            // Given
            coEvery { dao.getUser("") } returns null
            coEvery { api.getUser("") } throws IllegalArgumentException("Invalid id")
            
            // When
            val result = repository.getUser("")
            
            // Then
            assertThat(result.isFailure).isTrue()
        }
        
        @Test
        fun `when concurrent getUser calls then handles correctly`() = runTest {
            // Given
            coEvery { dao.getUser("user-1") } returns null
            coEvery { api.getUser("user-1") } coAnswers {
                kotlinx.coroutines.delay(100)
                testUserDto
            }
            coEvery { dao.insertUser(any()) } just Runs
            
            // When - Concurrent calls
            val job1 = kotlinx.coroutines.async { repository.getUser("user-1") }
            val job2 = kotlinx.coroutines.async { repository.getUser("user-1") }
            
            val result1 = job1.await()
            val result2 = job2.await()
            
            // Then - Both should succeed
            assertThat(result1.isSuccess).isTrue()
            assertThat(result2.isSuccess).isTrue()
        }
    }
}

// ============================================================
// HELPER CLASSES
// ============================================================

class HttpException(val code: Int, message: String) : Exception(message)

// ============================================================
// TEST DATA BUILDERS (for complex test scenarios)
// ============================================================

/**
 * Builder for creating test User instances.
 */
class UserTestDataBuilder {
    private var id: String = "user-${System.currentTimeMillis()}"
    private var name: String = "Test User"
    private var email: String = "test@example.com"
    private var updatedAt: Long = System.currentTimeMillis()
    
    fun withId(id: String) = apply { this.id = id }
    fun withName(name: String) = apply { this.name = name }
    fun withEmail(email: String) = apply { this.email = email }
    fun withUpdatedAt(updatedAt: Long) = apply { this.updatedAt = updatedAt }
    
    fun build() = User(id, name, email, updatedAt)
    fun buildDto() = UserDto(id, name, email, updatedAt)
    fun buildEntity(cachedAt: Long = System.currentTimeMillis()) = 
        UserEntity(id, name, email, updatedAt, cachedAt)
}

fun testUser(block: UserTestDataBuilder.() -> Unit = {}) = 
    UserTestDataBuilder().apply(block).build()

fun testUserDto(block: UserTestDataBuilder.() -> Unit = {}) = 
    UserTestDataBuilder().apply(block).buildDto()

fun testUserEntity(
    cachedAt: Long = System.currentTimeMillis(),
    block: UserTestDataBuilder.() -> Unit = {}
) = UserTestDataBuilder().apply(block).buildEntity(cachedAt)
