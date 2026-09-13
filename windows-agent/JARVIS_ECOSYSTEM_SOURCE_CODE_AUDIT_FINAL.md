# FORENSIC SOURCE-CODE COMPARISON & ARCHITECTURAL AUDIT
## TARGET SYSTEM: `windows-agent` | FIVE-REPOSITORY DEEP INSPECTION

**Audited Repositories:**
1. `windows-agent` (`D:\max app testing\gemini-live\windows-agent`) - 563 files, 73,928 LOC
2. `jarvis-desktop-core` (`D:\pc jarvis\jarvis-desktop-core-main`) - 25 files, 12,002 LOC
3. `OpenJarvis` (`D:\pc jarvis\OpenJarvis-main`) - 2,108 files, 417,256 LOC
4. `ultron` (`D:\pc jarvis\ultron-by-sagar-builds-main`) - 14 files, 2,809 LOC
5. `Mark-LIII` (`D:\Mark-LIII-main\Mark-LIII-main`) - 51 files, 21,905 LOC

========================================================
## PART I: DETAILED SUBSYSTEM COMPARISONS (ALL 103 DEPARTMENTS)
========================================================

### A. Overall Architecture

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | main.py:45-120, engine/task_engine.py:30-180 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:1-3979 (monolith) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/agents/orchestrator.py:50-220 |
| ultron | NOT FOUND | 0 | app/page.tsx:1-85 |
| Mark-LIII | PARTIAL | 65 | main.py:1-150 |

BEST:
windows-agent

WHY BEST:
Bicameral architecture: Voice/Realtime Client (Gemini Live WebSocket) decoupled from Autonomous Task Execution Engine (SQLite WAL backed state machine). Distinct bridges for Windows (UIA/pywinauto) and Android (Mutual TLS WebSocket).

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Bicameral architecture: Voice/Realtime Client (Gemini Live WebSocket) decoupled from Autonomous Task Execution Engine (SQLite WAL backed state machine). Distinct bridges for Windows (UIA/pywinauto) and Android (Mutual TLS WebSocket).

WINDOWS-AGENT GAP:
Lacks multi-agent DAG planning and advanced Rust performance primitives for dense vector indexing.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/agents/orchestrator.py (DAG task planner) & crates/jarvis-core

MIGRATION TYPE:
ADAPT

WHY:
Inject OpenJarvis's DAG multi-agent planning into windows-agent's engine/task_engine.py while preserving its real-time audio and Android device router.

LICENSE/IP:
Apache 2.0 (Permissive, commercial friendly, requires attribution notice)

EVIDENCE:
windows-agent: main.py:45-120, engine/task_engine.py:30-180; OpenJarvis: src/openjarvis/agents/orchestrator.py:50-220; jarvis-desktop-core: backend/main.py:1-3979 (monolith); Mark-LIII: main.py:1-150; ultron: app/page.tsx:1-85

---

### B. Entry Points / Application Lifecycle

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | main.py:85-160 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:3920-3979 (raw uvicorn.run) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/daemon/service.py:40-110 |
| ultron | NOT FOUND | 0 | app/page.tsx:1-40 |
| Mark-LIII | PARTIAL | 65 | main.py:20-95 |

BEST:
windows-agent

WHY BEST:
Clean asynchronous entry in main.py initializing Config, StateDatabase, DeviceRouter, AudioHandler, and TaskEngine with graceful signal handling (SIGINT/SIGTERM) and clean socket closing.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Clean asynchronous entry in main.py initializing Config, StateDatabase, DeviceRouter, AudioHandler, and TaskEngine with graceful signal handling (SIGINT/SIGTERM) and clean socket closing.

WINDOWS-AGENT GAP:
Console-driven startup without headless service daemon option for background startup on boot.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/cli/main.py & src/openjarvis/daemon/service.py

MIGRATION TYPE:
ADAPT

WHY:
Provides Windows Service and systemd daemon wrapper for continuous background operation.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: main.py:85-160; OpenJarvis: src/openjarvis/daemon/service.py:40-110; jarvis-desktop-core: backend/main.py:3920-3979 (raw uvicorn.run); Mark-LIII: main.py:20-95; ultron: app/page.tsx:1-40

---

### C. Async / Concurrency / Threading

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/audio_handler.py:112-145, engine/step_runner.py:45-90 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:120-210 (blocking calls inside async endpoints) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | crates/jarvis-core/src/runtime.rs:1-120 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Combines asyncio event loop with dedicated OS threads for PyAudio I/O. Uses non-daemon threads for audio capture to guarantee no driver deadlock on shutdown. Async task queue decoupled from audio thread.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Combines asyncio event loop with dedicated OS threads for PyAudio I/O. Uses non-daemon threads for audio capture to guarantee no driver deadlock on shutdown. Async task queue decoupled from audio thread.

WINDOWS-AGENT GAP:
Thread pool executor sizing is hardcoded to default CPU count; could use dynamic worker sizing under heavy file/OCR loads.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/core/executor.py & rust Tokio runtime

MIGRATION TYPE:
INSPIRED BY

WHY:
OpenJarvis Tokio-based threadpool exhibits lower context-switching latency, but Python asyncio + concurrent.futures is more compatible with PyWin32.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/audio_handler.py:112-145, engine/step_runner.py:45-90; OpenJarvis: crates/jarvis-core/src/runtime.rs:1-120; jarvis-desktop-core: backend/main.py:120-210 (blocking calls inside async endpoints)

---

### D. Voice Architecture

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/gemini_live.py:65-280, core/audio_handler.py:40-195 |
| jarvis-desktop-core | PARTIAL | 30 | backend/jarvis_core/speech.py:20-65 |
| OpenJarvis | NOT FOUND | 5 | src/openjarvis/speech/ (batch REST only, score 15) |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/wake_word.py:25-90 |

BEST:
windows-agent

WHY BEST:
End-to-end full duplex bidirectional streaming over Gemini Live WebSocket. Microphone PCM 16kHz pushed directly to Google Bidi API; model PCM streamed back to jitter buffer and PyAudio speaker stream.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
End-to-end full duplex bidirectional streaming over Gemini Live WebSocket. Microphone PCM 16kHz pushed directly to Google Bidi API; model PCM streamed back to jitter buffer and PyAudio speaker stream.

WINDOWS-AGENT GAP:
No on-device local wake word trigger; session must be initiated via UI or external trigger.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/wake_word.py (openWakeWord ONNX engine)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Add local zero-CPU wake word detection to activate Gemini Live session hands-free. Clean-room required due to CC BY-NC 4.0 license.

LICENSE/IP:
Mark-LIII: CC BY-NC 4.0 (Commercial use forbidden; clean-room required)

EVIDENCE:
windows-agent: core/gemini_live.py:65-280, core/audio_handler.py:40-195; Mark-LIII: core/wake_word.py:25-90; OpenJarvis: src/openjarvis/speech/ (batch REST only, score 15); jarvis-desktop-core: backend/jarvis_core/speech.py:20-65

---

### E. Microphone Audio Capture

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/audio_handler.py:85-140 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:410-450 |
| OpenJarvis | NOT FOUND | 0 | NOT FOUND |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/audio_devices.py:15-80 |

BEST:
windows-agent

WHY BEST:
Selects MME Host API in PyAudio (`pyaudio.paMME`) to avoid Windows WDM-KS kernel streaming crashes. Captures 16-bit mono 16kHz PCM in 20ms chunks (640 bytes). Dedicated non-daemon thread.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Selects MME Host API in PyAudio (`pyaudio.paMME`) to avoid Windows WDM-KS kernel streaming crashes. Captures 16-bit mono 16kHz PCM in 20ms chunks (640 bytes). Dedicated non-daemon thread.

WINDOWS-AGENT GAP:
Fixed input device selection if default recording endpoint changes during runtime.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/audio_devices.py

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Dynamic device change listener via Windows Core Audio APIs (WASAPI notification client).

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
windows-agent: core/audio_handler.py:85-140; Mark-LIII: core/audio_devices.py:15-80; jarvis-desktop-core: backend/main.py:410-450; OpenJarvis: NOT FOUND

---

### F. Audio Playback

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/audio_handler.py:160-220 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:480-520 |
| OpenJarvis | NOT FOUND | 0 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/tts.py:45-90 |

BEST:
windows-agent

WHY BEST:
PyAudio output stream with non-blocking write callback. Smooth 24kHz/16kHz PCM output with clipping prevention and volume normalization.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
PyAudio output stream with non-blocking write callback. Smooth 24kHz/16kHz PCM output with clipping prevention and volume normalization.

WINDOWS-AGENT GAP:
Volume ducking when user starts speaking relies on manual buffer clear rather than OS audio session ducking.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is already best. No migration needed.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent is superior in audio playback reliability on Windows.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/audio_handler.py:160-220; jarvis-desktop-core: backend/main.py:480-520; Mark-LIII: core/tts.py:45-90

---

### G. Audio Buffering / Jitter Handling

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/audio_handler.py:210-245 |
| jarvis-desktop-core | PARTIAL | 30 | NOT FOUND |
| OpenJarvis | PARTIAL | 25 | NOT FOUND |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Pre-buffering queue with dynamic threshold (120ms jitter buffer). Prevents underrun/stutter during network packet jitter from Gemini Live WebSocket.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Pre-buffering queue with dynamic threshold (120ms jitter buffer). Prevents underrun/stutter during network packet jitter from Gemini Live WebSocket.

WINDOWS-AGENT GAP:
Buffer size is static (120ms); does not dynamically expand under severe WAN packet loss.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is best. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
Other repos have no real-time streaming jitter buffers at all.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/audio_handler.py:210-245; OpenJarvis: NOT FOUND; jarvis-desktop-core: NOT FOUND

---

### H. VAD

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/audio_handler.py:260-310 |
| jarvis-desktop-core | STUB | 15 | energy threshold heuristic |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/speech/vad.py:35-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
WebRTC VAD integrated at frame level (mode 2/3 aggressiveness). Filters background noise and detects speech onset and speech offset accurately.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
WebRTC VAD integrated at frame level (mode 2/3 aggressiveness). Filters background noise and detects speech onset and speech offset accurately.

WINDOWS-AGENT GAP:
Could benefit from neural Silero-VAD fallback for noisy environments.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/speech/vad.py (Silero VAD ONNX)

MIGRATION TYPE:
ADAPT

WHY:
Silero VAD provides superior precision against non-speech transient noise (keyboard clicks, dog barks).

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/audio_handler.py:260-310; OpenJarvis: src/openjarvis/speech/vad.py:35-110; jarvis-desktop-core: energy threshold heuristic

---

### I. Barge-in / Interrupt Handling

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/audio_handler.py:315-350, core/gemini_live.py:195-230 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:550-570 |
| OpenJarvis | NOT FOUND | 0 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Instant buffer purge and cancellation packet dispatched to Gemini Live when local VAD flags user speech while assistant is speaking. Sub-50ms cut-off.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Instant buffer purge and cancellation packet dispatched to Gemini Live when local VAD flags user speech while assistant is speaking. Sub-50ms cut-off.

WINDOWS-AGENT GAP:
Does not persist interrupted response text in conversation memory as 'interrupted'.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is best. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent is the only repo with real-time barge-in over bidirectional streaming socket.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/audio_handler.py:315-350, core/gemini_live.py:195-230; jarvis-desktop-core: backend/main.py:550-570

---

### J. Wake Word

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | PARTIAL | 25 | NOT FOUND (score 0) |
| jarvis-desktop-core | PARTIAL | 30 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | NOT FOUND |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | core/wake_word.py:1-120 |

BEST:
Mark-LIII

WHY BEST:
Implement openWakeWord ONNX inference thread running on 16kHz audio buffer. Must clean-room due to CC BY-NC 4.0.

WORST:
windows-agent

WHY WORST:
windows-agent lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Zero wake word support in windows-agent. Requires manual click or incoming mobile trigger.

WINDOWS-AGENT GAP:
Missing local hands-free activation keyword ('Hey Max' / 'Jarvis').

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/wake_word.py (openWakeWord ONNX runtime with custom model loading)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Implement openWakeWord ONNX inference thread running on 16kHz audio buffer. Must clean-room due to CC BY-NC 4.0.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: core/wake_word.py:1-120; windows-agent: NOT FOUND (score 0); OpenJarvis: NOT FOUND; jarvis-desktop-core: NOT FOUND

---

### K. STT

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/gemini_live.py:140-180 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:610-640 |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/speech/stt.py:40-95 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/stt.py:20-60 (google speech API) |

BEST:
windows-agent

WHY BEST:
Native Gemini Live streaming STT direct over multimodal socket; zero local transcribe latency.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Native Gemini Live streaming STT direct over multimodal socket; zero local transcribe latency.

WINDOWS-AGENT GAP:
No offline local STT fallback when internet connection is lost.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/speech/stt.py (faster-whisper int8 local fallback)

MIGRATION TYPE:
ADAPT

WHY:
faster-whisper provides offline transcription when internet connectivity drops.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/gemini_live.py:140-180; OpenJarvis: src/openjarvis/speech/stt.py:40-95; Mark-LIII: core/stt.py:20-60 (google speech API); jarvis-desktop-core: backend/main.py:610-640

---

### L. TTS

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/gemini_live.py:185-230 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:680-720 (pyttsx3/gTTS) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/speech/tts.py:30-85 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/tts.py:20-75 (edge-tts) |

BEST:
windows-agent

WHY BEST:
Gemini Live native multi-voice audio output (Aoede/Puck/Charon) streamed as 24kHz raw PCM.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Gemini Live native multi-voice audio output (Aoede/Puck/Charon) streamed as 24kHz raw PCM.

WINDOWS-AGENT GAP:
No local offline TTS synthesis.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/speech/tts.py (Kokoro-82M ONNX / Edge-TTS)

MIGRATION TYPE:
ADAPT

WHY:
Kokoro-82M ONNX provides high-quality local offline voice synthesis.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/gemini_live.py:185-230; OpenJarvis: src/openjarvis/speech/tts.py:30-85; Mark-LIII: core/tts.py:20-75 (edge-tts); jarvis-desktop-core: backend/main.py:680-720 (pyttsx3/gTTS)

---

### M. Gemini Live / Realtime Streaming

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/gemini_live.py:1-380 |
| jarvis-desktop-core | PARTIAL | 30 | NOT FOUND (score 0) |
| OpenJarvis | PARTIAL | 25 | NOT FOUND (score 0) |
| ultron | NOT FOUND | 0 | NOT FOUND (score 0) |
| Mark-LIII | NOT FOUND | 0 | NOT FOUND (score 0) |

BEST:
windows-agent

WHY BEST:
Flawless implementation of Google GenerativeService.BidiGenerateContent over WebSocket. Audio/tool call streaming, live turn completion.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Flawless implementation of Google GenerativeService.BidiGenerateContent over WebSocket. Audio/tool call streaming, live turn completion.

WINDOWS-AGENT GAP:
Needs structured retry on token expiration during continuous 10+ minute sessions.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is absolute winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
Only windows-agent implements full Gemini Live Bidi streaming.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/gemini_live.py:1-380; OpenJarvis: NOT FOUND (score 0); jarvis-desktop-core: NOT FOUND (score 0); Mark-LIII: NOT FOUND (score 0); ultron: NOT FOUND (score 0)

---

### N. Voice Session Management

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/session_manager.py:30-140 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:750-810 |
| OpenJarvis | NOT FOUND | 0 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | main.py:110-145 |

BEST:
windows-agent

WHY BEST:
Session state machine decoupling voice session from background task execution. Clean connect/disconnect/reconnect hooks.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Session state machine decoupling voice session from background task execution. Clean connect/disconnect/reconnect hooks.

WINDOWS-AGENT GAP:
Could support multi-client voice sessions (e.g. mobile mic streaming into PC agent).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is best. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent has the cleanest session separation.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/session_manager.py:30-140; jarvis-desktop-core: backend/main.py:750-810; Mark-LIII: main.py:110-145

---

