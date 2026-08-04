# MAX AI Agent - Complete AI Knowledge Base

> **Generated:** 2026-07-20 | **Version:** 2.0 | **Protocol Version:** 1 (Frozen)
> Every file, class, function, constant, state machine, dependency, and call flow documented.

---

# 1. Project Overview

## 1.1 Purpose
MAX is a dual-component AI assistant platform:
- **Android App** (Kotlin/Compose): 225 Kotlin source files, Gemini Live WebSocket, 30+ automation tools, voice subsystem, WhatsApp/Telegram/Instagram integration, Shizuku privileged access
- **Windows Companion Agent** (Python asyncio): 29 Python files, WebSocket+HTTP server, CmdTool, pairing protocol, web dashboard, SSE events

## 1.2 Build Configuration
- **applicationId:** `com.aistudio.geminilive.abcde`
- **minSdk:** 24, **targetSdk:** 36, **compileSdk:** 36
- **AGP:** via version catalog, **Kotlin:** compose plugin
- **Database:** Room v10 with fallbackToDestructiveMigration
- **Python:** `websockets>=12.0` only dependency

## 1.3 Permissions (AndroidManifest.xml)
INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, POST_NOTIFICATIONS, CAMERA, SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, WAKE_LOCK, RECORD_AUDIO, SYSTEM_ALERT_WINDOW, READ_CONTACTS, CALL_PHONE, BLUETOOTH_CONNECT, WRITE_SETTINGS, ACCESS_NOTIFICATION_POLICY, ACCESS_WIFI_STATE, MANAGE_EXTERNAL_STORAGE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, ACCESS_BACKGROUND_LOCATION, PACKAGE_USAGE_STATS, READ_CALENDAR, WRITE_CALENDAR, SET_ALARM

## 1.4 Registered Services (AndroidManifest.xml)
| Service | Type | Purpose |
|---|---|---|
| JarvisAccessibilityService | AccessibilityService | WhatsApp/Telegram/Instagram automation |
| WhatsAppNotificationService | NotificationListenerService | WhatsApp message interception |
| JarvisVoiceInteractionService | VoiceInteractionService | Voice assistant trigger |
| JarvisRecognitionService | RecognitionService | Speech recognition |
| JarvisVoiceSessionService | VoiceInteraction | Voice session |
| AssistantEngineService | foreground(microphone) | Assistant engine |
| VoiceForegroundService | foreground(microphone) | Voice recording |
| TelegramBotService | foreground(specialUse) | Telegram bot polling |
| DynamicIslandOverlayService | Service | Overlay UI |

---

# 2. Android App - Network Layer

## 2.1 GeminiWebSocketClient.kt
**Path:** `app/src/main/java/com/example/network/GeminiWebSocketClient.kt`

### Class: `GeminiWebSocketClient`
**Constructor params:** apiKey, voiceName, responseLanguage, onMessageReceived, onAudioReceived, onConnectionError, onConnectionStateChanged, onExecuteAutomation

**Key fields:**
- `client: OkHttpClient` — pingInterval 30s, no read timeout
- `webSocket: WebSocket?`
- `MODEL_NAME = "gemini-3.1-flash-live-preview"`
- `isSetupComplete: Boolean`
- `lastInputTranscription: String`

**Functions:**
| Function | Purpose |
|---|---|
| `connect()` | Opens WebSocket to `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=...` |
| `sendSetupMessage()` | Sends setup JSON with model, voice config, system instruction (via PromptBuilder), and 40+ tool declarations |
| `sendInitialTrigger()` | Sends "Hello, please say your greeting." |
| `handleIncomingMessage(json)` | Parses ServerMessage, handles setupComplete, inputTranscription, modelTurn (text+audio), toolCall dispatch |
| `sendText(text)` | Sends clientContent with Gson-escaped text |
| `sendAudio(audioData)` | Sends realtimeInput with Base64 PCM audio (16kHz) |
| `disconnect()` | Closes WebSocket with code 1000 |
| `sendToolResponse(id, name, responseJsonStr)` | Sends toolResponse with functionResponses |

**Enum: `ConnectionState`** — DISCONNECTED, CONNECTING, CONNECTED, FAILED

**Tool call flow:**
1. `handleIncomingMessage` receives `toolCall.functionCalls`
2. Checks `ToolDispatcher.supportedTools.contains(name)`
3. Calls `ToolDispatcher.dispatch(functionCall, lastInputTranscription)`
4. Passes result JSON to `onExecuteAutomation(jsonStr)` callback
5. Sends response back via `sendToolResponse(callId, name, resultJsonStr)`

## 2.2 ToolDispatcher.kt
**Path:** `app/src/main/java/com/example/network/ToolDispatcher.kt`

