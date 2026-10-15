package com.example.firebase.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.firebase.model.FirebaseUserAccount
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/** SDK boundary for the existing manager; contains no account/session policy. */
internal interface GoogleAuthGateway {
    val currentUser: FirebaseUserAccount?
    fun observeAuth(listener: () -> Unit)
    suspend fun googleToken(activity: Activity, clientId: String, authorizedOnly: Boolean): String
    suspend fun signIn(token: String)
    fun signOut()
    suspend fun clearCredentialState()
}

internal class AndroidGoogleAuthGateway(context: Context, private val auth: FirebaseAuth?) : GoogleAuthGateway {
    private val credentials = CredentialManager.create(context)
    override val currentUser: FirebaseUserAccount?
        get() = auth?.currentUser?.let { user ->
            FirebaseUserAccount(user.uid, user.email, user.displayName, user.photoUrl?.toString(),
                user.isAnonymous, user.metadata?.creationTimestamp ?: 0L, user.metadata?.lastSignInTimestamp ?: 0L)
        }

    override fun observeAuth(listener: () -> Unit) {
        auth?.addAuthStateListener { listener() }
    }

    override suspend fun googleToken(activity: Activity, clientId: String, authorizedOnly: Boolean): String {
        if (auth == null) throw AuthException.ConfigurationError
        val option = GetGoogleIdOption.Builder().setServerClientId(clientId)
            .setFilterByAuthorizedAccounts(authorizedOnly).setAutoSelectEnabled(authorizedOnly).build()
        val credential = credentials.getCredential(activity,
            GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AuthException.InvalidCredentialType(credential::class.java.simpleName)
        }
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }

    override suspend fun signIn(token: String) {
        val firebaseAuth = auth ?: throw AuthException.ConfigurationError
        firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    override fun signOut() { auth?.signOut() }
    override suspend fun clearCredentialState() {
        credentials.clearCredentialState(ClearCredentialStateRequest())
    }
}
