package com.example.firebase.firestore

import android.content.Context
import com.example.data.preferences.SettingsManager
import com.example.firebase.auth.FirebaseAuthManager
import com.example.memory.account.MemorySession
import com.example.memory.data.PermanentMemory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Narrow, opt-in cloud adapter. FirebaseAuthManager remains the sole auth authority. */
class FirestoreMemoryRepository(
    context: Context,
    private val auth: FirebaseAuthManager,
    private val firestore: FirebaseFirestore? = try { FirebaseFirestore.getInstance() } catch (_: Exception) { null }
) {
    enum class ErrorKind { REVISION_CONFLICT, AUTH, PERMISSION, NETWORK, VALIDATION, UNKNOWN }
    data class RemoteWriteResult(val revision: Long, val payload: Map<String, Any?>? = null)
    class RemoteWriteException(val kind: ErrorKind, message: String, val revision: Long? = null, val payload: Map<String, Any?>? = null) : Exception(message)
    private val settings = SettingsManager(context.applicationContext)

    fun isSyncEnabled(): Boolean = settings.isCloudMemorySyncEnabled
    fun setSyncEnabled(enabled: Boolean) { settings.isCloudMemorySyncEnabled = enabled }

    suspend fun upsert(memory: PermanentMemory, session: MemorySession, revision: Long): Result<RemoteWriteResult> = withContext(Dispatchers.IO) {
        write(memory, session, revision, deleted = false)
    }

    suspend fun tombstone(memoryId: String, session: MemorySession, revision: Long): Result<RemoteWriteResult> = withContext(Dispatchers.IO) {
        if (memoryId.isBlank()) return@withContext Result.failure(IllegalArgumentException("memoryId required"))
        write(null, session, revision, deleted = true, memoryId = memoryId)
    }

    private suspend fun write(memory: PermanentMemory?, session: MemorySession, revision: Long, deleted: Boolean, memoryId: String? = memory?.memoryId): Result<RemoteWriteResult> {
        if (!isSyncEnabled()) return Result.failure(RemoteWriteException(ErrorKind.AUTH, "Cloud memory sync is disabled"))
        val uid = auth.currentUid ?: return Result.failure(RemoteWriteException(ErrorKind.AUTH, "Authentication required"))
        if (session.ownerKey != "uid:$uid" || !auth.isCurrentSession(auth.sessionState.value) || revision < 0L || memoryId.isNullOrBlank())
            return Result.failure(RemoteWriteException(ErrorKind.AUTH, "Stale or invalid account session"))
        val db = firestore ?: return Result.failure(RemoteWriteException(ErrorKind.NETWORK, "Firestore is not initialized"))
        return try {
            val ref = db.collection("users").document(uid).collection("memories").document(memoryId)
            db.runTransaction { tx ->
                val current = tx.get(ref)
                val previous = current.getLong("revision") ?: -1L
                if (previous > revision) throw RemoteWriteException(ErrorKind.REVISION_CONFLICT, "Memory revision conflict", previous, current.data)
                val data = if (deleted) mapOf("memoryId" to memoryId, "revision" to revision, "deleted" to true)
                else mapOf("memoryId" to memory!!.memoryId, "revision" to revision, "deleted" to false,
                    "title" to memory.title, "content" to memory.content, "category" to memory.category,
                    "tags" to memory.tags, "enabled" to memory.enabled, "pinned" to memory.pinned,
                    "createdAt" to memory.createdAt, "updatedAt" to memory.updatedAt,
                    "contentHash" to memory.effectiveContentHash())
                tx.set(ref, data, SetOptions.merge())
            }.await()
            if (auth.currentUid != uid || !auth.isCurrentSession(auth.sessionState.value) || !MemorySession(session.ownerKey, session.generation).let { com.example.memory.account.MemoryAccountScope.isCurrent(it) }) throw RemoteWriteException(ErrorKind.AUTH, "Account changed during sync")
            Result.success(RemoteWriteResult(revision))
        } catch (e: RemoteWriteException) { Result.failure(e)
        } catch (e: FirebaseFirestoreException) { Result.failure(RemoteWriteException(if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ErrorKind.PERMISSION else ErrorKind.NETWORK, e.message ?: "Firestore error"))
        } catch (e: Exception) { Result.failure(RemoteWriteException(ErrorKind.UNKNOWN, e.message ?: e.javaClass.simpleName)) }
    }

    companion object { const val COLLECTION_MEMORIES = "memories" }
}