**Singleton object with:**
- `supportedTools: Set<String>` — 40+ tool names
- `functionToActionMap: Map<String, String>` — Maps function names to action strings
- `dispatch(functionCall, lastInputTranscription): String` — Converts Gemini function calls to ActionDispatcher JSON

**Special handling:**
- `control_media` — Maps media_action to action field
- `call_contact` — Maps contact to CALL_PHONE
- `youtube_search` — Maps query to YOUTUBE_SEARCH
- `open_app(YouTube)` — Local fallback: detects search triggers in transcription, converts to YOUTUBE_SEARCH

## 2.3 MessageModels.kt
**Path:** `app/src/main/java/com/example/network/MessageModels.kt`

**Data classes:**
- `ServerMessage(serverContent?, toolCall?)`
- `ToolCall(functionCalls: List<FunctionCall>)`
- `FunctionCall(name, args: JsonObject, id?)`
- `ServerContent(modelTurn?, turnComplete?, inputTranscription?)`
- `ModelTurn(parts: List<ResponsePart>)`
- `ResponsePart(text?, inlineData?)`
- `InlineData(mimeType, data: Base64 String)`

## 2.4 PromptBuilder.kt
**Path:** `app/src/main/java/com/example/network/PromptBuilder.kt`

**Function:** `buildSystemInstruction(responseLanguage): String`

Builds system prompt with sections:
1. Identity — "You are MAX", never claim tool success without confirmation
2. Personality — Professional, address user as "Sir", never "bro"/"dude"
3. Language Policy — English-only, Telugu-only, or Tenglish (English+Telugu blend)
4. Automation Policy — Select best tool, provide required params only
5. Tool Selection Rules — Prefer single tool, use youtube_search for music
6. Multi-step Planning — Chain tools when necessary
7. Proactive Behavior — Silent mode awareness, notification checks

## 2.5 WindowsAgentClient.kt
**Path:** `app/src/main/java/com/example/network/agent/WindowsAgentClient.kt`

### Class: `WindowsAgentClient(context: Context)`

**Interfaces:**
- `ConnectionListener` — onConnected(capabilities), onDisconnected(), onError(t)
- `ToolResponseCallback` — onProgress(message), onResponse(status, output), onError(error)

**Key fields:**
- `toolCallbacks: MutableMap<String, ToolResponseCallback>` — with 60s timeout cleanup
- `pairCallback: ((Boolean, String?) -> Unit)?`
- `deviceId: String` — persisted in SharedPreferences

**Functions:**
| Function | Purpose |
|---|---|
| `connect(ip, port, listener)` | Opens WebSocket, sends hello handshake |
| `sendHelloHandshake()` | Sends Envelope(type="hello") with device info |
| `handleIncomingMessage(text)` | Handles hello, pair_response, tool_progress, tool_response, heartbeat |
| `pair(pairingCode, callback)` | Sends pair_request, stores auth_token on success |
| `sendToolRequest(tool, action, arguments, callback)` | Sends tool_request with auth token |
| `sendEvent(event, payload)` | Sends event envelope |
| `disconnect()` | Closes WebSocket, clears callbacks |
| `cleanExpiredCallbacks()` | Removes callbacks older than 60s |

**Protocol data classes:** Envelope, Source, Target, HelloPayload, HelloResponsePayload, PairRequestPayload, PairResponsePayload, ToolRequestPayload, ToolProgressPayload, ToolResponsePayload

## 2.6 WindowsToolExecutor.kt
**Path:** `app/src/main/java/com/example/network/agent/WindowsToolExecutor.kt`

Bridges Gemini tool calls to Windows agent via `WindowsAgentClient`.

---

# 3. Android App - Automation Engine

## 3.1 ActionDispatcher.kt
**Path:** `app/src/main/java/com/example/automation/engine/ActionDispatcher.kt`

**Singleton with:**
- `normalizeJson(json)` — Normalizes action names (PERFORM_BACK→SYSTEM_ACTION, VOLUME_UP→SET_VOLUME+direction, DND_ON→SET_DND+dndEnabled, SILENT_MODE_ON→SET_RINGER_MODE+mode)
- `dispatch(context, json): Boolean` — Normalizes → creates ExecutionRequest → calls ExecutionEngine.execute()
- `dispatchWithResult(context, json): String` — Same but returns JSON result with success/message/error/data

**Normalization table:**
| Input Action | Normalized Action | Added Fields |
|---|---|---|
| PERFORM_BACK | SYSTEM_ACTION | system_action_str="back" |
| PERFORM_HOME | SYSTEM_ACTION | system_action_str="home" |
| PERFORM_RECENT_APPS | SYSTEM_ACTION | system_action_str="recent" |
| VOLUME_UP | SET_VOLUME | direction="up" |
| VOLUME_DOWN | SET_VOLUME | direction="down" |
| BRIGHTNESS_UP | SET_BRIGHTNESS | direction="up" |
| BRIGHTNESS_DOWN | SET_BRIGHTNESS | direction="down" |
| DND_ON | SET_DND | dndEnabled=true |
| DND_OFF | SET_DND | dndEnabled=false |
| SILENT_MODE_ON | SET_RINGER_MODE | mode="silent" |
| SILENT_MODE_OFF | SET_RINGER_MODE | mode="normal" |

