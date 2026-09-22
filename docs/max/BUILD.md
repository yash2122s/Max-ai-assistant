# Build & Environment — MAX

## Build Configuration

| Property | Value |
|----------|-------|
| Project name | Gemini Live |
| App module | `:app` |
| App ID | `com.maxaiagent.yash` |
| Package | `com.example` |
| compileSdk | 36 (API 36.1 — `release(36) { minorApiLevel = 1 }`) |
| targetSdk | 36 |
| minSdk | 24 (Android 7.0) |
| versionCode | 1 |
| versionName | 1.0 |
| Kotlin | (via BOM, likely 2.x) |
| Java compatibility | VERSION_11 |
| Build features | Compose=true, AIDL=true, BuildConfig=true |

## Key Plugins
- `com.android.application`
- `org.jetbrains.kotlin.plugin.compose`
- `com.google.devtools.ksp` (Room + Moshi codegen)
- `io.github.takahirom.roborazzi` (screenshot testing)
- `com.google.android.libraries.mapsplatform.secrets-gradle-plugin`
- `com.google.gms.google-services` (Firebase, MissingGoogleServicesStrategy.WARN)

## Environment

| Property | Value |
|----------|-------|
| Host OS | Windows 11 x64 |
| Workspace | `d:\max app testing\gemini-live\` |
| JAVA_HOME | `C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot` |
| Android SDK | `C:\Users\yaswa\AppData\Local\Android\Sdk` |
| ADB | `...\platform-tools\adb.exe` |
| Target Device | Redmi K20 Pro (device ID: 3bc00016) |
| Android version | Android 16 (API 36) |
| Architecture | arm64-v8a |
| Root | Enabled |
| Shizuku | Running |

## Build Commands
```powershell
# Set JAVA_HOME (always required)
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"

# Fast debug build
.\gradlew.bat assembleDebug

# Clean build
.\gradlew.bat clean assembleDebug

# Install debug
.\gradlew.bat installDebug

# Manual install
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Launch
adb shell am start -n com.example/.MainActivity
```

## Secrets / API Keys
Loaded via `secrets-gradle-plugin`:
- Properties file: `.env`
- Example file: `.env.example`
- **SECRET PRESENT — DO NOT RECORD VALUES**

## Key Dependencies
| Dependency | Purpose |
|-----------|---------|
| `androidx.compose.*` (BOM) | UI framework |
| `androidx.room.*` | Local database |
| `androidx.navigation.compose` | In-app navigation |
| `androidx.security.crypto` | EncryptedSharedPreferences |
| `firebase.ai` | Firebase AI SDK |
| `firebase.appcheck.recaptcha` | App Check |
| `okhttp` + `logging-interceptor` | WebSocket + HTTP |
| `retrofit` + `moshi` + `converter.moshi` | REST API |
| `gson` | JSON parsing (in network layer) |
| `shizuku.api` + `shizuku.provider` | Elevated shell execution |
| `kotlinx.coroutines.*` | Async/coroutines |
| `play.services.code.scanner` | QR/barcode (not actively used) |
| `roborazzi` | Screenshot testing |
| `robolectric` | Unit tests on JVM |

## Commented-Out (Available to Add)
- `accompanist.permissions`
- `androidx.camera.*` (camera2, core, lifecycle, view)
- `androidx.datastore.preferences`
- `coil.compose`
- `play.services.location`
- Firebase Auth, Firestore, Credentials

## Build Constraints
- Do NOT use Oracle Java 8 — use JDK 25 (Eclipse Adoptium)
- ProGuard disabled (`isMinifyEnabled = false` in release)
- Release signing uses env vars: `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`
- Debug signing uses `debug.keystore` in project root
- `usesCleartextTraffic = true` — for local network companion (CompanionConnectionService)

## APK Output
`app/build/outputs/apk/debug/app-debug.apk`

## Logcat Tags (Filter for Debugging)
```
ScreenCaptureProvider, GeminiVisionReasoner, GeminiWebSocket
AssistantEngine, ExecutionEngine, Accessibility
CameraTool, ToolDispatcher, WA_DEBUG (WhatsApp)
```
