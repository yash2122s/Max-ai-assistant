package com.example.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.firebase.auth.FirebaseAuthManager
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.security.MemoryCryptography
import com.example.memory.account.MemoryAccountScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoryAccountContextTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun guestLeaseIsRevokedDuringSignInAndRenewedAfterCancellation() = runTest {
        val gateway = FakeGoogleGateway()
        val auth = FirebaseAuthManager(gateway) { "test-client" }
        MemoryAccountScope.bind(auth)
        try {
            val before = MemoryAccountScope.capture()
            assertEquals(MemoryAccountScope.GUEST, before.ownerKey)
            gateway.token = {
                assertEquals(MemoryAccountScope.UNAVAILABLE, MemoryAccountScope.capture().ownerKey)
                assertFalse(MemoryAccountScope.isCurrent(before))
                throw androidx.credentials.exceptions.GetCredentialCancellationException()
            }
            assertTrue(auth.signInWithGoogle(android.app.Activity()).isFailure)
            val after = MemoryAccountScope.capture()
            assertEquals(MemoryAccountScope.GUEST, after.ownerKey)
            assertTrue(MemoryAccountScope.isCurrent(after))
            assertFalse(MemoryAccountScope.isCurrent(before))
        } finally {
            MemoryAccountScope.unbind()
        }
    }

    @Test fun logoutClearsDerivedPlaintextWithoutDeletingDeviceLocalMemory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dao = AppDatabase.getDatabase(context).permanentMemoryDao()
        val auth = FirebaseAuthManager(FakeGoogleGateway("A")) { "test-client" }
        MemoryAccountScope.bind(auth)
        try {
        val ownerKey = MemoryAccountScope.capture().ownerKey
        val record = PermanentMemory(memoryId = "account-context-test", title = MemoryCryptography.encrypt("Synthetic preference"),
            content = MemoryCryptography.encrypt("Prefer short answers"), category = "Personal", type = MemoryType.FACT, ownerKey = ownerKey)
        withContext(Dispatchers.IO) { dao.insert(record) }
        val repository = MemoryRepository(context)
        withTimeout(10000) { repository.memoriesMarkdownFlow.first { it.contains("Prefer short answers") } }
        auth.addSessionInvalidationListener(repository) { repository.invalidateAccountContext() }
        val revision = repository.memoryRevision.value
        auth.signOut().getOrThrow()
        assertEquals("", repository.getMemoriesMarkdown())
        assertTrue(repository.memoryRevision.value > revision)
        val stored = withContext(Dispatchers.IO) { dao.getAll(ownerKey).first { it.memoryId == record.memoryId } }
        assertEquals("Prefer short answers", MemoryCryptography.decrypt(stored.content))
        } finally { MemoryAccountScope.unbind() }
    }
}
