# MAX Project Development Rules
# For all AI coding agents working on this codebase

## MANDATORY STARTUP PROTOCOL

Every session MUST begin with:

1. Read `MAX_PROJECT_STATE.md` — understand current status, what is complete, what is broken
2. Read `MAX_PROJECT_MAP.md` — locate the files relevant to the requested feature
3. Identify the relevant subsystem
4. Read the specific `docs/max/<SUBSYSTEM>.md` document
5. Read ONLY the source files required for the task

**DO NOT scan the entire repository for every request.**

---

## DOCUMENTATION ACCURACY RULE

**Never treat undocumented or inferred information as verified architecture.**

The knowledge documents are a navigation layer, not a guarantee. Before implementing anything that touches a subsystem, check whether the relevant documentation section contains:

- Inferred file or class names (not directly read from source)
- "unclear" or "unknown" relationships
- Approximate counts ("~27 action classes", "~45 tools")
- Lists marked as "from directory listing" rather than from source content
- Assumptions about integration paths

If any of these flags are present in the relevant doc, **read the actual source files for that specific area before implementing**. Do not blindly trust the knowledge documents when they conflict with actual source code.

**After discovering the correct architecture, update the affected `docs/max/*.md` section.**

This keeps the index accurate without requiring a full re-audit.

---

## TOKEN EFFICIENCY — CORE REQUIREMENT

The project knowledge system is an index. Use it as a navigation guide:

```
User Request
  → MAX_PROJECT_MAP.md  (find the files)
  → docs/max/<SUBSYSTEM>.md  (understand the subsystem)
  → Read only those source files
  → Implement
```

NOT:

```
User Request
  → Read entire repository
  → Analyze everything
  → Implement
```

Expand context beyond the subsystem ONLY when:
- The map is clearly stale (files moved or renamed)
- The task crosses multiple subsystem boundaries
- Dependencies between subsystems are unclear for the specific change
- The requested change is architectural in scope

---

## DEVELOPMENT RULES

### Before Starting Any Task
1. Check `MAX_PROJECT_STATE.md` — is this feature already COMPLETED or IN_PROGRESS?
2. Locate relevant files in `MAX_PROJECT_MAP.md` before opening any source file
3. Identify the subsystem — read its `docs/max/*.md` before reading source
4. Do NOT re-invent components that already exist
5. Do NOT create parallel systems to existing ones

### While Implementing
- NEVER redesign the core pipeline without explicit user approval
- NEVER rename packages, interfaces, or move files without explicit request
- NEVER introduce new DI frameworks or architectural patterns
- NEVER change Room database schema without providing a migration
- NEVER touch `MyApplication.kt` tool registration without explicitly updating the map
- Prefer minimal changes to working code

### Critical Preserved Architecture (Do NOT change without approval)
```
GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine → ToolRegistry → Tool
AssistantEngine → ConnectionManager → GeminiWebSocketClient
MemoryRepository → PermanentMemoryDao → AppDatabase
JarvisAccessibilityService.pendingTask → onAccessibilityEvent()
```

### After Completing Work
1. Update `MAX_PROJECT_STATE.md` — change feature status from PLANNED to IMPLEMENTED
2. Update `MAX_PROJECT_MAP.md` ONLY if new files were added or architecture changed
3. Update the relevant `docs/max/<SUBSYSTEM>.md` if the subsystem changed significantly
4. Report: what was changed, what files were modified, build result

---