## 3.2 ExecutionEngine.kt
**Path:** `app/src/main/java/com/example/automation/engine/ExecutionEngine.kt`

**Core execution loop:**
1. Publish `ExecutionStarted` event
2. Look up tool via `ToolRegistry.getToolForAction(request.action)`
3. If `RequestValidator` → validate request
4. Publish `ToolStarted` event
5. Execute via `RetryEngine.executeWithRetry()`:
   - `executeBlock` → `tool.execute(context, request)`
   - `verifyBlock` → capture DeviceContext snapshot, run `VerificationRegistry.getVerifierForTool()`
6. Attach `ExecutionMetrics` (timing, retries, verification time)
7. Publish completion/failure events

## 3.3 Supporting Engine Types

| File | Type | Fields/Values |
|---|---|---|
| ExecutionRequest.kt | data class | executionId(UUID), action, arguments(JsonObject), source, cancellationToken, timestamp |
| ExecutionResult.kt | data class | success, output(JSONObject?), error?, duration |
| ExecutionSource.kt | enum | GEMINI_LIVE, WAKE_WORD, SCHEDULER, TELEGRAM, HTTP_API, MANUAL, PLANNER |
| ExecutionState.kt | enum | QUEUED, RUNNING, VERIFYING, RETRYING, SUCCEEDED, FAILED, CANCELLED |
| ExecutionMetrics.kt | data class | startedAt, finishedAt, retries, verificationTimeMs, totalDurationMs |
| CancellationToken.kt | class | isCancelled (lazy check) |
| ExecutionPlan.kt | data class | steps: List<ExecutionStep> |
| ExecutionStep | data class | toolName, arguments(JSONObject) |
| TaskContext.kt | data class | source(TaskSource), taskId?, scheduled, createdAt |
| TaskSource.kt | enum | GEMINI_LIVE, SCHEDULER, LEGACY_ACTION, UNKNOWN |
| RequestValidator.kt | interface | validate(request): Boolean |

## 3.4 ServerActionExecutor.kt
**Path:** `app/src/main/java/com/example/automation/engine/ServerActionExecutor.kt`

Executes server-pushed actions via CloudSocketManager. Has `ALLOWED_ACTIONS` whitelist (15 actions). Iterates action array, dispatches each via ActionDispatcher.dispatchWithResult(), sends results back via CloudSocketManager.

---

# 4. Android App - Automation Tools

## 4.1 Tool Interface & Registry

**Tool.kt interface:**
```kotlin
interface Tool {
    val name: String
    val supportedActions: Set<String>
    val retryPolicy: RetryPolicy
    val capabilities: ToolCapabilities
    fun validate(request: ExecutionRequest): Boolean
    suspend fun execute(context: Context, request: ExecutionRequest): ToolResult
}
```

**ToolResult.kt:** success, toolName, attemptCount, errorCode?, message?, retryable, verificationRequired, verification?, metrics?, metadata(JSONObject)

**ToolCapabilities.kt:** supportsPlanner, requiresAccessibility, requiresNetwork, cancellable

**ToolRegistry.kt:** Singleton with register(tool), freeze(), getToolForAction(action), getToolByName(name), getAllTools(). Freezes after MyApplication registers all tools. Validates no duplicates. Warns if tool has no matching verifier.

## 4.2 Registered Tools (34 tools)

