# Known Issues — MAX Codebase Audit

## CRITICAL 🔴

| ID | Issue | Location | Impact |
|----|-------|----------|--------|
| C-01 | `fallbackToDestructiveMigration(dropAllTables=true)` — if any Room migration fails, ALL user data (memories, reminders, period logs, chat history) is silently destroyed | `data/local/AppDatabase.kt:111` | Complete data loss |
| C-02 | `EncryptedSharedPreferences` recovery path falls back to plain `SharedPreferences` on exception — API key and Telegram token stored in plaintext on encryption failure | `data/preferences/SettingsManager.kt:52-54` | Security breach |

---

## HIGH 🟠

| ID | Issue | Location | Impact |
|----|-------|----------|--------|
| H-01 | `JarvisAccessibilityService.pendingTask` is a static mutable var — not thread-safe; concurrent automation calls can corrupt task state | `service/JarvisAccessibilityService.kt:26` | Race condition / wrong task executed |
| H-02 | `sanitizeModelName()` ignores `liveVoiceModelName` setting and always returns `gemini-3.1-flash-live-preview` — user model preference silently ignored | `network/GeminiWebSocketClient.kt:48-50` | Feature broken silently |
| H-03 | `ActionDispatcher` uses `runBlocking` in some code paths — potential ANR on main thread if called from UI | `automation/engine/ActionDispatcher.kt` | ANR / freeze |
| H-04 | `ChatViewModel._instance` is a static singleton with `Context` field — classic Android context leak | `viewmodel/ChatViewModel.kt:25-32` | Memory leak |

---

## MEDIUM 🟡

| ID | Issue | Location | Impact |
|----|-------|----------|--------|
| M-01 | Two parallel ToolRegistry systems: `automation/tools/ToolRegistry` (app-level, 45 tools) and `voice/tools/ToolRegistry` (AssistantToolRegistry, voice-scoped). Easy to confuse or register in wrong one | Both ToolRegistry files | Tool not found in voice session |
| M-02 | Two routing paths: `ToolDispatcher` (Gemini Live path) vs `ActionDispatcher` (direct dispatch) — both normalizing JSONs independently. Duplication risk if action names diverge | `network/ToolDispatcher.kt`, `automation/engine/ActionDispatcher.kt` | Action handling inconsistency |
| M-03 | `OfflineCommandEngine` (23KB) appears to duplicate tool dispatch logic — relationship to main pipeline unclear. May be dead code or an unconnected fallback | `automation/engine/OfflineCommandEngine.kt` | Technical debt, dead code risk |
| M-04 | WhatsApp automation 20s failsafe silently cancels and shows a Toast — no error reported back to Gemini/user via structured result | `service/JarvisAccessibilityService.kt:75-81` | Silent automation failures |
| M-05 | `JarvisAccessibilityService.instance` static nullable — consumers check `== null` but no synchronization for concurrent access | Multiple callers | Null pointer risk |
| M-06 | Telegram `ALLOWED_ACTIONS` whitelist is extremely limited (5 commands) — no AI routing | `service/TelegramBotService.kt:40-46` | Feature severely limited |
| M-07 | `AgentActivityTracker` may accumulate unbounded trace entries — no eviction policy documented | `automation/activity/AgentActivityTracker.kt` | Memory growth |

---

## LOW 🟢

| ID | Issue | Location | Impact |
|----|-------|----------|--------|
| L-01 | Color.kt has many legacy alias tokens (BgDark, NeonPink, NeonBlue, JarvisAmber, etc.) — all aliased to MAX tokens but creates naming confusion in code | `ui/theme/Color.kt` | Readability |
| L-02 | `RewardDao` referenced in `AppDatabase` but `ActionReward.kt` purpose is unclear — may be unused reinforcement learning scaffolding | `data/local/ActionReward.kt` | Dead code |
| L-03 | `MemoryRepository.init{}` block uses `runBlocking` for migration — could block calling thread | `memory/data/MemoryRepository.kt:42` | Potential thread block |
| L-04 | `MemoryRepository.isMigrationCompleted` is per-process only — if process killed mid-migration, re-runs on next launch | `memory/data/MemoryRepository.kt:31` | Redundant migration work |
| L-05 | `clouds_app_secret` key typo inconsistency between companion const and actual key string — verify consistency | `data/preferences/SettingsManager.kt` | Wrong key read |
| L-06 | `RemoteInputSender` sends replies via notification RemoteInput — fragile if notification structure changes across Android versions | `automation/RemoteInputSender.kt` | Auto-reply may fail |
| L-07 | No memory size cap in `MemoryRepository` — memories can grow without bound | `memory/data/MemoryRepository.kt` | Storage growth |
| L-08 | YouTube automation in `YoutubeAutomation.kt` and `YoutubeTool.kt` — verify which is actually used | Both files | Duplicate |

---

## OBSERVATIONS 📋

| ID | Observation |
|----|-------------|
| O-01 | `OfflineCommandEngine.kt` (23KB) — large offline fallback; unclear if actively wired into main execution path |
| O-02 | `CloudSocketManager.kt` (7KB) — appears to be an alternative remote connection path; not in main flow |
| O-03 | `hermes-agent-main/` directory exists in workspace — Windows PC companion agent; separate Python project |
| O-04 | `windows-agent/` directory exists in workspace — another Windows agent component |
| O-05 | `frontend/` directory exists — possible web dashboard; not analyzed |
| O-06 | Many large log dump files in workspace (adb_dump.txt 5MB, crash_log.txt 6MB) — should not be committed to version control |
| O-07 | `.env` file in workspace root — contains API key; must be in `.gitignore` |
| O-08 | `PeriodDataSeeder.kt` hard-codes specific cycle dates (personal data seeded into DB) — consider making configurable |
| O-09 | `NaturalCommandNormalizer.kt` (11KB) — large normalization logic; unclear if used in main voice path |
| O-10 | `combined_memories.md` (88KB) in workspace root — large memory dump file; should not be committed |