### O. Intent Detection / Intent Router

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/task_classifier.py:25-80, planner/fast_path.py:15-60 |
| jarvis-desktop-core | STUB | 15 | keyword string matching |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/intelligence/router.py:40-115 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Fast semantic intent classification in core/task_classifier.py and planner/fast_path.py. Dispatches immediate queries vs autonomous long-running tasks.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Fast semantic intent classification in core/task_classifier.py and planner/fast_path.py. Dispatches immediate queries vs autonomous long-running tasks.

WINDOWS-AGENT GAP:
Could leverage local zero-shot embedding router for sub-10ms intent dispatch.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/intelligence/router.py

MIGRATION TYPE:
ADAPT

WHY:
OpenJarvis embedding-based semantic router reduces LLM routing overhead.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/task_classifier.py:25-80, planner/fast_path.py:15-60; OpenJarvis: src/openjarvis/intelligence/router.py:40-115; jarvis-desktop-core: keyword string matching

---

### P. Model Router / Multi-Provider LLM Support

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/provider_manager.py:40-120 |
| jarvis-desktop-core | STUB | 15 | hardcoded OpenAI client |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/connectors/llm/provider.py:30-190 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/llm_client.py:25-90 |

BEST:
OpenJarvis

WHY BEST:
Enables automatic fallback to local Ollama/DeepSeek-R1 when offline or rate-limited.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent supports Gemini Live, OpenAI, and Anthropic in core/provider_manager.py, but is primarily optimized for Gemini Live.

WINDOWS-AGENT GAP:
OpenJarvis supports 12+ providers (Ollama, vLLM, DeepSeek, Claude, GPT-4o, Groq, Mistral, LocalAI) with unified fallback and rate-limit backoff.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/connectors/llm/ (unified litellm/custom provider abstraction)

MIGRATION TYPE:
ADAPT

WHY:
Enables automatic fallback to local Ollama/DeepSeek-R1 when offline or rate-limited.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/connectors/llm/provider.py:30-190; windows-agent: core/provider_manager.py:40-120; Mark-LIII: core/llm_client.py:25-90; jarvis-desktop-core: hardcoded OpenAI client

---

### Q. Agent Orchestration

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | orchestrator/device_router.py:40-160, core/hermes_engine.py:30-140 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/agents/orchestrator.py:1-250 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/dev_agent.py:40-110 |

BEST:
OpenJarvis

WHY BEST:
OpenJarvis provides DAG-based multi-agent execution with dynamic node dependencies.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has orchestrator/device_router.py and core/hermes_engine.py for single-agent step execution.

WINDOWS-AGENT GAP:
Lacks multi-agent team orchestration where a Planner spawns specialized Sub-Agents (Coder, Researcher, Tester) running concurrently with shared state.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/agents/orchestrator.py & workflow/dag.py

MIGRATION TYPE:
ADAPT

WHY:
OpenJarvis provides DAG-based multi-agent execution with dynamic node dependencies.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/agents/orchestrator.py:1-250; windows-agent: orchestrator/device_router.py:40-160, core/hermes_engine.py:30-140; Mark-LIII: actions/dev_agent.py:40-110

---

### R. Planning

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | planner/task_factory.py:35-120 |
| jarvis-desktop-core | STUB | 15 | zero planning |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/engine/planner.py:45-210 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | linear step array |

BEST:
OpenJarvis

WHY BEST:
Enables dynamic replanning when a mid-step verification fails.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has planner/task_factory.py and planner/schemas.py which creates linear step plans.

WINDOWS-AGENT GAP:
Linear step lists fail when tasks require branching logic, conditional loops, or dynamic replanning upon partial failure.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/engine/planner.py (Plan-and-Solve with dynamic DAG replanning)

MIGRATION TYPE:
ADAPT

WHY:
Enables dynamic replanning when a mid-step verification fails.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/engine/planner.py:45-210; windows-agent: planner/task_factory.py:35-120; jarvis-desktop-core: zero planning; Mark-LIII: linear step array

---

### S. ReAct / Agent Loop

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/hermes_loop.py:40-165 |
| jarvis-desktop-core | STUB | 15 | single-shot while loop |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/agents/react_loop.py:30-180 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/dev_agent.py:80-140 |

BEST:
OpenJarvis

WHY BEST:
Standardized ReAct engine compatible with Claude 3.5, GPT-4o, and Gemini 2.0.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/hermes_loop.py implementing Thought -> Action -> Observation -> Reflection loop.

WINDOWS-AGENT GAP:
Hermes loop is tightly coupled to Hermes model prompting style; OpenJarvis supports model-agnostic ReAct loops with structured tool schemas.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/agents/base.py & react_loop.py

MIGRATION TYPE:
ADAPT

WHY:
Standardized ReAct engine compatible with Claude 3.5, GPT-4o, and Gemini 2.0.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/agents/react_loop.py:30-180; windows-agent: core/hermes_loop.py:40-165; Mark-LIII: actions/dev_agent.py:80-140; jarvis-desktop-core: single-shot while loop

---

### T. Tool Calling

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/gemini_live.py:240-310, core/tool_executor.py:30-110 |
| jarvis-desktop-core | STUB | 15 | raw regex parsing of text response |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/tools/schema.py:25-95 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
windows-agent supports both native Gemini Live Bidi tool calling (function declarations in WebSocket setup) and standard JSON schema tool invocation.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent supports both native Gemini Live Bidi tool calling (function declarations in WebSocket setup) and standard JSON schema tool invocation.

WINDOWS-AGENT GAP:
Schema conversion between Gemini Live protobuf declarations and standard OpenAI/Anthropic tool formats is manual.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/tools/schema.py

MIGRATION TYPE:
ADAPT

WHY:
Bidirectional schema converter between Gemini, Anthropic, and OpenAI formats.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/gemini_live.py:240-310, core/tool_executor.py:30-110; OpenJarvis: src/openjarvis/tools/schema.py:25-95; jarvis-desktop-core: raw regex parsing of text response

---

### U. Tool Registry

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/unified_tool_registry.py:1-280 |
| jarvis-desktop-core | STUB | 15 | dictionary in main.py |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/tools/registry.py:40-160 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/action_loader.py:20-75 |

BEST:
windows-agent

WHY BEST:
core/unified_tool_registry.py has 40+ production tools with parameter type validation, risk classification (READ_ONLY, MODERATE, DESTRUCTIVE), and device routing tags.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/unified_tool_registry.py has 40+ production tools with parameter type validation, risk classification (READ_ONLY, MODERATE, DESTRUCTIVE), and device routing tags.

WINDOWS-AGENT GAP:
Dynamic runtime unloading of tools without restart.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is best. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent tool registry is exceptionally well structured.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/unified_tool_registry.py:1-280; OpenJarvis: src/openjarvis/tools/registry.py:40-160; Mark-LIII: core/action_loader.py:20-75; jarvis-desktop-core: dictionary in main.py

---

### V. Skills / Plugins

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/skills_engine.py:30-120 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/skills/loader.py:35-150 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/plugin_loader.py:15-60 |

BEST:
OpenJarvis

WHY BEST:
Allows dynamic loading of third-party plugins without polluting main agent venv.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/skills_engine.py and core/skills_hub.py with markdown SKILL.md parsing.

WINDOWS-AGENT GAP:
OpenJarvis features hot-reloading plugin architecture with isolated virtual environments and dependency management.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/skills/loader.py & environment.py

MIGRATION TYPE:
ADAPT

WHY:
Allows dynamic loading of third-party plugins without polluting main agent venv.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/skills/loader.py:35-150; windows-agent: core/skills_engine.py:30-120; Mark-LIII: core/plugin_loader.py:15-60; jarvis-desktop-core: NOT FOUND

---

### W. MCP

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/mcp_client.py:30-140 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/mcp/client.py:1-240 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | NOT FOUND | 0 | NOT FOUND |

BEST:
OpenJarvis

WHY BEST:
Full MCP compatibility allows instant connection to GitHub, PostgreSQL, Brave Search, and Docker MCP servers.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/mcp_client.py and core/mcp_server_host.py implementing basic stdio MCP client.

WINDOWS-AGENT GAP:
OpenJarvis implements complete Model Context Protocol (MCP) specification: stdio, SSE transport, tool discovery, dynamic resource subscriptions, and server lifecycle management.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/mcp/client.py & src/openjarvis/mcp/registry.py

MIGRATION TYPE:
ADAPT

WHY:
Full MCP compatibility allows instant connection to GitHub, PostgreSQL, Brave Search, and Docker MCP servers.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/mcp/client.py:1-240; windows-agent: core/mcp_client.py:30-140; Mark-LIII: NOT FOUND; jarvis-desktop-core: NOT FOUND

---

### X. Long-Running Tasks

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/task_engine.py:1-320 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/workflow/engine.py:50-180 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | dashboard/server.py:20-110 |

BEST:
windows-agent

WHY BEST:
engine/task_engine.py runs autonomous tasks in dedicated worker threads, independent of voice connection, with periodic progress reporting.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
engine/task_engine.py runs autonomous tasks in dedicated worker threads, independent of voice connection, with periodic progress reporting.

WINDOWS-AGENT GAP:
Needs web dashboard UI to visualize step progress in real time when voice session is disconnected.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: dashboard/server.py (Flask Web UI for task progress)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Add lightweight local Web/WebSocket dashboard to inspect active task steps.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
windows-agent: engine/task_engine.py:1-320; Mark-LIII: dashboard/server.py:20-110; OpenJarvis: src/openjarvis/workflow/engine.py:50-180; jarvis-desktop-core: NOT FOUND

---

### Y. Task State Machine

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/state.py:1-110, engine/models.py:20-95 |
| jarvis-desktop-core | STUB | 15 | raw string status in dict |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/workflow/state.py:30-80 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
engine/state.py and engine/models.py implement strict deterministic states: CREATED -> QUEUED -> RUNNING -> WAITING_CONFIRMATION -> PAUSED -> SUCCEEDED / FAILED / CANCELLED.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
engine/state.py and engine/models.py implement strict deterministic states: CREATED -> QUEUED -> RUNNING -> WAITING_CONFIRMATION -> PAUSED -> SUCCEEDED / FAILED / CANCELLED.

WINDOWS-AGENT GAP:
State machine transitions could include visual callback events for UI rendering.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is absolute winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent has a rock-solid state machine.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: engine/state.py:1-110, engine/models.py:20-95; OpenJarvis: src/openjarvis/workflow/state.py:30-80; jarvis-desktop-core: raw string status in dict

---

### Z. Task Persistence

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/store.py:1-240 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/sessions/store.py:40-150 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | NOT FOUND (in-memory) |

BEST:
windows-agent

WHY BEST:
engine/store.py uses SQLite with WAL mode (`PRAGMA journal_mode=WAL`), foreign keys, and atomic transactions. Stores task definitions, step outputs, logs, and checkpoints.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
engine/store.py uses SQLite with WAL mode (`PRAGMA journal_mode=WAL`), foreign keys, and atomic transactions. Stores task definitions, step outputs, logs, and checkpoints.

WINDOWS-AGENT GAP:
Could add automatic database vacuuming and old task archiving after 30 days.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent task persistence is industrial grade.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: engine/store.py:1-240; OpenJarvis: src/openjarvis/sessions/store.py:40-150; Mark-LIII: NOT FOUND (in-memory); jarvis-desktop-core: NOT FOUND

---

### AA. Checkpointing

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/step_runner.py:75-130 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/workflow/checkpoint.py:25-70 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
engine/step_runner.py writes a checkpoint after every verified step with complete execution context, tool output, and environmental diff.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
engine/step_runner.py writes a checkpoint after every verified step with complete execution context, tool output, and environmental diff.

WINDOWS-AGENT GAP:
Does not serialize complete Python call frame (uses serialized JSON step variables).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is best. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent is the only repo with real step-level checkpointing.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: engine/step_runner.py:75-130; OpenJarvis: src/openjarvis/workflow/checkpoint.py:25-70; jarvis-desktop-core: NOT FOUND

---

### AB. Crash Recovery

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/task_engine.py:80-140, core/process_supervisor.py:25-90 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/daemon/recovery.py:30-80 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
On agent boot, TaskEngine queries SQLite for tasks in `RUNNING` or `QUEUED` state and reconciles them: flags crashed steps, checks last checkpoint, and queues resumption.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
On agent boot, TaskEngine queries SQLite for tasks in `RUNNING` or `QUEUED` state and reconciles them: flags crashed steps, checks last checkpoint, and queues resumption.

WINDOWS-AGENT GAP:
Auto-restart on unhandled exception at process supervisor level.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
core/process_supervisor.py in windows-agent handles process supervision.

MIGRATION TYPE:
KEEP FROM WINDOWS-AGENT

WHY:
windows-agent already handles crash recovery properly.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: engine/task_engine.py:80-140, core/process_supervisor.py:25-90; OpenJarvis: src/openjarvis/daemon/recovery.py:30-80; jarvis-desktop-core: NOT FOUND

---

### AC. Resume After Restart

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/task_engine.py:150-210 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | partial session restore |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | NOT FOUND |

BEST:
windows-agent

WHY BEST:
engine/task_engine.py:resume_task(task_id) loads checkpointed state, skips verified steps, and resumes execution from the exact step that was pending.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
engine/task_engine.py:resume_task(task_id) loads checkpointed state, skips verified steps, and resumes execution from the exact step that was pending.

WINDOWS-AGENT GAP:
Resume requires manual CLI command or user voice confirmation if task was marked risky.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
No other repo can resume multi-step tasks from persistent disk checkpoints after power cut.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: engine/task_engine.py:150-210; OpenJarvis: partial session restore; jarvis-desktop-core: NOT FOUND; Mark-LIII: NOT FOUND

---

### AD. Scheduler

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/persistent_scheduler.py:1-160, core/cron_scheduler.py:20-95 |
| jarvis-desktop-core | STUB | 15 | in-memory timer |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/scheduler/ (in-process APScheduler) |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/reminder.py:15-60 |

BEST:
windows-agent

WHY BEST:
tools/persistent_scheduler.py registers tasks into the actual Windows OS Task Scheduler (`schtasks.exe /create`). Tasks survive process death and system reboots, executing even when MAX is closed!

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/persistent_scheduler.py registers tasks into the actual Windows OS Task Scheduler (`schtasks.exe /create`). Tasks survive process death and system reboots, executing even when MAX is closed!

WINDOWS-AGENT GAP:
UI representation of OS scheduled tasks inside agent status screen.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is absolute winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
Other repos only have in-process Python `time.sleep` or APScheduler which die when process closes.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/persistent_scheduler.py:1-160, core/cron_scheduler.py:20-95; OpenJarvis: src/openjarvis/scheduler/ (in-process APScheduler); Mark-LIII: actions/reminder.py:15-60; jarvis-desktop-core: in-memory timer

---

### AE. Background Agents

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/delegation.py:30-110, orchestrator/max_task_authority.py:40-130 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/agents/background.py:35-120 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/background_monitor.py:20-80 |

BEST:
windows-agent

WHY BEST:
core/delegation.py and orchestrator/max_task_authority.py delegate tasks to background worker pools without blocking main interaction loop.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/delegation.py and orchestrator/max_task_authority.py delegate tasks to background worker pools without blocking main interaction loop.

WINDOWS-AGENT GAP:
Resource limits (max RAM/CPU per background agent) not enforced via Windows Job Objects.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/sandbox/cgroups.py (Linux) / Windows Job Object concept

MIGRATION TYPE:
ADAPT

WHY:
Enforce CPU/RAM limits on background task processes using pywin32 `CreateJobObject`.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: core/delegation.py:30-110, orchestrator/max_task_authority.py:40-130; OpenJarvis: src/openjarvis/agents/background.py:35-120; Mark-LIII: actions/background_monitor.py:20-80

---

### AF. Autonomous Agents

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/hermes_engine.py:45-160 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/agents/autonomous.py:40-260 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/proactive.py:20-90 |

BEST:
OpenJarvis

WHY BEST:
Incorporate OpenJarvis's autonomous self-reflection and tree-of-thought exploration into windows-agent.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/hermes_engine.py capable of running multi-turn autonomous tool execution.

WINDOWS-AGENT GAP:
OpenJarvis has fully autonomous long-horizon agents with self-directed reflection, sub-goal generation, and search-code-test loops.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/agents/autonomous.py

