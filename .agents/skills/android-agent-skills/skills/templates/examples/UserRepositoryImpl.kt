package com.agentcore.examples

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
// import javax.inject.Inject // Uncomment for Hilt

/*
 * ============================================================================
 * 📚 TEMPLATE FILE - Reference for AI Code Generation
 * ============================================================================
 * 
 * This is an EXAMPLE showing the correct Repository implementation pattern.
 * When generating new repositories, follow this pattern with your own entity names.
 * 
 * FILE NAMING CONVENTION:
 *   Template:   [Entity]RepositoryImpl.kt
 *   Example:    UserRepositoryImpl.kt, ProductRepositoryImpl.kt
 * 
 * CLASS NAMING CONVENTION:
 *   Interface:  [Entity]Repository (in domain layer)
 *   Implementation: [Entity]RepositoryImpl (in data layer)
 * 
 * @see DomainSkillGuide.kt for the interface definition (UserRepository)
 * ============================================================================
 */

/**
 * Standard Repository Implementation Pattern.
 * 
 * This implements the [UserRepository] interface defined in [DomainSkillGuide.kt].
 * It fills the gap between the Domain definitions and the DI setup.
 * 
 * Key Patterns:
 * 1. Inject Dispatchers (don't hardcode)
 * 2. Use Result<T> for error handling
 * 3. Use runCatching for safety
 * 4. Apply flowOn for Flow operations
 */

// Implementation (Data Layer)
class UserRepositoryImpl /* @Inject constructor(
    // private val remoteDataSource: UserRemoteDataSource,
    // private val localDataSource: UserLocalDao,
    // @Dispatcher(IoDispatcher) private val ioDispatcher: CoroutineDispatcher,
) */ : UserRepository {

    // Default dispatcher for template valid compilation
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO 

    override suspend fun getUser(userId: String): Result<UserData> = withContext(ioDispatcher) {
        runCatching {
            // Simulator:
            // val dto = remoteDataSource.getUser(userId)
            // localDataSource.save(dto.toEntity())
            // dto.toDomain()
            
            UserData(
                id = userId,
                name = "Mock User",
                email = "mock@example.com"
            )
        }
    }

    override suspend fun updateUser(user: UserData): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            // remoteDataSource.updateUser(user.toDto())
            // localDataSource.save(user.toEntity())
        }
    }
}
