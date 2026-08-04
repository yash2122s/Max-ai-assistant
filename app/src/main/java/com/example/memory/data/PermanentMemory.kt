package com.example.memory.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import java.util.UUID

@Entity(
    tableName = "permanent_memory",
    indices = [
        Index(value = ["pinned"]),
        Index(value = ["enabled"]),
        Index(value = ["category"]),
        Index(value = ["type"])
    ]
)
data class PermanentMemory(
    @PrimaryKey
    val memoryId: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val category: String,
    val type: MemoryType,
    val source: MemorySource = MemorySource.MANUAL,
    val tags: String = "",
    val pinned: Boolean = false,
    val enabled: Boolean = true,
    val usageCount: Int = 0,
    val lastUsedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PermanentMemory

        if (memoryId != other.memoryId) return false
        if (title != other.title) return false
        if (content != other.content) return false
        if (category != other.category) return false
        if (type != other.type) return false
        if (source != other.source) return false
        if (pinned != other.pinned) return false
        if (enabled != other.enabled) return false
        if (usageCount != other.usageCount) return false
        if (lastUsedAt != other.lastUsedAt) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + pinned.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + usageCount
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
