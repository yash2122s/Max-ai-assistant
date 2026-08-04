# MAX AI Project Memory & Agent Rules (PROJECT_MEMORY.md)

> **Purpose**
>
> This document is the permanent source of truth for the MAX AI project.
> Read this file before performing **any** development task.
>
> **DO NOT** rediscover the environment unless the user explicitly requests it or an environment-related error proves the information below is no longer valid.

---

# 1. Session Startup Rules

At the beginning of every session:

1. Read `PROJECT_MEMORY.md`.
2. Summarize your understanding in one sentence.
3. Do not perform environment discovery.
4. Wait for the user's task.
5. Modify only the requested files.

---

# 2. Completion Rules

After completing a task:

* Report modified files.
* Report build result.
* Report install result.
* Report verification result.
* Stop.

Do not continue searching for improvements unless requested.

---

# 3. Token Saving Rules

Minimize unnecessary token usage.

Avoid:

* repeated searches
* repeated file exploration
* repeated architecture summaries
* repeated environment checks
* repeated command discovery

Reuse verified information whenever possible.

Only inspect files directly related to the user's request.

---

# 4. Project Overview

* **Project:** MAX AI Assistant
* **Host OS:** Windows 11 x64
* **Workspace:** `C:\Users\yaswa\Downloads\gemini-live`
* **Package:** `com.example`
* **Primary AI:** Gemini Live
* **Languages:** Telugu, English, Tenglish

---

# 5. Verified Development Environment

## Java

**Primary JDK**

```
C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot
```

Always use:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
```

Do **NOT** use Oracle Java 8.

---

## Android SDK

```
C:\Users\yaswa\AppData\Local\Android\Sdk
```

ADB

```
C:\Users\yaswa\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

Platform Tools are already installed.

---

# 6. Target Device

| Property     | Value         |
| ------------ | ------------- |
| Device       | Redmi K20 Pro |
| Device ID    | 3bc00016      |
| Android      | Android 16    |
| API          | 36            |
| Architecture | arm64-v8a     |
| Root         | Enabled       |
| Shizuku      | Running       |
| ADB          | Connected     |

Assume this device is available unless the build/install explicitly reports otherwise.

---

# 7. Build Commands

## Fast Build

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
.\gradlew.bat assembleDebug
```

---

## Clean Build

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
.\gradlew.bat clean assembleDebug
```

---

## Install

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"
.\gradlew.bat installDebug
```

---

## Manual Install

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Launch

```powershell
adb shell am start -n com.example/.MainActivity
```

---

## Logcat

Filter only relevant tags.

```
ScreenCaptureProvider
GeminiVisionReasoner
GeminiWebSocket
AssistantEngine
ExecutionEngine
Accessibility
CameraTool
ToolDispatcher
```

Never inspect the full logcat unless explicitly requested.

---

# 8. Standard Development Workflow

Follow this workflow every time.

```
Read PROJECT_MEMORY.md

↓

Understand request

↓

Modify only relevant files

↓

Build

↓

Install

↓

Launch

↓

Verify

↓

