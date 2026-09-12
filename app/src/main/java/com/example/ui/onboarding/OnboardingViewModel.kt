package com.example.ui.onboarding

import android.Manifest
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
import com.example.data.preferences.SettingsManager
import com.example.service.JarvisAccessibilityService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager(application)

    private val _uiState = MutableStateFlow(loadCurrentState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _events = Channel<OnboardingUiEvent>(Channel.BUFFERED)
    val events: Flow<OnboardingUiEvent> = _events.receiveAsFlow()

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

        val currentStep = when (stage) {
            SettingsManager.OnboardingStage.PERMISSIONS_IN_PROGRESS -> OnboardingStep.PERMISSIONS
            SettingsManager.OnboardingStage.AI_CONFIG_IN_PROGRESS -> OnboardingStep.AI_CONFIG
            SettingsManager.OnboardingStage.COMPLETED -> OnboardingStep.WELCOME
            else -> OnboardingStep.WELCOME
        }

        return OnboardingUiState(
            currentStep = currentStep,
            stage = stage,
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

    fun goToStep(step: OnboardingStep) {
        _uiState.update { it.copy(currentStep = step) }
        when (step) {
            OnboardingStep.PERMISSIONS -> settingsManager.onboardingStage = SettingsManager.OnboardingStage.PERMISSIONS_IN_PROGRESS
            OnboardingStep.AI_CONFIG -> settingsManager.onboardingStage = SettingsManager.OnboardingStage.AI_CONFIG_IN_PROGRESS
            else -> {}
        }
        refreshPermissions()
    }

    fun updateGeminiApiKey(key: String) {
        _uiState.update { it.copy(geminiApiKey = key) }
        settingsManager.geminiApiKey = key
    }

    fun updateLiveVoiceName(voiceName: String) {
        _uiState.update { it.copy(liveVoiceName = voiceName) }
        settingsManager.liveVoiceName = voiceName
    }

    fun completeOnboarding() {
        settingsManager.onboardingStage = SettingsManager.OnboardingStage.COMPLETED
        _uiState.update { it.copy(isCompleted = true) }
        viewModelScope.launch {
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
