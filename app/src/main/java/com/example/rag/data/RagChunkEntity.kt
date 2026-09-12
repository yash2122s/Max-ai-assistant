package com.example.rag.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "rag_chunks",
    indices = [
        Index(value = ["memoryId"]),
        Index(value = ["contentHash"]),
        Index(value = ["memoryId", "chunkIndex"], unique = true)
    ]
)
data class RagChunkEntity(
    @PrimaryKey
    val chunkId: String = UUID.randomUUID().toString(),
    val memoryId: String,
    val chunkIndex: Int,
    val content: String,
    val contentHash: String,
    val embedding: FloatArray? = null,
    val embeddingModel: String = "local-hash-v1",
    val embeddingDimension: Int = 128,
    val category: String = "Personal",
    val tags: String = "",
    val provider: String? = null,
    val sourceRecordId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RagChunkEntity

        if (chunkId != other.chunkId) return false
        if (memoryId != other.memoryId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (content != other.content) return false
        if (contentHash != other.contentHash) return false
        if (embeddingModel != other.embeddingModel) return false
        if (embeddingDimension != other.embeddingDimension) return false
        if (category != other.category) return false
        if (tags != other.tags) return false
        if (provider != other.provider) return false
        if (sourceRecordId != other.sourceRecordId) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false

        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + memoryId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + content.hashCode()
        result = 31 * result + contentHash.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
