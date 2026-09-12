# MAX AI — KOTLIN ANDROID NATIVE MODULES REFERENCE

**Version:** 3.1.0  
**Target:** Kotlin Android Host Modules  

---

## 1. Native Capability Delegates (`com.example.flutter.delegates`)

| Module | Responsibility | Key Methods |
|---|---|---|
| **AccessibilityDelegate** | A11y service checks & settings intent | `isAccessibilityEnabled()`, `openAccessibilitySettings()` |
| **PermissionDelegate** | OS runtime permissions querying & dispatch | `getPermissionStatuses()`, `requestRuntimePermissions()` |
| **DeviceDelegate** | Hardware toggles, quick actions & PC pairing | `executeQuickAction()`, `pairWindowsAgent()`, `openOverlaySettings()`, `openBatteryOptimizationSettings()` |
| **RuntimeDelegate** | MAX ON/OFF master switch & chat shell | `isMaxEnabled()`, `setMaxEnabled()`, `getOnboardingStage()`, `initializeChat()`, `sendTextMessage()`, `reconnect()` |
| **AutomationDelegate** | UI reasoning tasks, routines, dynamic models | `executeUiTask()`, `getUiAgentConfig()`, `saveUiAgentConfig()`, `testUiAgentConnection()`, `getRoutines()` |
| **NotificationDelegate** | Notification listener checks & monitored apps | `isNotificationListenerGranted()`, `getMonitoredApps()`, `setAppNotificationEnabled()`, `setWhatsAppAutoReply()` |
| **DataDelegate** | Settings, Memory Vault, Period Tracker, Reminders | `getSettingsMap()`, `getMemories()`, `saveMemory()`, `getPeriodData()`, `addPeriod()`, `getReminders()`, `addReminder()` |

---

## 2. Accessibility Subsystem (`com.example.service`)

### `JarvisAccessibilityService.kt`
- Operates as an independent Android OS daemon.
- **Event Bypass:** Inspects `MaxRuntimeManager.isMaxEnabled(this)` at the start of `onAccessibilityEvent`. When MAX is OFF, pending tasks are purged and no event processing is performed.
- **Gesture Execution:** Supports programmatic click, double click, long press, swipe, and scroll gestures via `dispatchGesture()`.
- **Node Safety:** Intermediate node handles in recursive traversals are deterministically recycled via `try-finally` blocks.

### `AccessibilityTreePruner.kt`
- Filters system status bar noise (`status_bar`, battery, clock).
- Normalizes raw class names to compact identifiers:
  - `EditText` → `input`
  - `Button` → `btn`
  - `TextView` → `text`
  - `ImageView` → `image`
  - `RecyclerView` / `ListView` / `ScrollView` → `list`
  - `CheckBox` → `checkbox`
  - `Switch` → `switch`
- Truncates oversized text strings to 120 characters to minimize token usage during vision/reasoning calls.
- Bounds list to maximum 60 nodes.

---

## 3. Persistence & Database Integrity (`com.example.data`)

Room SQLite database schema contracts:
- `AppDatabase` (Version 3)
  - `PermanentMemory` (Table: `permanent_memories`)
  - `PeriodLog` (Table: `period_logs`)
  - `Reminder` (Table: `reminders`)
  - `ChatMessageEntity` (Table: `chat_messages`)
- Zero destructive migrations; entity structures are completely preserved.
