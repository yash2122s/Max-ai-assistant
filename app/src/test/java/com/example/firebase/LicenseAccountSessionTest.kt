package com.example.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.firebase.auth.FirebaseAuthManager
import com.example.firebase.cache.*
import com.example.firebase.firestore.FirestoreLicenseRepository
import com.example.firebase.manager.MaxLicenseManager
import com.example.firebase.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LicenseAccountSessionTest {
    private val managers = mutableListOf<MaxLicenseManager>()
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { managers.forEach { it.close() }; Dispatchers.resetMain() }

    private class Cache : FirebaseLicenseCacheDao {
        val rows = mutableMapOf<String, FirebaseLicenseCacheEntity>()
        val reads = mutableListOf<String>()
        override fun getLicense(userId: String): FirebaseLicenseCacheEntity? { reads += userId; return rows[userId] }
        override fun getLatestLicense(): FirebaseLicenseCacheEntity? = error("Cross-account cache lookup must never run")
        override fun insertOrUpdate(entity: FirebaseLicenseCacheEntity) { rows[entity.userId] = entity }
        override fun deleteLicense(userId: String) { rows.remove(userId) }
        override fun clearAll() { rows.clear() }
    }

    private class Cloud : FirestoreLicenseRepository(null) {
        val requests = mutableListOf<String>()
        val listeners = mutableSetOf<String>()
        var fetch: suspend (String) -> Result<CloudLicense> = { Result.success(CloudLicense.createTrial(it)) }
        override suspend fun syncUserProfile(account: FirebaseUserAccount, currentDeviceId: String): Result<Unit> {
            requests += account.uid; return Result.success(Unit)
        }
        override suspend fun registerDevice(userId: String, deviceId: String, appVersion: String) = Result.success(
            DeviceRegistration(deviceId = deviceId, deviceModel = "test-device", androidVersion = 34, appVersion = appVersion))
        override suspend fun getOrCreateLicense(userId: String) = fetch(userId)
        override suspend fun getDailyUsage(userId: String, period: String) = 0L
        override suspend fun consumeDailyMessage(userId: String, period: String, maxDailyLimit: Long) = Result.failure<Pair<Long, Boolean>>(IllegalStateException("offline test"))
        override fun listenToLicense(userId: String): Flow<CloudLicense> = callbackFlow {
            listeners += "license:$userId"
            awaitClose { listeners -= "license:$userId" }
        }
        override fun listenToDailyUsage(userId: String, period: String): Flow<Long> = callbackFlow {
            listeners += "usage:$userId"
            awaitClose { listeners -= "usage:$userId" }
        }
    }

    private fun manager(auth: FirebaseAuthManager, cloud: Cloud = Cloud(), cache: Cache = Cache()): MaxLicenseManager {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return MaxLicenseManager(context, auth, cloud, cache,
            initializeAppCheck = {}).also { managers += it }
    }

    @Test fun forgedUidIsRejectedBeforeAnyCloudCall() = runBlocking {
        val fake = FakeGoogleGateway("A")
        val auth = FirebaseAuthManager(fake) { "test-client" }
        val cloud = Cloud()
        assertTrue(manager(auth, cloud).syncWithCloud("B").isFailure)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test fun signedOutSyncIsRejected() = runBlocking {
        val auth = FirebaseAuthManager(FakeGoogleGateway()) { "test-client" }
        val cloud = Cloud()
        assertTrue(manager(auth, cloud).syncWithCloud("A").isFailure)
        assertTrue(cloud.requests.isEmpty())
    }

    @Test fun oldSuccessCannotPublishOrCacheAfterSwitch() = runBlocking {
        val fake = FakeGoogleGateway("A")
        val auth = FirebaseAuthManager(fake) { "test-client" }
        val ready = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<CloudLicense>>()
        val cloud = Cloud().apply { fetch = { ready.complete(Unit); reply.await() } }
        val cache = Cache()
        val manager = manager(auth, cloud, cache)
        val pending = async { manager.syncWithCloud("A") }
        ready.await()
        fake.changeAccount("B")
        reply.complete(Result.success(CloudLicense.createTrial("A")))
        assertTrue(pending.await().isFailure)
        assertNotEquals("A", manager.currentLicense.value.userId)
        assertTrue(cache.rows.isEmpty())
    }

    @Test fun offlineFallbackNeverReadsLatestOtherAccountCache() = runBlocking {
        val auth = FirebaseAuthManager(FakeGoogleGateway("B")) { "test-client" }
        val cloud = Cloud().apply { fetch = { Result.failure(IllegalStateException("offline")) } }
        val cache = Cache()
        val manager = manager(auth, cloud, cache)
        assertTrue(manager.syncWithCloud("B").isFailure)
        assertEquals(listOf("B"), cache.reads)
        assertEquals("B", manager.currentLicense.value.userId)
        assertEquals(LicensePlan.FREE, manager.currentLicense.value.plan)
    }

    @Test fun staleFailureCannotRestoreCacheAfterAtoBtoA() = runBlocking {
        val fake = FakeGoogleGateway("A")
        val auth = FirebaseAuthManager(fake) { "test-client" }
        val ready = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<CloudLicense>>()
        val cloud = Cloud().apply { fetch = { ready.complete(Unit); reply.await() } }
        val cache = Cache()
        val manager = manager(auth, cloud, cache)
        val pending = async { manager.syncWithCloud("A") }
        ready.await()
        fake.changeAccount("B")
        fake.changeAccount("A")
        reply.complete(Result.failure(IllegalStateException("offline old session")))
        assertTrue(pending.await().isFailure)
        assertTrue(cache.reads.isEmpty())
    }

    @Test fun listenerJobsAreCancelledAndInitializeIsIdempotent() = runBlocking {
        val fake = FakeGoogleGateway("A")
        val auth = FirebaseAuthManager(fake) { "test-client" }
        val cloud = Cloud()
        val manager = manager(auth, cloud)
        manager.initialize()
        manager.initialize()
        withTimeout(5000) {
            while (cloud.listeners.size != 2) delay(10)
        }
        assertEquals(setOf("license:A", "usage:A"), cloud.listeners)
        auth.signOut().getOrThrow()
        withTimeout(5000) { while (cloud.listeners.isNotEmpty()) delay(10) }
        assertEquals(LicensePlan.FREE, manager.currentLicense.value.plan)
        assertEquals(0L, manager.dailyUsageCount.value)
        assertNull(manager.currentDevice.value)
    }

    @Test fun unboundOfflineAccountDoesNotInheritAnotherUsersQuota() = runBlocking {
        val fake = FakeGoogleGateway("A")
        val auth = FirebaseAuthManager(fake) { "test-client" }
        val manager = manager(auth)
        manager.syncWithCloud("A").getOrThrow()
        assertEquals(Pair(1L, true), manager.consumeDailyMessage().getOrThrow())
        fake.changeAccount("B")
        assertEquals(Pair(1L, false), manager.consumeDailyMessage().getOrThrow())
        assertEquals(Pair(1L, false), manager.consumeDailyMessage().getOrThrow())
        fake.changeAccount("A")
        assertEquals(Pair(2L, true), manager.consumeDailyMessage().getOrThrow())
    }

    @Test fun signedOutLocalQuotaStillAccumulates() = runBlocking {
        val auth = FirebaseAuthManager(FakeGoogleGateway()) { "test-client" }
        val manager = manager(auth)
        assertEquals(1L, manager.consumeDailyMessage().getOrThrow().first)
        assertEquals(2L, manager.consumeDailyMessage().getOrThrow().first)
    }
}
