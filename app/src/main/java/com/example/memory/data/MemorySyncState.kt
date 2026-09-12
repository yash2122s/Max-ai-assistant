package com.example.memory.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_sync_state")
data class MemorySyncState(
    @PrimaryKey val id: Int = 1,
    val lastWrittenFileHash: String? = null,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncedMemoryCount: Int = 0,
    val syncStatus: String = "IDLE",
    val pendingChanges: String? = null
)
