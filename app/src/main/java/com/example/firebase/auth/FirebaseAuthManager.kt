package com.example.firebase.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.firebase.model.FirebaseUserAccount
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

data class AuthSession(val uid: String?, val generation: Long)

sealed class AuthException(message: String) : Exception(message) {
    object UserCancelled : AuthException("Sign-in was cancelled by user.")
    object SessionChanged : AuthException("Account session changed. Please try again.")
    object ProviderUnavailable : AuthException("Google Sign-In is unavailable on this device.")
    class GetCredentialFailed(reason: String) : AuthException(reason)
    class InvalidCredentialType(typeName: String) : AuthException("Unexpected credential type: $typeName")
    class FirebaseAuthFailed(reason: String) : AuthException(reason)
    object ConfigurationError : AuthException("Firebase configuration or Web Client ID is missing.")
}

class FirebaseAuthManager internal constructor(
    private val gateway: GoogleAuthGateway,
    private val webClientId: () -> String?
) {
    constructor(context: Context, auth: FirebaseAuth? = initializedAuth(context)) : this(
        AndroidGoogleAuthGateway(context.applicationContext, auth), {
            val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (id == 0) null else context.getString(id)
        })

    private val _userState = MutableStateFlow(gateway.currentUser)
    val userState: StateFlow<FirebaseUserAccount?> = _userState.asStateFlow()
    private val _sessionState = MutableStateFlow(AuthSession(gateway.currentUser?.uid, 0L))
    val sessionState: StateFlow<AuthSession> = _sessionState.asStateFlow()
    private val operationMutex = Mutex()
    private val invalidationListeners = CopyOnWriteArrayList<Pair<Any, () -> Unit>>()
    private var operationEpoch = 0L
    private var activeAttempt: Long? = null
    private var managedAuthWrite = false

    init {
        gateway.observeAuth {
            if (!managedAuthWrite && activeAttempt == null) publishAccount(gateway.currentUser)
        }
    }

    // The SDK's current user is authoritative, not email or a caller-supplied UID.
    val currentUid: String? get() = gateway.currentUser?.uid
    val isAuthenticated: Boolean get() = currentUid != null && isCurrentSession(sessionState.value)
    fun isCurrentSession(session: AuthSession): Boolean =
        sessionState.value == session && currentUid == session.uid

    fun addSessionInvalidationListener(owner: Any, listener: () -> Unit) {
        removeSessionInvalidationListener(owner)
        invalidationListeners += owner to listener
    }
    fun removeSessionInvalidationListener(owner: Any) {
        invalidationListeners.removeAll { it.first === owner }
    }

    suspend fun signInWithGoogle(activity: Activity, customServerClientId: String? = null): Result<FirebaseUserAccount> =
        signInInternal(activity, customServerClientId, forceChooser = false)

    suspend fun switchAccount(activity: Activity): Result<FirebaseUserAccount> =
        signInInternal(activity, null, forceChooser = true)

    private suspend fun signInInternal(activity: Activity, clientOverride: String?, forceChooser: Boolean): Result<FirebaseUserAccount> =
        withContext(Dispatchers.Main.immediate) {
            val clientId = clientOverride ?: webClientId()
            if (clientId.isNullOrBlank()) return@withContext Result.failure(AuthException.ConfigurationError)
            if (!forceChooser && activeAttempt == null && isAuthenticated) {
                return@withContext Result.success(gateway.currentUser!!)
            }
            val attempt = ++operationEpoch
            activeAttempt = attempt
            invalidateSession()
            try {
                if (forceChooser) operationMutex.withLock {
                    if (attempt != operationEpoch) return@withContext Result.failure(AuthException.SessionChanged)
                    managedAuthWrite = true
                    try {
                        gateway.signOut()
                        clearCredentials()
                    } finally { managedAuthWrite = false }
                }
                val token = try {
                    gateway.googleToken(activity, clientId, authorizedOnly = !forceChooser)
                } catch (e: NoCredentialException) {
                    if (forceChooser || attempt != operationEpoch) throw e
                    gateway.googleToken(activity, clientId, authorizedOnly = false)
                }
                operationMutex.withLock {
                    if (attempt != operationEpoch) return@withContext Result.failure(AuthException.SessionChanged)
                    managedAuthWrite = true
                    try {
                        // Firebase Task cancellation does not undo sign-in. Keep the lock
                        // until it settles, then clean up stale success before newer writes.
                        withContext(NonCancellable) { gateway.signIn(token) }
                        currentCoroutineContext().ensureActive()
                        if (attempt != operationEpoch) {
                            gateway.signOut()
                            return@withContext Result.failure(AuthException.SessionChanged)
                        }
                        val account = gateway.currentUser ?: throw AuthException.FirebaseAuthFailed("Google authentication returned no user.")
                        activeAttempt = null
                        publishAccount(account)
                        Result.success(account)
                    } catch (cancel: CancellationException) {
                        gateway.signOut()
                        throw cancel
                    } finally { managedAuthWrite = false }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: GetCredentialCancellationException) {
                Result.failure(AuthException.UserCancelled)
            } catch (e: GetCredentialProviderConfigurationException) {
                Result.failure(AuthException.ProviderUnavailable)
            } catch (e: GetCredentialException) {
                Result.failure(AuthException.GetCredentialFailed("Unable to retrieve Google credentials. Please try again."))
            } catch (e: AuthException) {
                Result.failure(e)
            } catch (e: Exception) {
                // Do not copy SDK exception payloads, credentials or account details to logs/UI.
                Result.failure(AuthException.FirebaseAuthFailed("Google authentication failed. Check your connection and Firebase configuration."))
            } finally {
                if (activeAttempt == attempt) {
                    activeAttempt = null
                    publishAccount(gateway.currentUser)
                }
            }
        }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        val attempt = ++operationEpoch
        activeAttempt = attempt
        // Invalidate before waiting for a still-running Firebase sign-in task.
        invalidateSession()
        try {
            withContext(NonCancellable) {
                operationMutex.withLock {
                    if (attempt != operationEpoch) return@withLock Result.failure<Unit>(AuthException.SessionChanged)
                    managedAuthWrite = true
                    try {
                        gateway.signOut()
                        clearCredentials()
                        activeAttempt = null
                        publishAccount(null)
                        Result.success(Unit)
                    } finally { managedAuthWrite = false }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Result.failure(AuthException.FirebaseAuthFailed("Unable to sign out. Please try again."))
        } finally {
            if (activeAttempt == attempt) {
                activeAttempt = null
                publishAccount(gateway.currentUser)
            }
        }
    }

    private suspend fun clearCredentials() {
        try { gateway.clearCredentialState() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            // Firebase logout still succeeds; explicit switching never auto-selects.
            Log.w("FirebaseAuthManager", "Credential provider state could not be cleared")
        }
    }

    private fun invalidateSession() {
        _userState.value = null
        _sessionState.value = AuthSession(null, sessionState.value.generation + 1)
        invalidationListeners.forEach { (_, listener) -> listener() }
    }

    private fun publishAccount(account: FirebaseUserAccount?) {
        if (sessionState.value.uid != account?.uid) {
            invalidateSession()
            _userState.value = account
            _sessionState.value = AuthSession(account?.uid, sessionState.value.generation + 1)
        } else _userState.value = account
    }

    companion object {
        private fun initializedAuth(context: Context): FirebaseAuth? = try {
            com.example.firebase.FirebaseInitHelper.ensureFirebaseApp(context)?.let { FirebaseAuth.getInstance(it) }
        } catch (_: Exception) { null }
    }
}