## ENVIRONMENT (DO NOT REDISCOVER)

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
.\gradlew.bat assembleDebug      # build
.\gradlew.bat installDebug       # install
adb shell am start -n com.example/.MainActivity  # launch
```

Device: `3bc00016` (Redmi K20 Pro, Android 16, root+Shizuku)

---

## SECURITY RULES
- NEVER write API keys, tokens, or secrets into any documentation
- Secrets in `SettingsManager`: gemini_api_key, telegram_bot_token, cloud_app_secret
- Document as: `SECRET PRESENT — DO NOT RECORD VALUE`

---

## KNOWN RISK AREAS (Read carefully before touching)
- `AppDatabase.kt` — has `fallbackToDestructiveMigration` — any migration failure destroys all user data
- `SettingsManager.kt` — EncryptedSharedPreferences has fallback to plaintext on failure
- `JarvisAccessibilityService.kt` — 1739 lines; static pendingTask; not thread-safe
- `ChatViewModel.kt` — static singleton instance; context leak risk
- `ToolDispatcher.kt` + `ActionDispatcher.kt` — two JSON normalization paths; keep in sync

---

## MASTER ARCHITECTURAL RULES
1. **MAX OFF / Runtime Control**:
   - `MaxRuntimeManager` is the single source of truth for global operating state.
   - When MAX is OFF, all non-essential background activity (Gemini Live auto-reconnect, microphone recording, UI agent loops, Telegram bot, dynamic overlay) must stop.
   - Essential system alarms (`AlarmManager`, `ReminderDao`, task scheduler) and boot receivers remain intact.
   - Do NOT bypass `MaxRuntimeManager` with direct service start/stop calls.
2. **Pure Flutter UI**:
   - Flutter (`max_flutter/`) is 100% the sole user-facing UI stack.
   - Do NOT re-introduce Jetpack Compose UI screens. `MainActivity.kt` is a lightweight intent forwarder to `MaxFlutterActivity`.
3. **Event-Driven Real-Time State Pipeline**:
   - Assistant status (`OFF`, `OFFLINE`, `CONNECTING`, `ONLINE`, `LISTENING`, `THINKING`, `SPEAKING`, `ERROR`) MUST come from real Kotlin runtime events (`AssistantEventBus`, `AudioPlaybackManager.onPlaybackStateChanged`, `SessionStateMachine`) pushed via `MaxNativeBridge.onChatStateChanged`.
   - Never use Flutter polling timers to sync state.
4. **UI Automation Agent**:
   - `UiAgentRouter` delegates dynamic visual UI tasks to OpenRouter (`stealth/ox-alpha` default) with Gemini fallback.
   - Known deterministic tasks MUST use existing Kotlin tools (`ToolRegistry`) without LLM vision overhead.
   - UI agent tasks must immediately abort if `!MaxRuntimeManager.isMaxEnabled()`.
5. **Documentation & Changelog Integrity**:
   - Update `MAX_PROJECT_STATE.md`, `MAX_PROJECT_MAP.md`, `docs/max/*`, and `MAX_CHANGELOG.md` after every architectural change.

---

## SUBSYSTEM QUICK REFERENCE

| Task | Subsystem Doc | Key Files |
|------|--------------|-----------|
| Runtime Lifecycle | `docs/max/AUTOMATION.md` | `core/lifecycle/MaxRuntimeManager.kt`, `data/preferences/SettingsManager.kt` |
| UI Automation Agent | `docs/max/UI_AGENT.md` | `automation/agent/`, `automation/tools/UiAgentTool.kt` |
| AI/Gemini Live | `docs/max/AI.md` | `network/GeminiWebSocketClient.kt`, `voice/assistant/AssistantEngine.kt` |
| Voice & Audio | `docs/max/VOICE.md` | `voice/audio/AudioRecorder.kt`, `utils/AudioUtils.kt` |
| Automation Tools | `docs/max/AUTOMATION.md` | `automation/tools/`, `automation/engine/ActionDispatcher.kt` |
| User-Facing UI (Flutter) | `docs/max/UI.md` | `max_flutter/lib/`, `flutter/MaxNativeBridge.kt`, `flutter/MaxFlutterActivity.kt` |
| WhatsApp | `docs/max/WHATSAPP.md` | `automation/WhatsAppController.kt`, `service/JarvisAccessibilityService.kt` |
| Telegram | `docs/max/TELEGRAM.md` | `service/TelegramBotService.kt` |
| Incoming Calls | `docs/max/CALLS.md` | `receiver/IncomingCallReceiver.kt`, `automation/IncomingCallManager.kt` |
| Memory Vault | `docs/max/MEMORY.md` | `memory/data/MemoryRepository.kt`, `memory/data/PermanentMemoryDao.kt` |
| Settings & DB | `docs/max/DATA.md` | `data/preferences/SettingsManager.kt`, `data/local/AppDatabase.kt` |
| Build & Device | `docs/max/BUILD.md` | `app/build.gradle.kts`, `gradlew.bat` |
| Accessibility | `docs/max/ACCESSIBILITY.md` | `service/JarvisAccessibilityService.kt` |
