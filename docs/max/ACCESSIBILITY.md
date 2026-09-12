# Accessibility Subsystem — MAX

## Purpose
Provides UI tree access, screen interaction, gestures, automated app navigation, and screen capture for MAX automation.

## Entry Point
`JarvisAccessibilityService` — registered in AndroidManifest with `BIND_ACCESSIBILITY_SERVICE`

## Main Classes

| Class | File | Role |
|-------|------|------|
| `JarvisAccessibilityService` | `service/JarvisAccessibilityService.kt` | 1739-line central accessibility service |
| `AccessibilityTreePruner` | `service/AccessibilityTreePruner.kt` | Prunes accessibility tree for performance |
| `AutomationTask` | `service/AutomationTask.kt` | Data class describing pending automation |
| `JarvisServiceStateManager` | `service/JarvisServiceStateManager.kt` | Service state tracking |

## Key Capabilities (JarvisAccessibilityService)

1. **WhatsApp Automation**
   - `handleWhatsAppDeepLinkAutomation()` — reads deep-link open, waits for chat, types + sends
   - `handleWhatsAppSearchAutomation()` — opens WA, searches contact, opens chat, types + sends

2. **YouTube Automation**
   - `YoutubeAutomation.kt` — opens YT, searches, clicks video

3. **Generic UI Automation**
   - `handleGenericAutomation()` — click by text, scroll, type

4. **Screen Dump** (for screen context)
   - `dumpScreenFlat()` — returns flat list of node maps (text, viewId, className, clickable, bounds)

5. **Screenshot**
   - `takeScreenshot()` — accessibility screenshot API (Android 9+)

6. **PIN Unlock**
   - `unlockWithPin(pin)` — accessibility-based PIN entry on lockscreen

7. **Gestures**
   - `dispatchGesture()` — custom gesture dispatch
   - Scroll, swipe, tap via GestureDescription

## Automation Task Lifecycle
```
Tool.execute() → sets JarvisAccessibilityService.pendingTask (static var)
  └─ onAccessibilityEvent() fires on window changes
        ├─ 20s failsafe timeout check
        ├─ Event filter: TYPE_WINDOW_STATE_CHANGED / WINDOWS_CHANGED / WINDOW_CONTENT_CHANGED only
        └─ Routes by task.type:
              ├─ SEND_MESSAGE_DEEP_LINK → handleWhatsAppDeepLinkAutomation()
              ├─ SEND_MESSAGE → handleWhatsAppSearchAutomation()
              ├─ GENERIC → handleGenericAutomation()
              └─ others...
```

## Accessibility Config
- `app/src/main/res/xml/accessibility_config.xml`
- Listens for: TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_WINDOWS_CHANGED
- Configured for WhatsApp, WhatsApp Business, system packages

## Screen Capture (via Accessibility)
- `ScreenCaptureProvider` Tier 2: `JarvisAccessibilityService.instance?.takeScreenshot()`
- Returns JPEG ByteArray; stored in RAM only (never saved to disk)
- Tier 1 is Shizuku (preferred)

## ServiceRegistry
- `onServiceConnected()` → `ServiceRegistry.register(ServiceType.ACCESSIBILITY, this)`
- Other components access it via `ServiceRegistry`
- Also exposed as `JarvisAccessibilityService.instance` (static nullable)

## Notification Listener
- `WhatsAppNotificationService` — BIND_NOTIFICATION_LISTENER_SERVICE for WhatsApp auto-reply
- `NotificationListenerBridge` — general notification listener bridge

## Current Status: IMPLEMENTED ✅
Full WhatsApp automation working. Screen dump working. Screenshot working. PIN unlock working.

## Known Issues
- `pendingTask` is static mutable var — NOT thread-safe if multiple actions fire simultaneously
- 20s timeout may silently fail without user feedback in some scenarios
- Node searching is heuristic — fragile to WhatsApp UI changes
- Accessibility service requires manual user activation in Settings
