# Automation Subsystem — MAX

## Purpose
Translates natural-language commands (via Gemini tool calls or direct dispatch) into executable device actions via a structured registry + execution pipeline.

## Execution verification update — 2026-10-04
- `LongTaskOrchestrator` validates initial/replacement DAGs, checks executor success AND outcome verification, persists replacement plans with versioned history, and derives completion from persisted steps. A failed dispatch cannot be promoted by a matching cached foreground.
- `AgentTaskStore` stores ActionStep payloads/dependencies in existing JSON columns; no schema migration is required. It distinguishes WAITING_FOR_INPUT from WAITING_FOR_APPROVAL and rejects stale execution results. `TaskTransitionAuthority` rejects mismatched execution IDs and success without verification evidence.
- `ToolResult.isVerifiedSuccess` is used for planner advancement and native result envelopes. Required verification without a registered verifier fails closed; tools with an intentional non-verification contract must declare `verificationRequired=false`.
- `DurableRecoveryCoordinator` cannot complete a whole task from one verified interrupted call. Ambiguous external effects are blocked, and RetryEngine does not replay irreversible actions.
- Computer-use observations use unique observation IDs, semantic screen fingerprints, fresh fallback snapshots, visible nodes, and the existing RAM-only screenshot provider. Launch verification requires exact package identity; unchanged Enter/key dispatch is not proof of success; stale coordinate targets require re-grounding.
- Existing ComputerUseEngine and UiVerificationLoop remain bounded execution paths. Complete unified scheduling, independent-step parallelism, approval continuation, and restart resumption still need integration/device acceptance; this update does not establish full autonomous operation.

## Main Classes

### Engine
| Class | File | Role |
|-------|------|------|
| `ActionDispatcher` | `automation/engine/ActionDispatcher.kt` | dispatch()/dispatchWithResult(); normalizeJson(); creates ExecutionRequest |
| `ExecutionEngine` | `automation/engine/ExecutionEngine.kt` | Core execute loop; ToolRegistry lookup; RetryEngine; VerificationRegistry |
| `GoalPlanner` | `automation/engine/GoalPlanner.kt` | Multi-step LLM planning; ActionQueue; WorldStateManager integration |
| `AgentOrchestrator` | `automation/engine/AgentOrchestrator.kt` | Orchestrates multi-step agentic execution |
| `ActionAuthorizer` | `automation/engine/ActionAuthorizer.kt` | Validates that action matches user's last explicit intent |
| `IntentClassifier` | `automation/engine/IntentClassifier.kt` | Classifies user intent from text |
| `NaturalCommandNormalizer` | `automation/engine/NaturalCommandNormalizer.kt` | Normalizes Telugu/English/Tenglish commands |
| `OfflineCommandEngine` | `automation/engine/OfflineCommandEngine.kt` | Rule-based fallback (23KB; parallel to LLM path) |
| `WorldStateManager` | `automation/state/WorldStateManager.kt` | Current goal + step state |
| `ActionQueue` | `automation/engine/ActionQueue.kt` | FIFO queue for multi-step plan steps |
| `ServerActionExecutor` | `automation/engine/ServerActionExecutor.kt` | Executes actions from Windows server |

### UI Automation Agent Subsystem (`com.example.automation.agent`)
| Class | File | Role |
|-------|------|------|
| `UiAgentRouter` | `automation/agent/UiAgentRouter.kt` | Dynamic provider routing (OpenRouter primary, Gemini fallback) |
| `OpenRouterUiAgentProvider` | `automation/agent/OpenRouterUiAgentProvider.kt` | OpenRouter REST API with `stealth/ox-alpha` default, dynamic model discovery & tool calling |
| `GeminiUiAgentProvider` | `automation/agent/GeminiUiAgentProvider.kt` | Google Gemini REST API (`gemini-2.0-flash`) fallback agent |
| `UiVerificationLoop` | `automation/agent/UiVerificationLoop.kt` | Bounded (max 8 steps) observation -> reasoning -> action -> verification cycle |
| `UiActionExecutor` | `automation/agent/UiActionExecutor.kt` | Dual-tier atomic execution: Tier 1 (Accessibility gestures) + Tier 2 (Shizuku ADB) |
| `UiAgentTool` | `automation/tools/UiAgentTool.kt` | `ToolRegistry` tool exposing `UI_AGENT_AUTOMATION` for Gemini Live tool calling |