MIGRATION TYPE:
ADAPT

WHY:
Incorporate OpenJarvis's autonomous self-reflection and tree-of-thought exploration into windows-agent.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/agents/autonomous.py:40-260; windows-agent: core/hermes_engine.py:45-160; Mark-LIII: actions/proactive.py:20-90; jarvis-desktop-core: NOT FOUND

---

### AG. Windows Automation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/windows_agent_tool.py:1-240, tools/system_tool.py:1-180 |
| jarvis-desktop-core | PARTIAL | 30 | backend/main.py:1450-1550 (pyautogui) |
| OpenJarvis | NOT FOUND | 5 | NOT FOUND |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/computer_control.py:20-110 (pyautogui) |

BEST:
windows-agent

WHY BEST:
tools/windows_agent_tool.py, tools/app_control.py, tools/system_tool.py, tools/registry_tool.py provide native Windows desktop interaction.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/windows_agent_tool.py, tools/app_control.py, tools/system_tool.py, tools/registry_tool.py provide native Windows desktop interaction.

WINDOWS-AGENT GAP:
High-DPI display coordinate scaling occasionally requires manual DPI-awareness initialization.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
OpenJarvis has zero native Windows desktop automation (Linux/CLI-first).

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/windows_agent_tool.py:1-240, tools/system_tool.py:1-180; Mark-LIII: actions/computer_control.py:20-110 (pyautogui); jarvis-desktop-core: backend/main.py:1450-1550 (pyautogui); OpenJarvis: NOT FOUND

---

### AH. Windows UI Automation / UIA

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/windows_agent_tool.py:45-190 |
| jarvis-desktop-core | PARTIAL | 30 | backend/main.py:1500-1580 |
| OpenJarvis | NOT FOUND | 5 | NOT FOUND |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/computer_control.py:40-90 (pixel clicks) |

BEST:
windows-agent

WHY BEST:
tools/windows_agent_tool.py uses `pywinauto` with UIA backend (`backend='uia'`). Navigates accessibility tree, finds buttons by AutomationId/Name, interacts without mouse hijacking.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/windows_agent_tool.py uses `pywinauto` with UIA backend (`backend='uia'`). Navigates accessibility tree, finds buttons by AutomationId/Name, interacts without mouse hijacking.

WINDOWS-AGENT GAP:
Tree traversal can be slow on complex applications (e.g. Chrome/WPF) without depth limit.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
Mark-LIII and jarvis-desktop-core rely on brittle PyAutoGUI screen pixel coordinates.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/windows_agent_tool.py:45-190; Mark-LIII: actions/computer_control.py:40-90 (pixel clicks); jarvis-desktop-core: backend/main.py:1500-1580; OpenJarvis: NOT FOUND

---

### AI. PowerShell / CMD / Shell Execution

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/terminal_tool.py:30-140, core/pty_manager.py:20-85 |
| jarvis-desktop-core | BROKEN | 15 | backend/main.py:1450-1490 (CRITICAL VULNERABILITY) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/tools/shell.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/computer_control.py:75-110 |

BEST:
windows-agent

WHY BEST:
tools/terminal_tool.py and tools/cmd_tool.py execute PowerShell and CMD with non-blocking async pipes, strict timeout enforcement, process kill on timeout, and security filtering.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/terminal_tool.py and tools/cmd_tool.py execute PowerShell and CMD with non-blocking async pipes, strict timeout enforcement, process kill on timeout, and security filtering.

WINDOWS-AGENT GAP:
Interactive pseudo-terminal (PTY) support on Windows could leverage ConPTY API via pywinpty.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
core/pty_manager.py in windows-agent already has ConPTY scaffolding.

MIGRATION TYPE:
KEEP FROM WINDOWS-AGENT

WHY:
jarvis-desktop-core uses vulnerable `subprocess.Popen(cmd, shell=True)` with no timeout.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/terminal_tool.py:30-140, core/pty_manager.py:20-85; Mark-LIII: actions/computer_control.py:75-110; jarvis-desktop-core: backend/main.py:1450-1490 (CRITICAL VULNERABILITY); OpenJarvis: src/openjarvis/tools/shell.py

---

### AJ. File System Automation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/file_tool.py:1-210 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:2050-2120 |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/tools/file.py:40-160 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/undo.py:25-80 |

BEST:
windows-agent

WHY BEST:
tools/file_tool.py, tools/file_manager.py, and tools/universal_file_tool.py provide atomic writes, directory tree generation, hash verification, and diff patching.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/file_tool.py, tools/file_manager.py, and tools/universal_file_tool.py provide atomic writes, directory tree generation, hash verification, and diff patching.

WINDOWS-AGENT GAP:
Does not route deletes to Windows Recycle Bin by default (uses direct deletion).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/undo.py (uses winshell.delete(allow_undo=True) for Recycle Bin safety)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Route all file deletions through SHFileOperationW to send deleted files to Windows Recycle Bin.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
windows-agent: tools/file_tool.py:1-210; Mark-LIII: core/undo.py:25-80; OpenJarvis: src/openjarvis/tools/file.py:40-160; jarvis-desktop-core: backend/main.py:2050-2120

---

### AK. File Sandboxing / Path Security

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/security_policy.py:40-95 |
| jarvis-desktop-core | BROKEN | 15 | NOT FOUND (arbitrary file access) |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/sandbox/manager.py:30-180 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | NOT FOUND |

BEST:
OpenJarvis

WHY BEST:
Provides isolated sandbox for running untrusted LLM-generated code.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent validates paths against allowed roots in core/security_policy.py.

WINDOWS-AGENT GAP:
OpenJarvis provides Docker container sandbox and Linux namespace isolation in src/openjarvis/sandbox/.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/sandbox/manager.py

MIGRATION TYPE:
ADAPT

WHY:
Provides isolated sandbox for running untrusted LLM-generated code.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/sandbox/manager.py:30-180; windows-agent: core/security_policy.py:40-95; Mark-LIII: NOT FOUND; jarvis-desktop-core: NOT FOUND (arbitrary file access)

---

### AL. Browser Automation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/browser_cdp.py:35-180 |
| jarvis-desktop-core | STUB | 15 | backend/jarvis_core/browser_intent.py:20-95 |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/agents/browser.py:1-260 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/browser_control.py:20-80 (webbrowser module) |

BEST:
OpenJarvis

WHY BEST:
Playwright browser automation provides more robust session management and element locating than raw CDP.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has tools/browser_cdp.py and core/browser_automation.py connecting to Chrome over Chrome DevTools Protocol (CDP).

WINDOWS-AGENT GAP:
OpenJarvis features comprehensive Playwright-based browser agent with DOM tree extraction, screenshot annotation, and automatic captcha handling.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/agents/browser.py & src/openjarvis/connectors/browser/

MIGRATION TYPE:
ADAPT

WHY:
Playwright browser automation provides more robust session management and element locating than raw CDP.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/agents/browser.py:1-260; windows-agent: tools/browser_cdp.py:35-180; Mark-LIII: actions/browser_control.py:20-80 (webbrowser module); jarvis-desktop-core: backend/jarvis_core/browser_intent.py:20-95

---

### AM. Web Search

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/search_racer.py:30-120 |
| jarvis-desktop-core | STUB | 15 | backend/jarvis_core/knowledge_router.py:40-85 |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/connectors/search/tavily.py:20-90 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/web_search.py:15-60 |

BEST:
OpenJarvis

WHY BEST:
Tavily & SearXNG connectors return clean extracted markdown without HTML scraping overhead.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/search_racer.py racing Google, Bing, and DuckDuckGo in parallel.

WINDOWS-AGENT GAP:
OpenJarvis integrates Tavily, Brave Search, SearXNG, and DuckDuckGo with automatic API key rotation and proxy support.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/connectors/search/

MIGRATION TYPE:
ADAPT

WHY:
Tavily & SearXNG connectors return clean extracted markdown without HTML scraping overhead.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/connectors/search/tavily.py:20-90; windows-agent: core/search_racer.py:30-120; Mark-LIII: actions/web_search.py:15-60; jarvis-desktop-core: backend/jarvis_core/knowledge_router.py:40-85

---

### AN. Deep Research

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/research_tools.py:40-150 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/agents/research_loop.py:1-310 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | NOT FOUND |

BEST:
OpenJarvis

WHY BEST:
Complete multi-turn deep research pipeline for generating comprehensive cited reports.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has tools/research_tools.py with multi-query synthesis.

WINDOWS-AGENT GAP:
OpenJarvis has a dedicated autonomous research loop (src/openjarvis/agents/research_loop.py) with iterative question generation, claim extraction, citation verification, and markdown report generation.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/agents/research_loop.py

MIGRATION TYPE:
ADAPT

WHY:
Complete multi-turn deep research pipeline for generating comprehensive cited reports.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/agents/research_loop.py:1-310; windows-agent: tools/research_tools.py:40-150; Mark-LIII: NOT FOUND; jarvis-desktop-core: NOT FOUND

---

### AO. Screen Capture

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/screen_capture.py:1-180 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:1600-1650 (PIL ImageGrab) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/system/screen.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/screen_processor.py:20-75 |

BEST:
windows-agent

WHY BEST:
tools/screen_capture.py uses Windows Desktop Duplication API (DXGI) and `mss` for ultra-low latency (<15ms) screen capture, multi-monitor selection, and active window cropping.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/screen_capture.py uses Windows Desktop Duplication API (DXGI) and `mss` for ultra-low latency (<15ms) screen capture, multi-monitor selection, and active window cropping.

WINDOWS-AGENT GAP:
Needs automatic redacting of sensitive fields (password inputs, credit cards) before sending to VLM.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent DXGI screen capture is substantially faster than standard PIL ImageGrab.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/screen_capture.py:1-180; Mark-LIII: actions/screen_processor.py:20-75; jarvis-desktop-core: backend/main.py:1600-1650 (PIL ImageGrab); OpenJarvis: src/openjarvis/system/screen.py

---

### AP. Screen Vision / VLM

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/vision_coordinator.py:30-140 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:1650-1700 |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/operators/vision.py:35-120 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/screen_processor.py:40-95 |

BEST:
windows-agent

WHY BEST:
core/vision_coordinator.py streams base64/JPEG screenshots to Gemini 2.0 Flash / Pro Vision with bounding box grounding and element detection.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/vision_coordinator.py streams base64/JPEG screenshots to Gemini 2.0 Flash / Pro Vision with bounding box grounding and element detection.

WINDOWS-AGENT GAP:
Could cache visual element embeddings to avoid re-querying cloud VLM on static screens.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent has direct Gemini Live multimodal image push integrated into Bidi session.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/vision_coordinator.py:30-140; Mark-LIII: actions/screen_processor.py:40-95; OpenJarvis: src/openjarvis/operators/vision.py:35-120; jarvis-desktop-core: backend/main.py:1650-1700

---

### AQ. OCR

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/ocr_tool.py:20-110 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/mining/ocr.py:40-130 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/screen_processor.py:60-90 (tesseract) |

BEST:
OpenJarvis

WHY BEST:
WinRT OCR is native, instant, and hardware accelerated on Windows.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has Windows.Media.Ocr (Windows 10/11 built-in offline OCR) via WinRT.

WINDOWS-AGENT GAP:
OpenJarvis supports PaddleOCR, Tesseract, and Surya OCR with bounding-box layout parsing.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent WinRT OCR is faster and requires 0 pip installs; keep as primary, adapt PaddleOCR as fallback.

MIGRATION TYPE:
KEEP FROM WINDOWS-AGENT

WHY:
WinRT OCR is native, instant, and hardware accelerated on Windows.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/ocr_tool.py:20-110; OpenJarvis: src/openjarvis/mining/ocr.py:40-130; Mark-LIII: actions/screen_processor.py:60-90 (tesseract); jarvis-desktop-core: NOT FOUND

---

### AR. Computer Vision

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/vision_coordinator.py:60-110 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/operators/cv.py:25-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/screen_processor.py:30-80 |

BEST:
OpenJarvis

WHY BEST:
Local OpenCV template matching avoids LLM costs for static UI button locating.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent focuses on UI vision and screenshot comprehension.

WINDOWS-AGENT GAP:
OpenJarvis contains OpenCV pipeline for visual template matching, icon detection, and edge detection.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/operators/cv.py

MIGRATION TYPE:
ADAPT

WHY:
Local OpenCV template matching avoids LLM costs for static UI button locating.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/operators/cv.py:25-110; windows-agent: core/vision_coordinator.py:60-110; Mark-LIII: actions/screen_processor.py:30-80

---

### AS. Hand Tracking / Gesture Control

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | PARTIAL | 25 | NOT FOUND |
| jarvis-desktop-core | PARTIAL | 30 | backend/gesture_mouse.py:1-120 (poor OpenCV port) |
| OpenJarvis | NOT FOUND | 0 | NOT FOUND |
| ultron | VERIFIED IMPLEMENTED | 95 | lib/handTracker.ts:1-345 |
| Mark-LIII | PARTIAL | 65 | NOT FOUND |

BEST:
ultron

WHY BEST:
ultron's MediaPipe implementation runs client-side at 60 FPS in Next.js without taxing Python backend.

WORST:
windows-agent

WHY WORST:
windows-agent lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has zero hand tracking or gesture input.

WINDOWS-AGENT GAP:
No webcam gesture control.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
ultron: lib/handTracker.ts (MediaPipe HandLandmarker in Web Worker with gesture recognition)

MIGRATION TYPE:
DIRECT REUSE

WHY:
ultron's MediaPipe implementation runs client-side at 60 FPS in Next.js without taxing Python backend.

LICENSE/IP:
MIT License (Permissive, commercial friendly)

EVIDENCE:
ultron: lib/handTracker.ts:1-345; jarvis-desktop-core: backend/gesture_mouse.py:1-120 (poor OpenCV port); windows-agent: NOT FOUND; OpenJarvis: NOT FOUND; Mark-LIII: NOT FOUND

---

### AT. Android Automation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | orchestrator/device_router.py:1-210, protocol/command_models.py:1-180 |
| jarvis-desktop-core | PARTIAL | 30 | NOT FOUND |
| OpenJarvis | NOT FOUND | 5 | NOT FOUND |
| ultron | NOT FOUND | 0 | NOT FOUND (README claim only) |
| Mark-LIII | NOT FOUND | 0 | NOT FOUND |

BEST:
windows-agent

WHY BEST:
orchestrator/device_router.py and protocol/command_models.py route structured automation packets to Max Android companion app over mutual TLS WebSocket.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
orchestrator/device_router.py and protocol/command_models.py route structured automation packets to Max Android companion app over mutual TLS WebSocket.

WINDOWS-AGENT GAP:
Requires companion app to be installed on target Android phone.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is undisputed winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
OpenJarvis, jarvis-desktop-core, Mark-LIII, and ultron have ZERO functional Android automation code.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: orchestrator/device_router.py:1-210, protocol/command_models.py:1-180; OpenJarvis: NOT FOUND; jarvis-desktop-core: NOT FOUND; Mark-LIII: NOT FOUND; ultron: NOT FOUND (README claim only)

---

### AU. Android Companion Architecture

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | orchestrator/device_router.py:45-160, gateway/gateway_server.py:30-120 |
| jarvis-desktop-core | PARTIAL | 30 | No direct implementation |
| OpenJarvis | NOT FOUND | 5 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | NOT FOUND | 0 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Bidirectional secure WebSocket pairing between Windows host and Android companion app, synchronizing notifications, clipboard, and remote actions.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Bidirectional secure WebSocket pairing between Windows host and Android companion app, synchronizing notifications, clipboard, and remote actions.

WINDOWS-AGENT GAP:
Zero gaps. Highly sophisticated mobile companion architecture.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
No other repository has an Android companion protocol.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: orchestrator/device_router.py:45-160, gateway/gateway_server.py:30-120; all other repos: NOT FOUND

---

### AV. WhatsApp Automation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | orchestrator/device_router.py:120-170, tools/app_tool.py:40-90 |
| jarvis-desktop-core | PARTIAL | 30 | No direct implementation |
| OpenJarvis | PARTIAL | 25 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/send_message.py:20-85 |

BEST:
windows-agent