| Tool | Actions | Verifier |
|---|---|---|
| OpenAppTool | OPEN_APP | OpenAppVerifier |
| FlashlightTool | FLASHLIGHT_ON/OFF | — |
| WhatsAppTool | SEND_WHATSAPP | WhatsAppVerifier |
| ScheduleTaskTool | SCHEDULE_TASK | — |
| CancelTaskTool | CANCEL_TASK | — |
| ListScheduledTasksTool | LIST_TASKS | — |
| VolumeTool | SET_VOLUME | VolumeVerifier |
| BrightnessTool | SET_BRIGHTNESS | BrightnessVerifier |
| RingerTool | SET_RINGER_MODE | RingerVerifier |
| CallTool | CALL_PHONE | CallVerifier |
| SystemTool | SYSTEM_ACTION | SystemVerifier |
| YoutubeTool | YOUTUBE_SEARCH | YoutubeVerifier |
| WifiTool | TOGGLE_WIFI | WifiVerifier |
| DiagnosticsTool | MAX_DIAGNOSTICS | DiagnosticsVerifier |
| CreateContactTool | CREATE_CONTACT | CreateContactVerifier |
| WindowsAgentTool | WINDOWS_CMD | — |
| LocationTool | GET_LOCATION | — |
| ShizukuTool | RUN_ADB_COMMAND | ShizukuVerifier |
| BluetoothTool | SET_BLUETOOTH | BluetoothVerifier |
| BatteryTool | GET_BATTERY_STATUS | BatteryVerifier |
| DndTool | SET_DND | DndVerifier |
| CameraTool | TAKE_PHOTO | CameraVerifier |
| CalendarTool | GET/ADD_CALENDAR_EVENT | CalendarVerifier |
| NotificationTool | GET_NOTIFICATIONS, REPLY_NOTIFICATION | — |
| MediaTool | CONTROL_MEDIA | — |
| AlarmTool | SET_ALARM | — |
| FileSearchTool | SEARCH_FILES | — |
| SettingsSearchTool | OPEN_SETTINGS | — |
| DeviceStatusTool | GET_DEVICE_STATUS | — |
| UsageStatsTool | GET_APP_USAGE | — |
| ClipboardTool | GET/SET_CLIPBOARD | — |
| RoutineTool | RUN/CREATE/DELETE/LIST_ROUTINE | — |
| PeriodTrackerTool | LOG_PERIOD_START/END/NOTE, GET_PERIOD_HISTORY/PREDICTION, CLEAR_ALL_PERIOD_DATA | PeriodTrackerVerifier |

---

# 5. Android App - Verification System

## 5.1 Verifier Interface
```kotlin
interface Verifier {
    fun verify(context: Context, request: ExecutionRequest, result: ToolResult, snapshot: DeviceContext): VerificationResult
}
```

**VerificationResult.kt:** success, reason?, retryRecommended, snapshot(DeviceContext)

**DeviceContext.kt:** Captures device state snapshot at verification time.

**RetryEngine.kt:** Executes tool with retry logic, calls verifyBlock after each attempt. Uses exponential backoff via RetryPolicy.

**RetryPolicy.kt:** maxRetries, backoffMs, maxBackoffMs

## 5.2 VerificationRegistry
Singleton. Maps tool names to verifiers. Registered at startup in MyApplication. `freeze()` prevents further registration. `getVerifierForTool(toolName)` returns matching verifier or null.

---

# 6. Android App - Voice System

## 6.1 AssistantEngine.kt
**Path:** `app/src/main/java/com/example/voice/assistant/AssistantEngine.kt`

Central voice orchestrator. Creates and owns:
- `stateMachine: SessionStateMachine`
- `conversationManager: ConversationManager`
- `capabilityProvider: CapabilityProvider`
- `executionPolicy: ExecutionPolicy`
- `authorizationManager: AuthorizationManager`
- `toolRegistry: AssistantToolRegistry`
- `audioFocusManager: AudioFocusManager`
- `audioPlayer: AudioPlayer`
- `audioProcessor: AudioProcessor` (30s silence timeout)
- `audioRecorder: AudioRecorder`
- `connectionManager: ConnectionManager` (wraps GeminiWebSocketClient)
- `streamingResponseManager: StreamingResponseManager`

**Functions:**
- `start()` — Starts session, connects if not connected
- `startListening()` — Starts audio recording, sends chunks to Gemini, barge-in support
- `stopListening()` — Stops recording
- `sendTextMessage(text)` — Sends text to Gemini
- `pause()` / `stop()` — Stops listening + playback
- `destroy()` — Full cleanup

## 6.2 SessionStateMachine.kt
**States:** IDLE, CONNECTING, CONNECTED, LISTENING, PROCESSING_AUDIO, WAITING_FOR_MODEL, STREAMING_TEXT, STREAMING_AUDIO, TOOL_EXECUTION, WAITING_TOOL_RESULT, RESUMING, DISCONNECTING, ERROR

## 6.3 ConnectionManager.kt (voice/assistant/)
Wraps GeminiWebSocketClient with auto-reconnect (exponential backoff, max 30s). Reads API key from SharedPreferences.

## 6.4 ConversationManager.kt
Maintains message history (max 30 turns = 15 user + 15 assistant). Thread-safe via @Synchronized.

