package com.example.memory

import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.sync.*
import org.junit.Assert.*
import org.junit.Test

class MemoryConflictResolverTest {
    private val local = PermanentMemory(memoryId = "m", title = "local", content = "a", category = "Personal", type = MemoryType.FACT, ownerKey = "uid:a")
    private val remote = local.copy(title = "remote", content = "b")
    private val conflict = MemoryConflict(ownerKey = "uid:a", memoryId = "m", localRevision = 2, remoteRevision = 3, localJson = MemoryConflictResolver.encode(local), remoteJson = MemoryConflictResolver.encode(remote))

    @Test fun localAndRemoteChoicesPreserveTheirVersions() {
        assertEquals("local", MemoryConflictResolver.choose(conflict, MemoryConflictChoice.LOCAL).getOrNull()?.title)
        assertEquals("remote", MemoryConflictResolver.choose(conflict, MemoryConflictChoice.REMOTE).getOrNull()?.title)
    }

    @Test fun mergeRequiresExplicitMergedPayloadAndRetainsOwnerAndId() {
        assertTrue(MemoryConflictResolver.choose(conflict, MemoryConflictChoice.MERGE).isFailure)
        val merged = local.copy(title = "merged")
        val result = MemoryConflictResolver.choose(conflict, MemoryConflictChoice.MERGE, merged).getOrNull()
        assertEquals("merged", result?.title); assertEquals("uid:a", result?.ownerKey); assertEquals("m", result?.memoryId)
    }

    @Test fun cancelDoesNotProduceMutation() { assertNull(MemoryConflictResolver.choose(conflict, MemoryConflictChoice.CANCEL).getOrNull()) }

    @Test fun tombstoneCannotChooseLocalOrMerge() {
        val tombstone = conflict.copy(tombstone = true)
        assertTrue(MemoryConflictResolver.choose(tombstone, MemoryConflictChoice.LOCAL).isFailure)
        assertTrue(MemoryConflictResolver.choose(tombstone, MemoryConflictChoice.MERGE, local).isFailure)
        assertTrue(MemoryConflictResolver.choose(tombstone, MemoryConflictChoice.REMOTE).isSuccess)
    }
}
