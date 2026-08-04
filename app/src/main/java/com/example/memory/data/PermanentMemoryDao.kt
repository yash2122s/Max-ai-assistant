package com.example.memory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PermanentMemoryDao {

    @Query("SELECT * FROM permanent_memory ORDER BY pinned DESC, updatedAt DESC")
    fun getAllFlow(): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory WHERE enabled = 1 ORDER BY pinned DESC, updatedAt DESC")
    fun getEnabledFlow(): Flow<List<PermanentMemory>>

    @Query("SELECT * FROM permanent_memory ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getAll(): List<PermanentMemory>

    @Query("SELECT * FROM permanent_memory WHERE enabled = 1 ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getEnabled(): List<PermanentMemory>

    @Query("""
        SELECT * FROM permanent_memory 
        WHERE enabled = 1 AND (
            title LIKE '%' || :query || '%' 
            OR content LIKE '%' || :query || '%' 
            OR category LIKE '%' || :query || '%'
        )
        ORDER BY pinned DESC, updatedAt DESC
    """)
    suspend fun searchUi(query: String): List<PermanentMemory>

    @Query("""
        SELECT * FROM permanent_memory 
        WHERE enabled = 1 AND (
            title LIKE '%' || :query || '%' 
            OR content LIKE '%' || :query || '%' 
            OR category LIKE '%' || :query || '%'
        )
        ORDER BY pinned DESC, updatedAt DESC
    """)
    fun searchFlow(query: String): Flow<List<PermanentMemory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: PermanentMemory): Long

    @Update
    suspend fun update(memory: PermanentMemory)

    @Query("DELETE FROM permanent_memory WHERE memoryId = :memoryId")
    suspend fun deleteById(memoryId: String)

    @Query("DELETE FROM permanent_memory")
    suspend fun deleteAll()

    @Query("UPDATE permanent_memory SET enabled = :enabled WHERE memoryId = :memoryId")
    suspend fun toggleEnabled(memoryId: String, enabled: Boolean)

    @Query("UPDATE permanent_memory SET pinned = :pinned WHERE memoryId = :memoryId")
    suspend fun togglePinned(memoryId: String, pinned: Boolean)

    @Query("UPDATE permanent_memory SET usageCount = usageCount + 1 WHERE memoryId = :memoryId")
    suspend fun incrementUsage(memoryId: String)

    @Query("UPDATE permanent_memory SET lastUsedAt = :timestamp WHERE memoryId = :memoryId")
    suspend fun updateLastUsed(memoryId: String, timestamp: Long)

    @Query("SELECT * FROM permanent_memory WHERE memoryId = :memoryId")
    suspend fun getById(memoryId: String): PermanentMemory?

    @Query("SELECT COUNT(*) FROM permanent_memory")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM permanent_memory WHERE pinned = 1")
    suspend fun getPinnedCount(): Int
}