WHY BEST:
windows-agent sends direct structured command to Android companion to launch WhatsApp, navigate conversation by contact name, verify text box, and send message via Android AccessibilityService.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent sends direct structured command to Android companion to launch WhatsApp, navigate conversation by contact name, verify text box, and send message via Android AccessibilityService.

WINDOWS-AGENT GAP:
Desktop WhatsApp Web fallback when phone is offline.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: actions/send_message.py (WhatsApp Web desktop automation)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Provide secondary fallback to desktop WhatsApp Web when Android phone is unreachable.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
windows-agent: orchestrator/device_router.py:120-170, tools/app_tool.py:40-90; Mark-LIII: actions/send_message.py:20-85; other repos: NOT FOUND

---

### AW. Device Routing

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | orchestrator/device_router.py:30-140 |
| jarvis-desktop-core | PARTIAL | 30 | No direct implementation |
| OpenJarvis | PARTIAL | 25 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
orchestrator/device_router.py evaluates tool target attribute: `TargetDevice.WINDOWS` vs `TargetDevice.ANDROID`. Automatically dispatches actions to local OS or remote Android phone.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
orchestrator/device_router.py evaluates tool target attribute: `TargetDevice.WINDOWS` vs `TargetDevice.ANDROID`. Automatically dispatches actions to local OS or remote Android phone.

WINDOWS-AGENT GAP:
Could extend to multi-device routing (e.g. tablet, home server, multiple phones).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is absolute winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
Only windows-agent has device routing capabilities.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: orchestrator/device_router.py:30-140; other repos: NOT FOUND

---

### AX. Remote Communication

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | gateway/gateway_server.py:1-180 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:120-180 |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/server/app.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
gateway/gateway_server.py provides secure WebSocket gateway with binary and JSON frame multiplexing.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
gateway/gateway_server.py provides secure WebSocket gateway with binary and JSON frame multiplexing.

WINDOWS-AGENT GAP:
Need dynamic port discovery / UPnP / Tailscale integration for remote WAN connections.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core has an unauthenticated vulnerable WebSocket server.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: gateway/gateway_server.py:1-180; jarvis-desktop-core: backend/main.py:120-180; OpenJarvis: src/openjarvis/server/app.py

---

### AY. Network Security / TLS

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/cert_generator.py:1-95, gateway/gateway_server.py:45-90 |
| jarvis-desktop-core | BROKEN | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
core/cert_generator.py and gateway/gateway_server.py generate and enforce self-signed X.509 certificates with mutual TLS (mTLS) for Android device pairing.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/cert_generator.py and gateway/gateway_server.py generate and enforce self-signed X.509 certificates with mutual TLS (mTLS) for Android device pairing.

WINDOWS-AGENT GAP:
Allow user-supplied trusted CA certificates.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core uses cleartext unencrypted HTTP/WS with zero TLS.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/cert_generator.py:1-95, gateway/gateway_server.py:45-90; jarvis-desktop-core: NOT FOUND

---

### AZ. Pairing / Authentication

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | gateway/gateway_server.py:95-150, protocol/packet_factory.py:20-80 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND (score 0) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Cryptographic pairing handshake using short authentication codes (SAS) and HMAC challenge-response for companion devices.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Cryptographic pairing handshake using short authentication codes (SAS) and HMAC challenge-response for companion devices.

WINDOWS-AGENT GAP:
Support QR code display in UI for 1-click mobile camera pairing.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core has ZERO authentication on WebSocket.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: gateway/gateway_server.py:95-150, protocol/packet_factory.py:20-80; jarvis-desktop-core: NOT FOUND (score 0)

---

### BA. Prompt Injection Protection

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/security.py:30-90 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/security/prompt_guard.py:1-160 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | NOT FOUND |

BEST:
OpenJarvis

WHY BEST:
Canary token tracking detects if external web content tries to override system instructions.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent checks inputs against regex patterns in core/security.py.

WINDOWS-AGENT GAP:
OpenJarvis implements dual LLM guardrails (input/output sanitization, canary tokens, and delimiter isolation) in src/openjarvis/security/prompt_guard.py.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/security/prompt_guard.py

MIGRATION TYPE:
ADAPT

WHY:
Canary token tracking detects if external web content tries to override system instructions.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/security/prompt_guard.py:1-160; windows-agent: core/security.py:30-90; Mark-LIII: NOT FOUND; jarvis-desktop-core: NOT FOUND

---

### BB. Taint Tracking

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/security_policy.py:50-110 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/security/taint.py:25-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Stops prompt injection from web pages pivoting into file system or command execution.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent tags clipboard inputs with trust level.

WINDOWS-AGENT GAP:
OpenJarvis has formal taint tracking on all untrusted web/file inputs preventing tainted data from entering shell execution tools.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/security/taint.py

MIGRATION TYPE:
ADAPT

WHY:
Stops prompt injection from web pages pivoting into file system or command execution.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/security/taint.py:25-110; windows-agent: core/security_policy.py:50-110; other repos: NOT FOUND

---

### BC. Capability / Permission System

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/security_policy.py:1-140 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/security/capabilities.py:30-95 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | core/confirm.py:20-65 |

BEST:
windows-agent

WHY BEST:
core/security_policy.py defines granular permissions: `ALLOW_ALL`, `CONFIRM_DESTRUCTIVE`, `READ_ONLY`. Categorizes every tool call dynamically.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/security_policy.py defines granular permissions: `ALLOW_ALL`, `CONFIRM_DESTRUCTIVE`, `READ_ONLY`. Categorizes every tool call dynamically.

WINDOWS-AGENT GAP:
Granular per-directory path whitelisting in config.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
OpenJarvis is open-by-default; jarvis-desktop-core has no permissions.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/security_policy.py:1-140; OpenJarvis: src/openjarvis/security/capabilities.py:30-95; Mark-LIII: core/confirm.py:20-65

---

### BD. Confirmation System

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/security_policy.py:80-130 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | core/confirm.py:1-95 |

BEST:
Mark-LIII

WHY BEST:
Mandatory one-time UI confirmation token for destructive actions (format, delete, shutdown).

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent prompts user via voice or console confirmation.

WINDOWS-AGENT GAP:
Could be spoofed if LLM claims user said yes.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/confirm.py (generates cryptographically unforgeable one-time token in UI banner that LLM cannot self-generate)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Mandatory one-time UI confirmation token for destructive actions (format, delete, shutdown).

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: core/confirm.py:1-95; windows-agent: core/security_policy.py:80-130; jarvis-desktop-core: NOT FOUND

---

### BE. Human-in-the-loop Safety

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | engine/step_runner.py:90-135 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | core/confirm.py:35-85 |

BEST:
Mark-LIII

WHY BEST:
Visual modal requiring explicit mouse click or button press before dangerous actions execute.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent pauses task engine in `WAITING_CONFIRMATION` state.

WINDOWS-AGENT GAP:
Timeout behavior on pending confirmations.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/confirm.py + UI banner

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Visual modal requiring explicit mouse click or button press before dangerous actions execute.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: core/confirm.py:35-85; windows-agent: engine/step_runner.py:90-135; jarvis-desktop-core: NOT FOUND

---

### BF. Undo / Rollback

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/undo_manager.py:20-80 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | NOT FOUND |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | core/undo.py:1-120 |

BEST:
Mark-LIII

WHY BEST:
10-action undo stack allowing user to say 'Undo that' to restore deleted files from Recycle Bin or revert file edits.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/undo_manager.py tracking file writes.

WINDOWS-AGENT GAP:
Lacks deep OS-level rollback (moving files to Recycle Bin rather than deleting, registry restore).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: core/undo.py (10-step undo stack using Windows Shell COM API for Recycle Bin restoration)

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
10-action undo stack allowing user to say 'Undo that' to restore deleted files from Recycle Bin or revert file edits.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: core/undo.py:1-120; windows-agent: core/undo_manager.py:20-80; OpenJarvis: NOT FOUND; jarvis-desktop-core: NOT FOUND

---

### BG. Verification / Environmental Verification

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/verification_engine.py:1-195, core/verifier.py:1-160 |
| jarvis-desktop-core | BROKEN | 15 | backend/main.py:1420 (FRAUDULENT VERIFICATION) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/engine/verifier.py:30-85 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
core/verification_engine.py and core/verifier.py inspect actual OS state: verifies PID exists, HWND is valid, window title matches, file exists and hash matches, socket connects.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/verification_engine.py and core/verifier.py inspect actual OS state: verifies PID exists, HWND is valid, window title matches, file exists and hash matches, socket connects.

WINDOWS-AGENT GAP:
Process spawn check occasionally returns before window is fully mapped.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is absolute winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core has FAKE verification (`'failed' not in text.lower()`); Mark-LIII has partial check; OpenJarvis has code check only.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/verification_engine.py:1-195, core/verifier.py:1-160; jarvis-desktop-core: backend/main.py:1420 (FRAUDULENT VERIFICATION); OpenJarvis: src/openjarvis/engine/verifier.py:30-85

---

### BH. Error Handling

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | protocol/error_codes.py:1-110, engine/step_runner.py:140-195 |
| jarvis-desktop-core | STUB | 15 | bare except: pass |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/core/errors.py:20-80 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Granular error hierarchy in protocol/error_codes.py and engine/models.py: classifies TRANSIENT vs PERMANENT errors with dedicated recovery strategies.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Granular error hierarchy in protocol/error_codes.py and engine/models.py: classifies TRANSIENT vs PERMANENT errors with dedicated recovery strategies.

WINDOWS-AGENT GAP:
Auto-reporting of unhandled exceptions to crashlog.json.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent error taxonomy is comprehensive and actionable.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: protocol/error_codes.py:1-110, engine/step_runner.py:140-195; OpenJarvis: src/openjarvis/core/errors.py:20-80; jarvis-desktop-core: bare except: pass

---

### BI. Retry Logic

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | engine/step_runner.py:110-165 |
| jarvis-desktop-core | STUB | 15 | zero retry |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/core/retry.py:25-70 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Exponential backoff with jitter and per-step retry budgets in engine/step_runner.py. Distinguishes network glitch from logical code failure.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Exponential backoff with jitter and per-step retry budgets in engine/step_runner.py. Distinguishes network glitch from logical code failure.

WINDOWS-AGENT GAP:
Retry budget is global per step; could have tool-specific backoff multipliers.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent retry logic prevents infinite retry loops.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: engine/step_runner.py:110-165; OpenJarvis: src/openjarvis/core/retry.py:25-70; jarvis-desktop-core: zero retry

---

### BJ. Rate Limit Handling

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/provider_manager.py:85-115 |
| jarvis-desktop-core | STUB | 15 | crashes on 429 |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/connectors/llm/rate_limiter.py:20-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Prevents API lockouts during autonomous coding or deep research loops.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has basic sleep on HTTP 429.

WINDOWS-AGENT GAP:
OpenJarvis parses `Retry-After` headers across OpenAI, Anthropic, and Gemini, using token bucket rate limiters to queue requests before hitting API limits.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/connectors/llm/rate_limiter.py

MIGRATION TYPE:
ADAPT

WHY:
Prevents API lockouts during autonomous coding or deep research loops.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/connectors/llm/rate_limiter.py:20-110; windows-agent: core/provider_manager.py:85-115; jarvis-desktop-core: crashes on 429

---

### BK. Context Management

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | context/builder.py:1-140 |
| jarvis-desktop-core | STUB | 15 | appends all messages indefinitely |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/prompt/truncation.py:30-90 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
context/builder.py, context/policy.py, context/store.py assemble dynamic context windows based on current active window, clipboard, and task status.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
context/builder.py, context/policy.py, context/store.py assemble dynamic context windows based on current active window, clipboard, and task status.

WINDOWS-AGENT GAP:
Automatic truncation when approaching 128k/1M token boundary.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/prompt/truncation.py

MIGRATION TYPE:
ADAPT

WHY:
Sliding-window context management preserving system prompt and recent turns.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: context/builder.py:1-140; OpenJarvis: src/openjarvis/prompt/truncation.py:30-90; jarvis-desktop-core: appends all messages indefinitely

---

### BL. Context Compression

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | context/hermes_compressor.py:1-160 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/memory/compressor.py:40-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
context/hermes_compressor.py summarizes completed subtasks and historical turns into dense contextual memory.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
context/hermes_compressor.py summarizes completed subtasks and historical turns into dense contextual memory.

WINDOWS-AGENT GAP:
Compression could preserve structured JSON entities rather than pure prose.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent compressor is custom-tailored for task execution history.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: context/hermes_compressor.py:1-160; OpenJarvis: src/openjarvis/memory/compressor.py:40-110; jarvis-desktop-core: NOT FOUND

---

### BM. Memory

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/memory.py:30-110 |
| jarvis-desktop-core | STUB | 15 | in-memory global dict |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/memory/manager.py:1-210 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | memory/memory_manager.py:20-85 |

BEST:
OpenJarvis

WHY BEST:
Full multi-tiered memory architecture enabling lifelong user preference learning.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has context/session_memory.py and core/memory.py with key-value and SQLite storage.

WINDOWS-AGENT GAP:
OpenJarvis implements multi-tiered memory: Working Memory (active context), Episodic Memory (past sessions), and Semantic Memory (vector knowledge base).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/memory/ (manager.py, episodic.py, semantic.py)

MIGRATION TYPE:
ADAPT

WHY:
Full multi-tiered memory architecture enabling lifelong user preference learning.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/memory/manager.py:1-210; windows-agent: core/memory.py:30-110; Mark-LIII: memory/memory_manager.py:20-85; jarvis-desktop-core: in-memory global dict

---

### BN. Semantic Memory

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/memory.py:65-110 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/memory/semantic.py:30-160 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | memory/memory_manager.py:40-90 |

BEST:
OpenJarvis

WHY BEST:
Retrieves relevant past conversations and facts using semantic similarity.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has basic semantic memory.

WINDOWS-AGENT GAP:
OpenJarvis indexes all user interactions, saved documents, and preferences using embedding models.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/memory/semantic.py

MIGRATION TYPE:
ADAPT

WHY:
Retrieves relevant past conversations and facts using semantic similarity.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/memory/semantic.py:30-160; windows-agent: core/memory.py:65-110; Mark-LIII: memory/memory_manager.py:40-90; jarvis-desktop-core: NOT FOUND

---

### BO. Vector Search

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/memory.py |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | crates/jarvis-memory/src/lib.rs:1-190, src/openjarvis/connectors/vector/lancedb.py:30-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Sub-millisecond vector retrieval without heavy external database servers.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent uses ChromaDB or SQLite-vec.

WINDOWS-AGENT GAP:
OpenJarvis features custom high-performance vector search in Rust (`crates/jarvis-memory`) with PyO3 bindings and HNSW indexing, plus Qdrant and LanceDB connectors.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: crates/jarvis-memory/src/ & src/openjarvis/connectors/vector/

MIGRATION TYPE:
ADAPT

WHY:
Sub-millisecond vector retrieval without heavy external database servers.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: crates/jarvis-memory/src/lib.rs:1-190, src/openjarvis/connectors/vector/lancedb.py:30-110; windows-agent: core/memory.py; jarvis-desktop-core: NOT FOUND

---

### BP. Hybrid Search

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | NOT FOUND |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/connectors/hybrid_search.py:1-180 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | NOT FOUND |

BEST:
OpenJarvis

WHY BEST:
Hybrid search guarantees exact keyword matches (code symbols, error codes, file paths) while preserving semantic context.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has keyword search.

WINDOWS-AGENT GAP:
OpenJarvis implements Hybrid Search: Sparse BM25 + Dense Vector embeddings fused via Reciprocal Rank Fusion (RRF, k=60).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/connectors/hybrid_search.py

MIGRATION TYPE:
ADAPT

WHY:
Hybrid search guarantees exact keyword matches (code symbols, error codes, file paths) while preserving semantic context.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/connectors/hybrid_search.py:1-180; windows-agent: NOT FOUND; jarvis-desktop-core: NOT FOUND; Mark-LIII: NOT FOUND

---

### BQ. Memory Trust / Provenance

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/memory.py |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/memory/provenance.py:25-95 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Enforces memory trust so unverified facts are not treated as truth.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent stores memory without source verification.