Return result
```

Never insert additional discovery steps.

---

# 9. Decision Rules

Before starting any task:

1. Read this document.
2. Assume the environment is already configured.
3. Modify only requested functionality.
4. Build immediately.
5. Install immediately after successful build.
6. Explain changes briefly.
7. Stop after successful verification.

Never perform unnecessary analysis.

---

# 10. Build Failure Rules

If Gradle fails:

1. Read only the **first compilation error**.
2. Fix only the root cause.
3. Do not perform speculative fixes.
4. Build again.
5. Repeat until successful.

Never rewrite unrelated code.

Never redesign architecture because of one compiler error.

---

# 11. Runtime Debug Rules

If runtime fails:

1. Launch app.
2. Read filtered logcat.
3. Identify root cause.
4. Fix only that issue.
5. Build again.
6. Reinstall.
7. Verify.

Do not inspect unrelated classes.

---

# 12. Search Policy

Search project files **ONLY IF**:

* Class cannot be located.
* Symbol is unresolved.
* Build error references an unknown file.
* User explicitly requests architecture exploration.

Never search for:

* Java
* JAVA_HOME
* Android SDK
* Gradle
* adb.exe
* Connected devices
* Platform Tools
* Android Studio location

These are already known.

---

# 13. Code Modification Policy

Only modify files directly related to the requested feature.

Never:

* Rename packages.
* Rename interfaces.
* Move files.
* Refactor unrelated modules.
* Rewrite architecture.
* Change APIs without request.

Minimal changes are preferred.

---

# 14. Build Policy

Small bug fix

↓

assembleDebug

↓

Verify

---

Feature complete

↓

installDebug

↓

Launch

↓

Verify

Do not reinstall after every tiny edit unless required.

---

# 15. Vision Policy

Screen capture should happen ONLY when required.

Capture screen when:

* User asks "What's on my screen?"
* OCR
* UI understanding
* Vision navigation
* Verification after automation

Do NOT capture screen for:

* General chat
* Coding questions
* Weather
* Translation
* Math
* Normal conversation

---

# 16. Screen Capture Strategy

Preferred order:

## Tier 1

Shizuku

```
exec-out screencap -p
```

Memory only.

No files.

No notifications.

No gallery entry.

---

## Tier 2

Accessibility

```
takeScreenshot()
```

Memory only.

---

Never save screenshots to storage.

Always use RAM streams.

---

# 17. Voice Pipeline

```
Microphone

↓

AudioRecorder

↓

AssistantEngine

↓

ConnectionManager

↓

GeminiWebSocket

↓

Gemini Live

↓

AudioPlayer
```

---

# 18. Vision Pipeline

```
ScreenCaptureProvider

↓

VisionContext

↓

VisionReasoner

↓

GeminiVisionReasoner

↓

GeminiWebSocketClient

↓

Gemini Live
```

---

# 19. Automation Pipeline

```
IntentClassifier

↓

AgentOrchestrator

↓

ExecutionEngine

↓

ActionDispatcher

↓

Accessibility

↓

Verification
```

---

# 20. Tool Calling Policy

Always use deterministic execution.

Prefer:

```
Intent

↓

Tool

↓

