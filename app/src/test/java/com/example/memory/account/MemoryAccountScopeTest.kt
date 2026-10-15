package com.example.memory.account

import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAccountScopeTest {
    @After fun reset() = MemoryAccountScope.unbind()

    @Test fun unboundScopeIsUnavailableAndRejectsLease() {
        MemoryAccountScope.unbind()
        val lease = MemoryAccountScope.capture()
        assertTrue(lease.ownerKey == MemoryAccountScope.UNAVAILABLE)
        try { MemoryAccountScope.requireCurrent(lease); throw AssertionError("expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun directoryNamesAreStableAndAccountSafe() {
        val a = MemorySession("uid:a", 1)
        val b = MemorySession("uid:b", 1)
        assertTrue(MemoryAccountScope.directoryName(a).matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(MemoryAccountScope.directoryName(a), MemoryAccountScope.directoryName(b))
    }
}
