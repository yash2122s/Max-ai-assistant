# Permissions — MAX

## Permission Matrix

| Permission | Why | Feature | Requested In | Denied Behavior |
|-----------|-----|---------|-------------|----------------|
| `INTERNET` | Gemini Live WSS | All AI | Manifest | App non-functional |
| `RECORD_AUDIO` | Microphone for voice | Voice Input | PermissionsScreen | Voice input disabled |
| `FOREGROUND_SERVICE` | Keep services running | All services | Manifest | Services killed |
| `FOREGROUND_SERVICE_MICROPHONE` | Mic while backgrounded | AssistantEngineService | Manifest | Voice stops in BG |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Overlay, Telegram, Windows | Overlay/Telegram/PC | Manifest | Services blocked |
| `POST_NOTIFICATIONS` | Reminders, alerts | Reminders, automation | PermissionsScreen | No notifications |
| `CAMERA` | Photo taking | CameraTool | PermissionsScreen | Camera tool fails |
| `SYSTEM_ALERT_WINDOW` | Floating capsule overlay | MaxCapsuleOverlayService | PermissionsScreen (Special) | Overlay not shown |
| `SCHEDULE_EXACT_ALARM` | Precise reminders | ReminderTool, Scheduler | PermissionsScreen (Special) | Imprecise alarms |
| `USE_EXACT_ALARM` | Exact alarm API 33+ | ReminderTool | Manifest | Imprecise alarms |
| `RECEIVE_BOOT_COMPLETED` | Restore alarms after reboot | BootReceiver | Manifest | Reminders lost on reboot |
| `WAKE_LOCK` | Keep CPU for Gemini session | VoiceForegroundService | Manifest | Session dropped in BG |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent Doze killing services | All background services | PermissionsScreen (Special) | Services throttled |
| `READ_CONTACTS` | Caller ID; WhatsApp contact search | Calls, WhatsApp | PermissionsScreen | Raw numbers only |
| `CALL_PHONE` | Make outgoing calls | CallTool | PermissionsScreen | Call tool fails |
| `READ_PHONE_STATE` | Incoming call detection | IncomingCallReceiver | PermissionsScreen | No call detection |
| `ANSWER_PHONE_CALLS` | Answer call programmatically | AnswerCallTool | Manifest | Cannot auto-answer |
| `SEND_SMS` | SMS after call rejection | SmsTool | PermissionsScreen | SMS tool fails |
| `BLUETOOTH_CONNECT` | Bluetooth control | BluetoothTool | PermissionsScreen | BT tool fails |
| `WRITE_SETTINGS` | Brightness, ringer control | BrightnessTool, RingerTool | Manifest (Protected) | Settings tools fail |
| `ACCESS_NOTIFICATION_POLICY` | DnD control | DndTool | PermissionsScreen (Special) | DnD tool fails |
| `ACCESS_WIFI_STATE` | WiFi status | WifiTool | Manifest | WiFi info unavailable |
| `MANAGE_EXTERNAL_STORAGE` | File search | FileSearchTool | Manifest | File search limited |
| `ACCESS_FINE_LOCATION` | GPS location | LocationTool | PermissionsScreen | Location tool fails |
| `ACCESS_COARSE_LOCATION` | Backup location | LocationTool | PermissionsScreen | Location degraded |
| `PACKAGE_USAGE_STATS` | App usage stats | UsageStatsTool | PermissionsScreen (Special) | Usage stats unavailable |
| `READ_CALENDAR` | Calendar events | CalendarTool | PermissionsScreen | Calendar read fails |
| `WRITE_CALENDAR` | Add calendar events | CalendarTool | PermissionsScreen | Cannot create events |
| `QUERY_ALL_PACKAGES` | App list / open app | InstalledAppsRepository | Manifest | App list incomplete |
| `BIND_ACCESSIBILITY_SERVICE` | UI automation, screen dump | JarvisAccessibilityService | Settings > Accessibility | All automation fails |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | WhatsApp auto-reply | WhatsAppNotificationService | Settings > Special App Access | Auto-reply fails |
| `BIND_VOICE_INTERACTION` | Voice assistant integration | JarvisVoiceInteractionService | System setting | Voice assistant fails |
| `BIND_DEVICE_ADMIN` | Device admin (lock) | MyDeviceAdminReceiver | Device Admin settings | Lock tool fails |
| `BIND_QUICK_SETTINGS_TILE` | Desktop link QS tile | DesktopLinkTileService | Manifest | QS tile unavailable |
| `INTERACT_ACROSS_USERS_FULL` | Shizuku provider | ShizukuProvider | Manifest | Shizuku fails |

## Special Permissions (Not Runtime — Require Intent/Settings Navigation)
1. **SYSTEM_ALERT_WINDOW** — Settings.ACTION_MANAGE_OVERLAY_PERMISSION
2. **SCHEDULE_EXACT_ALARM** — Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM (API 31+)
3. **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS** — Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
4. **PACKAGE_USAGE_STATS** — Settings.ACTION_USAGE_ACCESS_SETTINGS
5. **WRITE_SETTINGS** — Settings.ACTION_MANAGE_WRITE_SETTINGS
6. **ACCESS_NOTIFICATION_POLICY** — NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
7. **Accessibility Service** — Settings.ACTION_ACCESSIBILITY_SETTINGS
8. **Notification Listener** — Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
9. **Device Admin** — DevicePolicyManager activation intent
10. **Shizuku** — Must run `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`

## Where Permissions Are Requested
- **Runtime permissions** (dangerous): `PermissionsScreen.kt`
- **Special permissions**: `PermissionsScreen.kt` (navigates to system Settings)
- **Accessibility + Notification Listener**: User must enable manually in Settings

## Onboarding Permission Flow
`OnboardingSetupScreen` guides user through granting all critical permissions before API key setup.

## Current Status
`PermissionsScreen` shows live permission status for all major permissions.
`BootReceiver` does not re-request permissions on boot — permissions must persist.
