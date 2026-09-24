package com.example.ui.onboarding

import com.example.data.preferences.SettingsManager
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.FirebaseUserAccount

enum class OnboardingStep {
    AUTH,
    WELCOME,
    PERMISSIONS,
    AI_CONFIG
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.AUTH,
    val stage: SettingsManager.OnboardingStage = SettingsManager.OnboardingStage.NOT_STARTED,
    val isAuthenticating: Boolean = false,
    val authError: String? = null,
    val userAccount: FirebaseUserAccount? = null,
    val cloudLicense: CloudLicense? = null,
    val geminiApiKey: String = "",
    val liveVoiceName: String = "Aoede",
    val isLiveVoiceMode: Boolean = true,
    val isAccessibilityGranted: Boolean = false,
    val isMicrophoneGranted: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isBatteryOptimizationsIgnored: Boolean = false,
    val isCompleted: Boolean = false
)

sealed interface OnboardingUiEvent {
    data class ShowToast(val message: String) : OnboardingUiEvent
    data object OnboardingFinished : OnboardingUiEvent
}
