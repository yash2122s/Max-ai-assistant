# MAX — PHYSICAL DEVICE VALIDATION CHECKLIST (Redmi K20 Pro)

This checklist provides step-by-step instructions for validating the 13 core fixes on physical hardware.

---

## Prerequisites & Installation

1. **Connect device via USB** and ensure USB Debugging is enabled:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
   ```
2. **Install fresh debug build**:
   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r -d app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 13-Point Forensic Validation Suite

| # | Test Area | Test Procedure | Expected Result | Pass/Fail |
|---|---|---|---|:---:|
| **1** | **Fresh Install → Onboarding** | Clear app data (`adb shell pm clear com.example`) and launch app. | Opens directly to `OnboardingScreen` (not `HomeScreen`). Completing onboarding transitions to `HOME` permanently. | [ ] |
| **2** | **Gemini API Key Authority** | Enter API key in Settings or Onboarding; start voice session. | Connection succeeds using `SettingsManager.geminiApiKey` without fallback or preference desync. | [ ] |
| **3** | **Voice Pipeline Transitions** | Speak into microphone -> wait for response. | State transitions seamlessly: `Listening` (Cyan) -> `Thinking` (Purple) -> `Speaking` (Indigo). `isAiSpeaking` stops accurately with audio track completion. | [ ] |
| **4** | **Empty Home Neural Orb** | View Home screen with zero messages; trigger voice. | Neural Orb pulses and scales with live microphone amplitude (RMS) in the empty state. | [ ] |
| **5** | **Drawer on Home** | Swipe from left edge while on Home screen. | Navigation drawer opens smoothly. | [ ] |
| **6** | **Sub-Screen Back Navigation** | Navigate to Settings, Memory, or Routines; perform edge back gesture. | Navigates back to Home without drawer gesture interference. | [ ] |
| **7** | **In-App Permissions** | Open Permissions screen; tap Microphone, Camera, or Contacts. | Displays native in-app permission grant dialog instead of dumping to Android Settings. | [ ] |
| **8** | **Camera Persistence** | Trigger camera action ("take a photo"). | Launches Camera with MediaStore `EXTRA_OUTPUT`; captures full-resolution photo into `Pictures/MAX`. | [ ] |
| **9** | **Silent Screenshot Storage** | Trigger background screenshot ("take screenshot"). | Saves compressed JPEG screenshot directly to `Pictures/Screenshots`. | [ ] |
| **10** | **Quick Actions Feedback** | Tap Flashlight, Wi-Fi, or Bluetooth quick buttons. | Action executes immediately and displays an accessible Snackbar confirmation. | [ ] |
| **11** | **Go Home (English)** | Speak "Go to home screen" or "Go home". | Offline engine matches command immediately and executes `GLOBAL_ACTION_HOME`. | [ ] |
| **12** | **Go Home (Telugu)** | Speak "Home ki vellu" or "Intiki vellu". | Offline engine matches Telugu fast-path and returns to device home screen. | [ ] |
| **13** | **MAX OFF Quiescence** | Toggle MAX Master Switch to OFF in TopBar. | Neural Orb enters static dormant mode; audio recording and infinite animations stop completely. | [ ] |

---

## Real-Device Profiling Guide

To inspect frame rates and recomposition stability during live speech:
```powershell
# Reset frame stats
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell dumpsys gfxinfo com.example reset

# ... perform 30 seconds of live voice interaction ...

# Dump frame rendering statistics
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell dumpsys gfxinfo com.example
```
**Target**: Janky frames < 5%, 90th percentile frame time < 16.6ms (60 FPS) / 8.3ms (120 FPS).