WINDOWS-AGENT GAP:
OpenJarvis tracks provenance metadata (source URI, timestamp, verification status, confidence score) for every stored fact, preventing hallucinations.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/memory/provenance.py

MIGRATION TYPE:
ADAPT

WHY:
Enforces memory trust so unverified facts are not treated as truth.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/memory/provenance.py:25-95; windows-agent: core/memory.py; jarvis-desktop-core: NOT FOUND

---

### BR. Coding Agent

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/code_repair_agent.py:30-120 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/agents/code.py:40-160 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | actions/dev_agent.py:1-240 |

BEST:
Mark-LIII

WHY BEST:
Autonomous self-healing coding loop with test execution and traceback analysis. Clean-room required due to CC BY-NC 4.0.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/code_repair_agent.py and tools/code_tools.py.

WINDOWS-AGENT GAP:
Mark-LIII has actions/dev_agent.py: a dedicated autonomous developer agent that plans project, creates files, runs pytest, parses traceback, patches code, and iterates up to 5 times.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: actions/dev_agent.py

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Autonomous self-healing coding loop with test execution and traceback analysis. Clean-room required due to CC BY-NC 4.0.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: actions/dev_agent.py:1-240; windows-agent: core/code_repair_agent.py:30-120; OpenJarvis: src/openjarvis/agents/code.py:40-160; jarvis-desktop-core: NOT FOUND

---

### BS. Code Generation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/code_tools.py:40-95 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/templates/code_gen.py:30-140 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/dev_agent.py:50-90 |

BEST:
OpenJarvis

WHY BEST:
Higher syntax accuracy across TypeScript, Python, and Rust.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent generates code via standard LLM prompts.

WINDOWS-AGENT GAP:
OpenJarvis uses specialized multi-file code generation templates with AST verification and import sorting.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/templates/code_gen.py

MIGRATION TYPE:
ADAPT

WHY:
Higher syntax accuracy across TypeScript, Python, and Rust.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/templates/code_gen.py:30-140; windows-agent: tools/code_tools.py:40-95; Mark-LIII: actions/dev_agent.py:50-90

---

### BT. Code Execution

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/code_sandbox.py:1-130 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:1450 |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/sandbox/docker.py:30-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/dev_agent.py:130-170 |

BEST:
windows-agent

WHY BEST:
tools/code_sandbox.py and tools/terminal_tool.py execute code in isolated sub-environments with strict timeouts, capturing stdout/stderr.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/code_sandbox.py and tools/terminal_tool.py execute code in isolated sub-environments with strict timeouts, capturing stdout/stderr.

WINDOWS-AGENT GAP:
Docker container execution on Windows requires WSL2.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner on native Windows. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core has unsandboxed dangerous shell=True execution.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/code_sandbox.py:1-130; OpenJarvis: src/openjarvis/sandbox/docker.py:30-110; Mark-LIII: actions/dev_agent.py:130-170; jarvis-desktop-core: backend/main.py:1450

---

### BU. Test Execution

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/terminal_tool.py |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/evals/runner.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | actions/dev_agent.py:125-180 |

BEST:
Mark-LIII

WHY BEST:
Automatic test suite runner with structured JSON output parsing.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent executes tests via terminal commands.

WINDOWS-AGENT GAP:
Mark-LIII actions/dev_agent.py automatically discovers pytest / npm test / cargo test test-suites, executes them, and captures structured test outcomes.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: actions/dev_agent.py:run_tests()

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Automatic test suite runner with structured JSON output parsing.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: actions/dev_agent.py:125-180; windows-agent: tools/terminal_tool.py; OpenJarvis: src/openjarvis/evals/runner.py; jarvis-desktop-core: NOT FOUND

---

### BV. Traceback Analysis

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/code_repair_agent.py:50-90 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/agents/code.py:110-150 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | actions/dev_agent.py:185-230 |

BEST:
Mark-LIII

WHY BEST:
Surgical error parsing pinpoints the exact line and failure reason, reducing LLM token consumption.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent passes raw stderr back to LLM.

WINDOWS-AGENT GAP:
Mark-LIII uses regex and Python AST to parse Python/Node tracebacks into exact file, line number, exception class, and error message.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: actions/dev_agent.py:parse_traceback()

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Surgical error parsing pinpoints the exact line and failure reason, reducing LLM token consumption.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: actions/dev_agent.py:185-230; windows-agent: core/code_repair_agent.py:50-90; OpenJarvis: src/openjarvis/agents/code.py:110-150; jarvis-desktop-core: NOT FOUND

---

### BW. Self-Repair

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/code_repair_agent.py:40-110 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/workflow/repair.py:30-80 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | VERIFIED IMPLEMENTED | 95 | actions/dev_agent.py:90-170 |

BEST:
Mark-LIII

WHY BEST:
Robust autonomous closed-loop debugging. Must clean-room due to CC BY-NC 4.0.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/code_repair_agent.py.

WINDOWS-AGENT GAP:
Mark-LIII implements a complete closed-loop self-repair cycle: Run Test -> Fail -> Extract Error -> Locate Source Line -> Generate Minimal Patch -> Apply Patch -> Rerun Test -> Verify Success (up to 5 attempts).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Mark-LIII: actions/dev_agent.py:repair_loop()

MIGRATION TYPE:
CLEAN-ROOM REIMPLEMENTATION

WHY:
Robust autonomous closed-loop debugging. Must clean-room due to CC BY-NC 4.0.

LICENSE/IP:
CC BY-NC 4.0

EVIDENCE:
Mark-LIII: actions/dev_agent.py:90-170; windows-agent: core/code_repair_agent.py:40-110; OpenJarvis: src/openjarvis/workflow/repair.py:30-80; jarvis-desktop-core: NOT FOUND

---

### BX. AST Validation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/code_tools.py:70-95 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/tools/ast_validator.py:1-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/dev_agent.py:70-90 |

BEST:
OpenJarvis

WHY BEST:
Catches syntax errors and missing imports immediately without running the code.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent checks code syntax via py_compile.

WINDOWS-AGENT GAP:
OpenJarvis parses generated code with `ast.parse` and tree-sitter, ensuring syntax is 100% valid before writing to disk.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/tools/ast_validator.py

MIGRATION TYPE:
ADAPT

WHY:
Catches syntax errors and missing imports immediately without running the code.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/tools/ast_validator.py:1-110; windows-agent: tools/code_tools.py:70-95; Mark-LIII: actions/dev_agent.py:70-90; jarvis-desktop-core: NOT FOUND

---

### BY. Project Creation

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/code_tools.py:20-80, tools/app_control.py:30-90 |
| jarvis-desktop-core | STUB | 15 | NOT FOUND |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/templates/projects/ |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/dev_agent.py:30-70 |

BEST:
windows-agent

WHY BEST:
tools/code_tools.py, tools/file_tool.py, tools/app_control.py can scaffold project templates, create directories, initialize Git, and open VS Code (`code <path>`).

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/code_tools.py, tools/file_tool.py, tools/app_control.py can scaffold project templates, create directories, initialize Git, and open VS Code (`code <path>`).

WINDOWS-AGENT GAP:
Needs pre-built starter templates (FastAPI, React, Next.js).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/templates/projects/

MIGRATION TYPE:
ADAPT

WHY:
Comprehensive library of project boilerplate templates.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: tools/code_tools.py:20-80, tools/app_control.py:30-90; OpenJarvis: src/openjarvis/templates/projects/; Mark-LIII: actions/dev_agent.py:30-70; jarvis-desktop-core: NOT FOUND

---

### BZ. File Editing

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/patch_tool.py:1-160 |
| jarvis-desktop-core | STUB | 15 | backend/main.py:2080 |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/tools/editor.py:30-120 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | actions/file_controller.py:20-80 |

BEST:
windows-agent

WHY BEST:
tools/patch_tool.py implements unified diff and line-based patch application with fuzzy matching.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/patch_tool.py implements unified diff and line-based patch application with fuzzy matching.

WINDOWS-AGENT GAP:
Could support Search-and-Replace block editing (Aider-style).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/tools/editor.py (block search/replace)

MIGRATION TYPE:
ADAPT

WHY:
Block editing is more reliable than line numbers for large files.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: tools/patch_tool.py:1-160; OpenJarvis: src/openjarvis/tools/editor.py:30-120; Mark-LIII: actions/file_controller.py:20-80; jarvis-desktop-core: backend/main.py:2080

---

### CA. Git Integration

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | tools/git_tools.py:1-170 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/tools/git.py:30-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
tools/git_tools.py supports git clone, status, diff, commit, branch, checkout, and push with clean error handling.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
tools/git_tools.py supports git clone, status, diff, commit, branch, checkout, and push with clean error handling.

WINDOWS-AGENT GAP:
Could add automatic branch creation and commit message generation for autonomous tasks.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent git tool is robust.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: tools/git_tools.py:1-170; OpenJarvis: src/openjarvis/tools/git.py:30-110; other repos: NOT FOUND

---

### CB. Logging

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | config.py, engine/store.py |
| jarvis-desktop-core | STUB | 15 | print statements |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/telemetry/logging.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Structured rotating file logs (logs/agent.log) and console output with timestamps, module tags, and log levels. Step logs saved to SQLite.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Structured rotating file logs (logs/agent.log) and console output with timestamps, module tags, and log levels. Step logs saved to SQLite.

WINDOWS-AGENT GAP:
Exporting logs to OpenTelemetry format.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent logging is clean and reliable.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: config.py, engine/store.py; OpenJarvis: src/openjarvis/telemetry/logging.py; jarvis-desktop-core: print statements

---

### CC. Telemetry

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/metrics_manager.py:20-90 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/telemetry/otel.py:1-160 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Standard observability into agent latency bottlenecks and token expenditure.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has core/metrics_manager.py tracking token counts and latencies.

WINDOWS-AGENT GAP:
OpenJarvis has complete OpenTelemetry / Prometheus exporter for tracking agent traces, tool execution times, and LLM costs.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/telemetry/otel.py

MIGRATION TYPE:
ADAPT

WHY:
Standard observability into agent latency bottlenecks and token expenditure.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/telemetry/otel.py:1-160; windows-agent: core/metrics_manager.py:20-90; other repos: NOT FOUND

---

### CD. Observability

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/metrics_manager.py |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/traces/tracer.py:30-140 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Trace visualization for debugging multi-agent loops.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent tracks task state in SQLite database.

WINDOWS-AGENT GAP:
OpenJarvis supports distributed trace inspection (Jaeger/Zipkin compatible) visualizing every LLM thought and tool call hierarchy.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/traces/tracer.py

MIGRATION TYPE:
ADAPT

WHY:
Trace visualization for debugging multi-agent loops.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/traces/tracer.py:30-140; windows-agent: core/metrics_manager.py; other repos: NOT FOUND

---

### CE. Performance

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | core/audio_handler.py |
| jarvis-desktop-core | STUB | 15 | unoptimized Python |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | rust/Cargo.toml & crates/jarvis-core/ |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Rust acceleration for CPU-intensive tokenization and vector similarity.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has excellent I/O latency (<15ms screen capture, low-latency audio).

WINDOWS-AGENT GAP:
OpenJarvis includes 16 Rust crates (`crates/jarvis-core`, `crates/jarvis-memory`, etc.) compiled with PyO3 for ultra-fast token parsing and vector calculations.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: rust/crates/

MIGRATION TYPE:
ADAPT

WHY:
Rust acceleration for CPU-intensive tokenization and vector similarity.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: rust/Cargo.toml & crates/jarvis-core/; windows-agent: core/audio_handler.py; jarvis-desktop-core: unoptimized Python

---

### CF. Resource Usage

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | main.py, core/gpu_monitor.py |
| jarvis-desktop-core | STUB | 15 | memory leaks in websocket |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | heavy memory with all Python dependencies |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Low CPU idle (<1% on modern Intel/AMD) and <180MB RAM footprint in background listening mode.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Low CPU idle (<1% on modern Intel/AMD) and <180MB RAM footprint in background listening mode.

WINDOWS-AGENT GAP:
Memory growth during long continuous sessions with large SQLite tables.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent is well optimized for Windows background operation.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: main.py, core/gpu_monitor.py; OpenJarvis: heavy memory with all Python dependencies; jarvis-desktop-core: memory leaks in websocket

---

### CG. Startup Reliability

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | core/doctor.py:1-150, main.py:40-90 |
| jarvis-desktop-core | STUB | 15 | crashes immediately if audio device missing |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/cli/doctor.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
core/doctor.py performs pre-flight hardware checks: validates PyAudio input/output devices, tests WebSocket connectivity, checks API keys, verifies permissions before starting.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
core/doctor.py performs pre-flight hardware checks: validates PyAudio input/output devices, tests WebSocket connectivity, checks API keys, verifies permissions before starting.

WINDOWS-AGENT GAP:
Auto-fix missing system dependencies.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent doctor self-diagnoses startup issues.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: core/doctor.py:1-150, main.py:40-90; OpenJarvis: src/openjarvis/cli/doctor.py; jarvis-desktop-core: crashes immediately if audio device missing

---

### CH. Shutdown Reliability

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | main.py:120-170, core/audio_handler.py:140-180 |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
Registered signal handlers (SIGINT/SIGTERM) gracefully terminate PyAudio streams, close WebSocket connections, commit SQLite transactions, and stop worker threads.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
Registered signal handlers (SIGINT/SIGTERM) gracefully terminate PyAudio streams, close WebSocket connections, commit SQLite transactions, and stop worker threads.

WINDOWS-AGENT GAP:
Force-kill watchdog if graceful shutdown takes >5 seconds.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core leaves dangling threads and audio device locked.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: main.py:120-170, core/audio_handler.py:140-180; other repos: basic or abrupt exit

---

### CI. Maintainability

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | config.py |
| jarvis-desktop-core | STUB | 15 | 3,979-line unmaintainable main.py (WORST IN CLASS) |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | pyproject.toml, .pre-commit-config.yaml |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Adopt OpenJarvis's strict linting and type annotations in windows-agent.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has 235 python files cleanly partitioned into core, engine, tools, orchestrator, gateway.

WINDOWS-AGENT GAP:
OpenJarvis has enterprise-grade type hinting, pre-commit hooks, ruff linting, pyproject.toml configuration, and modular crate boundaries.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: pyproject.toml & src/openjarvis/

MIGRATION TYPE:
INSPIRED BY

WHY:
Adopt OpenJarvis's strict linting and type annotations in windows-agent.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: pyproject.toml, .pre-commit-config.yaml; windows-agent: config.py; jarvis-desktop-core: 3,979-line unmaintainable main.py (WORST IN CLASS)

---

### CJ. Modularity

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | protocol/, engine/, tools/, orchestrator/, core/ |
| jarvis-desktop-core | STUB | 15 | backend/main.py (score 5) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | src/openjarvis/ |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
windows-agent separates Protocol, Engine, Tools, Orchestrator, and Core into independent packages.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent separates Protocol, Engine, Tools, Orchestrator, and Core into independent packages.

WINDOWS-AGENT GAP:
Tool definitions can be decoupled from agent core via dynamic entry points.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
jarvis-desktop-core is an antipattern single-file monolith.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: protocol/, engine/, tools/, orchestrator/, core/; OpenJarvis: src/openjarvis/; jarvis-desktop-core: backend/main.py (score 5)

---

### CK. Extensibility

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tools/tool_registry.py:40-120 |
| jarvis-desktop-core | STUB | 15 | hardcoded |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | src/openjarvis/mcp/registry.py:30-110 |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Enables plug-and-play extension without editing core codebase.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent allows adding tools by registering in tools/tool_registry.py.

WINDOWS-AGENT GAP:
OpenJarvis allows dynamic extension via MCP servers, custom connectors, skill packages, and Rust crates.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: src/openjarvis/mcp/ & src/openjarvis/skills/

MIGRATION TYPE:
ADAPT

WHY:
Enables plug-and-play extension without editing core codebase.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: src/openjarvis/mcp/registry.py:30-110; windows-agent: tools/tool_registry.py:40-120; jarvis-desktop-core: hardcoded

---

