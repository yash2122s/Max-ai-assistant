package com.example.memory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemorySyncStateDao {
    @Query("SELECT * FROM memory_sync_state WHERE id = 1 AND ownerKey = :ownerKey LIMIT 1")
    suspend fun getSyncState(ownerKey: String = "legacy"): MemorySyncState?

    @Query("SELECT * FROM memory_sync_state WHERE id = 1 AND ownerKey = :ownerKey LIMIT 1")
    fun getSyncStateFlow(ownerKey: String): Flow<MemorySyncState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: MemorySyncState)

    @Query("UPDATE memory_sync_state SET syncStatus = :status WHERE id = 1 AND ownerKey = :ownerKey")
    suspend fun updateStatus(status: String, ownerKey: String)

    @Query("UPDATE memory_sync_state SET lastWrittenFileHash = :hash, lastSyncTimestamp = :timestamp, lastSyncedMemoryCount = :count, syncStatus = :status WHERE id = 1 AND ownerKey = :ownerKey")
    suspend fun updateSyncRecord(hash: String?, timestamp: Long, count: Int, status: String = "IDLE", ownerKey: String)

    @Query("UPDATE memory_sync_state SET pendingChanges = :pendingJson WHERE id = 1 AND ownerKey = :ownerKey")
    suspend fun updatePendingChanges(pendingJson: String?, ownerKey: String)
}
