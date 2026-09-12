# Telegram Subsystem — MAX

## Purpose
Provides remote device control via a Telegram Bot. User sends commands to a Telegram bot; MAX executes them on-device.

## Main Classes

| Class | File | Role |
|-------|------|------|
| `TelegramBotService` | `service/TelegramBotService.kt` | Foreground service; long-polling Telegram API |
| `TelegramController` | `automation/TelegramController.kt` | Telegram automation controller |
| `CloudSocketManager` | `telegram/CloudSocketManager.kt` | Alternative cloud WebSocket connection |

## Configuration
Stored in `SettingsManager`:
- `isTelegramBotEnabled` — toggle (SharedPreferences)
- `telegramBotToken` — **SECRET PRESENT — DO NOT RECORD VALUE** (EncryptedSharedPreferences)
- `telegramChatId` — authorized chat ID (SharedPreferences)

## Service Lifecycle
```
MyApplication.onCreate()
  └─ If isTelegramBotEnabled && token.isNotBlank() && chatId.isNotBlank()
        └─ startForegroundService(TelegramBotService)

TelegramBotService.onStartCommand()
  └─ Launch coroutine polling loop:
        while(isActive):
          pollUpdates(token, chatId)  ← Telegram getUpdates?timeout=30
            └─ handleUpdate(update, token, targetChatId)
                  ├─ chatId check (fail-closed: rejects unauthorized senders)
                  └─ processBotCommand(text, token, chatId)
```

## Allowed Commands (Hardcoded Whitelist)
```kotlin
ALLOWED_ACTIONS = setOf(
    "FLASHLIGHT_ON",
    "FLASHLIGHT_OFF",
    "GET_LOCATION",
    "TAKE_SCREENSHOT",
    "HELP"
)
```
All other commands → "Unknown command" response. No Gemini/AI forwarding.

## Command Processing
```
User sends "/FLASHLIGHT_ON" in Telegram
  └─ processBotCommand() normalizes → "FLASHLIGHT_ON"
        └─ ActionDispatcher.dispatchWithResult(JSONObject {action: "FLASHLIGHT_ON"})
              └─ ExecutionEngine → FlashlightTool → result
                    └─ sendTelegramMessage(token, chatId, "Command executed successfully.")
```

## Telegram API Calls
- `getUpdates?offset=N&timeout=30` — long-polling
- `sendMessage` — HTML parse mode; can include links

## Authorization Model
- Single `targetChatId` stored in settings
- Any message from a different chat → rejected with "Access Denied"
- No per-command auth

## Current Status: PARTIALLY IMPLEMENTED ⚠️

### What works:
- Foreground service with long-polling
- 5 commands: FLASHLIGHT_ON/OFF, GET_LOCATION, TAKE_SCREENSHOT, HELP
- Chat ID authorization

### What does NOT work:
- No natural language / AI-powered command processing
- Cannot execute arbitrary tools (only 5 hardcoded)
- No memory access via Telegram
- No WhatsApp, reminders, contacts, etc. via Telegram
- No callback buttons or inline keyboards for most commands

## Extension Points
1. **Add NLP**: Route bot messages through AssistantEngine/Gemini for full AI-powered Telegram control
2. **Expand whitelist**: Add more commands (set_reminder, send_whatsapp, etc.)
3. **Inline keyboards**: Add Yes/No buttons for confirmations
4. **Location sharing**: Live location updates
5. **Photo delivery**: Send screenshot as photo attachment via `sendPhoto` API