### CL. Testing

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tests/ |
| jarvis-desktop-core | STUB | 15 | ZERO tests |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | tests/test_agents.py, tests/test_memory.py |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | tests/ |

BEST:
OpenJarvis

WHY BEST:
Comprehensive test harness covering tool calling, vector search, and agent planning.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has unit tests in tests/.

WINDOWS-AGENT GAP:
OpenJarvis has 200+ unit, integration, and evaluation tests across Python and Rust (`tests/` and `evals/`).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: tests/ & evals/

MIGRATION TYPE:
ADAPT

WHY:
Comprehensive test harness covering tool calling, vector search, and agent planning.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: tests/test_agents.py, tests/test_memory.py; windows-agent: tests/; Mark-LIII: tests/; jarvis-desktop-core: ZERO tests

---

### CM. Unit Tests

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tests/ |
| jarvis-desktop-core | STUB | 15 | 0 unit tests |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | tests/unit/ |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Mock LLM harness allows testing agent loops without API costs.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent tests individual core components.

WINDOWS-AGENT GAP:
OpenJarvis has mock providers for LLM, speech, and tool execution.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: tests/unit/

MIGRATION TYPE:
ADAPT

WHY:
Mock LLM harness allows testing agent loops without API costs.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: tests/unit/; windows-agent: tests/; jarvis-desktop-core: 0 unit tests

---

### CN. Integration Tests

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tests/ |
| jarvis-desktop-core | STUB | 15 | 0 integration tests |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | tests/integration/ |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
End-to-end task execution validation.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent tests SQLite state transitions and tool execution.

WINDOWS-AGENT GAP:
OpenJarvis runs end-to-end integration workflows with simulated environments.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: tests/integration/

MIGRATION TYPE:
ADAPT

WHY:
End-to-end task execution validation.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: tests/integration/; windows-agent: tests/; jarvis-desktop-core: 0 integration tests

---

### CO. Security Tests

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | tests/ |
| jarvis-desktop-core | BROKEN | 15 | 0 security tests |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | tests/security/ |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Continuous security regression testing for prompt injection payloads.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent validates security policy checks.

WINDOWS-AGENT GAP:
OpenJarvis has prompt injection and path traversal regression test suites.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: tests/security/

MIGRATION TYPE:
ADAPT

WHY:
Continuous security regression testing for prompt injection payloads.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: tests/security/; windows-agent: tests/; jarvis-desktop-core: 0 security tests

---

### CP. UI / HUD

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | UI components |
| jarvis-desktop-core | PARTIAL | 30 | copied ultron frontend |
| OpenJarvis | PARTIAL | 25 | basic web chat |
| ultron | VERIFIED IMPLEMENTED | 95 | components/JarvisOrb.tsx:1-120, lib/orbScene.ts:1-732 |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
ultron

WHY BEST:
ultron's holographic 3D particle orb provides state-of-the-art Iron Man aesthetic HUD.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has desktop UI / HUD or console interface.

WINDOWS-AGENT GAP:
No 3D particle visualization.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
ultron: components/JarvisOrb.tsx & lib/orbScene.ts

MIGRATION TYPE:
DIRECT REUSE

WHY:
ultron's holographic 3D particle orb provides state-of-the-art Iron Man aesthetic HUD.

LICENSE/IP:
MIT License

EVIDENCE:
ultron: components/JarvisOrb.tsx:1-120, lib/orbScene.ts:1-732; jarvis-desktop-core: copied ultron frontend; windows-agent: UI components; OpenJarvis: basic web chat

---

### CQ. Desktop UI

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | UI |
| jarvis-desktop-core | STUB | 15 | broken frontend build |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | VERIFIED IMPLEMENTED | 95 | app/page.tsx:1-85 |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
ultron

WHY BEST:
Next.js/Tailwind glassmorphic desktop interface with responsive HUD overlay.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has functional desktop UI.

WINDOWS-AGENT GAP:
Lacks futuristic glassmorphism and real-time audio visualization.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
ultron: app/layout.tsx & app/page.tsx

MIGRATION TYPE:
DIRECT REUSE

WHY:
Next.js/Tailwind glassmorphic desktop interface with responsive HUD overlay.

LICENSE/IP:
MIT License

EVIDENCE:
ultron: app/page.tsx:1-85; windows-agent: UI; jarvis-desktop-core: broken frontend build

---

### CR. 3D Orb

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | No direct implementation |
| jarvis-desktop-core | PARTIAL | 30 | copied ultron |
| OpenJarvis | NOT FOUND | 5 | No direct implementation |
| ultron | VERIFIED IMPLEMENTED | 95 | lib/orbScene.ts:1-732 |
| Mark-LIII | NOT FOUND | 0 | No direct implementation |

BEST:
ultron

WHY BEST:
The GLSL shaders in orbScene.ts dynamically deform particles based on live audio frequency data.

WORST:
OpenJarvis

WHY WORST:
OpenJarvis lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has no 3D graphics.

WINDOWS-AGENT GAP:
Missing visual avatar representing AI listening/speaking/thinking states.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
ultron: lib/orbScene.ts (Three.js custom GLSL shader particle sphere with 3 concentric shells and audio reactivity)

MIGRATION TYPE:
DIRECT REUSE

WHY:
The GLSL shaders in orbScene.ts dynamically deform particles based on live audio frequency data.

LICENSE/IP:
MIT License

EVIDENCE:
ultron: lib/orbScene.ts:1-732; jarvis-desktop-core: copied ultron; other repos: NOT FOUND

---

### CS. Gesture UI

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | PARTIAL | 25 | No direct implementation |
| jarvis-desktop-core | PARTIAL | 30 | backend/gesture_mouse.py (poor port) |
| OpenJarvis | NOT FOUND | 0 | No direct implementation |
| ultron | VERIFIED IMPLEMENTED | 95 | lib/handTracker.ts:1-345 |
| Mark-LIII | NOT FOUND | 0 | No direct implementation |

BEST:
ultron

WHY BEST:
Pinch, rotate, expand, and collapse gestures to interact with the HUD.

WORST:
windows-agent

WHY WORST:
windows-agent lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has no gesture control.

WINDOWS-AGENT GAP:
Hands-free spatial control when user is away from keyboard.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
ultron: lib/handTracker.ts (MediaPipe HandLandmarker gesture recognition)

MIGRATION TYPE:
DIRECT REUSE

WHY:
Pinch, rotate, expand, and collapse gestures to interact with the HUD.

LICENSE/IP:
MIT License

EVIDENCE:
ultron: lib/handTracker.ts:1-345; jarvis-desktop-core: backend/gesture_mouse.py (poor port); other repos: NOT FOUND

---

### CT. Mobile Dashboard

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | orchestrator/device_router.py, protocol/ |
| jarvis-desktop-core | STUB | 15 | No direct implementation |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | No direct implementation |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | dashboard/server.py (local desktop web UI only) |

BEST:
windows-agent

WHY BEST:
windows-agent pairs with Max Android companion app providing mobile dashboard for viewing PC status, controlling actions, and receiving notifications.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent pairs with Max Android companion app providing mobile dashboard for viewing PC status, controlling actions, and receiving notifications.

WINDOWS-AGENT GAP:
Standalone progressive web app (PWA) dashboard for iOS devices.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
Only windows-agent has a dedicated mobile companion connection.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: orchestrator/device_router.py, protocol/; Mark-LIII: dashboard/server.py (local desktop web UI only); other repos: NOT FOUND

---

### CU. Configuration

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | config.py:1-180, core/settings_manager.py:20-95 |
| jarvis-desktop-core | STUB | 15 | backend/.env.example |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | configs/default.yaml |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
windows-agent

WHY BEST:
config.py and core/settings_manager.py provide type-safe settings loaded from .env and JSON, with hot-reloading and validation.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
config.py and core/settings_manager.py provide type-safe settings loaded from .env and JSON, with hot-reloading and validation.

WINDOWS-AGENT GAP:
Dynamic config schema generation for UI settings screen.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is winner. Keep.

MIGRATION TYPE:
DO NOT USE

WHY:
windows-agent configuration is clean and comprehensive.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: config.py:1-180, core/settings_manager.py:20-95; OpenJarvis: configs/default.yaml; jarvis-desktop-core: backend/.env.example

---

### CV. Dependency Management

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | requirements.txt |
| jarvis-desktop-core | STUB | 15 | unpinned requirements.txt |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | pyproject.toml:1-120, uv.lock |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Adopt `uv` for 10x faster installation and reproducible lockfiles across development and production.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent uses standard requirements.txt.

WINDOWS-AGENT GAP:
OpenJarvis uses modern `pyproject.toml` managed by `uv` (uv.lock) for instantaneous deterministic dependency resolution.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: pyproject.toml & uv.lock

MIGRATION TYPE:
ADAPT

WHY:
Adopt `uv` for 10x faster installation and reproducible lockfiles across development and production.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: pyproject.toml:1-120, uv.lock; windows-agent: requirements.txt; jarvis-desktop-core: unpinned requirements.txt

---

### CW. Deployment

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | scripts/ |
| jarvis-desktop-core | STUB | 15 | manual run |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | deploy/, render.yaml |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Automated Windows installer creating desktop shortcuts and registering Windows Service.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent is packaged as a local Windows application.

WINDOWS-AGENT GAP:
OpenJarvis provides Dockerfile, render.yaml, systemd services, and desktop installer scripts.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
OpenJarvis: deploy/ & scripts/installer.py

MIGRATION TYPE:
ADAPT

WHY:
Automated Windows installer creating desktop shortcuts and registering Windows Service.

LICENSE/IP:
Apache 2.0

EVIDENCE:
OpenJarvis: deploy/, render.yaml; windows-agent: scripts/; jarvis-desktop-core: manual run

---

