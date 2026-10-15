# Package-by-Feature Modular Architecture Migration Plan

> **STATUS:** **COMPLETED & VERIFIED**  
> **Date:** 2026-10-10  
> **Result:** Successfully organized YouTube (`features/youtube/`), WhatsApp (`features/whatsapp/`), Telegram (`features/telegram/`), and Calls (`features/calls/`) into dedicated feature packages. Maintained 100% backward compatibility via typealiases and shims. All unit tests passed and debug APK assembled cleanly (`app-debug.apk`).

---

## 1. Architectural Philosophy: Package-by-Feature

### Current Structure (Layer-Based)
Currently, code for a single feature like **WhatsApp** is scattered across multiple unrelated directories:
- Tools: `automation/tools/WhatsAppTool.kt`
- Automation: `automation/WhatsAppController.kt`
- Verification: `automation/verification/WhatsAppVerifier.kt`
- Contact Resolution: `automation/resolver/WhatsAppContactResolver.kt`
- Notification Service: `service/WhatsAppNotificationService.kt`
- Auto-Reply: `automation/AutoReplyController.kt`
- RemoteInput: `automation/RemoteInputSender.kt`
- BroadcastReceiver: `receiver/AutoReplyActionReceiver.kt`
- UI: `ui/notifications/NotificationsScreen.kt`

### Target Structure (Feature-Based)
Under the proposed architecture, all code belonging to a feature lives in its own dedicated package:
```
com.example.features/
├── whatsapp/
│   ├── WhatsAppTool.kt              # Gemini Live / Agent Tool
│   ├── WhatsAppController.kt        # UI automation & accessibility driver
│   ├── WhatsAppVerifier.kt          # UI state verifier
│   ├── WhatsAppContactResolver.kt   # Chat / Contact matcher
│   ├── AutoReplyController.kt       # Auto-reply business logic & rate limiting
│   ├── RemoteInputSender.kt         # Direct Reply RemoteInput dispatcher
│   ├── WhatsAppNotificationService.kt # Android NotificationListenerService
│   ├── AutoReplyActionReceiver.kt   # System broadcast receiver for notification actions
│   └── ui/                          # WhatsApp-specific Compose cards & pickers
│       ├── WhatsAppAudienceSection.kt
│       └── ContactPickerBottomSheet.kt
├── youtube/
│   ├── YoutubeTool.kt               # Gemini Live / Agent Tool
│   ├── YoutubeAutomation.kt         # YouTube search & playback automation
│   └── YoutubeVerifier.kt           # Playback & screen verification
├── calls/
│   ├── CallTool.kt                  # Gemini Live Call Tool
│   ├── AnswerCallTool.kt            # Answer Call Tool
│   ├── RejectCallTool.kt            # Reject Call Tool
│   ├── CallController.kt            # Higher-level telephony coordinator
│   ├── IncomingCallManager.kt       # Caller identity resolution & voice announcement
│   ├── PendingCallManager.kt        # Pending ringing call state
│   ├── DirectCallAuthorization.kt   # Security & authorization
│   ├── CallVerifier.kt              # Call state verification
│   ├── IncomingCallReceiver.kt      # Android PHONE_STATE BroadcastReceiver
│   └── actions/                     # Lower-level telephony actions
│       ├── CallPhoneAction.kt
│       ├── AnswerCallAction.kt
│       └── RejectCallAction.kt
└── telegram/
    ├── TelegramController.kt        # Telegram automation controller
    ├── TelegramBotService.kt        # Foreground long-polling service
    └── CloudSocketManager.kt        # Cloud WebSocket manager
```

---

## 2. Invariants & Guardrails

1. **Preserve Core Dispatch Pipeline:**
   - The central dispatch boundary `GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine` must remain strictly decoupled.
   - Core interfaces (`Tool`, `Action`, `VerificationResult`) remain in `com.example.automation.core` / `tools`. Feature classes implement these interfaces.
2. **Tool Registration & Map Updates:**
   - Any package updates to `WhatsAppTool`, `YoutubeTool`, etc., will be updated in `MyApplication.kt` (`toolRegistry.register(...)`) and reflected in `MAX_PROJECT_MAP.md`.
3. **Android Manifest Stability:**
   - Services (`WhatsAppNotificationService`, `TelegramBotService`) and Receivers (`IncomingCallReceiver`, `AutoReplyActionReceiver`) referenced in `AndroidManifest.xml` must have their `android:name` attributes updated accurately.
   - For extra safety, backwards-compatible shim subclasses can be maintained in `com.example.service.*` and `com.example.receiver.*` during migration to prevent any runtime ClassNotFound issues.
