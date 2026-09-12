# Flutter UI Architecture — MAX

## 1. Overview & Architecture

The MAX UI subsystem uses **Flutter** as its primary UI presentation layer while delegating all backend AI, automation, voice, accessibility, and background service execution to the **Kotlin Core**.

```
┌────────────────────────────────────────────────────────┐
│                   Flutter UI (Dart)                    │
│   WelcomeScreen → PermissionsScreen → AiConfigScreen   │
│   HomeScreen (Neural Orb, Live Captions, Drawer)       │
└───────────────────────────┬────────────────────────────┘
                            │ MethodChannel: "max/native"
┌───────────────────────────▼────────────────────────────┐
│              MAX Native Bridge (Kotlin)                │
│             com.example.flutter.MaxNativeBridge        │
└───────────────────────────┬────────────────────────────┘
                            │ Direct Kotlin Calls / Flow
┌───────────────────────────▼────────────────────────────┐
│                   MAX Kotlin Core                      │
│   • AssistantEngine          • Gemini Live WebSocket   │
│   • VoiceForegroundService   • SettingsManager         │
│   • JarvisAccessibilityService • Room DB & Memory      │
│   • Reminders / Automation   • Shizuku / SMS / Tools   │
└────────────────────────────────────────────────────────┘
```

---

## 2. Directory Structure

The Flutter module lives in `max_flutter/`:

```
max_flutter/
├── lib/
│   ├── main.dart                          # App entry point & initial route resolver
│   ├── core/
│   │   ├── bridge/
│   │   │   ├── max_native_bridge.dart     # MethodChannel API & event stream receiver
│   │   │   └── chat_controller.dart       # Chat state controller & bridge binding
│   │   └── theme/
│   │       ├── max_colors.dart            # Electric Blue, Bright Cyan, AI Purple, Glass tokens
│   │       ├── max_typography.dart        # Unified typography hierarchy
│   │       ├── max_spacing.dart           # Spacing & sizing tokens
│   │       ├── max_radius.dart            # Corner radius tokens
│   │       ├── max_animations.dart        # Animation curves and durations
│   │       └── max_theme.dart             # Dark Material 3 theme configuration
│   ├── features/
│   │   ├── onboarding/
│   │   │   ├── welcome_screen.dart        # Stage 1: Animated hero, value cards, Get Started
│   │   │   ├── permissions_screen.dart    # Stage 2: Capability cards, live status toggles
│   │   │   └── ai_config_screen.dart      # Stage 3: Gemini model selector, secure API key input
│   │   ├── home/
│   │   │   └── home_screen.dart           # Stage 4: Futuristic chat workspace, drawer, mic FAB
│   │   ├── settings/
│   │   │   └── settings_screen.dart       # System Settings: Theme, Gemini, Voice, Language, PC, Telegram, Security
│   │   └── memory/
│   │       └── memory_screen.dart         # Memory Vault: CRUD notes, categories, search, pinned, AES encryption
│   └── shared/
│       └── widgets/
│           ├── max_scaffold.dart          # Ambient atmospheric glow scaffold
│           ├── max_app_bar.dart           # MAX brand pill + live status badge + actions
│           ├── max_drawer.dart            # Categorized navigation drawer
│           ├── max_chat_bubble.dart       # User & AI bubbles, copy action, live speech captions
│           ├── max_voice_visualizer.dart  # Multi-state animated AI indicator
│           ├── max_empty_state.dart       # Greeting card + prompt suggestions
│           ├── max_button.dart            # Primary, Secondary, Outline, and Icon buttons
│           ├── max_card.dart              # Glass & action cards
│           ├── max_text_field.dart        # Glowing focus input with password toggle
│           └── max_status_badge.dart      # Live pulsing dot connection badge
├── pubspec.yaml                           # Flutter dependencies
└── test/
    └── widget_test.dart                   # Automated Flutter widget & bridge unit tests
```

---

## 3. Host Integration

- **Host Activity**: `com.example.flutter.MaxFlutterActivity` (extends `FlutterActivity`).
- **Gradle Linkage**: Root `settings.gradle.kts` evaluates `include_flutter.groovy` and links `:flutter`.
- **App Dependency**: `app/build.gradle.kts` imports `implementation(project(":flutter"))`.
- **Manifest Entry**:
  ```xml
  <activity
      android:name="com.example.flutter.MaxFlutterActivity"
      android:exported="true"
      android:launchMode="singleTop"
      android:windowSoftInputMode="adjustResize"
      android:theme="@style/Theme.GeminiLive.Flutter" />
  ```

---

## 4. Bridge Protocol (`max/native`)