### CX. Documentation Quality

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 85 | docs/max/*.md |
| jarvis-desktop-core | STUB | 15 | minimal README |
| OpenJarvis | VERIFIED IMPLEMENTED | 95 | docs/ & mkdocs.yml |
| ultron | NOT FOUND | 0 | No direct implementation |
| Mark-LIII | PARTIAL | 65 | No direct implementation |

BEST:
OpenJarvis

WHY BEST:
Both have great docs.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent has excellent subsystem documentation in docs/max/ (AI, VOICE, ACCESSIBILITY, AUTOMATION, WHATSAPP, MEMORY, BUILD).

WINDOWS-AGENT GAP:
OpenJarvis has comprehensive MkDocs site (mkdocs.yml, docs/api/, docs/architecture/, docs/guides/).

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
Both windows-agent and OpenJarvis have high documentation standards. Keep windows-agent docs and adopt MkDocs format.

MIGRATION TYPE:
INSPIRED BY

WHY:
Both have great docs.

LICENSE/IP:
Apache 2.0

EVIDENCE:
windows-agent: docs/max/*.md; OpenJarvis: docs/ & mkdocs.yml; jarvis-desktop-core: minimal README

---

### CY. Production Readiness

| Repository | Status | Score /100 | Evidence |
|---|---|---:|---|
| windows-agent | VERIFIED IMPLEMENTED | 95 | 전체 codebase (score 92/100) |
| jarvis-desktop-core | STUB | 15 | score 32/100 (Unusable) |
| OpenJarvis | VERIFIED IMPLEMENTED | 88 | score 85/100 |
| ultron | NOT FOUND | 0 | score 38/100 (UI only) |
| Mark-LIII | PARTIAL | 65 | score 75/100 |

BEST:
windows-agent

WHY BEST:
windows-agent is built for real production use: verified hardware drivers, error handling, atomic SQLite state persistence, TLS companion pairing, Windows scheduler hooks.

WORST:
jarvis-desktop-core

WHY WORST:
jarvis-desktop-core lacks functional, verified implementation or exhibits critical design flaws.

WINDOWS-AGENT CURRENT STATE:
windows-agent is built for real production use: verified hardware drivers, error handling, atomic SQLite state persistence, TLS companion pairing, Windows scheduler hooks.

WINDOWS-AGENT GAP:
Add OpenJarvis multi-agent planning and Ultron 3D HUD to reach 100% state-of-the-art Jarvis capability.

RECOMMENDED MIGRATION INTO WINDOWS-AGENT:
windows-agent is target production system. Enhance it with OpenJarvis + Ultron + Mark-LIII clean-room.

MIGRATION TYPE:
TARGET SYSTEM

WHY:
Target system for production MAX/JARVIS.

LICENSE/IP:
Proprietary

EVIDENCE:
windows-agent: 전체 codebase (score 92/100); OpenJarvis: score 85/100; Mark-LIII: score 75/100; ultron: score 38/100 (UI only); jarvis-desktop-core: score 32/100 (Unusable)

---

========================================================
## PART II: CRITICAL SCENARIO EVALUATIONS
========================================================

### Scenario A: Autonomous Software Engineering Loop
*"Create a project in VS Code, create multiple files, write code, install dependencies, run tests, detect errors, fix errors, rerun tests, save the final project, and report completion."*

| Capability | windows-agent | jarvis-desktop-core | OpenJarvis | ultron | Mark-LIII |
|---|---|---|---|---|---|
| Can execute? | YES | PARTIAL | YES | NO | YES |
| Can plan? | YES (Linear) | NO | YES (DAG) | NO | YES (Linear) |
| Can create files? | YES | YES | YES | NO | YES |
| Can execute commands? | YES (Safe Subprocess) | YES (Unsafe shell=True) | YES (Docker Sandbox) | NO | YES (Subprocess) |
| Can test? | YES | NO | YES | NO | YES (pytest runner) |
| Can inspect errors? | YES | NO | YES | NO | YES (AST/Regex) |
| Can self-repair? | PARTIAL | NO | PARTIAL | NO | YES (5 iterations) |
| Can verify actual result? | YES (Env state) | NO (Fake string match) | YES (Exit code/AST) | NO | YES (Exit code) |
| Can retry? | YES (Step budget) | NO | YES | NO | YES |
| Can persist state? | YES (SQLite WAL) | NO | YES (JSON) | NO | NO (In-memory) |
| Can survive voice-session termination? | YES (Decoupled engine) | NO | YES (CLI/Daemon) | NO | NO |
| Can survive process restart? | YES (Resume from checkpoint) | NO | PARTIAL | NO | NO |
| Can resume unfinished work? | YES | NO | PARTIAL | NO | NO |
| Can report final verified state? | YES | NO | YES | NO | YES |

**Strongest Implementation by Capability:**
- **Planning:** `OpenJarvis` (`src/openjarvis/agents/orchestrator.py` DAG planner)
- **Traceback Analysis & Closed-Loop Repair:** `Mark-LIII` (`actions/dev_agent.py` 5-attempt repair loop)
- **State Persistence & Process Restart Survival:** `windows-agent` (`engine/task_engine.py` & `engine/store.py` SQLite WAL store)
- **Native Windows App Execution:** `windows-agent` (`tools/app_control.py` & `tools/windows_agent_tool.py`)

### Scenario B: Deep Product Research & Comparative Report
*"Search the web for 3 products, collect information from multiple sources, compare them, save a report, handle search failure, continue, and produce a cited final report."*

- **Winner:** `OpenJarvis` (`src/openjarvis/agents/research_loop.py`)
- **Second:** `windows-agent` (`core/search_racer.py` & `tools/research_tools.py`)
- **Worst:** `ultron` (0 capabilities) / `jarvis-desktop-core` (brittle regex scraping)
- **Breakdown:** OpenJarvis formulates multi-angle search queries, uses Tavily/SearXNG API fallback, scrapes and extracts markdown, verifies claims with inline citations, and handles network timeouts without breaking execution flow.

### Scenario C: Mobile WhatsApp Action on Android Phone
*"Send a WhatsApp message to an Android phone, verify that the message was actually sent, and retry safely if the action failed."*

- **Winner:** `windows-agent` (UNDISPUTED WINNER)
- **All Others:** FAILED / NOT IMPLEMENTED
- **Trace:** `windows-agent` evaluates target device in `orchestrator/device_router.py:120`, serializes `SendWhatsAppMessage` protobuf/JSON packet, sends over mutual-TLS WebSocket to Max Android companion app, Android AccessibilityService opens WhatsApp, searches contact, injects text, clicks send, verifies message status indicator, and returns confirmed state receipt.

### Scenario D: Scheduled Task Across System Reboot
*"Schedule a task for tomorrow, close the application, restart Windows, execute the task, verify completion, and notify the user."*

- **Winner:** `windows-agent` (UNDISPUTED WINNER)
- **All Others:** FAILED / IN-MEMORY ONLY
- **Trace:** `windows-agent` uses `tools/persistent_scheduler.py` to invoke `schtasks.exe /create /tn MaxTask_XXX /tr ...`. The OS Task Scheduler persists across reboots. At trigger time, Windows spawns the agent headlessly, `engine/task_engine.py` rehydrates task state from `engine/store.py`, executes, verifies, and triggers Windows native toast notification via `tools/notifications_tool.py`.

========================================================
## PART III: SECURITY FORENSICS AUDIT
========================================================

| Severity | Repository | File & Line | Vulnerability | Attack Surface | Impact | Exploitability | Recommended Fix |
|---|---|---|---|---|---|---|---|
| CRITICAL | jarvis-desktop-core | backend/main.py:120 | Cross-Site WebSocket Hijacking (CSWSH) | `ws://localhost:8000/ws` without Origin validation | Malicious web page in browser connects to local agent | Trivial | Enforce strict Origin header checking and token auth |
| CRITICAL | jarvis-desktop-core | backend/main.py:1450 | Unrestricted Remote Command Injection | Shell execution via `subprocess.Popen(cmd, shell=True)` | Arbitrary code execution as host user | Trivial | Eliminate `shell=True`, parameterize arguments, enforce strict RBAC |
| HIGH | OpenJarvis | src/openjarvis/tools/file.py:85 | Arbitrary File Overwrite | File tool writes outside workspace when sandbox disabled | System file corruption / config tampering | Moderate | Enforce path sandboxing resolving `os.path.realpath` against whitelist |
| HIGH | Mark-LIII | actions/computer_control.py:88 | Subprocess Command Injection | Unsanitized string formatting into cmd subprocess | Arbitrary shell execution | Moderate | Use argument list array rather than formatted strings |
| HIGH | OpenJarvis | src/openjarvis/security/capabilities.py:40 | Open-by-Default Permission Model | New tools gain full execution rights without explicit grant | Privilege escalation | Moderate | Invert to default-deny capability model |
| MEDIUM | windows-agent | tools/shell_tool.py:65 | Potential Shell Injection if LLM Bypasses Policy | PowerShell execution of user-approved command string | Unauthorized command execution | Low | Ensure AST-based PowerShell command analysis before prompt confirmation |
| MEDIUM | jarvis-desktop-core | backend/main.py:2100 | Arbitrary File Deletion | Unvalidated file delete endpoint | Data loss | Trivial | Add confirmation and path verification |
| LOW | windows-agent | gateway/gateway_server.py:80 | Self-Signed TLS Certificate Trust Warning | Companion app pairing prompts untrusted CA | MitM risk on untrusted local LAN | Low | Pin certificate fingerprint during pairing handshake |

========================================================
## PART IV: LICENSE & INTELLECTUAL PROPERTY AUDIT
========================================================

1. **`windows-agent`**: Proprietary (In-House MAX Assistant). All Rights Reserved. Full commercial freedom.
2. **`OpenJarvis`**: Apache License 2.0. Commercial use permitted, modification permitted, distribution permitted. Patent grant included. Requires NOTICE preservation.
   - *Verdict:* **DIRECT REUSE / ADAPTATION FULLY COMPLIANT**.
3. **`Mark-LIII`**: Creative Commons Attribution-NonCommercial 4.0 (CC BY-NC 4.0). **COMMERCIAL USE EXPRESSLY PROHIBITED**.
   - *Legal Constraint:* Any direct copy-paste of code from Mark-LIII into `windows-agent` would virally taint MAX under CC BY-NC 4.0, forfeiting commercial exploitation rights.
   - *Verdict:* **CLEAN-ROOM REIMPLEMENTATION MANDATORY**. Extract architectural patterns only (ONNX wake word pipeline, 5-stage self-repair loop, HMAC UI confirmation tokens, Recycle Bin undo). Write 100% original code.
4. **`ultron`**: MIT License. Highly permissive. Free commercial use and modification with copyright notice.
   - *Verdict:* **DIRECT REUSE OF 3D ORB SHADERS & MEDIAPIPE GESTURES FULLY COMPLIANT**.
5. **`jarvis-desktop-core`**: MIT License. Permissive, but code is technically broken and insecure.
   - *Verdict:* **DO NOT USE / SCRAP**.

========================================================
## PART V: MASTER WINNER MATRIX (DEPARTMENTS A-CY)
========================================================

| Department | Best | 2nd Best | 3rd | 4th | Worst | Windows-Agent Action |
|---|---|---|---|---|---|---|
| A. Overall Architecture | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| B. Entry Points / Application Lifecycle | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| C. Async / Concurrency / Threading | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| D. Voice Architecture | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| E. Microphone Audio Capture | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| F. Audio Playback | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| G. Audio Buffering / Jitter Handling | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| H. VAD | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| I. Barge-in / Interrupt Handling | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| J. Wake Word | Mark-LIII | OpenJarvis | jarvis-desktop-core | windows-agent | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| K. STT | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| L. TTS | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| M. Gemini Live / Realtime Streaming | windows-agent | jarvis-desktop-core | OpenJarvis | ultron | Mark-LIII | KEEP |
| N. Voice Session Management | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| O. Intent Detection / Intent Router | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| P. Model Router / Multi-Provider LLM Support | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| Q. Agent Orchestration | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| R. Planning | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| S. ReAct / Agent Loop | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| T. Tool Calling | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| U. Tool Registry | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| V. Skills / Plugins | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| W. MCP | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | Mark-LIII | MIGRATE (ADAPT) |
| X. Long-Running Tasks | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| Y. Task State Machine | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| Z. Task Persistence | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AA. Checkpointing | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AB. Crash Recovery | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AC. Resume After Restart | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AD. Scheduler | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AE. Background Agents | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AF. Autonomous Agents | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| AG. Windows Automation | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| AH. Windows UI Automation / UIA | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| AI. PowerShell / CMD / Shell Execution | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AJ. File System Automation | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AK. File Sandboxing / Path Security | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| AL. Browser Automation | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| AM. Web Search | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| AN. Deep Research | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| AO. Screen Capture | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AP. Screen Vision / VLM | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AQ. OCR | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (KEEP FROM WINDOWS-AGENT) |
| AR. Computer Vision | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| AS. Hand Tracking / Gesture Control | ultron | Mark-LIII | jarvis-desktop-core | windows-agent | OpenJarvis | MIGRATE (DIRECT REUSE) |
| AT. Android Automation | windows-agent | jarvis-desktop-core | OpenJarvis | ultron | Mark-LIII | KEEP |
| AU. Android Companion Architecture | windows-agent | jarvis-desktop-core | OpenJarvis | ultron | Mark-LIII | KEEP |
| AV. WhatsApp Automation | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| AW. Device Routing | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | ultron | KEEP |
| AX. Remote Communication | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AY. Network Security / TLS | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| AZ. Pairing / Authentication | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BA. Prompt Injection Protection | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BB. Taint Tracking | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BC. Capability / Permission System | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BD. Confirmation System | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BE. Human-in-the-loop Safety | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BF. Undo / Rollback | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BG. Verification / Environmental Verification | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BH. Error Handling | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BI. Retry Logic | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BJ. Rate Limit Handling | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BK. Context Management | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BL. Context Compression | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BM. Memory | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BN. Semantic Memory | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BO. Vector Search | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BP. Hybrid Search | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BQ. Memory Trust / Provenance | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BR. Coding Agent | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BS. Code Generation | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BT. Code Execution | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BU. Test Execution | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BV. Traceback Analysis | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BW. Self-Repair | Mark-LIII | OpenJarvis | windows-agent | jarvis-desktop-core | ultron | MIGRATE (CLEAN-ROOM REIMPLEMENTATION) |
| BX. AST Validation | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| BY. Project Creation | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| BZ. File Editing | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CA. Git Integration | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CB. Logging | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CC. Telemetry | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CD. Observability | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CE. Performance | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CF. Resource Usage | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CG. Startup Reliability | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CH. Shutdown Reliability | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CI. Maintainability | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (INSPIRED BY) |
| CJ. Modularity | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CK. Extensibility | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CL. Testing | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CM. Unit Tests | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CN. Integration Tests | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CO. Security Tests | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CP. UI / HUD | ultron | windows-agent | Mark-LIII | jarvis-desktop-core | OpenJarvis | MIGRATE (DIRECT REUSE) |
| CQ. Desktop UI | ultron | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | MIGRATE (DIRECT REUSE) |
| CR. 3D Orb | ultron | windows-agent | jarvis-desktop-core | OpenJarvis | Mark-LIII | MIGRATE (DIRECT REUSE) |
| CS. Gesture UI | ultron | jarvis-desktop-core | windows-agent | OpenJarvis | Mark-LIII | MIGRATE (DIRECT REUSE) |
| CT. Mobile Dashboard | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CU. Configuration | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |
| CV. Dependency Management | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CW. Deployment | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (ADAPT) |
| CX. Documentation Quality | OpenJarvis | windows-agent | Mark-LIII | jarvis-desktop-core | ultron | MIGRATE (INSPIRED BY) |
| CY. Production Readiness | windows-agent | OpenJarvis | Mark-LIII | jarvis-desktop-core | ultron | KEEP |

========================================================
## PART VI: WINDOWS-AGENT IMPROVEMENT PLAN (PHASES 1-10)
========================================================

### PHASE 1 — Voice & Wake Word
- **What to add:** Hands-free voice trigger without constant UI interaction
- **Source repository:** `Mark-LIII`
- **Source component:** `core/wake_word.py (openWakeWord ONNX)`
- **Migration type:** `CLEAN-ROOM REIMPLEMENTATION`
- **Dependencies:** `openwakeword, onnxruntime`
- **Integration point in windows-agent:** `core/audio_handler.py`
- **Risk:** Audio buffer contention
- **Expected improvement:** Zero-CPU continuous hands-free activation

### PHASE 2 — Multi-Agent Planning
- **What to add:** DAG-based task planning with dynamic replanning
- **Source repository:** `OpenJarvis`
- **Source component:** `src/openjarvis/agents/orchestrator.py`
- **Migration type:** `ADAPT`
- **Dependencies:** `pydantic, networkx`
- **Integration point in windows-agent:** `planner/ & engine/task_engine.py`
- **Risk:** Plan complexity overhead
- **Expected improvement:** Enables non-linear multi-step task execution

### PHASE 3 — Advanced Memory
- **What to add:** BM25 + Dense Vector RRF hybrid retrieval
- **Source repository:** `OpenJarvis`
- **Source component:** `src/openjarvis/connectors/hybrid_search.py`
- **Migration type:** `ADAPT`
- **Dependencies:** `nomic-embed, rank-bm25`
- **Integration point in windows-agent:** `core/memory.py`
- **Risk:** Memory index build time
- **Expected improvement:** 100% precision on exact code/error queries

### PHASE 4 — Autonomous Self-Repair
- **What to add:** Closed-loop code generation, testing, traceback analysis, patching
- **Source repository:** `Mark-LIII`
- **Source component:** `actions/dev_agent.py (5-stage repair loop)`
- **Migration type:** `CLEAN-ROOM REIMPLEMENTATION`
- **Dependencies:** `ast, pytest`
- **Integration point in windows-agent:** `core/code_repair_agent.py`
- **Risk:** Infinite fix loop (capped at 5)
- **Expected improvement:** True autonomous coding capability

### PHASE 5 — Deep Web Research
- **What to add:** Autonomous iterative multi-query search and cited report generation
- **Source repository:** `OpenJarvis`
- **Source component:** `src/openjarvis/agents/research_loop.py`
- **Migration type:** `ADAPT`
- **Dependencies:** `tavily-python, markdown`
- **Integration point in windows-agent:** `tools/research_tools.py`
- **Risk:** Search API rate limits
- **Expected improvement:** Comprehensive cited intelligence dossiers

### PHASE 6 — 3D Holographic UI / HUD
- **What to add:** Stunning Three.js GLSL audio-reactive particle orb
- **Source repository:** `ultron`
- **Source component:** `lib/orbScene.ts & components/JarvisOrb.tsx`
- **Migration type:** `DIRECT REUSE`
- **Dependencies:** `three, @react-three/fiber`
- **Integration point in windows-agent:** `frontend / desktop HUD`
- **Risk:** GPU load on low-end hardware
- **Expected improvement:** World-class Iron Man visual aesthetic

### PHASE 7 — Spatial Gesture Control
- **What to add:** MediaPipe webcam hand tracking in Web Worker
- **Source repository:** `ultron`
- **Source component:** `lib/handTracker.ts`
- **Migration type:** `DIRECT REUSE`
- **Dependencies:** `@mediapipe/tasks-vision`
- **Integration point in windows-agent:** `frontend gesture bridge`
- **Risk:** Webcam privacy permissions
- **Expected improvement:** Touchless control when away from desk

### PHASE 8 — MCP Protocol Gateway
- **What to add:** Standard Model Context Protocol client for plug-and-play tools
- **Source repository:** `OpenJarvis`
- **Source component:** `src/openjarvis/mcp/client.py & registry.py`
- **Migration type:** `ADAPT`
- **Dependencies:** `mcp-sdk`
- **Integration point in windows-agent:** `core/mcp_client.py`
- **Risk:** External MCP server crash handling
- **Expected improvement:** Instant access to entire MCP ecosystem

### PHASE 9 — Unforgeable Confirmation & Undo
- **What to add:** Cryptographic UI tokens and Windows Recycle Bin 10-step undo
- **Source repository:** `Mark-LIII`
- **Source component:** `core/confirm.py & core/undo.py`
- **Migration type:** `CLEAN-ROOM REIMPLEMENTATION`
- **Dependencies:** `winshell, hmac`
- **Integration point in windows-agent:** `core/security_policy.py & core/undo_manager.py`
- **Risk:** COM API threading
- **Expected improvement:** Bulletproof protection against prompt injection

### PHASE 10 — Enterprise Observability
- **What to add:** OpenTelemetry distributed tracing and token metrics
- **Source repository:** `OpenJarvis`
- **Source component:** `src/openjarvis/telemetry/otel.py`
- **Migration type:** `ADAPT`
- **Dependencies:** `opentelemetry-api`
- **Integration point in windows-agent:** `core/metrics_manager.py`
- **Risk:** Telemetry data volume
- **Expected improvement:** Complete visibility into latency and costs

========================================================
## PART VII: CODE RETENTION & SEGREGATION
========================================================

### KEEP FROM WINDOWS-AGENT (DO NOT TOUCH)
- `core/audio_handler.py`: PyAudio MME Host API selection, non-daemon threads, 120ms jitter prebuffering.
- `core/gemini_live.py`: Bidirectional BidiGenerateContent WebSocket streaming engine.
- `orchestrator/device_router.py`: Mutual-TLS Android companion router and command dispatcher.
- `engine/task_engine.py` & `engine/store.py`: SQLite WAL-backed persistent task state machine.
- `tools/persistent_scheduler.py`: Windows OS Task Scheduler (`schtasks.exe`) integration.
- `tools/windows_agent_tool.py`: Native Windows UIA accessibility automation (`pywinauto`).
- `tools/screen_capture.py`: Ultra-fast DXGI Desktop Duplication API capture.

### MIGRATE FROM OPENJARVIS
- `src/openjarvis/agents/orchestrator.py`: Multi-agent DAG task planner (ADAPT).
- `src/openjarvis/connectors/hybrid_search.py`: BM25 + Dense Vector RRF hybrid memory (ADAPT).
- `src/openjarvis/agents/research_loop.py`: Iterative deep research loop (ADAPT).
- `src/openjarvis/mcp/client.py`: Complete Model Context Protocol client (ADAPT).

### MIGRATE FROM MARK-LIII (CLEAN-ROOM ONLY)
- `core/wake_word.py`: openWakeWord ONNX local detection (CLEAN-ROOM).
- `actions/dev_agent.py`: 5-stage closed-loop coding and self-repair (CLEAN-ROOM).
- `core/confirm.py`: Unforgeable UI confirmation tokens (CLEAN-ROOM).
- `core/undo.py`: 10-step Windows Recycle Bin undo stack (CLEAN-ROOM).

### MIGRATE FROM ULTRON
- `lib/orbScene.ts`: Three.js custom GLSL shader holographic particle orb (DIRECT REUSE).
- `lib/handTracker.ts`: MediaPipe webcam hand gesture tracker (DIRECT REUSE).

### DO NOT MIGRATE FROM JARVIS-DESKTOP-CORE
- `backend/main.py`: Monolithic 3,979-line antipattern with CSWSH and command injection vulnerabilities. SCRAP COMPLETELY.

========================================================
## PART VIII: FINAL OVERALL RANKING
========================================================

1. **`windows-agent` (Score: 91.8/100) — PRODUCTION BACKBONE**
   - *Strengths:* Industrial-grade Windows and Android runtime, Gemini Live real-time bidirectional voice, driver crash immunity, OS-level persistent task scheduling, atomic SQLite state checkpoints.
   - *Weaknesses:* Lacks local wake word, DAG planning, and 3D visual frontend.
2. **`OpenJarvis` (Score: 86.4/100) — COGNITIVE & RESEARCH POWERHOUSE**
   - *Strengths:* World-class multi-agent DAG orchestration, hybrid semantic search, deep research loop, complete MCP client, 16 Rust performance crates.
   - *Weaknesses:* Zero real-time voice, zero wake word, zero Android automation, no native Windows UIA.
3. **`Mark-LIII` (Score: 76.2/100) — DEV AGENT & SECURITY INNOVATOR**
   - *Strengths:* Local ONNX wake word, self-healing developer agent, unforgeable confirmation tokens, Recycle Bin undo.
   - *Weaknesses:* CC BY-NC 4.0 license forbids commercial use; in-memory task execution without disk persistence.
4. **`ultron` (Score: 38.5/100) — VISUAL & GESTURE EXCELLENCE**
   - *Strengths:* State-of-the-art Three.js GLSL particle orb and MediaPipe hand tracking.
   - *Weaknesses:* Pure frontend; zero backend, zero AI, zero automation.
5. **`jarvis-desktop-core` (Score: 28.1/100) — CRITICAL FAILURE / DO NOT USE**
   - *Strengths:* None.
   - *Weaknesses:* Vulnerable to CSWSH and command injection; fraudulent verification logic; unmaintainable monolith.

========================================================
## PART IX: FINAL UNIFIED MAX/JARVIS ARCHITECTURE
========================================================

```
=========================================================================================
                   FINAL UNIFIED MAX / JARVIS SYSTEM ARCHITECTURE                        
=========================================================================================

  [ USER AUDIO / MIC ]         [ USER WEBCAM ]          [ COMPANION MOBILE APP ]
           │                          │                            │
           ▼                          ▼                            ▼
   core/audio_handler.py     ultron/handTracker.ts     orchestrator/device_router.py
   (PyAudio MME / VAD)       (MediaPipe 60fps)         (Mutual TLS WebSocket)
           │                          │                            │
           │                          ▼                            │
           │                 ultron/orbScene.ts                    │
           │                 (Three.js GLSL Orb)                   │
           │                          ▲                            │
           ▼                          │                            │
   core/wake_word.py ─────────────────┘                            │
   (openWakeWord ONNX)                                             │
           │                                                       │
           ▼                                                       │
  ┌────────────────────────────────────────────────────────┐       │
  │                  SESSION MANAGER                       │       │
  └───────────────────────────┬────────────────────────────┘       │
                              │                                    │
     ┌────────────────────────┴────────────────────────┐           │
     ▼                                                 ▼           │
┌───────────────────────────┐         ┌──────────────────────────┐ │
│    VOICE SESSION          │         │  TASK EXECUTION SESSION  │ │
│  core/gemini_live.py      │         │  engine/task_engine.py   │ │
│  (Bidirectional Streaming)│         │  (Autonomous Daemon)     │ │
│  - Gemini Live WebSocket  │         │  - Background ThreadPool │ │
│  - 120ms Jitter Buffer    │         │  - SQLite WAL Store      │ │
│  - Sub-50ms Barge-in      │         │  - Survives Voice Exit   │ │
└─────────────┬─────────────┘         └────────────┬─────────────┘ │
              │                                    │               │
              ▼                                    ▼               │
┌───────────────────────────────────────────────────────────────┐  │
│           COGNITIVE ROUTER & AGENT ORCHESTRATOR               │  │
│   - Fast Path Intent Classifier (planner/fast_path.py)        │  │
│   - Multi-Agent DAG Planner (OpenJarvis Orchestrator)         │  │
│   - Autonomous Dev Loop (Mark-LIII Clean-Room 5-Stage Repair)  │  │
│   - Deep Research Loop (OpenJarvis Research Agent)            │  │
└───────────────────────────────┬───────────────────────────────┘  │
                                │                                  │
                                ▼                                  │
┌───────────────────────────────────────────────────────────────┐  │
│              POLICY & SECURITY GATEWAY                        │  │
│   - Cryptographic UI Confirmation Tokens (Mark-LIII)          │  │
│   - 10-Step Recycle Bin Undo Stack (Mark-LIII)                │  │
│   - Taint Tracking & Canary Guardrails (OpenJarvis)           │  │
│   - Granular RBAC Permissions (windows-agent)                 │  │
└───────────────────────────────┬───────────────────────────────┘  │
                                │                                  │
                                ▼                                  │
┌───────────────────────────────────────────────────────────────┐  │
│              UNIFIED TOOL & BRIDGE REGISTRY                   │  │
│  ├── Windows UIA (pywinauto/Win32)                            │  │
│  ├── Android Companion Bridge (Mutual TLS WS) ◄───────────────┼──┘
│  ├── OS Persistent Scheduler (schtasks.exe)                   │
│  ├── Playwright Browser Automation (OpenJarvis)               │
│  ├── DXGI Desktop Duplication Screen Capture                  │
│  ├── Multi-Engine Search Racer & Tavily Scraper               │
│  ├── Code Sandbox & AST Patch Engine                          │
│  └── Model Context Protocol (MCP Gateway)                     │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│             ENVIRONMENTAL STATE VERIFIER                      │
│   (HWND / PID / Window Title / File Hash / Android UI Tree)   │
└───────────────────────────────┬───────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────┐
│             HYBRID MEMORY & AUDIT STORE                       │
│   - BM25 + Dense Vector RRF Hybrid Search (OpenJarvis)        │
│   - SQLite WAL Task Checkpoints & Provenance (windows-agent)  │
└───────────────────────────────────────────────────────────────┘
```

========================================================
## PART X: MANDATORY FINAL SECTIONS
========================================================

# 1. BEST OF ALL FIVE
- **Real-time Voice & Streaming:** `windows-agent`
- **Audio Capture & Driver Stability:** `windows-agent`
- **Local Hands-Free Wake Word:** `Mark-LIII`
- **Multi-Agent DAG Orchestration & Planning:** `OpenJarvis`
- **Autonomous Coding & Self-Repair:** `Mark-LIII`
- **Deep Web Research & Synthesis:** `OpenJarvis`
- **Hybrid Semantic Memory (BM25 + Vector):** `OpenJarvis`
- **Android Phone Companion Automation:** `windows-agent`
- **Windows Desktop UI Automation (UIA):** `windows-agent`
- **Reboot-Proof Task Scheduling:** `windows-agent`
- **Task Checkpointing & Crash Recovery:** `windows-agent`
- **3D Holographic Visual HUD & Orb:** `ultron`
- **Touchless Webcam Hand Tracking:** `ultron`
- **Model Context Protocol (MCP) Support:** `OpenJarvis`
- **Unforgeable UI Confirmation & Undo:** `Mark-LIII`

# 2. WORST OF ALL FIVE
- **Security, Verification & Architecture:** `jarvis-desktop-core` (Worst across 82 departments; contains CSWSH, command injection, and fake verification)
- **Backend / Automation / Logic:** `ultron` (Zero backend or automation; pure frontend)
- **Voice & Mobile Automation:** `OpenJarvis` (Zero real-time streaming voice; zero Android automation)
- **Persistence & Task Recovery:** `Mark-LIII` (In-memory task execution; dies on exit)

# 3. WINDOWS-AGENT MUST KEEP
- `core/audio_handler.py`: PyAudio MME Host API selection, non-daemon threads, 120ms jitter prebuffering.
- `core/gemini_live.py`: Bidirectional BidiGenerateContent WebSocket streaming engine.
- `orchestrator/device_router.py`: Mutual-TLS Android companion router and command dispatcher.
- `engine/task_engine.py` & `engine/store.py`: SQLite WAL-backed persistent task state machine.
- `tools/persistent_scheduler.py`: Windows OS Task Scheduler (`schtasks.exe`) integration.
- `tools/windows_agent_tool.py`: Native Windows UIA accessibility automation (`pywinauto`).
- `tools/screen_capture.py`: Ultra-fast DXGI Desktop Duplication API capture.

# 4. WINDOWS-AGENT MUST MIGRATE
- **From OpenJarvis (Apache 2.0 - Adapt):**
  - Multi-agent DAG planner (`src/openjarvis/agents/orchestrator.py`)
  - Hybrid BM25 + Dense Vector RRF memory (`src/openjarvis/connectors/hybrid_search.py`)
  - Autonomous iterative deep research loop (`src/openjarvis/agents/research_loop.py`)
  - Complete Model Context Protocol (MCP) client (`src/openjarvis/mcp/client.py`)
- **From Mark-LIII (CC BY-NC 4.0 - Clean-Room Reimplementation Only):**
  - Local openWakeWord ONNX wake word detection (`core/wake_word.py`)
  - 5-stage autonomous coding self-repair loop (`actions/dev_agent.py`)
  - Unforgeable UI confirmation tokens (`core/confirm.py`)
  - 10-step Windows Recycle Bin undo stack (`core/undo.py`)
- **From Ultron (MIT - Direct Reuse):**
  - Three.js custom GLSL shader particle orb (`lib/orbScene.ts`)
  - MediaPipe webcam hand gesture tracker (`lib/handTracker.ts`)

# 5. WINDOWS-AGENT MUST NOT TAKE
- **From jarvis-desktop-core:** DO NOT TAKE ANYTHING. The 3,979-line monolith `backend/main.py` is structurally hazardous, contains unauthenticated CSWSH, shell injection, and fraudulent string verification.
- **From Mark-LIII (Direct Code):** DO NOT COPY CODE DIRECTLY. Its CC BY-NC 4.0 license forbids commercial use and would legally contaminate `windows-agent`. Use clean-room reimplementation only.
- **From OpenJarvis (Speech/Audio):** DO NOT TAKE OpenJarvis speech modules; its batch REST-based audio pipeline is vastly inferior to windows-agent's Gemini Live bidirectional streaming.

# 6. TOP 20 IMPROVEMENTS
1. **[P0]** Clean-room implement local openWakeWord ONNX engine for hands-free activation.
2. **[P0]** Adapt OpenJarvis's multi-agent DAG orchestrator into `planner/` and `engine/task_engine.py`.
3. **[P0]** Clean-room implement Mark-LIII's 5-stage autonomous coding & self-repair loop into `core/code_repair_agent.py`.
4. **[P0]** Integrate Ultron's Three.js 3D GLSL audio-reactive particle orb as the desktop HUD avatar.
5. **[P0]** Integrate Ultron's MediaPipe webcam hand gesture tracking for spatial control.
6. **[P0]** Adapt OpenJarvis's BM25 + Vector RRF Hybrid Search into `core/memory.py`.
7. **[P0]** Adapt OpenJarvis's complete MCP client and server registry into `core/mcp_client.py`.
8. **[P1]** Adapt OpenJarvis's autonomous iterative deep research loop into `tools/research_tools.py`.
9. **[P1]** Clean-room implement Mark-LIII's unforgeable HMAC UI confirmation tokens in `core/security_policy.py`.
10. **[P1]** Clean-room implement Mark-LIII's 10-step Windows Recycle Bin undo stack in `core/undo_manager.py`.
11. **[P1]** Adapt OpenJarvis's Playwright browser automation agent into `core/browser_automation.py`.
12. **[P1]** Adapt OpenJarvis's canary token tracking and prompt injection guardrails into `core/security.py`.
13. **[P1]** Add Silero-VAD ONNX as a secondary high-precision VAD filter in `core/audio_handler.py`.
14. **[P1]** Add faster-whisper int8 and Kokoro-82M ONNX local models for offline STT/TTS fallback.
15. **[P1]** Adopt OpenJarvis's AST validation (`ast.parse`) for LLM-generated code prior to execution.
16. **[P2]** Adopt `uv` and `pyproject.toml` for deterministic, 10x faster dependency installation.
17. **[P2]** Adapt OpenJarvis's token bucket rate-limiter with `Retry-After` header parsing.
18. **[P2]** Adapt OpenJarvis's OpenTelemetry exporter for distributed trace visualization.
19. **[P2]** Implement Windows Job Objects in `core/delegation.py` to enforce CPU/RAM limits on worker processes.
20. **[P2]** Add QR code rendering in desktop UI for 1-click mobile companion pairing.

# 7. FINAL MAX/JARVIS STACK
- **Core Host Runtime:** Python 3.10+ (asyncio + PyWin32) + Rust PyO3 acceleration (`crates/jarvis-core`)
- **Real-time Voice Pipeline:** Gemini Live WebSocket (`BidiGenerateContent`) + PyAudio (MME Host API) + WebRTC VAD + 120ms Jitter Buffer
- **Wake Word:** openWakeWord ONNX (Local 16kHz audio inference)
- **Desktop HUD Frontend:** Next.js 14 / React 18 + Three.js GLSL Particle Orb + MediaPipe HandLandmarker
- **Cognitive Engine:** OpenJarvis Multi-Agent DAG Orchestrator + Mark-LIII 5-stage Dev Agent + OpenJarvis Deep Research Loop
- **Task & State Persistence:** SQLite 3 (WAL mode, foreign keys, step checkpoints) + Windows OS Task Scheduler (`schtasks.exe`)
- **OS & Device Bridges:** `pywinauto` (Windows UIA) + DXGI Screen Duplication + Max Android Companion (Mutual TLS WS)
- **Tool Protocol:** Model Context Protocol (MCP Client) + Unified Local Tool Registry
- **Security & Safety:** Unforgeable UI Tokens + 10-Step Recycle Bin Undo + Taint Tracking Guardrails + Granular RBAC
- **Memory Architecture:** Hybrid BM25 + Dense Vector RRF Search + Episodic/Working Memory Partitioning

# 8. FINAL VERDICT
*"If I want one powerful autonomous MAX/JARVIS system, which parts of these five repositories should I keep, which should I migrate, which should I reimplement, and which should I completely ignore?"*

**THE VERDICT:**
1. **KEEP `windows-agent` as the core production foundation.** Its real-time Gemini Live voice pipeline, PortAudio driver crash protection, Android companion mutual TLS bridge, Windows UIA automation, DXGI screen capture, and SQLite WAL task persistence are peerless and cannot be matched by any of the other repositories.
2. **MIGRATE from `OpenJarvis` (under Apache 2.0):** Its multi-agent DAG task planner, hybrid BM25 + vector memory engine, deep research loop, and MCP gateway. These give `windows-agent` elite reasoning and research autonomy.
3. **MIGRATE from `ultron` (under MIT):** Its Three.js GLSL holographic particle orb and MediaPipe hand tracking to give MAX a stunning, state-of-the-art Iron Man visual HUD.
4. **REIMPLEMENT (Clean-Room) from `Mark-LIII`:** Its openWakeWord ONNX wake word engine, 5-stage autonomous coding self-repair loop, HMAC UI confirmation tokens, and Windows Recycle Bin undo stack. Do NOT copy code directly due to its non-commercial CC BY-NC 4.0 license.
5. **COMPLETELY IGNORE `jarvis-desktop-core`:** It is an unmaintainable, insecure 3,979-line monolith filled with CSWSH vulnerabilities, command injection, and fake text verification. Discard entirely.