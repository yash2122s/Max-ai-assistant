package com.example.ui.license

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MyApplication
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.DeviceRegistration
import com.example.firebase.model.FirebaseUserAccount
import com.example.firebase.model.LicenseSyncState
import com.example.license.entitlement.Entitlement
import com.example.license.licensing.SignedLicense
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed class LicenseImportState {
    data object Idle : LicenseImportState()
    data object Loading : LicenseImportState()
    data class Success(val message: String) : LicenseImportState()
    data class Error(val error: String) : LicenseImportState()
}

/** After logout the cloud fast-path is gone; refresh reads synchronous Room DAOs. */
internal suspend fun com.example.license.entitlement.EntitlementManager.refreshForAccountUi() =
    withContext(Dispatchers.IO) { refresh() }

class LicenseViewModel(application: Application) : AndroidViewModel(application) {

    private val json = Json { ignoreUnknownKeys = true }
    private val app = application as MyApplication

    val entitlement: StateFlow<Entitlement> = app.entitlementManager.entitlementFlow
    val currentUser: StateFlow<FirebaseUserAccount?> = app.maxLicenseManager.currentUser
    val cloudLicense: StateFlow<CloudLicense> = app.maxLicenseManager.currentLicense
    val dailyUsageCount: StateFlow<Long> = app.maxLicenseManager.dailyUsageCount
    val syncState: StateFlow<LicenseSyncState> = app.maxLicenseManager.syncState
    val currentDevice: StateFlow<DeviceRegistration?> = app.maxLicenseManager.currentDevice

    private val _importState = MutableStateFlow<LicenseImportState>(LicenseImportState.Idle)
    val importState: StateFlow<LicenseImportState> = _importState.asStateFlow()
    private var operation: Job? = null
    private var authenticationOperation = false

    init {
        app.maxLicenseManager.authManager.addSessionInvalidationListener(this) {
            if (!authenticationOperation) operation?.cancel()
            _importState.value = LicenseImportState.Idle
        }
    }

    private fun beginOperation(authAction: Boolean = false, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        authenticationOperation = authAction
        operation = viewModelScope.launch {
            try { block() } finally { authenticationOperation = false }
        }
    }

    fun resetState() {
        _importState.value = LicenseImportState.Idle
    }

    fun activateLicenseKey(rawKey: String) {
        val session = app.maxLicenseManager.authManager.sessionState.value
        viewModelScope.launch {
            _importState.value = LicenseImportState.Loading
            try {
                val cleaned = com.example.firebase.model.LicenseKeyHelper.normalize(rawKey)
                if (!com.example.firebase.model.LicenseKeyHelper.isValidFormat(cleaned)) {
                    _importState.value = LicenseImportState.Error("Invalid key format. Expected: MAX-LIFE-XXXX-XXXX-XXXX")
                    return@launch
                }

                val result = app.maxLicenseManager.claimLicenseKey(cleaned)
                if (!app.maxLicenseManager.authManager.isCurrentSession(session)) return@launch
                if (result.isSuccess) {
                    app.entitlementManager.refresh()
                    if (!app.maxLicenseManager.authManager.isCurrentSession(session)) return@launch
                    try {
                        com.example.voice.assistant.AssistantLifecycleManager.resume()
                        val useCase = com.example.domain.usecase.AssistantUseCase(app)
                        useCase.reconnect()
                        useCase.ensureConnected()
                    } catch (e: Exception) {
                        android.util.Log.w("LicenseViewModel", "Failed to auto-reconnect assistant engine: ${e.message}")
                    }
                    _importState.value = LicenseImportState.Success("LIFETIME PREMIUM Activated Successfully!")
                } else {
                    val err = result.exceptionOrNull()
                    val userMsg = when {
                        err is NoSuchElementException -> "Invalid license key. Key does not exist."
                        err is IllegalStateException && err.message?.contains("claimed by another", ignoreCase = true) == true ->
                            "This license key has already been claimed by another account."
                        else -> err?.message ?: "Failed to activate license key."
                    }
                    _importState.value = LicenseImportState.Error(userMsg)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!app.maxLicenseManager.authManager.isCurrentSession(session)) return@launch
                _importState.value = LicenseImportState.Error("Activation failed: ${e.message}")
            }
        }
    }

    fun signInWithGoogle(activity: Activity) {
        beginOperation(authAction = true) {
            _importState.value = LicenseImportState.Loading
            val result = app.maxLicenseManager.signInWithGoogle(activity)
            if (result.exceptionOrNull() is com.example.firebase.auth.AuthException.SessionChanged ||
                result.exceptionOrNull() is com.example.firebase.auth.AuthException.UserCancelled) {
                _importState.value = LicenseImportState.Idle
                return@beginOperation
            }
            if (result.isSuccess) {
                val user = result.getOrNull()
                _importState.value = LicenseImportState.Success("Signed in as ${user?.email ?: user?.displayName ?: "Google User"}")
            } else {
                _importState.value = LicenseImportState.Error(result.exceptionOrNull()?.message ?: "Sign in failed")
            }
        }
    }

