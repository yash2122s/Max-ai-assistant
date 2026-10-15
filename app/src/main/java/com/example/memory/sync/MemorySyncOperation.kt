package com.example.memory.sync

import androidx.room.*
import com.example.firebase.firestore.FirestoreMemoryRepository
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.memory.data.PermanentMemory
import org.json.JSONObject
import java.util.UUID

enum class MemorySyncOperationKind { CREATE, UPDATE, DELETE }
enum class MemorySyncOperationState { PENDING, IN_FLIGHT, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED }

class MemorySyncOperationConverters {
    @TypeConverter fun kind(v: MemorySyncOperationKind) = v.name
    @TypeConverter fun kind(v: String) = MemorySyncOperationKind.valueOf(v)
    @TypeConverter fun state(v: MemorySyncOperationState) = v.name
    @TypeConverter fun state(v: String) = MemorySyncOperationState.valueOf(v)
}

@Entity(tableName = "memory_sync_operations", primaryKeys = ["ownerKey", "operationId"], indices = [Index(value = ["ownerKey", "memoryId", "sequence"]), Index(value = ["ownerKey", "state", "nextAttemptAt"])])
data class MemorySyncOperation(
    val operationId: String = UUID.randomUUID().toString(), val ownerKey: String, val memoryId: String,
    val kind: MemorySyncOperationKind, val payloadJson: String?, val expectedRevision: Long,
    val resultRevision: Long? = null, val state: MemorySyncOperationState = MemorySyncOperationState.PENDING,
    val attemptCount: Int = 0, val lastError: String? = null, val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long = 0L, val sequence: Long = System.currentTimeMillis()
)

@Dao
interface MemorySyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(operation: MemorySyncOperation)
    @Query("SELECT * FROM memory_sync_operations WHERE ownerKey=:owner AND state IN ('PENDING','RETRY_WAIT') AND nextAttemptAt<=:now ORDER BY memoryId, sequence") suspend fun ready(owner: String, now: Long): List<MemorySyncOperation>
    @Query("UPDATE memory_sync_operations SET state=:state, attemptCount=:attempts, lastError=:error, lastAttemptAt=:at, nextAttemptAt=:next, resultRevision=:result WHERE ownerKey=:owner AND operationId=:id") suspend fun update(owner: String, id: String, state: MemorySyncOperationState, attempts: Int, error: String?, at: Long, next: Long, result: Long?)
    @Query("SELECT * FROM memory_sync_operations WHERE ownerKey=:owner ORDER BY sequence") suspend fun all(owner: String): List<MemorySyncOperation>
}

interface MemorySyncRemote {
    suspend fun upsert(memory: PermanentMemory, session: MemorySession, revision: Long): Result<com.example.firebase.firestore.FirestoreMemoryRepository.RemoteWriteResult>
    suspend fun tombstone(memoryId: String, session: MemorySession, revision: Long): Result<com.example.firebase.firestore.FirestoreMemoryRepository.RemoteWriteResult>
}

class FirestoreMemoryRemote(private val repository: FirestoreMemoryRepository) : MemorySyncRemote {
    override suspend fun upsert(memory: PermanentMemory, session: MemorySession, revision: Long) = repository.upsert(memory, session, revision)
    override suspend fun tombstone(memoryId: String, session: MemorySession, revision: Long) = repository.tombstone(memoryId, session, revision)
}

class MemorySyncOperationQueue(private val dao: MemorySyncOperationDao, private val remote: MemorySyncRemote, private val clock: () -> Long = System::currentTimeMillis, private val conflictDao: MemoryConflictDao? = null) {
    suspend fun enqueueCreate(memory: PermanentMemory, revision: Long): String = enqueue(MemorySyncOperationKind.CREATE, memory, revision)
    suspend fun enqueueUpdate(memory: PermanentMemory, revision: Long): String = enqueue(MemorySyncOperationKind.UPDATE, memory, revision)
    suspend fun enqueueDelete(session: MemorySession, memoryId: String, revision: Long): String { MemoryAccountScope.requireCurrent(session); val op=MemorySyncOperation(ownerKey=session.ownerKey, memoryId=memoryId, kind=MemorySyncOperationKind.DELETE, payloadJson=null, expectedRevision=revision); dao.insert(op); return op.operationId }
    private suspend fun enqueue(kind: MemorySyncOperationKind, memory: PermanentMemory, revision: Long): String { val session=MemoryAccountScope.capture(); MemoryAccountScope.requireCurrent(session); val op=MemorySyncOperation(ownerKey=session.ownerKey, memoryId=memory.memoryId, kind=kind, payloadJson=encode(memory), expectedRevision=revision); dao.insert(op); return op.operationId }

