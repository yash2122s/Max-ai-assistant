package com.example.ui.onboarding

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MyApplication
import com.example.data.preferences.SettingsManager
import com.example.firebase.auth.AuthException
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MyApplication
    private val settingsManager = SettingsManager(application)

    private val _uiState = MutableStateFlow(loadCurrentState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _events = Channel<OnboardingUiEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingUiEvent> = _events.receiveAsFlow()

    init {
        // Observe Firebase Auth and License updates reactively
        viewModelScope.launch {
            app.maxLicenseManager.currentUser.collect { user ->
                _uiState.update { current ->
                    if (user == null && !current.isAuthenticating) current.copy(userAccount = null,
                        currentStep = OnboardingStep.AUTH, stage = SettingsManager.OnboardingStage.AUTH_REQUIRED)
                    else current.copy(userAccount = user)
                }
            }
        }
        viewModelScope.launch {
            app.maxLicenseManager.currentLicense.collect { license ->
                _uiState.update { current ->
                    current.copy(cloudLicense = license)
                }
            }
        }
    }

    fun isAuthenticationRequired(): Boolean {
        return app.maxLicenseManager.currentUser.value == null
    }

    fun refreshPermissions() {
        _uiState.update { current ->
            val context = getApplication<Application>()
            val isAccessibility = JarvisAccessibilityService.isServiceRunning()
            val isMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val isOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } else true

            current.copy(
                isAccessibilityGranted = isAccessibility,
                isMicrophoneGranted = isMic,
                isOverlayGranted = isOverlay,
                isBatteryOptimizationsIgnored = isBattery
            )
        }
    }

    private fun loadCurrentState(): OnboardingUiState {
        val context = getApplication<Application>()
        val isAccessibility = JarvisAccessibilityService.isServiceRunning()
        val isMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else true

        val stage = settingsManager.onboardingStage
        val currentUser = app.maxLicenseManager.currentUser.value

        // Gatekeeper: Without an authenticated Google user, always force AUTH_REQUIRED step
        val currentStep = if (currentUser == null) {
            settingsManager.onboardingStage = SettingsManager.OnboardingStage.AUTH_REQUIRED
            OnboardingStep.AUTH
        } else {
            when (stage) {
                SettingsManager.OnboardingStage.NOT_STARTED,
                SettingsManager.OnboardingStage.AUTH_REQUIRED,
                SettingsManager.OnboardingStage.AUTHENTICATING,
                SettingsManager.OnboardingStage.AUTHENTICATED,
                SettingsManager.OnboardingStage.LICENSE_INITIALIZING -> OnboardingStep.WELCOME
                SettingsManager.OnboardingStage.PERMISSIONS_REQUIRED,
                SettingsManager.OnboardingStage.PERMISSIONS_IN_PROGRESS -> OnboardingStep.PERMISSIONS
                SettingsManager.OnboardingStage.AI_CONFIG_REQUIRED,
                SettingsManager.OnboardingStage.AI_CONFIG_IN_PROGRESS -> OnboardingStep.AI_CONFIG
                SettingsManager.OnboardingStage.COMPLETED -> OnboardingStep.WELCOME
            }
        }

        return OnboardingUiState(
            currentStep = currentStep,
            stage = settingsManager.onboardingStage,
            userAccount = currentUser,
            cloudLicense = app.maxLicenseManager.currentLicense.value,
            geminiApiKey = settingsManager.geminiApiKey,
            liveVoiceName = settingsManager.liveVoiceName,
            isLiveVoiceMode = settingsManager.isLiveVoiceMode,
            isAccessibilityGranted = isAccessibility,
            isMicrophoneGranted = isMic,
            isOverlayGranted = isOverlay,
            isBatteryOptimizationsIgnored = isBattery,
            isCompleted = stage == SettingsManager.OnboardingStage.COMPLETED
        )
    }

    fun signInWithGoogle(activity: Activity) {
        if (_uiState.value.isAuthenticating) return // Debounce duplicate taps
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAuthenticating = true,
                    authError = null,
                    stage = SettingsManager.OnboardingStage.AUTHENTICATING
                )
            }
            settingsManager.onboardingStage = SettingsManager.OnboardingStage.AUTHENTICATING

            val result = app.maxLicenseManager.signInWithGoogle(activity)
            if (result.exceptionOrNull() is AuthException.SessionChanged) {
                _uiState.update { it.copy(isAuthenticating = false, userAccount = app.maxLicenseManager.currentUser.value) }
                return@launch
            }
            if (result.isSuccess) {
                val user = result.getOrNull()
                settingsManager.onboardingStage = SettingsManager.OnboardingStage.AUTHENTICATED
                _uiState.update {
                    it.copy(
                        isAuthenticating = false,
                        userAccount = user,
                        cloudLicense = app.maxLicenseManager.currentLicense.value,
                        stage = SettingsManager.OnboardingStage.AUTHENTICATED,
                        authError = null
                    )
                }
                _events.send(OnboardingUiEvent.ShowToast("Signed in as ${user?.email ?: user?.displayName ?: "Google User"}"))
                if (settingsManager.onboardingStage == SettingsManager.OnboardingStage.COMPLETED) {
                    _events.send(OnboardingUiEvent.OnboardingFinished)
                } else {
                    goToStep(OnboardingStep.WELCOME)
                }
            } else {
                val err = result.exceptionOrNull()
                val userMsg = when (err) {
                    is AuthException.UserCancelled -> "Sign-in was cancelled."
                    is AuthException.ProviderUnavailable -> "Google Sign-In is currently unavailable on this device."
                    is AuthException.ConfigurationError -> "Firebase configuration error. Please check google-services.json."
                    else -> err?.message ?: "We couldn't complete your sign-in. Please try again."
                }
                settingsManager.onboardingStage = SettingsManager.OnboardingStage.AUTH_REQUIRED
                _uiState.update {
                    it.copy(
                        isAuthenticating = false,
                        authError = userMsg,
                        stage = SettingsManager.OnboardingStage.AUTH_REQUIRED
                    )
                }
                _events.send(OnboardingUiEvent.ShowToast(userMsg))
            }
        }
    }

    fun goToStep(step: OnboardingStep) {
        if (isAuthenticationRequired()) {
            _uiState.update { it.copy(currentStep = OnboardingStep.AUTH, stage = SettingsManager.OnboardingStage.AUTH_REQUIRED) }
            return
        }
        _uiState.update { it.copy(currentStep = step) }
        when (step) {
            OnboardingStep.AUTH -> settingsManager.onboardingStage = SettingsManager.OnboardingStage.AUTH_REQUIRED
            OnboardingStep.WELCOME -> settingsManager.onboardingStage = SettingsManager.OnboardingStage.PERMISSIONS_REQUIRED
            OnboardingStep.PERMISSIONS -> settingsManager.onboardingStage = SettingsManager.OnboardingStage.PERMISSIONS_IN_PROGRESS
            OnboardingStep.AI_CONFIG -> settingsManager.onboardingStage = SettingsManager.OnboardingStage.AI_CONFIG_IN_PROGRESS
        }
        refreshPermissions()
    }

    fun updateGeminiApiKey(key: String) {
        val trimmed = key.trim()
        _uiState.update { it.copy(geminiApiKey = trimmed) }
        settingsManager.geminiApiKey = trimmed
        if (trimmed.isNotEmpty()) {
            com.example.domain.usecase.AssistantUseCase(getApplication()).ensureConnected()
        }
    }

    fun updateLiveVoiceName(voiceName: String) {
        _uiState.update { it.copy(liveVoiceName = voiceName) }
        settingsManager.liveVoiceName = voiceName
    }

    fun completeOnboarding() {
        if (isAuthenticationRequired()) {
            _uiState.update { it.copy(currentStep = OnboardingStep.AUTH, stage = SettingsManager.OnboardingStage.AUTH_REQUIRED) }
            return
        }
        settingsManager.onboardingStage = SettingsManager.OnboardingStage.COMPLETED
        _uiState.update { it.copy(isCompleted = true) }
        viewModelScope.launch {
            com.example.domain.usecase.AssistantUseCase(getApplication()).ensureConnected()
            _events.send(OnboardingUiEvent.OnboardingFinished)
        }
    }

    fun requestAccessibility() {
        val context = getApplication<Application>()
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
        } catch (e: Exception) {
            viewModelScope.launch { _events.send(OnboardingUiEvent.ShowToast("Could not open accessibility settings")) }
        }
    }

    fun requestOverlay() {
        val context = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            viewModelScope.launch { _events.send(OnboardingUiEvent.ShowToast("Could not open overlay settings")) }
        }
    }

    fun requestBatteryOptimization() {
        val context = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            viewModelScope.launch { _events.send(OnboardingUiEvent.ShowToast("Could not open battery settings")) }
        }
    }
}
