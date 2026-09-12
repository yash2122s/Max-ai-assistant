package com.example.ui.onboarding

import com.example.data.preferences.SettingsManager

enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    AI_CONFIG
}

data class OnboardingUiState(
    val currentStep: OnboardingStep = OnboardingStep.WELCOME,
    val stage: SettingsManager.OnboardingStage = SettingsManager.OnboardingStage.NOT_STARTED,
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
    object OnboardingFinished : OnboardingUiEvent
}
