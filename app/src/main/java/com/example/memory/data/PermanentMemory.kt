package com.example.memory.data

import androidx.room.Entity
import androidx.room.Index
import java.security.MessageDigest
import java.util.UUID

@Entity(
    tableName = "permanent_memory",
    primaryKeys = ["ownerKey", "memoryId"],
    indices = [
        Index(value = ["ownerKey", "pinned"]),
        Index(value = ["ownerKey", "enabled"]),
        Index(value = ["ownerKey", "category"]),
        Index(value = ["ownerKey", "type"]),
        Index(value = ["ownerKey", "importChecksum"]),
        Index(value = ["ownerKey", "sourceRecordId"])
    ]
)
data class PermanentMemory(
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
    val embedding: FloatArray? = null,
    val isPrivate: Boolean = false,
    val sourceRecordId: String? = null,
    val importChecksum: String? = null,
    val provider: String? = null,
    val confidence: Float = 1.0f,
    val extractedAt: Long? = null,
    val lastSyncAt: Long? = null,
    val contentHash: String? = null,
    val metadataJson: String? = null,
    val ownerKey: String = "legacy"
) {
    companion object {
        fun computeContentHash(title: String, content: String, category: String, tags: String = ""): String {
            val normalized = "${title.trim()}|${content.trim()}|${category.trim().uppercase()}|${tags.trim()}"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        fun computeImportChecksum(rawContent: String): String {
            val normalized = rawContent.trim()
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    fun effectiveContentHash(): String {
        return contentHash ?: computeContentHash(title, content, category, tags)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PermanentMemory

        if (memoryId != other.memoryId) return false
        if (ownerKey != other.ownerKey) return false
        if (title != other.title) return false
        if (content != other.content) return false
        if (category != other.category) return false
        if (type != other.type) return false
        if (source != other.source) return false
        if (pinned != other.pinned) return false
        if (enabled != other.enabled) return false
        if (isPrivate != other.isPrivate) return false
        if (usageCount != other.usageCount) return false
        if (lastUsedAt != other.lastUsedAt) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (sourceRecordId != other.sourceRecordId) return false
        if (importChecksum != other.importChecksum) return false
        if (provider != other.provider) return false
        if (confidence != other.confidence) return false
        if (extractedAt != other.extractedAt) return false
        if (lastSyncAt != other.lastSyncAt) return false
        if (contentHash != other.contentHash) return false
        if (metadataJson != other.metadataJson) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + ownerKey.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + pinned.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + isPrivate.hashCode()
        result = 31 * result + usageCount
        result = 31 * result + (lastUsedAt?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + (sourceRecordId?.hashCode() ?: 0)
        result = 31 * result + (importChecksum?.hashCode() ?: 0)
        result = 31 * result + (provider?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        result = 31 * result + (extractedAt?.hashCode() ?: 0)
        result = 31 * result + (lastSyncAt?.hashCode() ?: 0)
        result = 31 * result + (contentHash?.hashCode() ?: 0)
        result = 31 * result + (metadataJson?.hashCode() ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
