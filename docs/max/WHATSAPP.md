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
- **Step 0 (Grounding & Active Chat Check)**:
  - Verifies if target contact chat is already open via `WhatsAppContactResolver.isCorrectChatOpen(root, target)`.
  - Checks if a different chat is open; unwinds back to the conversation list if needed.
  - Matches visible chat row in conversation list; if not found, taps WhatsApp Search.
- **Step 1 (Search Typing)**:
  - Enters target contact into search input via `ACTION_SET_TEXT`.
  - Enforces 300ms transition delay for search results to render.
- **Step 2 (Contact Resolution & Selection)**:
  - Resolves matching contact row in fresh tree via `resolveSearchRow(root, target)`.
  - Clicks target row; advances to Step 3 with a 400ms transition delay.
- **Step 3 (Chat Entry & Text Insertion)**:
  - Verifies chat header.
  - If message is blank (`open_whatsapp_chat` action), completes task successfully.
  - If message is present, injects text via `ACTION_SET_TEXT` into message entry node.
  - Enforces 200ms delay for WhatsApp to transition mic button to send button.
- **Step 4 (Send Button Dispatch)**:
  - Locates Send button in fresh tree (`findWhatsAppSendNode()` checking view IDs, content description, and English/Telugu labels: "Send", "పంపు", "పంపించు").
  - Dispatches click and sets `SEND_ACTION_CONFIRMED`.
- **Step 5 (Post-Send Verification)**:
  - Verifies that input entry text was cleared or outgoing message bubble appeared.
  - Completes task with verified outcome.
- **Failsafe**: 20s watchdog timeout cleans up any abandoned automation safely.

## Screen Navigation
Supports navigating directly to WhatsApp tabs and screens:
- `chats`: Main chat conversations list
- `updates` / `status`: Status / Updates tab
- `calls`: Audio & Video call history tab
- `communities`: Communities tab
- `settings`: In-app settings screen
- `new_chat`: New message contact picker
- `linked_devices`: Linked devices management
- `starred_messages`: Bookmarked / starred messages

## Supported Tools & Intents
- `send_whatsapp_message`: Sends message to contact or phone number.
- `open_whatsapp_chat`: Opens conversation without sending text.
- `navigate_whatsapp`: Navigates directly to any WhatsApp section/tab.
- Direct Offline Voice Command Engine: English, Telugu, and Tinglish regex patterns parsed locally without network latency.

## Current Status: IMPLEMENTED & VERIFIED ✅
- Native Accessibility Service automation is the first, authoritative, sub-second execution path.
- Deep-link and in-app contact search automation both functional.
- Zero ANR: removed blocking `runBlocking` calls from main dispatch path.
- Telugu language and Tinglish phonetic variants fully recognized across all stages.
