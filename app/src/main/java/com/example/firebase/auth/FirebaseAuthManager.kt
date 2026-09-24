package com.example.firebase.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.firebase.model.FirebaseUserAccount
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object UserCancelled : AuthException("Sign-in was cancelled by user.")
    object ProviderUnavailable : AuthException("Google Sign-In service is currently unavailable on this device.")
    class GetCredentialFailed(reason: String) : AuthException("Credential retrieval failed: $reason")
    class InvalidCredentialType(typeName: String) : AuthException("Unexpected credential type: $typeName")
    class FirebaseAuthFailed(reason: String, cause: Throwable? = null) : AuthException("Firebase authentication failed: $reason", cause)
    object ConfigurationError : AuthException("Firebase configuration or Web Client ID error.")
}

class FirebaseAuthManager(
    private val context: Context,
    private val auth: FirebaseAuth? = try {
        val app = com.example.firebase.FirebaseInitHelper.ensureFirebaseApp(context)
        if (app != null) FirebaseAuth.getInstance(app) else null
    } catch (e: Exception) {
        Log.w(TAG, "FirebaseAuth initialization deferred/unavailable: ${e.message}")
        null
    }
) {
    private val credentialManager: CredentialManager = CredentialManager.create(context)

    private val _userState = MutableStateFlow<FirebaseUserAccount?>(mapFirebaseUser(auth?.currentUser))
    val userState: StateFlow<FirebaseUserAccount?> = _userState.asStateFlow()

    init {
        try {
            auth?.addAuthStateListener { firebaseAuth ->
                val account = mapFirebaseUser(firebaseAuth.currentUser)
                _userState.value = account
                if (account != null) {
                    Log.d(TAG, "[AUTH] firebaseAuthSuccess. Active user UID: ${account.uid}")
                } else {
                    Log.d(TAG, "[AUTH] authRequired / Signed Out")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AuthStateListener: ${e.message}")
        }
    }

    val currentUid: String?
        get() = auth?.currentUser?.uid

    val isAuthenticated: Boolean
        get() = auth?.currentUser != null

    suspend fun signInWithGoogle(
        activity: Activity,
        customServerClientId: String? = null
    ): Result<FirebaseUserAccount> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "[AUTH] signInStarted")
            val serverClientId = customServerClientId
                ?: getWebClientIdFromResources()
                ?: FALLBACK_WEB_CLIENT_ID

            if (serverClientId.isBlank()) {
                Log.e(TAG, "[AUTH] ConfigurationError: Web Client ID is missing or blank")
                return@withContext Result.failure(AuthException.ConfigurationError)
            }

            // Stage 1: Try authorized credentials first (returning user fast-path)
            val response = try {
                Log.d(TAG, "[AUTH] authorizedCredentialAttempt starting...")
                val authorizedOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(serverClientId)
                    .setAutoSelectEnabled(true)
                    .build()

                val request1 = GetCredentialRequest.Builder()
                    .addCredentialOption(authorizedOption)
                    .build()

                credentialManager.getCredential(activity, request1)
            } catch (e: GetCredentialCancellationException) {
                Log.i(TAG, "[AUTH] User cancelled during authorized credential attempt.")
                return@withContext Result.failure(AuthException.UserCancelled)
            } catch (e: GetCredentialException) {
                Log.d(TAG, "[AUTH] noAuthorizedCredential (${e.message}). Proceeding to interactiveFallback...")

                // Stage 2: First-time user interactive fallback (shows Google Account Picker directly)
                try {
                    Log.d(TAG, "[AUTH] interactiveFallback starting...")
                    val interactiveOption = GetGoogleIdOption.Builder()
                        .setFilterByAuthorizedAccounts(false)
                        .setServerClientId(serverClientId)
                        .setAutoSelectEnabled(false)
                        .build()

                    val request2 = GetCredentialRequest.Builder()
                        .addCredentialOption(interactiveOption)
                        .build()

                    credentialManager.getCredential(activity, request2)
                } catch (cancelEx: GetCredentialCancellationException) {
                    Log.i(TAG, "[AUTH] User cancelled during interactive Google account selection.")
                    return@withContext Result.failure(AuthException.UserCancelled)
                } catch (fallbackEx: GetCredentialException) {
                    Log.e(TAG, "[AUTH] Interactive CredentialManager failed: ${fallbackEx.message}", fallbackEx)
                    return@withContext Result.failure(AuthException.GetCredentialFailed(fallbackEx.message ?: "Failed to get Google credentials"))
                }
            }

            val credential = response.credential
            val idToken = when {
                credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    Log.d(TAG, "[AUTH] googleCredentialReceived successfully")
                    googleCredential.idToken
                }
                else -> {
                    Log.e(TAG, "[AUTH] Invalid credential type received: ${credential::class.java.name}")
                    return@withContext Result.failure(AuthException.InvalidCredentialType(credential::class.java.name))
                }
            }

            Log.d(TAG, "[AUTH] firebaseAuthStarted")
            val firebaseAuth = auth ?: return@withContext Result.failure(AuthException.FirebaseAuthFailed("FirebaseAuth is not initialized"))
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(authCredential).await()
            val user = authResult.user
                ?: return@withContext Result.failure(AuthException.FirebaseAuthFailed("Firebase authentication returned null user."))

            val account = mapFirebaseUser(user) ?: FirebaseUserAccount(uid = user.uid)
            _userState.value = account
            Log.i(TAG, "[AUTH] firebaseAuthSuccess for Google User UID: ${account.uid}")
            Result.success(account)
        } catch (e: AuthException) {
            Log.w(TAG, "[AUTH] AuthException caught: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "[AUTH] firebaseAuthFailure: ${e.message}", e)
            Result.failure(AuthException.FirebaseAuthFailed(e.message ?: "Authentication failed", e))
        }
    }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Signing out user: ${auth?.currentUser?.uid}...")
            auth?.signOut()
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing credential state: ${e.message}")
            }
            _userState.value = null
            Log.i(TAG, "Sign-out completed successfully.")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error during sign-out: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun getWebClientIdFromResources(): String? {
        return try {
            val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
            if (resId != 0) {
                context.getString(resId)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun mapFirebaseUser(user: FirebaseUser?): FirebaseUserAccount? {
        if (user == null) return null
        return FirebaseUserAccount(
            uid = user.uid,
            email = user.email,
            displayName = user.displayName,
            photoUrl = user.photoUrl?.toString(),
            isAnonymous = user.isAnonymous,
            createdAtEpochMillis = user.metadata?.creationTimestamp ?: System.currentTimeMillis(),
            lastLoginAtEpochMillis = user.metadata?.lastSignInTimestamp ?: System.currentTimeMillis()
        )
    }

    companion object {
        private const val TAG = "FirebaseAuthManager"
        const val FALLBACK_WEB_CLIENT_ID = "789767250832-co5efn9n8fih5rchac5v9kdildivokil.apps.googleusercontent.com"
    }
}
