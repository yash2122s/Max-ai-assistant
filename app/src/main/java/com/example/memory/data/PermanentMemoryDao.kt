package com.example.memory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import com.example.memory.account.MemoryAccountScope

@Dao
interface PermanentMemoryDao {

    @Query("SELECT * FROM permanent_memory WHERE ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC")
    fun getAllFlow(ownerKey: String): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE isPrivate = 0 AND ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC")
    fun getAgentMemoriesFlow(ownerKey: String): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE isPrivate = 1 AND ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC")
    fun getPrivateMemoriesFlow(ownerKey: String): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE enabled = 1 AND ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC")
    fun getEnabledFlow(ownerKey: String): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE enabled = 1 AND ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    fun getTopMemoriesForPromptFlow(limit: Int = 30, ownerKey: String): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getAll(ownerKey: String = MemoryAccountScope.LEGACY): List<PermanentMemory>

    @Query("SELECT * FROM permanent_memory WHERE enabled = 1 AND ownerKey = :ownerKey ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getEnabled(ownerKey: String = MemoryAccountScope.LEGACY): List<PermanentMemory>

    @Query("""
        SELECT * FROM permanent_memory 
        WHERE ownerKey = :ownerKey AND enabled = 1 AND (
            title LIKE '%' || :query || '%' 
            OR content LIKE '%' || :query || '%' 
            OR category LIKE '%' || :query || '%'
            OR tags LIKE '%' || :query || '%'
        )
        ORDER BY pinned DESC, updatedAt DESC
    """)
    suspend fun searchUi(query: String, ownerKey: String = MemoryAccountScope.LEGACY): List<PermanentMemory>

    @Query("""
        SELECT * FROM permanent_memory 
        WHERE ownerKey = :ownerKey AND enabled = 1 AND (
            title LIKE '%' || :query || '%' 
            OR content LIKE '%' || :query || '%' 
            OR category LIKE '%' || :query || '%'
            OR tags LIKE '%' || :query || '%'
        )
        ORDER BY pinned DESC, updatedAt DESC
    """)
    fun searchFlow(query: String, ownerKey: String): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE enabled = 1 AND ownerKey = :ownerKey AND tags LIKE '%' || :tag || '%' ORDER BY pinned DESC, updatedAt DESC")
    fun getByTagFlow(tag: String, ownerKey: String): Flow<List<PermanentMemory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: PermanentMemory): Long

    @Update
    suspend fun update(memory: PermanentMemory)

    @Query("DELETE FROM permanent_memory WHERE isPrivate = :isPrivate AND ownerKey = :ownerKey")
    suspend fun deleteBySpace(isPrivate: Int, ownerKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<PermanentMemory>)

    @Query("DELETE FROM permanent_memory WHERE memoryId = :memoryId AND ownerKey = :ownerKey")
    suspend fun deleteById(memoryId: String, ownerKey: String)

    @Query("DELETE FROM permanent_memory WHERE ownerKey = :ownerKey")
    suspend fun deleteAll(ownerKey: String = MemoryAccountScope.LEGACY)

    @Query("UPDATE permanent_memory SET enabled = :enabled WHERE memoryId = :memoryId AND ownerKey = :ownerKey")
    suspend fun toggleEnabled(memoryId: String, enabled: Boolean, ownerKey: String)

    @Query("UPDATE permanent_memory SET pinned = :pinned WHERE memoryId = :memoryId AND ownerKey = :ownerKey")
    suspend fun togglePinned(memoryId: String, pinned: Boolean, ownerKey: String)

    @Query("UPDATE permanent_memory SET usageCount = usageCount + 1 WHERE memoryId = :memoryId AND ownerKey = :ownerKey")
    suspend fun incrementUsage(memoryId: String, ownerKey: String)

    @Query("UPDATE permanent_memory SET lastUsedAt = :timestamp WHERE memoryId = :memoryId AND ownerKey = :ownerKey")
    suspend fun updateLastUsed(memoryId: String, timestamp: Long, ownerKey: String)

    @Query("SELECT * FROM permanent_memory WHERE memoryId = :memoryId AND ownerKey = :ownerKey")
    suspend fun getById(memoryId: String, ownerKey: String): PermanentMemory?

    @Query("SELECT COUNT(*) FROM permanent_memory WHERE ownerKey = :ownerKey")
    suspend fun getCount(ownerKey: String = MemoryAccountScope.LEGACY): Int

    @Query("SELECT COUNT(*) FROM permanent_memory WHERE pinned = 1 AND ownerKey = :ownerKey")
    suspend fun getPinnedCount(ownerKey: String = MemoryAccountScope.LEGACY): Int

    // -- Universal Import & Sync Extension Queries ------------------------------

    @Query("SELECT * FROM permanent_memory WHERE importChecksum = :checksum AND ownerKey = :ownerKey LIMIT 1")
    suspend fun getByImportChecksum(checksum: String, ownerKey: String): PermanentMemory?

    @Query("SELECT * FROM permanent_memory WHERE provider = :provider AND sourceRecordId = :sourceRecordId AND ownerKey = :ownerKey LIMIT 1")
    suspend fun getBySourceRecordId(provider: String, sourceRecordId: String, ownerKey: String): PermanentMemory?

    @Query("SELECT * FROM permanent_memory WHERE contentHash = :hash AND ownerKey = :ownerKey LIMIT 1")
    suspend fun getByContentHash(hash: String, ownerKey: String = MemoryAccountScope.LEGACY): PermanentMemory?

    @Query("SELECT importChecksum FROM permanent_memory WHERE importChecksum IS NOT NULL AND ownerKey = :ownerKey")
    suspend fun getAllImportChecksums(ownerKey: String): List<String>
}
