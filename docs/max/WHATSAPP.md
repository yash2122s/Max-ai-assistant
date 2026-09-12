# WhatsApp Subsystem — MAX

## Purpose
Send WhatsApp messages to contacts (by name or phone number) via deep link or accessibility-based UI automation.

## Main Classes

| Class | File | Role |
|-------|------|------|
| `WhatsAppController` | `automation/WhatsAppController.kt` | Orchestrates send: deep-link vs search |
| `WhatsAppTool` | `automation/tools/WhatsAppTool.kt` | Tool interface; calls WhatsAppController |
| `WhatsAppVerifier` | `automation/verification/WhatsAppVerifier.kt` | Verifies send was successful |
| `JarvisAccessibilityService` | `service/JarvisAccessibilityService.kt` | Handles UI automation after app launches |
| `WhatsAppNotificationService` | `service/WhatsAppNotificationService.kt` | Notification listener for auto-reply |
| `AutoReplyController` | `automation/AutoReplyController.kt` | Auto-reply rules engine + AI fallback |
| `AutoReplyActionReceiver` | `receiver/AutoReplyActionReceiver.kt` | Handles notification action buttons |

## Data Flow

### Send Message (by phone number)
```
User: "WhatsApp Priya +91 9876543210 I'm coming"
  └─ Gemini → send_whatsapp_message(contact="+919876543210", message="I'm coming")
        └─ WhatsAppTool → WhatsAppController.execute()
              └─ contact matches phone regex → Deep Link path:
                    url = "https://api.whatsapp.com/send?phone=919876543210&text=I'm+coming"
                    pendingTask = AutomationTask(type="SEND_MESSAGE_DEEP_LINK", ...)
                    startActivity(Intent.ACTION_VIEW, uri)
                          └─ WhatsApp opens → JarvisAccessibilityService.onAccessibilityEvent()
                                └─ handleWhatsAppDeepLinkAutomation(task, pkg)
                                      └─ Wait for send button → click send
```

### Send Message (by contact name)
```
User: "WhatsApp Priya saying I'm coming"
  └─ Gemini → send_whatsapp_message(contact="Priya", message="I'm coming")
        └─ WhatsAppController.execute()
              └─ Not phone → Search path:
                    pendingTask = AutomationTask(type="SEND_MESSAGE", ...)
                    Launch WhatsApp → JarvisAccessibilityService.onAccessibilityEvent()
                          └─ handleWhatsAppSearchAutomation(task, pkg)
                                ├─ Search for "Priya" in WA search bar
                                ├─ Click matching contact
                                ├─ Type message
                                └─ Click send button
```

### Scheduled WhatsApp
- `WhatsAppController(isScheduled=true)` used by scheduler
- `ScheduleMessageAction` stores scheduled sends in Room DB
- `TaskReceiver` fires alarm → executes WhatsApp send

## Auto-Reply Pipeline
```
Incoming WhatsApp notification
  └─ WhatsAppNotificationService.onNotificationPosted()
        └─ AutoReplyController.handleNotification()
              ├─ Check AutoReplyRule (Room DB) for matching contact/keyword
              ├─ If rule matches → send reply via RemoteInput
              └─ If isAiFallbackEnabled + no rule → AI generates reply
                    └─ RemoteInputSender.sendReply()
```

## AutomationTask Steps (Accessibility)
WhatsApp automation steps tracked in `AutomationTask.step`:
- Step 0: Wait for WA to open
- Step 1: Find and click search / contact
- Step 2: Type message
- Step 3: Find and click send button
- Failsafe: 20s timeout → cancel

## Known Limitations
- **Name search is heuristic** — depends on WA contact list order and accessibility node text
- **WhatsApp UI changes** can break automation (accessibility node IDs change per WA version)
- **No read-receipts** — cannot confirm if message was delivered
- **Deep-link requires phone number** with country code (auto-adds +91 for 10-digit numbers)
- **Scheduled messages** require accessibility service active at alarm time
- **Group messages** not directly supported — requires manual group name navigation

## Current Status: IMPLEMENTED ✅
Both deep-link (phone#) and search-based automation working. Auto-reply working.

## Extension Points
- Add contacts cache to avoid re-searching
- Add Shizuku-based send for faster/more reliable automation
- Add message delivery confirmation via notification monitoring
