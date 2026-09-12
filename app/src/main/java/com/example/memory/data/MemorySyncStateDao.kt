package com.example.memory.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemorySyncStateDao {
    @Query("SELECT * FROM memory_sync_state WHERE id = 1 LIMIT 1")
    suspend fun getSyncState(): MemorySyncState?

    @Query("SELECT * FROM memory_sync_state WHERE id = 1 LIMIT 1")
    fun getSyncStateFlow(): Flow<MemorySyncState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: MemorySyncState)

    @Query("UPDATE memory_sync_state SET syncStatus = :status WHERE id = 1")
    suspend fun updateStatus(status: String)

    @Query("UPDATE memory_sync_state SET lastWrittenFileHash = :hash, lastSyncTimestamp = :timestamp, lastSyncedMemoryCount = :count, syncStatus = :status WHERE id = 1")
    suspend fun updateSyncRecord(hash: String?, timestamp: Long, count: Int, status: String = "IDLE")

    @Query("UPDATE memory_sync_state SET pendingChanges = :pendingJson WHERE id = 1")
    suspend fun updatePendingChanges(pendingJson: String?)
}
