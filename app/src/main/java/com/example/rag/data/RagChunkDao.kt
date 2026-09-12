package com.example.rag.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RagChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: RagChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    @Query("SELECT * FROM rag_chunks ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<RagChunkEntity>>

    @Query("SELECT * FROM rag_chunks ORDER BY createdAt DESC")
    suspend fun getAll(): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE memoryId = :memoryId ORDER BY chunkIndex ASC")
    suspend fun getByMemoryId(memoryId: String): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE contentHash = :hash")
    suspend fun getByContentHash(hash: String): List<RagChunkEntity>

    @Query("DELETE FROM rag_chunks WHERE memoryId = :memoryId")
    suspend fun deleteByMemoryId(memoryId: String): Int

    @Query("DELETE FROM rag_chunks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun getChunkCount(): Int

    @Query("SELECT COUNT(DISTINCT memoryId) FROM rag_chunks")
    suspend fun getDistinctMemoryCount(): Int

    @Query("""
        SELECT * FROM rag_chunks 
        WHERE content LIKE '%' || :keyword || '%' 
           OR tags LIKE '%' || :keyword || '%' 
           OR category LIKE '%' || :keyword || '%'
        ORDER BY updatedAt DESC LIMIT :limit
    """)
    suspend fun searchByKeyword(keyword: String, limit: Int = 30): List<RagChunkEntity>
}
