package com.example.firebase

import android.app.Activity
import android.app.Application
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.example.firebase.auth.*
import com.example.firebase.model.FirebaseUserAccount
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal class FakeGoogleGateway(initialUid: String? = null) : GoogleAuthGateway {
    override var currentUser: FirebaseUserAccount? = initialUid?.let { FirebaseUserAccount(it) }
    private var listener: () -> Unit = {}
    val chooserRequests = mutableListOf<Boolean>()
    var signIns = 0
    var clears = 0
    var token: suspend () -> String = { "synthetic-test-token" }
    var authentication: suspend () -> Unit = { changeAccount("firebase-uid") }
    override fun observeAuth(listener: () -> Unit) { this.listener = listener }
    fun changeAccount(uid: String?) {
        currentUser = uid?.let { FirebaseUserAccount(it) }
        listener()
    }
    override suspend fun googleToken(activity: Activity, clientId: String, authorizedOnly: Boolean): String {
        chooserRequests += authorizedOnly
        return token()
    }
    override suspend fun signIn(token: String) { signIns++; authentication() }
    override fun signOut() { changeAccount(null) }
    override suspend fun clearCredentialState() { clears++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirebaseAuthSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val activity = Activity()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun manager(fake: FakeGoogleGateway) = FirebaseAuthManager(fake) { "test.apps.googleusercontent.com" }

    @Test fun successUsesFirebaseCurrentUidNotCredentialOrEmail() = runTest {
        val fake = FakeGoogleGateway()
        val auth = manager(fake)
        assertEquals("firebase-uid", auth.signInWithGoogle(activity).getOrThrow().uid)
        assertEquals("firebase-uid", auth.currentUid)
        assertEquals(auth.currentUid, auth.sessionState.value.uid)
        assertTrue(auth.isAuthenticated)
    }

    @Test fun configurationMissingDoesNotRequestCredentials() = runTest {
        val fake = FakeGoogleGateway()
        val result = FirebaseAuthManager(fake) { null }.signInWithGoogle(activity)
        assertSame(AuthException.ConfigurationError, result.exceptionOrNull())
        assertTrue(fake.chooserRequests.isEmpty())
    }

    @Test fun userCancellationIsNotRetriedOrReportedAsProviderFailure() = runTest {
        val fake = FakeGoogleGateway().apply { token = { throw GetCredentialCancellationException() } }
        val auth = manager(fake)
        assertSame(AuthException.UserCancelled, auth.signInWithGoogle(activity).exceptionOrNull())
        assertEquals(listOf(true), fake.chooserRequests)
        assertEquals(0, fake.signIns)
        assertNull(auth.userState.value)
    }

    @Test fun onlyNoCredentialTriggersInteractiveFallback() = runTest {
        val fake = FakeGoogleGateway()
        fake.token = { if (fake.chooserRequests.size == 1) throw NoCredentialException() else "test-token" }
        assertTrue(manager(fake).signInWithGoogle(activity).isSuccess)
        assertEquals(listOf(true, false), fake.chooserRequests)
    }

    @Test fun providerFailureDoesNotRetryAndDoesNotExposePayload() = runTest {
        val fake = FakeGoogleGateway().apply { token = { throw GetCredentialUnknownException("private SDK payload") } }
        val result = manager(fake).signInWithGoogle(activity)
        assertTrue(result.exceptionOrNull() is AuthException.GetCredentialFailed)
        assertFalse(result.exceptionOrNull()!!.message!!.contains("private SDK payload"))
        assertEquals(listOf(true), fake.chooserRequests)
    }

    @Test fun firebaseFailureLeavesSignedOutAndRedactsDetails() = runTest {
        val fake = FakeGoogleGateway().apply { authentication = { throw IllegalStateException("private SDK payload") } }
        val auth = manager(fake)
        val result = auth.signInWithGoogle(activity)
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message!!.contains("private SDK payload"))
        assertNull(auth.userState.value)
    }

    @Test fun explicitSwitchAlwaysOffersChooserAndClearsCredentials() = runTest {
        val fake = FakeGoogleGateway("A").apply { authentication = { changeAccount("B") } }
        val auth = manager(fake)
        val before = auth.sessionState.value
        assertEquals("B", auth.switchAccount(activity).getOrThrow().uid)
        assertEquals(listOf(false), fake.chooserRequests)
        assertEquals(1, fake.clears)
        assertFalse(auth.isCurrentSession(before))
    }

    @Test fun cancelledSwitchStaysSignedOutRatherThanRestoringA() = runTest {
        val fake = FakeGoogleGateway("A").apply { token = { throw GetCredentialCancellationException() } }
        val auth = manager(fake)
        assertSame(AuthException.UserCancelled, auth.switchAccount(activity).exceptionOrNull())
        assertNull(auth.currentUid)
        assertNull(auth.userState.value)
    }

    @Test fun logoutDuringPickerRejectsLateCredentialWithoutFirebaseWrite() = runTest {
        val credential = CompletableDeferred<String>()
        val fake = FakeGoogleGateway().apply { token = { credential.await() } }
        val auth = manager(fake)
        val login = async { auth.signInWithGoogle(activity) }
        runCurrent()
        assertTrue(auth.signOut().isSuccess)
        credential.complete("late-test-token")
        assertSame(AuthException.SessionChanged, login.await().exceptionOrNull())
        assertEquals(0, fake.signIns)
        assertNull(auth.currentUid)
    }

    @Test fun logoutDuringFirebaseTaskNeverPublishesLateAccount() = runTest {
        val finish = CompletableDeferred<Unit>()
        val fake = FakeGoogleGateway().apply { authentication = { finish.await(); changeAccount("A") } }
        val auth = manager(fake)
        val seen = mutableListOf<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { auth.userState.collect { seen += it?.uid } }
        val login = async { auth.signInWithGoogle(activity) }
        runCurrent()
        val logout = async { auth.signOut() }
        runCurrent()
        assertNull(auth.sessionState.value.uid)
        finish.complete(Unit)
        assertTrue(login.await().isFailure)
        assertTrue(logout.await().isSuccess)
        assertNull(auth.currentUid)
        assertFalse(seen.contains("A"))
    }

    @Test fun cancellationWaitsForFirebaseSettlementThenSignsOut() = runTest {
        val finish = CompletableDeferred<Unit>()
        val fake = FakeGoogleGateway().apply { authentication = { finish.await(); changeAccount("A") } }
        val auth = manager(fake)
        val login = launch { auth.signInWithGoogle(activity) }
        runCurrent()
        login.cancel()
        finish.complete(Unit)
        login.join()
        assertTrue(login.isCancelled)
        assertNull(auth.currentUid)
        assertNull(auth.userState.value)
    }

    @Test fun staleCompletionCannotSignOutNewerSuccessfulAttempt() = runTest {
        val oldCredential = CompletableDeferred<String>()
        val fake = FakeGoogleGateway()
        fake.token = { if (fake.chooserRequests.size == 1) oldCredential.await() else "fresh-test-token" }
        val auth = manager(fake)
        val old = async { auth.signInWithGoogle(activity) }
        runCurrent()
        assertTrue(auth.signInWithGoogle(activity).isSuccess)
        oldCredential.complete("old-test-token")
        assertTrue(old.await().isFailure)
        assertEquals("firebase-uid", auth.currentUid)
        assertEquals(1, fake.signIns)
    }

    @Test fun accountChangeClearsRegisteredCacheBeforeNewUserPublication() = runTest {
        val fake = FakeGoogleGateway("A")
        val auth = manager(fake)
        val cache = mutableListOf("A plaintext")
        val owner = Any()
        auth.addSessionInvalidationListener(owner) {
            assertNull(auth.userState.value)
            cache.clear()
        }
        fake.changeAccount("B")
        assertTrue(cache.isEmpty())
        assertEquals("B", auth.userState.value?.uid)
        auth.removeSessionInvalidationListener(owner)
        cache += "listener removed"
        fake.changeAccount("C")
        assertFalse(cache.isEmpty())
    }

    @Test fun generationRejectsOldAccountEvenAfterAtoBtoA() = runTest {
        val fake = FakeGoogleGateway("A")
        val auth = manager(fake)
        val original = auth.sessionState.value
        fake.changeAccount("B")
        fake.changeAccount("A")
        assertFalse(auth.isCurrentSession(original))
        assertTrue(auth.isCurrentSession(auth.sessionState.value))
        fake.currentUser = FirebaseUserAccount("forged-current")
        assertFalse(auth.isCurrentSession(auth.sessionState.value))
    }
}