## 6.5 AudioStack
- **AudioFocusManager.kt** — Requests/abandons audio focus (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
- **AudioPlayer.kt** — Plays 24kHz PCM audio via AudioTrack, computes RMS for visualizer
- **AudioProcessor.kt** — VAD with 0.02 RMS threshold, 30s silence timeout triggers onSilenceTimeout callback
- **AudioRecorder.kt** — Records 16kHz PCM audio, sends chunks via callback

## 6.6 Voice Assistant Subsystem
**voice/assistant/** (11 files): AssistantEngine, AssistantEventBus, AssistantLifecycleManager, AssistantLogger, AssistantSessionManager, AssistantVoiceController, ConnectionManager, ConversationManager, FeatureManager, SessionStateMachine, StreamingResponseManager

**voice/session/** (6 files): AssistantEngineService, ComposeViewLifecycleOwner, JarvisRecognitionService, JarvisVoiceInteractionService, JarvisVoiceSession, JarvisVoiceSessionService

**voice/tools/** (5 files): AuthorizationManager, CapabilityProvider, ExecutionPolicy, PermissionChecker, ToolRegistry (assistant-specific)

**voice/trigger/** (1 file): JarvisVoiceTriggerActivity — Voice trigger entry point

**voice/ui/** (6 files): AssistantHud, BottomPill, GlowBorder, LiquidScreenEdges, SoundWave, TranscriptBubble

---

# 7. Android App - Data Layer

## 7.1 AppDatabase.kt
**Room database v10** with 8 entities:
| Entity | Table | Key Fields |
|---|---|---|
| ChatMessage | chat_messages | id(auto), sender, text, timestamp, isPending |
| Reminder | reminders | id, message, triggerAt, status, automationType/Target/Message |
| AutoReplyRule | auto_reply_rules | id, pattern, response, isEnabled |
| ActionReward | action_rewards | (tracking) |
| InstalledApp | installed_apps | (app cache) |
| ScheduledTask | scheduled_tasks | (task scheduling) |
| TaskExecutionLog | task_execution_logs | (execution logs) |
| PeriodLog | period_logs | id, startDate, endDate, durationDays, notes |

**Migration:** MIGRATION_9_10 creates period_logs table with unique index on startDate.

## 7.2 SettingsManager.kt
SharedPreferences wrapper with 30+ settings including:
- Backend URL, user ID, FCM token
- Local AI mode, Gemini API key
- Auto-reply: enabled, AI fallback, allowed contacts, quick replies
- Cloud AI model, language preference, bot persona, user name
- Telegram: enabled, bot token, chat ID
- Cloud bot URL, app secret
- Agentic mode: enabled, max iterations(8), daily budget(50), iterations used today
- Live voice: mode, model name, voice name

## 7.3 JarvisRepository.kt
Repository pattern wrapping AppDatabase + SettingsManager. Provides Flow-based access to messages, reminders, rules. Handles alarm scheduling via AlarmManager.

---

# 8. Android App - UI Layer

## 8.1 MainActivity.kt
**Main activity** with Compose NavHost. Routes:
- `home` → ChatScreen (main chat interface)
- `settings` → SettingsScreen (API key, voice, language, Windows agent, Telegram config)
- `permissions` → PermissionsScreen
- `privacy` → PrivacyPolicyScreen
- `about` → AboutScreen
- `period_tracker` → PeriodTrackerScreen

**ChatScreen composable:** Message list (LazyColumn, reverseLayout), text input, mic button with recording animation, pending automation dispatch via LaunchedEffect.

## 8.2 SettingsScreen.kt
Full settings: API key, voice selection (Aoede/Kore), language (English/Telugu/Tenglish), Windows agent IP/port/pairing, Telegram bot token/chat ID toggle.

## 8.3 Theme
- `Color.kt` — BgDark, SurfaceDark, NeonBlue, NeonPink, NeonGreen, TextLight, PrimaryLight, etc.
- `Theme.kt` — MyApplicationTheme
- `Type.kt` — Typography

---

# 9. Android App - Services

## 9.1 TelegramBotService.kt
Foreground service polling Telegram Bot API. Long-polls getUpdates with 30s timeout. Whitelist of allowed actions (FLASHLIGHT_ON/OFF, GET_LOCATION, TAKE_SCREENSHOT, HELP). Exponential backoff on errors. Sends results via Telegram sendMessage API.

## 9.2 VoiceForegroundService.kt
Foreground notification service for background voice recording. Uses FOREGROUND_SERVICE_TYPE_MICROPHONE.

## 9.3 JarvisAccessibilityService.kt (966 lines)
Full accessibility service for WhatsApp/Telegram/Instagram automation. Handles notification interception, remote input, message sending via UI automation.

## 9.4 WhatsAppNotificationService.kt
NotificationListenerService for intercepting WhatsApp notifications and triggering auto-replies.

---

# 10. Android App - Receivers

## 10.1 BootReceiver.kt
Listens for BOOT_COMPLETED. Restarts TelegramBotService if enabled. Re-schedules pending alarms.

## 10.2 ReminderReceiver.kt
Alarm-triggered receiver. Shows notification with reminder message.

## 10.3 AutoReplyActionReceiver.kt
Handles notification action buttons for auto-reply (cancel, send now, quick reply).

## 10.4 AppsInstalledReceiver.kt
Listens for PACKAGE_ADDED/REMOVED/REPLACED to update installed apps cache.

## 10.5 TaskReceiver.kt
Scheduler alarm trigger. Executes scheduled tasks via ActionDispatcher.

---

# 11. Android App - Security

- **MaxLockAutomationEngine.kt** — Lock screen automation
- **SecuritySettings.kt** — Security configuration
- **UnlockManager.kt** — Device unlock management

---

# 12. Android App - Core Event System

- **AgentEvent.kt** — Sealed class for agent events
- **EventBus.kt** — Core event bus
- **EventPriority.kt** — Event priority levels
- **StateManager.kt** — Agent state management
- **ServiceRegistry.kt** — Service type registry

---

# 13. Android App - Telegram Integration

## 13.1 CloudSocketManager.kt
WebSocket client connecting to cloud backend. Handles:
- `handshake` — Sends capabilities on connect
- `action_request` — Routes to ServerActionExecutor
- `final_response` — Routes to JarvisCore.processServerResponse()
- `settings_sync` — Syncs preferred AI model
- Auto-reconnect with 5s delay

## 13.2 TelegramController.kt
Handles Telegram message processing within the automation framework.

---

# 14. Android App - Automation Controllers

- **WhatsAppController.kt** — WhatsApp message handling
- **InstagramController.kt** — Instagram notification handling
- **AutoReplyController.kt** — Smart auto-reply with regex + AI fallback
- **YoutubeAutomation.kt** — YouTube search/play via accessibility
- **RemoteInputSender.kt** — Remote input via notification actions
- **AppAutomation.kt** — General app automation utilities

---

# 15. Windows Agent - Architecture

## 15.1 Directory Structure
```
max-windows-agent/
├── main.py                    # Entry point
├── config.py                  # Config loader
├── requirements.txt           # websockets>=12.0
├── core/
│   ├── __init__.py
│   ├── connection_manager.py  # Active connection tracking
│   ├── metrics_manager.py     # Runtime metrics
│   ├── session_manager.py     # Pairing + token management
│   └── settings_manager.py    # Config persistence
├── protocol/
│   ├── __init__.py            # Exports all protocol types
│   ├── close_codes.py         # WebSocket close codes
│   ├── constants.py           # Port/timeout constants
│   ├── error_codes.py         # API error codes
│   ├── event_types.py         # Domain event types
│   ├── packet_factory.py      # Envelope builder
│   ├── packet_types.py        # Packet type enum
│   ├── setting_keys.py        # Config key enum
│   ├── validators.py          # Envelope + payload validation
│   └── versions.py            # Protocol version (1)
├── server/
│   ├── api_router.py          # HTTP API + SSE + static files
│   ├── event_bus.py           # Pub/sub event system
│   ├── http_server.py         # Raw asyncio HTTP server
│   ├── pairing_manager.py     # Thin wrapper over SessionManager
│   └── websocket_server.py    # WebSocket handler
├── tools/
│   ├── __init__.py            # Auto-registers CmdTool
│   ├── base_tool.py           # Abstract BaseTool
│   ├── cmd_tool.py            # Shell commands (dir/echo/cd/where)
│   └── tool_registry.py       # Tool registration
└── tests/
    ├── test_architecture.py
    └── test_event_bus.py
```

## 15.2 Entry Point (main.py)
- Loads config via `load_config()`
- Creates WebSocketServer(host, ws_port) and HTTPServer(host, http_port, handler)
- Runs both servers concurrently via `asyncio.gather()`
- Publishes AGENT_STARTED/AGENT_SHUTDOWN domain events

---

# 16. Windows Agent - Protocol

## 16.1 Protocol Versions
- `PROTOCOL_VERSION = 1` (frozen)
- `API_VERSION = 1`
- `Protocol.supports(version)` — Only accepts version 1

## 16.2 Packet Types
```python
class PacketType(Enum):
    HELLO = "hello"
    PAIR_REQUEST = "pair_request"
    PAIR_RESPONSE = "pair_response"
    TOOL_REQUEST = "tool_request"
    TOOL_PROGRESS = "tool_progress"
    TOOL_RESPONSE = "tool_response"
    HEARTBEAT = "heartbeat"
```

## 16.3 Close Codes
```python
class CloseCode(Enum):
    NORMAL = 1000
    PROTOCOL_MISMATCH = 1003
    UNAUTHORIZED = 4001
    INVALID_PAYLOAD = 4002
```

## 16.4 Error Codes
```python
class ErrorCode(Enum):
    UNAUTHORIZED = "UNAUTHORIZED"
    NOT_FOUND = "NOT_FOUND"
    INTERNAL_ERROR = "INTERNAL_ERROR"
    METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
    INVALID_PARAMS = "INVALID_PARAMS"
```

## 16.5 Event Types
```python
class EventType(Enum):
    AGENT_STARTED, AGENT_SHUTDOWN, CLIENT_CONNECTED, CLIENT_DISCONNECTED,
    PAIR_STARTED, PAIR_SUCCESS, PAIR_FAILED,
    TOOL_REQUESTED, TOOL_PROGRESS, TOOL_COMPLETED, TOOL_FAILED, HEARTBEAT
```

## 16.6 Constants
```python
DEFAULT_WS_PORT = 9000
DEFAULT_HTTP_PORT = 9001
DEFAULT_HOST = "0.0.0.0"
HEARTBEAT_INTERVAL = 25
HANDSHAKE_TIMEOUT = 10
PAIR_TIMEOUT = 30
EXECUTION_TIMEOUT = 30
```

## 16.7 PacketFactory
`create_envelope(request_id, packet_type, target_device_id, payload)` — Builds envelope with protocol_version, api_version, timestamp, source(windows-main), target.

## 16.8 PacketValidator
- `validate_envelope(envelope)` — Checks required fields, protocol version compatibility
- `validate_payload(packet_type, payload)` — Checks type-specific required fields (pairing_code+device_name for pair_request, token+tool+action for tool_request)

---

# 17. Windows Agent - Server

## 17.1 WebSocketServer.kt
**Path:** `max-windows-agent/server/websocket_server.py`

**Handler flow:**
1. Receive hello handshake → validate envelope → send hello response with capabilities
2. Register connection in ConnectionManager, increment metrics
3. Main loop: `process_packet()` for each message
4. On disconnect: unregister, decrement metrics, publish CLIENT_DISCONNECTED

**Packet processing:**
- `pair_request` → PairingManager.pair_device() → send pair_response
- `tool_request` → verify token → ToolRegistry.get_tool() → tool.execute() → send tool_response
- `heartbeat` → echo back with uptime
- Progress callbacks via `send_progress()` → sends tool_progress packets

## 17.2 HTTPServer.kt
Raw asyncio TCP server. Reads HTTP request line, headers, body. Delegates to api_router handler.

## 17.3 api_router.py
**Endpoints:**
| Endpoint | Method | Purpose |
|---|---|---|
| `/health` or `/api/v1/health` | GET | Health check |
| `/metrics` or `/api/v1/metrics` | GET | Runtime metrics |
| `/api/status` or `/api/v1/status` | GET | Full status (uptime, devices, connections) |
| `/api/events` or `/api/v1/events` | GET | SSE event stream with replay |
| `/*` | GET | Static file serving from dashboard/ |

**SSE Events:** Subscribes to EventBus queue, streams events as `id: {id}\nevent: {type}\ndata: {json}\n\n`. Supports Last-Event-ID for replay. Keepalive comments every 20s.

**Static files:** Serves from `dashboard/` directory with MIME type detection and directory traversal prevention.

---

# 18. Windows Agent - Tools

## 18.1 BaseTool (ABC)
```python
class BaseTool(ABC):
    @property
    @abstractmethod
    def name(self) -> str: ...
    @abstractmethod
    async def execute(self, action: str, arguments: dict, send_progress) -> dict: ...
```

## 18.2 CmdTool
**Actions:**
| Action | Parameters | Purpose |
|---|---|---|
| `dir` | path? | List directory contents with sizes |
| `echo` | message | Return message |
| `cd` | path | Change working directory |
| `where` | program | Find program location via shutil.which or system `where` |

Returns `{"status": "success"/"failed", "output": "..."}` or `{"status": "failed", "error": {"code": "...", "message": "...", "retryable": false}}`

## 18.3 ToolRegistry
Class-level dict mapping tool name → BaseTool instance. Auto-registers CmdTool on import. `get_capabilities()` returns `{name: 1}` for all registered tools.

---

# 19. Windows Agent - Core

## 19.1 SessionManager
**Pairing code:** 6-digit random integer, regenerated per session.
**Paired devices:** Persisted to `storage/paired_devices.json`. Each entry: device_id, device_name, token (secrets.token_hex(16)).
**Functions:** pair_device() — validates code, generates/updates token. verify_token() — checks device_id + token match.

## 19.2 ConnectionManager
Tracks active WebSocket connections. Each ConnectionInfo: websocket, device_id, platform, device_name, connected_at, last_seen, latency_ms.

## 19.3 MetricsManager
Thread-safe counters: events_sent, connections, tool_requests, avg_latency_ms (running average). Used by HTTP API `/metrics` endpoint.

## 19.4 SettingsManager
Loads/saves `storage/config.json`. Default config: port=9000, host=0.0.0.0, agent_name="MAX Windows Agent", protocol_version=1, pairing_enabled=True.

## 19.5 EventBus
Pub/sub system with ring buffer (max 100 events). Subscribers are asyncio.Queue instances. Supports event replay via `get_events_after(last_event_id)`.

---

# 20. Dependency Graph

## 20.1 Android App Key Dependencies
```
MyApplication
├── ToolRegistry (34 tools registered)
│   ├── OpenAppTool → OpenAppAction → OpenAppVerifier
│   ├── VolumeTool → VolumeAction → VolumeVerifier
│   ├── WhatsAppTool → WhatsAppVerifier
│   ├── WindowsAgentTool → WindowsToolExecutor → WindowsAgentClient
│   └── ... (30+ more)
├── VerificationRegistry (18 verifiers registered)
└── TelegramBotService (if configured)

ChatViewModel
├── GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine
├── AudioRecorder → playAudioResponse
└── ChatUiState (messages, connectionState, pendingAutomation, isRecording)

AssistantEngine
├── ConnectionManager → GeminiWebSocketClient
├── SessionStateMachine
├── ConversationManager
├── StreamingResponseManager → AudioPlayer → AudioFocusManager
├── AudioRecorder → AudioProcessor (VAD)
└── AssistantToolRegistry → AuthorizationManager → ExecutionPolicy → CapabilityProvider
```

## 20.2 Windows Agent Dependencies
```
main.py
├── WebSocketServer → PairingManager → SessionManager
│   ├── ToolRegistry → CmdTool
│   ├── ConnectionManager
│   ├── MetricsManager
│   └── EventBus
├── HTTPServer → api_router
│   ├── SettingsManager
│   ├── SessionManager
│   ├── ConnectionManager
│   ├── MetricsManager
│   └── EventBus (SSE)
└── config.py → SettingsManager
```

---

# 21. State Machines

## 21.1 Gemini Connection State
```
DISCONNECTED → CONNECTING → CONNECTED
                          ↘ FAILED
CONNECTED → DISCONNECTED (on close/failure)
FAILED → CONNECTING (auto-reconnect)
```

## 21.2 Voice Session State Machine
```
IDLE → CONNECTING → CONNECTED
CONNECTED → LISTENING (startListening)
LISTENING → CONNECTED (stopListening)
LISTENING → WAITING_FOR_MODEL (sendText)
CONNECTED → STREAMING_AUDIO (model audio response)
STREAMING_AUDIO → LISTENING (user barge-in)
ANY → ERROR (connection failure)
ANY → DISCONNECTING → IDLE
```

## 21.3 Execution State Machine
```
QUEUED → RUNNING → VERIFYING → SUCCEEDED
                          ↘ RETRYING → RUNNING (retry)
                   ↘ FAILED
QUEUED → CANCELLED (via CancellationToken)
```

## 21.4 Telegram Bot Service State
```
onCreate → startForeground → onStartCommand
onStartCommand → validate token/chatId → poll loop
poll loop → pollUpdates → handleUpdate → processBotCommand → sendTelegramMessage
error → exponential backoff → retry poll
onDestroy → cancel scope → shutdown HTTP client
```

---

# 22. Key Sequence Diagrams

## 22.1 Voice Command Flow
```
User speaks → AudioRecorder → GeminiWebSocketClient.sendAudio()
→ Gemini API processes → returns toolCall or text
→ handleIncomingMessage()
  ├── If text: onMessageReceived → ChatViewModel adds to UI
  ├── If audio: onAudioReceived → AudioPlayer.play()
  └── If toolCall: ToolDispatcher.dispatch() → onExecuteAutomation()
      → ActionDispatcher.dispatchWithResult() → ExecutionEngine.execute()
      → Tool.execute() → Verification → sendToolResponse()
```

## 22.2 Text Command Flow
```
User types → ChatViewModel.sendTextMessage()
→ Regex interceptors (volume/brightness/ringer/call/youtube)
  ├── If matched: dispatch locally via pendingAutomation
  └── If not matched: GeminiWebSocketClient.sendText()
      → Gemini returns toolCall → same tool dispatch as voice
```

## 22.3 Windows Agent Tool Execution
```
Android: WindowsAgentClient.sendToolRequest()
→ WebSocket: tool_request envelope
→ Windows: WebSocketServer.process_packet()
→ verify_token() → ToolRegistry.get_tool()
→ CmdTool.execute(action, args, send_progress)
→ tool_response envelope → Android: callback.onResponse()
```

## 22.4 Pairing Flow
```
Android: WindowsAgentClient.pair(code)
→ pair_request envelope → Windows: PairingManager.pair_device()
→ validates code → generates token → stores in paired_devices.json
→ pair_response(success, token) → Android: stores token in SharedPreferences
```

## 22.5 SSE Event Stream
```
Client: GET /api/events (with Last-Event-ID header)
→ api_router subscribes EventBus queue
→ Replays missed events from buffer
→ Streams new events as: id: {id}\nevent: {type}\ndata: {json}\n\n
→ Keepalive comments every 20s
```
