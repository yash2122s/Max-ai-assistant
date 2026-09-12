# MAX — Web Preview Architecture & Project Detection Report

## Phase 0: Project Type Detection

**Detection Date:** September 1, 2026  
**Auditor:** Senior Android UI & Systems Engineer  

### 1. Build & Gradle Inspection Results
- **`settings.gradle.kts`**: Single module configured (`include(":app")`).
- **`build.gradle.kts`**: Standard Android application root buildscript with `com.android.application` and `org.jetbrains.kotlin.plugin.compose`.
- **`gradle/libs.versions.toml`**: Android AGP `9.3.2`, Kotlin `2.3.20`, AndroidX Compose BOM `2024.09.00`.
- **Target Directories**: No `commonMain`, `androidMain`, `wasmJsMain`, `jsMain`, or `composeApp` exists.
- **Project Classification**: **Case A: Pure Android Jetpack Compose Application** (No existing KMP or Compose Multiplatform web target).

---

## 2. Hard Architecture Rules & Constraints
1. **Production MAX remains 100% Native Jetpack Compose on Android (`app/`)**:
   - No framework migrations (no Flutter, no React Native).
   - No production dependencies on Kotlin Multiplatform or WebAssembly.
   - All native services (Gemini Live, OpenRouter, Room, Shizuku, Accessibility, MediaStore, AudioRecord, Foreground Services) remain 100% Android-native in `app/`.
2. **Development-Only Browser Preview Strategy**:
   - The browser preview must be **100% isolated** from production Android code.
   - It will render reusable pure UI tokens, cards, status badges, buttons, neural orb visual shells, and mock interaction states.
   - It must never attempt to invoke Android OS APIs, Room DB, AudioRecord, or native platform services.

---

## 3. Web Preview Architecture Plan

### Active Implementation Architecture:
```text
Production Tier (Production Android Application):
Kotlin 2.3.20
  ↓
Jetpack Compose (Compose BOM 2024.09.00)
  ↓
Android SDK 36 (Native Android Device / Emulator)

Development Preview Tier (Visual Design & QA):
React 18 + Vite + TailwindCSS / Canvas
  ↓
Browser (http://localhost:5173 / COMPOSE UI PREVIEW)
```

### Maintenance Invariants:
1. **Design Token Sync**: Maintain parity between Kotlin Compose design tokens (`Color.kt`, `MaxDimensions.kt`) and the browser preview (`MaxComposePreviewGallery.tsx`).
2. **Zero Runtime Coupling**: The browser preview remains strictly a dev/visual mock layer; it never calls Android services, Room, Keystore, or Gemini API keys.
3. **No KMP Overhead**: Preserves the pure native Android architecture without adding KMP Gradle complexity or WebAssembly bloat to the Android build.