4. **Zero Room Database Schema Changes:**
   - No database entities, DAOs, or Room versions will be modified during this migration.
5. **Cloud Memory Freeze Maintained:**
   - Cloud Memory, Firestore, and licensing files will remain untouched.

---

## 3. Detailed File Migration Mapping

### A. WhatsApp Feature (`com.example.features.whatsapp`)
| Current Location | Target Location | Purpose |
| :--- | :--- | :--- |
| `app/src/main/java/com/example/automation/tools/WhatsAppTool.kt` | `app/src/main/java/com/example/features/whatsapp/WhatsAppTool.kt` | Live Agent Tool |
| `app/src/main/java/com/example/automation/WhatsAppController.kt` | `app/src/main/java/com/example/features/whatsapp/WhatsAppController.kt` | UI automation |
| `app/src/main/java/com/example/automation/verification/WhatsAppVerifier.kt` | `app/src/main/java/com/example/features/whatsapp/WhatsAppVerifier.kt` | Verification |
| `app/src/main/java/com/example/automation/resolver/WhatsAppContactResolver.kt` | `app/src/main/java/com/example/features/whatsapp/WhatsAppContactResolver.kt` | Chat resolution |
| `app/src/main/java/com/example/automation/AutoReplyController.kt` | `app/src/main/java/com/example/features/whatsapp/AutoReplyController.kt` | Auto-reply engine |
| `app/src/main/java/com/example/automation/RemoteInputSender.kt` | `app/src/main/java/com/example/features/whatsapp/RemoteInputSender.kt` | Direct Reply |
| `app/src/main/java/com/example/service/WhatsAppNotificationService.kt` | `app/src/main/java/com/example/features/whatsapp/WhatsAppNotificationService.kt` | Notification listener |
| `app/src/main/java/com/example/receiver/AutoReplyActionReceiver.kt` | `app/src/main/java/com/example/features/whatsapp/AutoReplyActionReceiver.kt` | Action receiver |
| (Extract from `NotificationsScreen.kt`) | `app/src/main/java/com/example/features/whatsapp/ui/WhatsAppAudienceSection.kt` | Audience chips UI |
| (Extract from `NotificationsScreen.kt`) | `app/src/main/java/com/example/features/whatsapp/ui/ContactPickerBottomSheet.kt` | Contact picker UI |

### B. YouTube Feature (`com.example.features.youtube`)
| Current Location | Target Location | Purpose |
| :--- | :--- | :--- |
| `app/src/main/java/com/example/automation/tools/YoutubeTool.kt` | `app/src/main/java/com/example/features/youtube/YoutubeTool.kt` | Live Agent Tool |
| `app/src/main/java/com/example/automation/YoutubeAutomation.kt` | `app/src/main/java/com/example/features/youtube/YoutubeAutomation.kt` | YouTube automation |
| `app/src/main/java/com/example/automation/verification/YoutubeVerifier.kt` | `app/src/main/java/com/example/features/youtube/YoutubeVerifier.kt` | Playback verification |

### C. Calls Feature (`com.example.features.calls`)
| Current Location | Target Location | Purpose |
| :--- | :--- | :--- |
| `app/src/main/java/com/example/automation/tools/CallTool.kt` | `app/src/main/java/com/example/features/calls/CallTool.kt` | Place Call Tool |
| `app/src/main/java/com/example/automation/tools/AnswerCallTool.kt` | `app/src/main/java/com/example/features/calls/AnswerCallTool.kt` | Answer Call Tool |
| `app/src/main/java/com/example/automation/tools/RejectCallTool.kt` | `app/src/main/java/com/example/features/calls/RejectCallTool.kt` | Reject Call Tool |
| `app/src/main/java/com/example/automation/CallController.kt` | `app/src/main/java/com/example/features/calls/CallController.kt` | Call coordinator |
| `app/src/main/java/com/example/automation/IncomingCallManager.kt` | `app/src/main/java/com/example/features/calls/IncomingCallManager.kt` | Caller identity & TTS |
| `app/src/main/java/com/example/automation/engine/PendingCallManager.kt` | `app/src/main/java/com/example/features/calls/PendingCallManager.kt` | Ringing state tracker |
| `app/src/main/java/com/example/automation/engine/DirectCallAuthorization.kt` | `app/src/main/java/com/example/features/calls/DirectCallAuthorization.kt` | Direct call security |
| `app/src/main/java/com/example/automation/verification/CallVerifier.kt` | `app/src/main/java/com/example/features/calls/CallVerifier.kt` | Call state verifier |
| `app/src/main/java/com/example/receiver/IncomingCallReceiver.kt` | `app/src/main/java/com/example/features/calls/IncomingCallReceiver.kt` | PHONE_STATE receiver |
| `app/src/main/java/com/example/automation/actions/CallPhoneAction.kt` | `app/src/main/java/com/example/features/calls/actions/CallPhoneAction.kt` | Telephony action |
| `app/src/main/java/com/example/automation/actions/AnswerCallAction.kt` | `app/src/main/java/com/example/features/calls/actions/AnswerCallAction.kt` | Telephony action |
| `app/src/main/java/com/example/automation/actions/RejectCallAction.kt` | `app/src/main/java/com/example/features/calls/actions/RejectCallAction.kt` | Telephony action |

