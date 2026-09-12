# Onboarding — MAX

## Purpose
First-run setup: collect user name, request permissions, configure Gemini API key and voice settings.

## Onboarding Stages (enum in SettingsManager)
```
NOT_STARTED → PERMISSIONS_IN_PROGRESS → AI_CONFIG_IN_PROGRESS → COMPLETED
```

## Entry Logic (MainActivity)
```kotlin
when (settingsManager.onboardingStage) {
    NOT_STARTED → navigate to onboarding_welcome
    PERMISSIONS_IN_PROGRESS → navigate to onboarding_setup
    AI_CONFIG_IN_PROGRESS → navigate to onboarding_ai_config
    COMPLETED → proceed to main chat screen
}
```

## Screens

### OnboardingWelcomeScreen
- File: `ui/screens/onboarding/OnboardingWelcomeScreen.kt`
- Collects: user name (`SettingsManager.userName`)
- On continue: sets stage → PERMISSIONS_IN_PROGRESS → navigate to onboarding_setup

### OnboardingSetupScreen
- File: `ui/screens/onboarding/OnboardingSetupScreen.kt`
- Guides user through all critical permissions:
  - Microphone (RECORD_AUDIO)
  - Notification (POST_NOTIFICATIONS)
  - Accessibility Service
  - Notification Listener
  - Overlay (SYSTEM_ALERT_WINDOW)
  - Battery optimization exemption
  - Phone state (for call detection)
  - Contacts
- Shows live status for each permission
- On complete: sets stage → AI_CONFIG_IN_PROGRESS → navigate to onboarding_ai_config

### AiConfigScreen (Flutter)
- File: `max_flutter/lib/features/onboarding/ai_config_screen.dart`
- Interface:
  - Header: "Gemini AI Studio" / "Connect your Gemini AI account"
  - Inputs: Gemini AI Studio API key (masked with reveal toggle)
  - Validation: "Test Connection" button performs live ping to Gemini endpoint
  - Progress/Status: Displays "Testing..." state and verified badge ("✓ Gemini connected successfully")
  - Transition: "Complete Setup" enabled ONLY after successful connection test
- Internal Settings:
  - Persists API key to `SettingsManager.geminiApiKey` and SharedPreferences via `MaxNativeBridge`
  - Internal model defaults to `gemini-3.1-flash-live-preview` without exposing model configuration to the user
- On complete: sets stage → COMPLETED → navigate to Flutter `/home`

## Persistence
- Stage stored as string in `app_settings` SharedPreferences key `onboarding_stage_v3`
- Legacy migration: if `has_completed_onboarding` == true → treated as COMPLETED

## Current Status: IMPLEMENTED ✅
All 3 screens implemented in Flutter. Stage persisted. Navigation flow verified on device.
