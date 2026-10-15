package com.example.memory.sync

import androidx.room.*
import com.example.memory.data.PermanentMemory
import com.example.memory.data.MemoryType
import org.json.JSONObject
import java.util.UUID

enum class MemoryConflictChoice { LOCAL, REMOTE, MERGE, CANCEL }
enum class MemoryConflictState { UNRESOLVED, RESOLVING, RESOLVED, CANCELLED, FAILED }

@Entity(tableName = "memory_conflicts", primaryKeys = ["ownerKey", "conflictId"], indices = [Index(value = ["ownerKey", "operationId"], unique = true)])
data class MemoryConflict(
    val conflictId: String = UUID.randomUUID().toString(), val ownerKey: String, val memoryId: String,
    val operationId: String = "",
    val localRevision: Long, val remoteRevision: Long, val localJson: String?, val remoteJson: String?,
    val tombstone: Boolean = false, val state: MemoryConflictState = MemoryConflictState.UNRESOLVED,
    val error: String? = null, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt
)

class MemoryConflictConverters {
    @TypeConverter fun state(v: MemoryConflictState) = v.name
    @TypeConverter fun state(v: String) = MemoryConflictState.valueOf(v)
}

@Dao interface MemoryConflictDao {
    @Query("SELECT * FROM memory_conflicts WHERE ownerKey=:owner AND state NOT IN ('RESOLVED','CANCELLED') ORDER BY createdAt")
    fun unresolved(owner: String): kotlinx.coroutines.flow.Flow<List<MemoryConflict>>
    @Query("SELECT * FROM memory_conflicts WHERE ownerKey=:owner AND conflictId=:id LIMIT 1")
    suspend fun find(owner: String, id: String): MemoryConflict?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(value: MemoryConflict)
    @Query("SELECT * FROM memory_conflicts WHERE ownerKey=:owner AND operationId=:operation LIMIT 1") suspend fun byOperation(owner: String, operation: String): MemoryConflict?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: MemoryConflict)
}

object MemoryConflictResolver {
    fun choose(conflict: MemoryConflict, choice: MemoryConflictChoice, merge: PermanentMemory? = null): Result<PermanentMemory?> {
        if (conflict.tombstone && choice != MemoryConflictChoice.REMOTE && choice != MemoryConflictChoice.CANCEL)
            return Result.failure(IllegalStateException("Tombstone cannot be resurrected"))
        return when (choice) {
            MemoryConflictChoice.LOCAL -> Result.success(conflict.localJson?.let { decode(it, conflict.ownerKey) })
            MemoryConflictChoice.REMOTE -> Result.success(conflict.remoteJson?.let { decode(it, conflict.ownerKey) })
            MemoryConflictChoice.MERGE -> if (merge == null) Result.failure(IllegalArgumentException("Merged memory required")) else Result.success(merge.copy(ownerKey = conflict.ownerKey, memoryId = conflict.memoryId))
            MemoryConflictChoice.CANCEL -> Result.success(null)
        }
    }
    fun encode(m: PermanentMemory): String = listOf(m.memoryId,m.title,m.content,m.category,m.type.name,m.tags,m.enabled.toString(),m.pinned.toString(),m.createdAt.toString(),m.updatedAt.toString(),m.contentHash ?: "").joinToString("\u001f")
    private fun decode(s: String, owner: String): PermanentMemory { val p=s.split("\u001f"); return PermanentMemory(memoryId=p[0],title=p[1],content=p[2],category=p[3],type=MemoryType.valueOf(p[4]),tags=p[5],enabled=p[6].toBoolean(),pinned=p[7].toBoolean(),createdAt=p[8].toLong(),updatedAt=p[9].toLong(),contentHash=p.getOrNull(10)?.ifBlank{null},ownerKey=owner) }
}