### Tools (46 registered)
All tools implement `Tool` interface and are registered in `MyApplication.onCreate()` via `ToolRegistry.register()`.

Tool names (Gemini function_name → action string):
- `open_app` → `OPEN_APP`
- `send_whatsapp_message` → `SEND_WHATSAPP`
- `flashlight` → `FLASHLIGHT_ON/OFF`
- `call_contact` → `CALL_PHONE`
- `youtube_search` → `YOUTUBE_SEARCH`
- `set_reminder` → `SET_REMINDER`
- `save_memory` → `SAVE_MEMORY`
- `forget_memory` → `FORGET_MEMORY`
- `get_screen_context` → `GET_SCREEN_CONTEXT`
- `click_element` → `CLICK_TEXT`
- `scroll_screen` → `SCROLL_SCREEN`
- `unlock_device` → `UNLOCK_DEVICE`
- `execute_ui_agent_task` → `UI_AGENT_AUTOMATION`
- ... (see ToolDispatcher.supportedTools for complete list)

### Actions (27 classes)
Located in `automation/actions/`. Each implements a platform operation:
- `OpenAppAction`, `CallPhoneAction`, `VolumeAction`, `BrightnessAction`
- `BluetoothAction`, `WifiSettingsAction`, `DndAction`, `RingerAction`
- `BatteryAction`, `CalendarAction`, `CreateContactAction`, `SearchContactAction`
- `AnswerCallAction`, `RejectCallAction`, `SendSmsAction`, `SystemAction`
- `ShizukuAction`, `FlashlightAction`, `CameraAction`, `DirectAutomationAction`
- `AccessibilityStepsAction`, `ScheduleMessageAction`, `SetReminderAction`
- `SendMessageAction`, `TimeAction`, `VolumeAction`, `WifiSettingsAction`

### Verification (26 verifiers)
Located in `automation/verification/`. Each verifier checks post-execution device state.
- `RetryEngine` — handles retries based on `VerificationResult.retryRecommended`
- `DeviceContext` — captures volume, brightness, ringer, Bluetooth, DnD state
- Key verifiers: `WhatsAppVerifier`, `OpenAppVerifier`, `YoutubeVerifier`, `CreateContactVerifier`

## Execution Pipeline
```
Command (string or tool call)
  └─ ActionDispatcher.dispatchWithResult(context, json)
        ├─ normalizeJson() — handles legacy/shorthand action names
        └─ ExecutionEngine.execute(context, ExecutionRequest)
              ├─ ToolRegistry.getToolForAction(action) → Tool
              ├─ [25s timeout] RetryEngine.executeWithRetry()
              │     └─ tool.execute(context, request) → ToolResult
              ├─ If verificationRequired:
              │     └─ VerificationRegistry.getVerifierForTool()
              │           └─ verifier.verify() → VerificationResult
              │                 └─ If !success && retryRecommended → retry
              └─ AutomationEventBus.publish(events)
```

## Scheduler
```
automation/scheduler/
  ScheduleTaskTool → AlarmScheduler.scheduleTask(ScheduledTask)
    └─ AlarmManager.setExactAndAllowWhileIdle()
          └─ TaskReceiver.onReceive() fires
                └─ TaskManager.executeTask(task)
                      └─ ActionDispatcher.dispatch()
```

## Agentic Mode
- Toggle: `SettingsManager.isAgenticModeEnabled`
- Max iterations per call: `agenticMaxIterations` (1–15, default 8)
- Daily budget: `agenticDailyBudget` (5–200, default 50)
- `GoalPlanner.executePlan()` calls `GeminiPlanningProvider` → gets step list → executes via ExecutionEngine

## Activity Tracking
`AgentActivityTracker` — live trace of agent steps; displayed in `AgentActivityCard` UI component.

## Current Status
- Single-step tool execution: IMPLEMENTED ✅
- Scheduler: IMPLEMENTED ✅
- GoalPlanner / multi-step: PARTIALLY IMPLEMENTED ⚠️ (depends on agentic mode + budget)
- OfflineCommandEngine: EXPERIMENTAL 🔄

## Known Issues
- `OfflineCommandEngine` appears to duplicate tool routing logic. Relationship to main pipeline unclear.
- Action authorization (`ActionAuthorizer`) based on last user utterance — heuristic, may block valid actions in some conversation flows.