### Methods (Dart → Kotlin)
| Method | Arguments | Return | Description |
|---|---|---|---|
| `getOnboardingStage` | None | `String` | Returns `NOT_STARTED`, `PERMISSIONS_IN_PROGRESS`, `AI_CONFIG_IN_PROGRESS`, or `COMPLETED` |
| `setOnboardingStage` | `{"stage": String}` | `Boolean` | Updates persistent onboarding stage in `SettingsManager` |
| `getPermissionStatuses`| None | `Map<String, Boolean>` | Checks `audio`, `contacts`, `phone`, `sms`, `accessibility`, `notifications`, `overlay`, `battery` |
| `requestRuntimePermissions` | `{"permissions": List<String>}` | `Map<String, Boolean>` | Requests Android runtime permissions |
| `openAccessibilitySettings` | None | `Boolean` | Opens Android Accessibility settings for MAX service |
| `openNotificationListenerSettings` | None | `Boolean` | Opens Notification Access settings |
| `getApiConfig` | None | `Map<String, dynamic>` | Retrieves saved provider, model, API key |
| `saveApiConfig` | `{"apiKey": String, ...}` | `Boolean` | Persists API configuration into `SettingsManager` and `app_settings` |
| `testConnection` | `{"apiKey": String, ...}` | `Map<String, Any>` | Tests API key validity against live Google Gemini endpoints |
| `getSettings` | None | `Map<String, Any?>` | Retrieves all settings (`apiKey`, `model`, `voice`, `language`, `themeMode`, `isDesktopEnabled`, `agentIp`, `agentPort`, `isTelegramEnabled`, `autoUnlockEnabled`, `devicePin`, etc.) |
| `saveSettings` | `Map<String, Any?>` | `Boolean` | Persists all system settings, manages `TelegramBotService` and Windows Companion PC service |
| `pairWindowsAgent` | `{"ip": String, "port": Int, "pairingCode": String}` | `Map<String, Any>` | Connects and pairs with companion PC agent |
| `getMemories` | `{"query"?: String, "category"?: String}` | `List<Map<String, Any?>>` | Queries decrypted memories filtered by search keyword or category from Room |
| `saveMemory` | `Map<String, Any?>` | `Map<String, Any>` | Adds or updates encrypted memory in Room (`MemoryRepository.addMemory/updateMemory`) |
| `deleteMemory` | `{"memoryId": String}` | `void` | Deletes memory item by ID from Room database |
| `toggleMemoryPinned` | `{"memoryId": String, "pinned": Boolean}` | `void` | Toggles pinned state of memory in Room |
| `toggleMemoryEnabled` | `{"memoryId": String, "enabled": Boolean}` | `void` | Toggles enabled state of memory in Room |
| `clearAllMemories` | None | `void` | Permanently clears all memories |
| `chatInitialize` | None | `Boolean` | Initializes `AssistantEngine` and Gemini Live session |
| `chatSendText` | `{"text": String}` | `Boolean` | Sends text query to `ChatViewModel` / `AssistantEngine` |
| `chatToggleVoice` | None | `Boolean` | Toggles voice listening & foreground recording service |
| `chatClearHistory` | None | `Boolean` | Clears conversation messages and resets state |
| `chatReconnect` | None | `Boolean` | Reconnects WebSocket session |
| `openNativeScreen` | `{"route": String}` | `Boolean` | Opens native Jetpack Compose sub-screens (`reminders`, `period_tracker`, etc.) |
| `minimizeApp` | None | `Boolean` | Moves task to background |

### Push Stream (Kotlin → Dart)
- **Channel**: `onChatStateChanged`
- **Data Model**: `ChatState` (connection state, `isRecording`, `isThinking`, live `userCaption`, live `aiCaption`, list of `ChatMessage` objects).

---

## 5. Verification Status
- **`flutter analyze`**: 0 warnings / 0 errors.
- **`flutter test`**: All 7 widget & bridge tests passed.
- **On-Device Verification**: Fully tested on Redmi K20 Pro (Android 16, API 36).
  1. Welcome → Get Started onboarding flow.
  2. Permissions grant & real-time toggles.
  3. AI Configuration validation against live Gemini API.
  4. Home Chat screen with neural visualizer, text chat, mic toggle, and drawer navigation.
  5. System Settings screen (Flutter) with theme, API, voice, language, PC companion, Telegram, and security auto-unlock.
  6. Memory Vault screen (Flutter) with CRUD notes, real-time multi-field search, categories filter chips, pin/unpin toggles, delete confirmation dialog, and copy action.
  7. Smooth back-navigation from Memory Vault screen directly to Home screen.
  8. Native Compose sub-screens deep-linking and returning to Flutter.