Gemini
```

Instead of relying on Gemini to decide whether a tool should be executed.

---

# 21. Architecture Lock

Current architecture is considered stable.

Do not:

* Redesign
* Reorganize packages
* Replace interfaces
* Introduce new abstractions

Unless explicitly requested.

---

# 22. Performance Targets

| Component          | Target  |
| ------------------ | ------- |
| Voice Response     | <300 ms |
| Screen Capture     | <50 ms  |
| Accessibility      | <20 ms  |
| Vision Cache Hit   | 0 ms    |
| Navigation Success | >95%    |

---

# 23. Agent Behaviour Rules

Whenever a task is requested:

Think once.

Modify once.

Build once.

Install once.

Verify once.

Explain briefly.

Avoid unnecessary reasoning.

Avoid repeated searches.

Avoid rediscovering environment.

Focus on implementation.

---

# 24. Things That Must NEVER Be Rediscovered

Never search again for:

* Java installation
* JAVA_HOME
* Android SDK
* adb.exe
* Device information
* Root status
* Shizuku status
* Gradle wrapper
* Android Studio
* Platform Tools

Assume they remain valid.

---

# 25. Known Working State

Last verified status:

* Environment configured ✅
* Java verified ✅
* Android SDK verified ✅
* ADB verified ✅
* Device connected ✅
* Root verified ✅
* Shizuku running ✅
* Gradle builds successfully ✅
* APK installs successfully ✅
* Gemini Live connected ✅
* Voice pipeline working ✅
* WebSocket working ✅
* Tool calling working ✅
* Vision pipeline implemented ✅
* Silent RAM screen capture implemented ✅

---

# 27. Verified Voice Pipeline Fixes

* **Unified Audio Playback:** Single `AudioPlaybackManager` singleton manages `AudioTrack` across `ChatViewModel` and `AudioPlayer`. Eliminates multiple voice overlap.
* **Audio Stutter / Breaking Fix:** `AudioTrack.write()` uses `WRITE_BLOCKING` to ensure PCM audio stream chunks are fully queued without byte truncation.
* **Barge-in / Echo Prevention:** `stopAudioResponse()` flushes audio playback immediately when user speaks or mic opens, eliminating mic feedback loops.
* **WebSocket 1008 Goaway Fix:** Acknowledges server closing frames (`webSocket.close()`) and auto-reconnects on code 1008 signals.
* **Local Room Vector Search (Option A):**
  - Room DB migration 11 -> 12 with `embedding BLOB` column and `FloatArray` `TypeConverter`.
  - `GeminiEmbeddingClient` fetches 768-dim vectors using `text-embedding-004` API.
  - Hybrid scoring (`0.7 * CosineSim + 0.3 * KeywordScore`) with graceful keyword fallback when embedding is null.
  - Rate-limited backfill (10 items/batch, 500ms delay) for pre-existing memories.
  - Room DB persistence for chat history (`ChatMessageDao`).
* **Voice Memory Auto-Save Feature (`save_memory`):**
  - Added `SaveMemoryTool` registered in `ToolRegistry` and `GeminiWebSocketClient` setup schema.
  - User can say "Remember that I like black coffee" or "Note down X", and Gemini automatically calls `save_memory` to store a vector-embedded `PermanentMemory` in Room DB.
* **Low-Latency Voice Stream Optimizations:**
  - Added `pingInterval(10, TimeUnit.SECONDS)` in `OkHttpClient` WebSocket builder for active TCP socket keep-alive.
* **UI/UX Glassmorphism & Visualizer Upgrade:**
  - Enhanced `UserMessageCard` & `GeminiMessageCard` in [MainActivity.kt](file:///c:/Users/yaswa/Downloads/gemini-live/app/src/main/java/com/example/MainActivity.kt) with dark glassmorphic cards and multi-color gradient border highlights (`NeonBlue` -> `NeonPink` & `Emerald400`).
  - Upgraded `SoundWave.kt` to a 6-bar dynamic gradient frequency visualizer.
* **Java / Bittu Period Tracking History Saved:**
  - Added [PeriodDataSeeder.kt](file:///c:/Users/yaswa/Downloads/gemini-live/app/src/main/java/com/example/data/local/PeriodDataSeeder.kt) auto-seeding 2025 & 2026 cycle dates into `period_logs` database and `PermanentMemory` Room table with vector embeddings. Cycles saved:
    - 2025: May 28, July 9 (Bittu's period), August 8, September 10, October 16
    - 2026: January 2, February 7, March 10, June 7-8 (June 7 night / June 8 early)
* **Reminders Tab & Reminders Management Screen Added:**
  - Added [RemindersScreen.kt](file:///c:/Users/yaswa/Downloads/gemini-live/app/src/main/java/com/example/ui/screens/RemindersScreen.kt) with filtering ("All", "Pending", "Completed"), Date & Time picker dialog, mark as completed toggle, and delete reminder features.
  - Added `Reminders` navigation item to [DrawerContent.kt](file:///c:/Users/yaswa/Downloads/gemini-live/app/src/main/java/com/example/ui/screens/DrawerContent.kt) and `reminders` composable route in [MainActivity.kt](file:///c:/Users/yaswa/Downloads/gemini-live/app/src/main/java/com/example/MainActivity.kt). Integrated with `AlarmManager` & system `ReminderReceiver`.
* **Voice Reminder Tool Fixed (`set_reminder` / `create_reminder`):**
  - Created [ReminderTool.kt](file:///c:/Users/yaswa/Downloads/gemini-live/app/src/main/java/com/example/automation/tools/ReminderTool.kt) and registered in `ToolRegistry`, `ToolDispatcher`, and `GeminiWebSocketClient` setup function declarations schema (`set_reminder`).
  - When user asks Gemini via voice *"Remind me to X in Y minutes"* or *"Set a reminder for X"*, Gemini Live now directly invokes `set_reminder` to save to Room DB and schedule system alarms.

---

### 28. Next Steps & Pending Improvements

---

# 26. Golden Rule

Before every task:

1. Read this document.
2. Assume the environment is correct.
3. Never rediscover verified information.
4. Focus only on the requested implementation.
5. Build.
6. Install.
7. Verify.
8. Return the result.

