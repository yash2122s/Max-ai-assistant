package com.example.memory.data

import androidx.room.Entity

@Entity(tableName = "memory_sync_state", primaryKeys = ["ownerKey", "id"])
data class MemorySyncState(
    val id: Int = 1,
    val lastWrittenFileHash: String? = null,
    val lastSyncTimestamp: Long = 0L,
    val lastSyncedMemoryCount: Int = 0,
    val syncStatus: String = "IDLE",
    val pendingChanges: String? = null,
    val ownerKey: String = "legacy"
)
