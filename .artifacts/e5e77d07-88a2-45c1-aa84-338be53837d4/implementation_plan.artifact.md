# Implementation Plan - MAX AI Onboarding Flow

Implement a proper first-run onboarding experience for MAX AI Assistant, including a welcome screen, a permissions/setup checklist, and an AI model configuration screen.

## User Review Required

> [!IMPORTANT]
> The onboarding flow will be the first thing new users see. It will request critical permissions (Accessibility, Microphone, etc.) and AI configuration (API Key) before allowing access to the main chat UI.

## Proposed Changes

### UI Screens

#### [NEW] [OnboardingWelcomeScreen.kt](file:///D:/max app testing/gemini-live/app/src/main/java/com/example/ui/screens/onboarding/OnboardingWelcomeScreen.kt)
- Welcome message with MAX logo.
- "Get Started" button to navigate to the setup screen.

#### [NEW] [OnboardingSetupScreen.kt](file:///D:/max app testing/gemini-live/app/src/main/java/com/example/ui/screens/onboarding/OnboardingSetupScreen.kt)
- Checklist of items:
    - Accessibility (Redirects to system settings).
    - Microphone (Runtime permission).
    - Notifications (Runtime permission).
    - Contacts (Runtime permission).
    - Phone (Runtime permission).
    - SMS (Runtime permission).
    - Configure AI Model (Navigates to AI Config screen).
- "Continue" button enabled only when essential items are configured.

#### [NEW] [OnboardingAiConfigScreen.kt](file:///D:/max app testing/gemini-live/app/src/main/java/com/example/ui/screens/onboarding/OnboardingAiConfigScreen.kt)
- Configuration for Provider, Model, and API Key.
- "Test Connection" button.
- "Save & Continue" button to return to the setup checklist.

### Core Logic

#### [MODIFY] [MainActivity.kt](file:///D:/max app testing/gemini-live/app/src/main/java/com/example/MainActivity.kt)
- Check `onboarding_completed` flag in `SharedPreferences` on launch.
- Add navigation routes for `onboarding_welcome`, `onboarding_setup`, and `onboarding_ai_config`.
- Set `startDestination` of `NavHost` based on the `onboarding_completed` flag.

## Verification Plan

### Manual Verification
- Fresh install (or clear app data) to trigger the onboarding flow.
- Verify Welcome Screen displays correctly.
- Verify Checklist Screen items update dynamically (`Allow / Configure` -> `✓ Ready`).
- Verify AI Config Screen saves settings correctly.
- Verify that after completion, subsequent launches bypass onboarding and go directly to Chat UI.
- Verify all requested permissions are handled (Accessibility, Runtime).
