package com.example.memory.account

import android.content.Context
import androidx.room.withTransaction
import com.example.data.local.AppDatabase
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.memory.security.MemoryCryptography
import com.example.memory.sync.MemorySyncQueueCoordinator

/** Explicit quarantine review. Nothing is assigned until the user confirms selected IDs. */
class LegacyMemoryReview(context: Context) {
    private val db = AppDatabase.getDatabase(context.applicationContext)
    private val dao = db.permanentMemoryDao()

    suspend fun unassignedRecords(): List<PermanentMemory> = withContext(Dispatchers.IO) {
        dao.getAll(MemoryAccountScope.LEGACY)
    }

    suspend fun confirmSelected(
        memoryIds: List<String>,
        destination: String,
        previewSession: MemorySession
    ): Int = withContext(Dispatchers.IO) {
        require(destination != MemoryAccountScope.LEGACY)
        MemoryAccountScope.requireCurrent(previewSession)
        val selected = dao.getAll(MemoryAccountScope.LEGACY).filter { it.memoryId in memoryIds }
        if (selected.size != memoryIds.toSet().size) return@withContext 0
        val stamped = selected.map { it.copy(ownerKey = destination) }
        MemoryAccountScope.requireCurrent(previewSession)
        db.withTransaction {
            dao.insertAll(stamped)
            memoryIds.forEach { dao.deleteById(it, MemoryAccountScope.LEGACY) }
        }
        val revision = memoryIds.hashCode().toLong().let { if (it < 0) -it else it }
        stamped.forEach { encrypted ->
            val plain = encrypted.copy(
                title = MemoryCryptography.decrypt(encrypted.title),
                content = MemoryCryptography.decrypt(encrypted.content),
                tags = if (encrypted.tags.isNotBlank()) MemoryCryptography.decrypt(encrypted.tags) else encrypted.tags
            )
            MemorySyncQueueCoordinator.create(plain, revision)
        }
        stamped.size
    }
}