### D. Telegram Feature (`com.example.features.telegram`)
| Current Location | Target Location | Purpose |
| :--- | :--- | :--- |
| `app/src/main/java/com/example/automation/TelegramController.kt` | `app/src/main/java/com/example/features/telegram/TelegramController.kt` | Telegram automation |
| `app/src/main/java/com/example/service/TelegramBotService.kt` | `app/src/main/java/com/example/features/telegram/TelegramBotService.kt` | Bot polling service |
| `app/src/main/java/com/example/telegram/CloudSocketManager.kt` | `app/src/main/java/com/example/features/telegram/CloudSocketManager.kt` | Cloud WebSocket |

---

## 4. Step-by-Step Migration Execution Plan

### Step 1: YouTube Feature Migration (Low Risk / Self-Contained)
1. Create directory `app/src/main/java/com/example/features/youtube/`.
2. Move `YoutubeTool.kt`, `YoutubeAutomation.kt`, and `YoutubeVerifier.kt`.
3. Update package declarations to `com.example.features.youtube`.
4. Update imports in `MyApplication.kt` and caller classes.
5. Run unit tests and `:app:compileDebugKotlin` to verify.

### Step 2: WhatsApp Feature Migration
1. Create directory `app/src/main/java/com/example/features/whatsapp/`.
2. Move `WhatsAppTool.kt`, `WhatsAppController.kt`, `WhatsAppVerifier.kt`, `WhatsAppContactResolver.kt`, `AutoReplyController.kt`, and `RemoteInputSender.kt`.
3. Move `WhatsAppNotificationService.kt` and `AutoReplyActionReceiver.kt`.
4. Extract `WhatsAppAudienceSection` and `ContactPickerBottomSheet` into `features/whatsapp/ui/`.
5. Update `AndroidManifest.xml` with updated service/receiver paths (or provide shim subclasses).
6. Update imports across all callers and `MyApplication.kt`.
7. Run all WhatsApp unit tests (`AutoReplyControllerFilterTest`, `ContactMatchingTest`, etc.) and `:app:compileDebugKotlin`.

### Step 3: Telegram Feature Migration
1. Create directory `app/src/main/java/com/example/features/telegram/`.
2. Move `TelegramController.kt`, `TelegramBotService.kt`, and `CloudSocketManager.kt`.
3. Update `AndroidManifest.xml` service declaration for `TelegramBotService`.
4. Update imports in `MyApplication.kt` and callers.
5. Verify compilation and test suite.

### Step 4: Calls Subsystem Migration
1. Create directory `app/src/main/java/com/example/features/calls/`.
2. Move telephony tools, controllers, managers, verifiers, and actions.
3. Move `IncomingCallReceiver.kt` and update `AndroidManifest.xml`.
4. Update callers (`AssistantEngine`, `MyApplication.kt`).
5. Run call unit tests (`IncomingCallerIdentityResolutionTest`, `OutgoingCallLifecycleUnitTest`, etc.).

### Step 5: Full Build, Documentation, and APK Verification
1. Run full unit test suite:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest --console=plain
   ```
2. Run debug APK assembly:
   ```powershell
   .\gradlew.bat :app:assembleDebug --console=plain
   ```
3. Update `MAX_PROJECT_MAP.md` navigation index to record all new feature package paths.

---

## 5. Review & Approval Gate

Please review this plan. Upon your confirmation, we can begin **Step 1 (YouTube)** or **Step 2 (WhatsApp)** incrementally, verifying clean builds and passing test suites after each feature step.
