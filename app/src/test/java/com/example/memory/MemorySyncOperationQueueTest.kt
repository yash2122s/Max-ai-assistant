package com.example.memory

import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.sync.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Gate 1: deterministic coverage of the durable queue, without Firebase or Room I/O. */
class MemorySyncOperationQueueTest {
    private val session = MemorySession("uid:gate1", 7)
    private val dao = FakeDao()
    private val now = AtomicLong(1000)
    private lateinit var remote: FakeRemote

    @After fun cleanup() { MemoryAccountScope.unbind() }

    @Test fun createUpdateDeleteUseStableMemoryIdsAndDeleteIsTombstone() = runTest {
        current(); remote = FakeRemote()
        val queue = queue()
        val memory = memory("m1")
        queue.enqueueCreate(memory, 1); queue.enqueueUpdate(memory.copy(content = "new"), 2)
        queue.enqueueDelete(session, "m1", 3)
        assertEquals(listOf("m1", "m1", "m1"), dao.all(session.ownerKey).map { it.memoryId })
        assertEquals(MemorySyncOperationKind.DELETE, dao.all(session.ownerKey).last().kind)
        assertNull(dao.all(session.ownerKey).last().payloadJson)
        assertEquals(3, queue.drain(session))
        assertEquals(listOf("upsert:m1:1", "upsert:m1:2", "delete:m1:3"), remote.calls)
    }

    @Test fun duplicateOperationIdIsIdempotentAcrossQueueRecreation() = runTest {
        current(); remote = FakeRemote(); val op = MemorySyncOperation("op", session.ownerKey, "m", MemorySyncOperationKind.CREATE, "{}", 1, sequence = 1)
        dao.insert(op); dao.insert(op)
        assertEquals(1, dao.all(session.ownerKey).size)
        val recreated = MemorySyncOperationQueue(dao, remote, clock = { now.get() })
        assertEquals(1, recreated.drain(session)); assertEquals(0, recreated.drain(session))
    }

    @Test fun retryableFailuresAreBoundedAndTerminalOperationsNeverRerun() = runTest {
        current(); remote = FakeRemote(listOf(IllegalStateException("network"), IllegalStateException("network"), IllegalStateException("network")))
        val queue = queue(); queue.enqueueCreate(memory("m"), 1)
        assertEquals(0, queue.drain(session, maxAttempts = 2)); assertEquals(MemorySyncOperationState.RETRY_WAIT, dao.all(session.ownerKey)[0].state)
        now.set(5000); queue.drain(session, maxAttempts = 2)
        val terminal = dao.all(session.ownerKey)[0]; assertEquals(MemorySyncOperationState.FAILED, terminal.state); assertEquals(2, terminal.attemptCount)
        now.set(100000); queue.drain(session, maxAttempts = 2); assertEquals(2, remote.calls.size)
    }

    @Test fun securityFailureIsPermanentAndPerMemoryOrderingBlocksLaterOperation() = runTest {
        current(); remote = FakeRemote(listOf(SecurityException("auth")))
        val queue = queue(); queue.enqueueCreate(memory("m"), 1); queue.enqueueUpdate(memory("m"), 2); queue.enqueueCreate(memory("other"), 1)
        queue.drain(session)
        assertEquals(MemorySyncOperationState.FAILED, dao.all(session.ownerKey).first { it.memoryId == "m" }.state)
        assertEquals(MemorySyncOperationState.PENDING, dao.all(session.ownerKey).first { it.memoryId == "m" && it.expectedRevision == 2L }.state)
        assertEquals(MemorySyncOperationState.SUCCEEDED, dao.all(session.ownerKey).first { it.memoryId == "other" }.state)
    }

    @Test fun staleSessionAndAccountSwitchStopDrainAndNeverRetargetPayloadOwner() = runTest {
        current(); remote = FakeRemote(); val queue = queue(); queue.enqueueCreate(memory("m"), 1)
        MemoryAccountScope.unbind()
        var rejected = false
        try { queue.drain(session) } catch (_: kotlinx.coroutines.CancellationException) { rejected = true }
        assertTrue(rejected)
        assertEquals(session.ownerKey, dao.all(session.ownerKey).single().ownerKey)
    }

    @Test fun coordinatorRejectsConsentDisabledGuestAndUnavailableButAllowsOwner() = runTest {
        remote = FakeRemote(); val queue = queue(); MemorySyncQueueCoordinator.configure(queue) { false }
        MemorySyncQueueCoordinator.create(memory("off"), 1); assertTrue(dao.all("uid:gate1").isEmpty())
        current(); MemorySyncQueueCoordinator.configure(queue) { true }; MemorySyncQueueCoordinator.create(memory("on"), 1)
        assertEquals(1, dao.all(session.ownerKey).size)
    }

    @Test fun revisionConflictIsRetainedAsFailureMetadata() = runTest {
        current(); remote = FakeRemote(listOf(IllegalArgumentException("revision conflict"))); val queue = queue()
        queue.enqueueCreate(memory("m"), 9); queue.drain(session, 1)
        assertEquals("IllegalArgumentException", dao.all(session.ownerKey).single().lastError)
    }

    private fun queue() = MemorySyncOperationQueue(dao, remote, clock = { now.get() })
    private fun memory(id: String) = PermanentMemory(id, "title", "content", "general", MemoryType.CUSTOM, ownerKey = session.ownerKey)
    private fun current() { val f = MemoryAccountScope::class.java.getDeclaredField("state"); f.isAccessible = true; (f.get(null) as kotlinx.coroutines.flow.MutableStateFlow<MemorySession>).value = session }

    private class FakeDao : MemorySyncOperationDao {
        private val rows = linkedMapOf<Pair<String, String>, MemorySyncOperation>()
        override suspend fun insert(operation: MemorySyncOperation) { rows.putIfAbsent(operation.ownerKey to operation.operationId, operation) }
        override suspend fun ready(owner: String, now: Long) = rows.values.filter { it.ownerKey == owner && it.state in setOf(MemorySyncOperationState.PENDING, MemorySyncOperationState.RETRY_WAIT) && it.nextAttemptAt <= now }.sortedWith(compareBy({ it.memoryId }, { it.sequence }))
        override suspend fun update(owner: String, id: String, state: MemorySyncOperationState, attempts: Int, error: String?, at: Long, next: Long, result: Long?) { val old = rows[owner to id]!!; rows[owner to id] = old.copy(state = state, attemptCount = attempts, lastError = error, lastAttemptAt = at, nextAttemptAt = next, resultRevision = result) }
        override suspend fun all(owner: String) = rows.values.filter { it.ownerKey == owner }.sortedBy { it.sequence }
    }
    private class FakeRemote(private val failures: List<Throwable> = emptyList()) : MemorySyncRemote {
        val calls = mutableListOf<String>(); private var n = 0
        override suspend fun upsert(memory: PermanentMemory, session: MemorySession, revision: Long): Result<com.example.firebase.firestore.FirestoreMemoryRepository.RemoteWriteResult> { calls += "upsert:${memory.memoryId}:$revision"; return if (n < failures.size) Result.failure(failures[n++]) else Result.success(com.example.firebase.firestore.FirestoreMemoryRepository.RemoteWriteResult(revision)) }
        override suspend fun tombstone(memoryId: String, session: MemorySession, revision: Long): Result<com.example.firebase.firestore.FirestoreMemoryRepository.RemoteWriteResult> { calls += "delete:$memoryId:$revision"; return Result.success(com.example.firebase.firestore.FirestoreMemoryRepository.RemoteWriteResult(revision)) }
    }
}