    suspend fun drain(session: MemorySession, maxAttempts: Int = 5): Int {
        MemoryAccountScope.requireCurrent(session); var done=0; val blocked=mutableSetOf<String>()
        for (op in dao.ready(session.ownerKey, clock())) {
            if (op.memoryId in blocked) continue
            if (!MemoryAccountScope.isCurrent(session)) break
            val attempt=op.attemptCount+1; dao.update(session.ownerKey,op.operationId,MemorySyncOperationState.IN_FLIGHT,attempt,null,clock(),Long.MAX_VALUE,null)
            val result = if (op.kind == MemorySyncOperationKind.DELETE) remote.tombstone(op.memoryId,session,op.expectedRevision) else remote.upsert(decode(op.payloadJson!!, op.ownerKey),session,op.expectedRevision)
            if (result.isSuccess) { dao.update(session.ownerKey,op.operationId,MemorySyncOperationState.SUCCEEDED,attempt,null,clock(),0,result.getOrNull()?.revision ?: op.expectedRevision); if (MemoryAccountScope.isCurrent(session)) conflictDao?.byOperation(session.ownerKey, op.operationId)?.let { conflictDao.upsert(it.copy(state=MemoryConflictState.RESOLVED, error=null, updatedAt=clock())) }; done++ }
            else { val e=result.exceptionOrNull(); val conflict=e as? FirestoreMemoryRepository.RemoteWriteException
                if (conflict?.kind == FirestoreMemoryRepository.ErrorKind.REVISION_CONFLICT) {
                    blocked += op.memoryId
                    if (MemoryAccountScope.isCurrent(session)) conflictDao?.insert(MemoryConflict(operationId=op.operationId, ownerKey=session.ownerKey, memoryId=op.memoryId, localRevision=op.expectedRevision, remoteRevision=conflict.revision ?: -1L, localJson=op.payloadJson?.let { decode(it, op.ownerKey) }?.let(MemoryConflictResolver::encode), remoteJson=conflict.payload?.let { remoteJson(it, op.ownerKey) }, tombstone=op.kind == MemorySyncOperationKind.DELETE))
                    dao.update(session.ownerKey,op.operationId,MemorySyncOperationState.FAILED,attempt,conflict.kind.name,clock(),0,null)
                } else { val terminal=attempt>=maxAttempts || e is SecurityException || conflict?.kind == FirestoreMemoryRepository.ErrorKind.AUTH || conflict?.kind == FirestoreMemoryRepository.ErrorKind.PERMISSION; blocked += op.memoryId; val state=if(terminal) MemorySyncOperationState.FAILED else MemorySyncOperationState.RETRY_WAIT; dao.update(session.ownerKey,op.operationId,state,attempt,conflict?.kind?.name ?: e?.javaClass?.simpleName,clock(),if(terminal) 0 else clock()+backoff(attempt),null); if (terminal && MemoryAccountScope.isCurrent(session)) conflictDao?.byOperation(session.ownerKey, op.operationId)?.let { conflictDao.upsert(it.copy(state=MemoryConflictState.FAILED, error=conflict?.kind?.name ?: e?.javaClass?.simpleName, updatedAt=clock())) } }
            }
        }; return done
    }
    private fun backoff(attempt:Int)=1000L shl (attempt-1).coerceAtMost(5)
    private fun encode(m: PermanentMemory): String {
        fun q(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val hash = m.contentHash?.let { ",\"contentHash\":\"${q(it)}\"" } ?: ""
        return "{\"memoryId\":\"${q(m.memoryId)}\",\"title\":\"${q(m.title)}\",\"content\":\"${q(m.content)}\",\"category\":\"${q(m.category)}\",\"type\":\"${m.type.name}\",\"tags\":\"${q(m.tags)}\",\"enabled\":${m.enabled},\"pinned\":${m.pinned},\"createdAt\":${m.createdAt},\"updatedAt\":${m.updatedAt}$hash}"
    }
    private fun decode(s: String, ownerKey: String): PermanentMemory {
        fun text(name: String): String = Regex("\\\"$name\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(s)?.groupValues?.get(1)?.replace("\\\\\\\"", "\\\"")?.replace("\\\\n", "\\n") ?: ""
        fun number(name: String): Long = Regex("\\\"$name\\\":(-?\\d+)").find(s)?.groupValues?.get(1)?.toLong() ?: 0L
        fun bool(name: String, default: Boolean): Boolean = Regex("\\\"$name\\\":(true|false)").find(s)?.groupValues?.get(1)?.toBoolean() ?: default
        return PermanentMemory(memoryId=text("memoryId"), title=text("title"), content=text("content"), category=text("category"), type=com.example.memory.data.MemoryType.valueOf(text("type").ifBlank { "CUSTOM" }), tags=text("tags"), enabled=bool("enabled", true), pinned=bool("pinned", false), createdAt=number("createdAt"), updatedAt=number("updatedAt"), contentHash=text("contentHash").ifBlank { null }, ownerKey=ownerKey)
    }
    private fun remoteJson(data: Map<String, Any?>, owner: String): String = PermanentMemory(memoryId=data["memoryId"] as? String ?: "", title=data["title"] as? String ?: "", content=data["content"] as? String ?: "", category=data["category"] as? String ?: "", type=com.example.memory.data.MemoryType.CUSTOM, tags=data["tags"] as? String ?: "", enabled=data["enabled"] as? Boolean ?: true, pinned=data["pinned"] as? Boolean ?: false, createdAt=data["createdAt"] as? Long ?: 0L, updatedAt=data["updatedAt"] as? Long ?: 0L, ownerKey=owner).let(MemoryConflictResolver::encode)
}

/** Process-wide wiring point; mutations remain local when consent/account state is absent. */
object MemorySyncQueueCoordinator {
    @Volatile private var queue: MemorySyncOperationQueue? = null
    @Volatile private var enabled: (() -> Boolean)? = null

    fun configure(value: MemorySyncOperationQueue, consent: () -> Boolean) { queue = value; enabled = consent }
    suspend fun create(memory: PermanentMemory, revision: Long): String? = if (allowed()) queue?.enqueueCreate(memory, revision) else null
    suspend fun update(memory: PermanentMemory, revision: Long): String? = if (allowed()) queue?.enqueueUpdate(memory, revision) else null
    suspend fun delete(session: MemorySession, memoryId: String, revision: Long): String? = if (allowed()) queue?.enqueueDelete(session, memoryId, revision) else null
    private fun allowed(): Boolean {
        val owner = MemoryAccountScope.capture().ownerKey
        return enabled?.invoke() == true && owner.startsWith("uid:") && owner != MemoryAccountScope.GUEST
    }
}
