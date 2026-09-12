# Data & Persistence — MAX

## Purpose
All local data storage: Room database, SharedPreferences, EncryptedSharedPreferences.

## Room Database
- **Class**: `AppDatabase` → `data/local/AppDatabase.kt`
- **Name**: `jarvis_database`
- **Version**: 14
- **Migrations**: 9→10, 10→11, 11→12, 12→13, 13→14 (all defined in AppDatabase companion)
- **Fallback**: `fallbackToDestructiveMigration(dropAllTables=true)` — DATA LOSS RISK

### Tables

| Entity | Table | File | Key Columns |
|--------|-------|------|-------------|
| `ChatMessage` | `chat_messages` | `data/local/ChatMessage.kt` | id, sender, text, timestamp |
| `Reminder` | `reminders` | `data/local/Reminder.kt` | id, title, description, triggerTime, isCompleted, isRecurring |
| `AutoReplyRule` | `auto_reply_rules` | `data/local/AutoReplyRule.kt` | id, contact, keyword, replyText |
| `ActionReward` | (reward table) | `data/local/ActionReward.kt` | Reinforcement learning rewards |
| `InstalledApp` | `installed_apps` | `data/local/InstalledApp.kt` | packageName, appName, lastSeen |
| `ScheduledTask` | `scheduled_tasks` | `automation/scheduler/ScheduledTask.kt` | id, toolName, args, triggerTime, repeat |
| `TaskExecutionLog` | (log table) | `automation/scheduler/TaskExecutionLog.kt` | taskId, executedAt, success |
| `PeriodLog` | `period_logs` | `data/local/PeriodLog.kt` | id, startDate, endDate, durationDays, notes |
| `PermanentMemory` | `permanent_memory` | `memory/data/PermanentMemory.kt` | memoryId (PK UUID), title, content, category, type, source, pinned, enabled, usageCount, embedding BLOB, tags, isPrivate |

### DAOs
- `ChatMessageDao` — getAllMessagesFlow(), insertMessage(), deleteOld()
- `ReminderDao` — CRUD, getByStatus()
- `AutoReplyRuleDao` — CRUD by contact/keyword
- `RewardDao` — reward tracking
- `InstalledAppDao` — upsert, getAll()
- `ScheduledTaskDao` — CRUD, getByTime()
- `PeriodLogDao` — CRUD, getHistory()
- `PermanentMemoryDao` — CRUD, search, getByCategory(), updateEmbedding()

### Migration History
```
v9→10  Created period_logs table
v10→11 Created permanent_memory table (no embedding)
v11→12 ALTER permanent_memory ADD COLUMN embedding BLOB
v12→13 ALTER permanent_memory ADD COLUMN tags TEXT NOT NULL DEFAULT ''
v13→14 ALTER permanent_memory ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0
```

## SharedPreferences

### `app_settings` (Plain)
| Key | Type | Default | Consumer |
|-----|------|---------|----------|
| `backend_url` | String | `https://your-jarvis-backend.onrender.com` | ServerActionExecutor |
| `user_id` | String | UUID auto-gen | Auth |
| `fcm_token` | String | simulated | FCM |
| `local_ai_mode` | Boolean | true | AI mode selection |
| `auto_reply_enabled` | Boolean | false | AutoReplyController |
| `ai_fallback_enabled` | Boolean | true | AutoReplyController |
| `ai_allowed_contacts` | String | "" | AutoReplyController |
| `quick_replies` | String | "Yes,No,..." | AutoReplyController |
| `cloud_ai_model` | String | "groq" | Legacy |
| `language_preference` | String | "English" | PromptBuilder |
| `bot_persona` | String | "Human Type Talking" | PromptBuilder |
| `onboarding_stage_v3` | String | null | Navigation |
| `user_name` | String | "" | UI |
| `telegram_bot_enabled` | Boolean | false | TelegramBotService |
| `telegram_chat_id` | String | "" | TelegramBotService |
| `cloud_bot_url` | String | "" | CloudSocketManager |
| `agentic_mode_enabled` | Boolean | false | GoalPlanner |
| `agentic_max_iterations` | Int | 8 | GoalPlanner |
| `agentic_daily_budget` | Int | 50 | GoalPlanner |
| `agentic_budget_date` | String | today | Budget reset |
| `agentic_iterations_today` | Int | 0 | Budget counter |
| `live_voice_mode` | Boolean | false | ConnectionManager |
| `live_voice_model_name` | String | "gemini-3.1-flash-live-preview" | GeminiWebSocketClient (ignored!) |
| `live_voice_name` | String | "Aoede" | GeminiWebSocketClient |
| `notif_app_enabled_<pkg>` | Boolean | false | WhatsAppNotificationService |

### `jarvis_secure_settings` (EncryptedSharedPreferences — AES256)
| Key | Consumer |
|-----|---------|
| `gemini_api_key` | **SECRET PRESENT — DO NOT RECORD VALUE** → GeminiWebSocketClient |
| `telegram_bot_token` | **SECRET PRESENT — DO NOT RECORD VALUE** → TelegramBotService |
| `cloud_app_secret` | **SECRET PRESENT — DO NOT RECORD VALUE** → CompanionConnectionService |

### Migration on Init
`SettingsManager.init {}` migrates `gemini_api_key`, `telegram_bot_token`, `cloud_app_secret` from plain prefs to encrypted prefs if found.

## Files Storage
- `MemoryRepository` once used `memories.md` file in `context.filesDir` — now deleted on startup (migrated to Room)
- Screen captures: RAM only, never written to disk

## Current Status: IMPLEMENTED ✅

## Known Issues
- `fallbackToDestructiveMigration` is a safety net that silently destroys all user data if a migration fails — HIGH RISK
- `EncryptedSharedPreferences` corruption recovery silently falls back to plaintext prefs — security risk
- `live_voice_model_name` setting exists but is ignored by `sanitizeModelName()` in `GeminiWebSocketClient`
