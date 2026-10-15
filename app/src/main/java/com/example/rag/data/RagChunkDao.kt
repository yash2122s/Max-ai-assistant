package com.example.rag.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RagChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: RagChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    @Query("SELECT * FROM rag_chunks WHERE (:ownerKey IS NULL OR ownerKey = :ownerKey) ORDER BY createdAt DESC")
    fun getAllFlow(ownerKey: String? = null): Flow<List<RagChunkEntity>>

    @Query("SELECT * FROM rag_chunks WHERE (:ownerKey IS NULL OR ownerKey = :ownerKey) ORDER BY createdAt DESC")
    suspend fun getAll(ownerKey: String? = null): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE memoryId = :memoryId AND (:ownerKey IS NULL OR ownerKey = :ownerKey) ORDER BY chunkIndex ASC")
    suspend fun getByMemoryId(memoryId: String, ownerKey: String? = null): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE contentHash = :hash AND (:ownerKey IS NULL OR ownerKey = :ownerKey)")
    suspend fun getByContentHash(hash: String, ownerKey: String? = null): List<RagChunkEntity>

    @Query("DELETE FROM rag_chunks WHERE memoryId = :memoryId AND (:ownerKey IS NULL OR ownerKey = :ownerKey)")
    suspend fun deleteByMemoryId(memoryId: String, ownerKey: String? = null): Int

    @Query("DELETE FROM rag_chunks WHERE (:ownerKey IS NULL OR ownerKey = :ownerKey)")
    suspend fun deleteAll(ownerKey: String? = null)

    @Query("SELECT COUNT(*) FROM rag_chunks WHERE (:ownerKey IS NULL OR ownerKey = :ownerKey)")
    suspend fun getChunkCount(ownerKey: String? = null): Int

    @Query("SELECT COUNT(DISTINCT memoryId) FROM rag_chunks WHERE (:ownerKey IS NULL OR ownerKey = :ownerKey)")
    suspend fun getDistinctMemoryCount(ownerKey: String? = null): Int

    @Query("""
        SELECT * FROM rag_chunks 
        WHERE (:ownerKey IS NULL OR ownerKey = :ownerKey) AND (content LIKE '%' || :keyword || '%' 
           OR tags LIKE '%' || :keyword || '%' 
           OR category LIKE '%' || :keyword || '%')
        ORDER BY updatedAt DESC LIMIT :limit
    """)
    suspend fun searchByKeyword(keyword: String, limit: Int = 30, ownerKey: String? = null): List<RagChunkEntity>
}