    fun switchAccount(activity: Activity) {
        beginOperation(authAction = true) {
            _importState.value = LicenseImportState.Loading
            val result = app.maxLicenseManager.switchAccount(activity)
            if (result.exceptionOrNull() is com.example.firebase.auth.AuthException.SessionChanged ||
                result.exceptionOrNull() is com.example.firebase.auth.AuthException.UserCancelled) {
                _importState.value = LicenseImportState.Idle
                return@beginOperation
            }
            _importState.value = if (result.isSuccess) {
                LicenseImportState.Success("Switched to ${result.getOrNull()?.email ?: "Google account"}")
            } else LicenseImportState.Error(result.exceptionOrNull()?.message ?: "Account switch failed")
        }
    }

    fun signOut() {
        beginOperation(authAction = true) {
            _importState.value = LicenseImportState.Loading
            val result = app.maxLicenseManager.signOut()
            if (result.isSuccess) {
                app.entitlementManager.refreshForAccountUi()
                if (currentUser.value != null) return@beginOperation
                _importState.value = LicenseImportState.Success("Signed out successfully.")
            } else {
                _importState.value = LicenseImportState.Error(result.exceptionOrNull()?.message ?: "Sign out failed")
            }
        }
    }

    fun syncLicense() {
        val user = currentUser.value ?: return
        val session = app.maxLicenseManager.authManager.sessionState.value
        beginOperation {
            _importState.value = LicenseImportState.Loading
            val result = app.maxLicenseManager.syncWithCloud(user.uid)
            if (!app.maxLicenseManager.authManager.isCurrentSession(session)) return@beginOperation
            if (result.isSuccess) {
                app.entitlementManager.refresh()
                if (!app.maxLicenseManager.authManager.isCurrentSession(session)) return@beginOperation
                _importState.value = LicenseImportState.Success("License synchronized with cloud.")
            } else {
                _importState.value = LicenseImportState.Error(result.exceptionOrNull()?.message ?: "Sync failed")
            }
        }
    }

    fun importLicenseJson(rawJson: String) {
        viewModelScope.launch {
            _importState.value = LicenseImportState.Loading
            try {
                val cleaned = rawJson.trim()
                if (cleaned.isBlank()) {
                    _importState.value = LicenseImportState.Error("License content cannot be empty.")
                    return@launch
                }
                val signedLicense = json.decodeFromString(SignedLicense.serializer(), cleaned)

                val verification = app.licenseVerifier.verify(signedLicense)
                when (verification) {
                    is com.example.license.licensing.LicenseVerificationResult.InvalidSignature -> {
                        _importState.value = LicenseImportState.Error("Invalid cryptographic signature or tampered license.")
                        return@launch
                    }
                    is com.example.license.licensing.LicenseVerificationResult.Malformed -> {
                        _importState.value = LicenseImportState.Error("Malformed license signature encoding.")
                        return@launch
                    }
                    is com.example.license.licensing.LicenseVerificationResult.UnsupportedVersion -> {
                        _importState.value = LicenseImportState.Error("Unsupported license schema version.")
                        return@launch
                    }
                    is com.example.license.licensing.LicenseVerificationResult.Valid -> {
                        if (verification.payload.licenseId in app.revocationProvider.cachedRevokedIds()) {
                            _importState.value = LicenseImportState.Error("This license has been revoked by the issuer.")
                            return@launch
                        }
                        if (java.time.Instant.parse(verification.payload.expiresAt).isBefore(java.time.Instant.now())) {
                            _importState.value = LicenseImportState.Error("This license has expired.")
                            return@launch
                        }

                        // Valid signature, unrevoked, unexpired: Persist and refresh
                        app.licenseStorage.setStoredLicense(signedLicense)
                        app.entitlementManager.refresh()
                        _importState.value = LicenseImportState.Success("Premium License Activated successfully!")
                    }
                }
            } catch (e: Exception) {
                _importState.value = LicenseImportState.Error("Failed to parse license: ${e.message ?: "Invalid format"}")
            }
        }
    }

    fun removeLicense() {
        viewModelScope.launch {
            app.licenseStorage.clearStoredLicense()
            app.entitlementManager.refresh()
            _importState.value = LicenseImportState.Success("Offline license removed.")
        }
    }

    override fun onCleared() {
        app.maxLicenseManager.authManager.removeSessionInvalidationListener(this)
        super.onCleared()
    }
}
