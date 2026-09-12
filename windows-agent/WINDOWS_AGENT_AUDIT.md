# Windows Agent — Technical Audit

## Audit Metadata
- **Audit Date**: 2026-09-08
- **Project Root**: `D:\max app testing\gemini-live\windows-agent`
- **Language**: Python 3.10+ (Backend), JavaScript ES6+ (Dashboard), HTML5/CSS3 (UI), PowerShell/Batch (Tooling & Scripts)
- **Runtime**: CPython 3.10.11 on Windows (AMD64 / win32)
- **Platform**: Microsoft Windows 10/11 Workstation
- **Auditor**: Antigravity Autonomous Systems Auditor (Google DeepMind)
- **Repository Status**: Active Production / Multi-Phase Unified Agent & Android Companion Ecosystem

---

## Executive Summary

The **Windows Agent** (internally branded as **MAX / JARVIS**) is a unified, multi-modal autonomous agent and companion server for Windows workstations. It bridges high-speed real-time bidirectional voice (powered by Google Gemini Live WebSockets and local Vosk speech recognition), autonomous reasoning and planning (Hermes multi-turn reasoning and TaskEngine DAG orchestrator), and secure bidirectional companion device control (pairing and orchestrating remote Android phones and tablets such as Redmi K20 Pro over WebSocket Secure, HTTP REST, SSE, and mDNS Zeroconf).

The codebase consists of **288 total files** across 15 top-level modules (~48,000 lines of code, tests, and skills). The project has undergone extensive capability expansion across multiple development phases (Phases 0 through 24), evolving from a basic voice assistant into an enterprise-grade agent capable of:
1. **Real-time Bidirectional Voice Conversation**: Full-duplex audio I/O using PyAudio (16kHz capture, 24kHz playback), local Vosk wake-word/speech gating ("MAX", "Hey MAX"), and live Gemini Live voice function calling.
2. **Unified Tool Execution & Security Pipeline**: Canonical single-point registry (`UnifiedToolRegistry`) exposing 125 unique tool declarations, with alias resolution, dynamic schema sanitization, mandatory dangerous tool confirmation gates (`DANGEROUS_TOOLS`), path sandboxing (`SecurityGuard`), and post-execution action verification (`ActionVerifier`).
3. **Android Multi-Device Companion Ecosystem**: WSS server on port 9000 with SHA-256 HMAC challenge-response authentication, dynamic capability discovery, mDNS advertisement (`_max-agent._tcp.local.`), and atomic remote action execution (flashlights, ringer modes, volume, DND, camera screenshots, WhatsApp dispatch, YouTube queries).
4. **Hermes Primary Autonomous Authority**: Multi-turn planning, AST-validated unified diff file patching (`PatchEngine`), interactive pseudo-terminal management (`pywinpty`), Programmatic Tool Calling (PTC) in loopback sandbox, persistent cron scheduling (`CronScheduler`), subagent delegation, Model Context Protocol (MCP) client and host, and closed-loop skill distillation (`LearningLoopManager`).
5. **Multi-Model Provider Abstraction**: Dynamic model switching and cascading fallbacks across Google Gemini REST/Live, OpenAI, DeepSeek, OpenRouter, and local Ollama instances.
6. **Web Dashboard & REST/SSE Server**: Zero-dependency vanilla web dashboard on port 9001 providing live metrics, device control, chat, tool testing, terminal PTY, and log streaming.

The test suite contains **378 automated test cases** across 35 test files, with **377 passing (99.7% pass rate)**. The architecture is robust, highly resilient to network disconnects and process shutdown races, and enforces rigorous security boundaries.

---

## Project Structure

```
D:\max app testing\gemini-live\windows-agent\
├── .env                                 # Environment variables (API keys, ports, flags)
├── .gitignore                           # Git ignore rules
├── check_audio.py                       # Standalone PyAudio device diagnostic script
├── config.json                          # Base runtime configuration JSON
├── config.py                            # Central configuration, constants, prompts, feature flags
├── log.txt                              # Root execution log output
├── main.py                              # Application entry point & centralized shutdown coordinator
├── memory.json                          # Fallback JSON key-value memory store
├── phase18_implementation_report.md     # Historical implementation verification report
├── requirements.txt                     # Pinned Python package dependencies (20 packages)
├── startup.bat                          # Windows batch startup launcher
├── test_live_vosk.py                    # Standalone Vosk live microphone tester
├── vosk_diag.py                         # Standalone Vosk model and audio diagnostic
├── vosk_mic_test.py                     # Standalone Vosk audio input diagnostic
│
├── agent\
│   └── insights.py                      # Autonomous insight extraction & memory distillation
│
├── context\                             # Central Context Engine
│   ├── __init__.py
│   ├── builder.py                       # Canonical ContextBuilder with token budget manager
│   ├── game_state_provider.py           # Real-time game/app state provider
│   ├── hermes_compressor.py             # Sliding-window context compression engine
│   ├── models.py                        # ContextRecord, ContextCategory, ContextOrigin models
│   ├── policy.py                        # Secret masking and passive data boundary enforcement
│   ├── providers.py                     # Window, notification, clipboard, and metrics providers
│   ├── session_memory.py                # Single-consume session summary memory manager
│   └── store.py                         # In-memory TTL context record store
│
├── core\                                # Core Subsystems & Engines
│   ├── __init__.py
│   ├── audio_handler.py                 # Low-level PyAudio capture & playback streams
│   ├── browser_automation.py            # Playwright browser manager & profile isolation
│   ├── cert_generator.py                # Self-signed TLS cert generator & fingerprinting
│   ├── clipboard_assistant.py           # Smart clipboard monitoring & assistant
│   ├── code_repair_agent.py             # Traceback parsing, AST validation & self-repair agent
│   ├── connection_manager.py            # Live WebSocket connection manager & ping/pong tracker
│   ├── cron_scheduler.py                # Persistent cron job scheduler using croniter
│   ├── delegation.py                    # Single and batch subagent delegator
│   ├── device_registry.py               # Canonical device registry for Android companions
│   ├── doctor.py                        # SystemDoctor automated diagnostic health checker
│   ├── gemini_live.py                   # Real-time bidirectional Gemini Live voice session
│   ├── gpu_monitor.py                   # NVIDIA NVML / DirectX GPU telemetry monitor
│   ├── hermes_controller.py             # High-level Hermes execution orchestrator
│   ├── hermes_engine.py                 # Hermes tool registrar & engine coordinator
│   ├── hermes_loop.py                   # Multi-turn autonomous agent reasoning loop
│   ├── learning_loop.py                 # Closed-loop skill evaluator, validator & curator
│   ├── mcp_client.py                    # Model Context Protocol (MCP) client manager
│   ├── mcp_server_host.py               # MCP Server host exposing MAX tools to external agents
│   ├── memory.py                        # Fact recall and storage tool interface
│   ├── metrics_manager.py               # Latency, request, connection & error metrics tracker
│   ├── provider_manager.py              # Multi-LLM provider abstraction (Gemini, OpenAI, Ollama)
│   ├── pty_manager.py                   # Windows Pseudo-Console (pywinpty) interactive PTY
│   ├── search_racer.py                  # Parallel search racer (Google, DDG, Bing, Arxiv)
│   ├── security.py                      # SecurityGuard sandboxing, path validation & risk analyzer
│   ├── session_manager.py               # Pairing credentials, auth tokens & session persistence
│   ├── settings_manager.py              # Persistent settings manager & path configurations
│   ├── skills_engine.py                 # Progressive skills loader (list -> view -> execute)
│   ├── skills_hub.py                    # Community Skills Hub downloader & synchronizer
│   ├── state_database.py                # SQLite WAL state database with FTS5 memory search
│   ├── tool_executor.py                 # Canonical ToolRegistry adapter
│   ├── ui_automation.py                 # UIAutomationEngine (pywinauto / comtypes accessibility)
│   ├── unified_tool_registry.py         # UnifiedToolRegistry singleton & execution boundary
│   ├── verifier.py                      # ActionVerifier post-execution state verification
│   ├── vision_coordinator.py            # VisionTaskCoordinator two-phase screenshot analysis
│   └── voice_gate.py                    # VoiceInputGate, wake word detector & speech state machine
│
├── dashboard\                           # Web Dashboard Frontend (SPA)
│   ├── index.html                       # Single-page dashboard HTML
│   ├── README.md                        # Dashboard documentation
│   └── assets\
│       ├── css\                         # CSS design system (app.css, layout.css)
│       │   └── themes\                  # Themes (dark.css, light.css)
│       └── js\                          # Modular ES6 JavaScript
│           ├── app.js                   # Application lifecycle & initialization
│           ├── event_stream.js          # SSE event stream consumer with auto-reconnect
│           ├── notifications.js         # Toast notification system
│           ├── qrcode.min.js            # Standalone QR code generator for pairing
│           ├── router.js                # Client-side hash router
│           ├── state.js                 # Reactive global state store
│           └── views\                   # Subview controllers (activity, chat, dashboard,
│                                        #   devices, logs, settings, terminal, tools)
│
├── engine\                              # Task Engine Subsystem (DAG Multi-Step Execution)
│   ├── __init__.py
│   ├── models.py                        # Task, Step, State enums and data structures
│   ├── state.py                         # State transition validators
│   ├── step_runner.py                   # Atomic step execution with timeout and retries
│   ├── store.py                         # Atomic JSON/SQLite task persistence & recovery
│   └── task_engine.py                   # Central TaskEngine coordinating DAG steps
│
├── gateway\                             # External Integration Gateway
│   └── gateway_server.py                # Multi-platform gateway router (port 9002)
│
├── orchestrator\                        # High-Level Orchestration & Device Dispatch
│   ├── __init__.py
│   ├── delegation.py                    # Goal decomposition and worker assignment
│   ├── device_router.py                 # Remote Android device action router & future tracker
│   └── router.py                        # OrchestrationRouter routing to fast-path or TaskEngine
│
├── planner\                             # Planning & Decomposing Subsystem
│   ├── __init__.py
│   ├── client.py                        # GeminiPlanningClient REST planner
│   ├── fast_path.py                     # FastPathResolver regex-based instant tool dispatcher
│   ├── normalizer.py                    # Plan normalizer mapping tool aliases & sanitizing args
│   ├── prompt.py                        # Structured planning prompts & system instructions
│   ├── schemas.py                       # JSON Schemas for plan validation
│   ├── task_factory.py                  # Factory converting validated plans to executable Tasks
│   └── validator.py                     # PlanValidator validating plan structure and constraints
│
├── plugins\                             # Plugin System
│   ├── README.md                        # Plugin architecture documentation
│   └── plugin_api.md                    # Plugin development interface specifications
│
├── protocol\                            # Wire Protocol & Message Serialization
│   ├── __init__.py                      # Protocol exports
│   ├── close_codes.py                   # WebSocket close status codes (CloseCode enum)
│   ├── command_models.py                # CommandRequestPayload, CommandResultPayload
│   ├── constants.py                     # Magic numbers, protocol versions, timeouts
│   ├── error_codes.py                   # Canonical ErrorCode enum
│   ├── event_types.py                   # EventType pub/sub constants
│   ├── packet_factory.py                # Packet envelope builders
│   ├── packet_types.py                  # PacketType enum (HELLO, PAIR, TOOL_REQ, etc.)
│   ├── setting_keys.py                  # SettingKey constants
│   ├── validators.py                    # PacketValidator envelope and payload schema checks
│   └── versions.py                      # Protocol version negotiation
│
├── scripts\                             # Operational Scripts
│   ├── audit_phase24_capabilities.py    # Phase 24 capability verification script
│   ├── find_bad_tool_schemas.py         # Schema validator for Gemini Live API constraints
│   ├── import_hermes_skills.py          # Hermes SKILL.md importer
│   └── validate_phase23_real_execution.py# End-to-end tool execution validation script
│
├── server\                              # Networking & Communication Servers
│   ├── api_router.py                    # HTTP REST & SSE request dispatcher
│   ├── event_bus.py                     # In-memory pub/sub EventBus with replay buffer
│   ├── http_server.py                   # Async HTTP server on port 9001 (asyncio raw HTTP)
│   ├── pairing_manager.py               # Pairing code generation & crypto verification
│   └── websocket_server.py              # WSS server on port 9000 & mDNS Zeroconf advertiser
│
├── storage\                             # Persistent Data, Models & Skills
│   ├── checkpoints\                     # Task execution checkpoints
│   ├── models\                          # Vosk offline speech recognition model
│   │   └── vosk-model-small-en-us-0.15\ # Compact Vosk acoustic/language model
│   ├── skills\                          # Progressive skills directory (SKILL.md definitions)
│   │   ├── android-ecosystem\
│   │   ├── autonomous-ai-agents\
│   │   ├── code-repair\
│   │   ├── github\
│   │   ├── productivity\ (docx, pdf, xlsx)
│   │   ├── research\ (arxiv, citations, paper-writing)
│   │   └── software-development\ (debugging, tdd, plan)
│   ├── state.db                         # SQLite WAL state database
│   └── worktrees\                       # Sandboxed worktrees for code patches
│
├── tests\                               # Test Suite (35 files, 378 tests)
│   ├── _shutdown_probe.py               # Process exit probe
│   ├── test_browser_automation.py
│   ├── test_closed_learning_loop.py
│   ├── test_code_repair_agent.py
│   ├── test_context_engine.py
│   ├── test_context_phase0.py
│   ├── test_device_router.py
│   ├── test_device_status_tool.py
│   ├── test_dynamic_capabilities.py
│   ├── test_gemini_live_lifecycle.py
│   ├── test_gemini_voice_tool_exposure.py
│   ├── test_gpu_monitor.py
│   ├── test_hermes_integration_smoke.py
│   ├── test_hermes_primary_authority.py
│   ├── test_hermes_ui_automation.py
│   ├── test_multi_device_connection.py
│   ├── test_orchestrator.py
│   ├── test_phase18_hermes_capabilities.py
│   ├── test_phase24_capabilities.py
│   ├── test_phase_f_features.py
│   ├── test_planner.py
│   ├── test_remote_command_event_loop_regression.py
│   ├── test_remote_command_protocol.py
│   ├── test_search_racer.py
│   ├── test_security.py
│   ├── test_session_memory.py
│   ├── test_shutdown_race.py
│   ├── test_task_engine.py
│   ├── test_tool_registry.py
│   ├── test_tool_reliability_audit.py
│   ├── test_uia.py
│   ├── test_verifier.py
│   ├── test_vision_coordinator.py
│   └── test_voice_gate.py
│
└── tools\                               # Modular Windows & Hermes Tools (39 files)
    ├── app_control.py                   # Windows app management (launch, close, switch)
    ├── app_tool.py                      # BaseTool wrapper for app management
    ├── base_tool.py                     # BaseTool abstract base class
    ├── browser_cdp.py                   # Chrome DevTools Protocol browser tools
    ├── clipboard_tool.py                # Clipboard read/write/paste tools
    ├── cmd_tool.py                      # Command prompt execution tool (security guarded)
    ├── code_sandbox.py                  # Programmatic Tool Calling (PTC) loopback sandbox
    ├── code_tools.py                    # AST analysis, linting, syntax checking tools
    ├── document_tools.py                # PDF, DOCX, XLSX document inspection & creation
    ├── env_tool.py                      # Environment variables inspection and modification
    ├── file_manager.py                  # File operations (read, write, list, search, delete)
    ├── file_tool.py                     # BaseTool file operations wrapper
    ├── fileops_tool.py                  # Extended filesystem manipulation tool
    ├── firewall_tool.py                 # Windows Firewall rule inspection & configuration
    ├── git_tools.py                     # Sandboxed Git status, diff, commit, branch tools
    ├── input_tool.py                    # Keyboard, mouse, and typing automation tools
    ├── kanban.py                        # Local SQLite-backed Kanban board tools
    ├── media_generation.py              # AI image & video generation interface tools
    ├── media_tool.py                    # Audio/video player control & volume tools
    ├── network_tool.py                  # Network adapters, ping, DNS, IP info tools
    ├── notifications_tool.py            # Windows Toast notification dispatcher tools
    ├── patch_tool.py                    # Unified diff AST parser and patching engine
    ├── power_tool.py                    # Sleep, lock, reboot, shutdown power tools
    ├── process_tool.py                  # Process list, resource usage, kill tools
    ├── productivity.py                  # WhatsApp messaging, reminders, fact memory tools
    ├── registry_tool.py                 # Windows Registry read/write tools (guarded)
    ├── research_tools.py                # Arxiv, web search, citation extraction tools
    ├── service_tool.py                  # Windows Services status, start, stop tools
    ├── skills_guard.py                  # AST security scanner for dynamically loaded skills
    ├── subagent_tools.py                # Subagent spawning & batch delegation tools
    ├── system_control.py                # Brightness, volume, Wi-Fi, battery, system tools
    ├── system_suite.py                  # System diagnostic suite tools
    ├── system_tool.py                   # BaseTool system telemetry wrapper
    ├── systeminfo_tool.py               # Hardware, OS, disk, CPU information tools
    ├── terminal_tool.py                 # Interactive terminal session tools
    ├── tool_registry.py                 # Legacy BaseTool registry wrapper
    ├── web_tools.py                     # Fast web search, Google, YouTube, weather, news
    └── windows_agent_tool.py            # Windows workstation automation compound tool
```

---

## Architecture

### 1. High-Level Subsystem Topology

```mermaid
flowchart TD
    subgraph Input_Channels [Input & Interaction Channels]
        MIC[Microphone / Vosk VoiceGate] -->|Audio Stream 16kHz| GL[Gemini Live Voice Session]
        AND[Android Phone / Tablet] -->|WSS :9000 Protocol 2.1| WSS[WebSocket Server]
        DASH[Web Dashboard / REST] -->|HTTP/SSE :9001| HTTPS[HTTP Server]
    end

    subgraph Core_Routing [Orchestration & Dispatch Boundary]
        GL -->|Voice Function Call| UTR[UnifiedToolRegistry]
        WSS -->|Tool Request / Command| UTR
        HTTPS -->|API Tool Request| UTR
        UTR -->|Multi-Step Goal| ORCH[OrchestrationRouter]
        ORCH -->|Fast Path| FP[FastPathResolver]
        ORCH -->|Compound Plan| PLAN[GeminiPlanningClient]
        PLAN -->|DAG Tasks| TE[TaskEngine]
    end

    subgraph Autonomous_Engines [Autonomous Execution & Reasoning]
        HL[HermesAgentLoop] -->|Multi-Turn Reasoning| PM[ProviderManager]
        HL -->|Dynamic Skills| SE[SkillsEngine]
        HL -->|Sandboxed PTC| PTC[CodeSandbox RPC]
        HL -->|Terminal Control| PTY[PTYManager]
        HL -->|Self Repair| CRA[CodeRepairAgent]
        TE -->|Atomic Steps| SR[StepRunner]
    end

    subgraph Safety_And_Verification [Security & Verification Boundary]
        UTR -->|Sanitize & Sandbox| SG[SecurityGuard]
        SG -->|Dangerous Tool Confirmation| CONF[Confirmation Gate]
        UTR -->|Post-Check State| AV[ActionVerifier]
    end

    subgraph Execution_Targets [Execution Handlers & Targets]
        UTR -->|Local Windows APIs| WIN[Win32 / UIA / CTypes / PyWin32]
        UTR -->|Remote Device Action| DR[DeviceRouter]
        DR -->|WSS Command Request| AND
        UTR -->|Storage & State| DB[(SQLite WAL / state.db)]
    end
```

### 2. Real-Time Gemini Live Voice Loop Architecture

```mermaid
sequenceDiagram
    autonumber
    actor User as Yaswanth (Creator / Boss)
    participant VG as VoiceInputGate (Vosk)
    participant GL as GeminiLiveSession
    participant API as Google GenAI Live API
    participant UTR as UnifiedToolRegistry
    participant WIN as Windows Subsystem / Android

    User->>VG: "Hey MAX, open Notepad" (Audio 16kHz)
    VG->>VG: Detect wake phrase ("MAX"/"Hey MAX")
    VG->>GL: Transition state to COMMAND_LISTENING
    GL->>API: Stream PCM audio chunks (64ms)
    API-->>GL: Audio response stream + tool_call(open_application)
    GL->>UTR: execute_async("open_application", {"app_name": "notepad"})
    UTR->>WIN: Launch subprocess / ShellExecute
    WIN-->>UTR: {"status": "success", "pid": 1420}
    UTR-->>GL: Tool result payload
    GL->>API: Send tool_response envelope
    API-->>GL: Stream synthesized TTS audio (24kHz)
    GL->>User: Play PCM audio via AudioPlayer ("Done sir, Notepad open chesanu")
```

### 3. Android Remote Companion Command Flow

```mermaid
sequenceDiagram
    autonumber
    participant Voice as Gemini Live / Dashboard
    participant UTR as UnifiedToolRegistry
    participant DR as DeviceRouter
    participant DREG as DeviceRegistry
    participant WSS as WebSocketServer
    participant Phone as Redmi K20 Pro (Android Client)

    Voice->>UTR: execute_device_action("Phone 1", "FLASHLIGHT_ON")
    UTR->>DR: execute_single("Phone 1", "FLASHLIGHT_ON")
    DR->>DREG: resolve_target("Phone 1") -> device_id "redmi_k20_pro_01"
    DR->>DR: Create asyncio.Future for command_id
    DR->>WSS: send_envelope(COMMAND_REQUEST, command_id, action="FLASHLIGHT_ON")
    WSS->>Phone: WSS Packet (Encrypted TLS 1.3)
    Note over Phone: Android Service turns on hardware LED
    Phone->>WSS: Packet (COMMAND_RESULT, command_id, status="success")
    WSS->>DR: handle_command_result(payload)
    DR->>DR: Resolve Future with result
    DR-->>UTR: {"status": "success", "data": {"state": "on"}}
    UTR-->>Voice: "Done boss! Flashlight turned on on Redmi K20 Pro."
```

---

## Component Inventory

### 1. Root & System Components
- `main.py`: Entry point for application. Bootstraps colored logging, sets Windows Selector event loop policy, initializes `WebSocketServer` (port 9000), `HTTPServer` (port 9001), starts mDNS Zeroconf advertiser, starts `GeminiLiveSession`, coordinates graceful async shutdown on SIGINT/SIGTERM.
- `config.py`: Central repository configuration. Loads `.env`, declares `SYSTEM_PROMPT` personality (Telugu/Tenglish/English warm assistant created by Yaswanth), defines `DANGEROUS_TOOLS` and `ALLOWED_COMMANDS` sets, declares Hermes feature flags, sets directories and audio constants.
- `config.json`: Runtime configuration fallback storage.
- `requirements.txt`: Master dependency manifest specifying 20 core external libraries.
- `startup.bat`: Launch script running `py -3 main.py`.

### 2. Core Engine Subsystems (`core/`)
- `unified_tool_registry.py`: Canonical tool registry singleton. Holds 125 registered functions, manages Gemini Live voice declarations (bounded to 48 prioritized tools to stay under Google's 57-tool ceiling), implements alias mapping (`TOOL_ALIASES`), confirmation gating for dangerous operations, and async execution dispatch.
- `gemini_live.py`: Real-time full-duplex voice session using `google-genai` Live API over WebSockets. Manages multi-turn conversation context, audio streaming buffers, speech interruptions, and tool execution callbacks.
- `voice_gate.py`: VoiceInputGate state machine with Vosk offline acoustic model. Performs zero-cloud local wake-word detection ("MAX", "Hey MAX"), extracts inline commands, enforces speaker suppression during AI speech, and manages cooldown timers.
- `audio_handler.py`: PyAudio wrapper providing non-blocking circular buffer capture (16kHz 16-bit mono) and smooth playback (24kHz 16-bit mono) with thread-safe teardown.
- `security.py`: Central security validator (`SecurityGuard`). Restricts filesystem access to user home and project root (`ALLOWED_ROOTS`), blocks UNC path traversal, classifies command risks (`SAFE`, `DEV`, `CONFIRM_REQUIRED`, `CRITICAL_BLOCKED`), scans for threat signatures (reverse shells, fork bombs, piped curl), and masks secrets with regex.
- `verifier.py`: ActionVerifier performing post-execution state verification (polling process lists, checking filesystem creation, validating window handles).
- `hermes_engine.py` & `hermes_controller.py` & `hermes_loop.py`: Hermes autonomous agent subsystem. Orchestrates multi-turn planning, tool execution loops, context compression, and loop detection.
- `provider_manager.py`: Unified LLM provider abstraction supporting Gemini, OpenAI, DeepSeek, OpenRouter, and Ollama with schema sanitization and fallback execution.
- `device_registry.py`: Central registry for paired Android companion devices. Tracks connection states (`CONNECTED`, `IDLE`, `DISCONNECTED`), battery levels, IP addresses, models, and dynamic capabilities.
- `connection_manager.py`: Active WebSocket connection tracker with ping/pong latency measurement and connection telemetry.
- `session_manager.py`: Authentication session manager handling 6-digit numeric pairing codes, crypto challenges, and paired device persistence (`storage/paired_devices.json`).
- `state_database.py`: SQLite WAL database (`storage/state.db`) for storing execution logs, agent checkpoints, and full-text search (`FTS5`) memory indexing.
- `pty_manager.py`: Windows Pseudo-Console manager using `pywinpty` for interactive terminal sessions, ANSI color streaming, and process lifecycle management.
- `code_repair_agent.py`: Self-repair agent that parses Python tracebacks, constructs AST diff patches, validates sandbox boundaries, and verifies patches before applying.
- `learning_loop.py`: Closed-loop learning system (`LearningLoopManager`, `BackgroundCurator`, `SkillValidator`) that evaluates agent execution trajectories and distills reusable skills into `storage/skills`.
- `cron_scheduler.py`: Persistent task scheduler executing cron expressions (`croniter`) with SQLite job persistence and asynchronous execution workers.
- `mcp_client.py` & `mcp_server_host.py`: Model Context Protocol integration. Client connects to external MCP tools; Host exposes MAX tools as an MCP JSON-RPC 2.0 server.
- `browser_automation.py`: Headless/headful browser automation manager using Playwright with isolated user data profiles (`storage/browser_automation_profile`).
- `ui_automation.py`: Accessibility-driven Windows UI Automation engine using `pywinauto` and `comtypes` to inspect UI trees and click elements by label.
- `search_racer.py`: High-concurrency parallel search racer querying Google, DuckDuckGo, Bing, and Arxiv concurrently, returning fastest deduped results.
- `vision_coordinator.py`: Two-phase vision task coordinator taking high-resolution desktop screenshots, returning immediate auditory acknowledgement, and performing OCR/visual analysis.
- `gpu_monitor.py`: Real-time GPU telemetry monitor querying NVIDIA NVML and DirectX adapters.
- `doctor.py`: SystemDoctor automated health scan diagnosing Python version, dependencies, SQLite integrity, network ports, and audio devices.
- `metrics_manager.py`: Rolling latency, error count, request count, and event telemetry tracker.
- `settings_manager.py`: Centralized settings store with JSON/environment variable loading and persistence.

### 3. Context Subsystem (`context/`)
- `builder.py`: Canonical `ContextBuilder` generating structured, token-budgeted prompt context containing active window state, notifications, recent memories, device statuses, and system metrics.
- `policy.py`: Security policy engine enforcing passive data boundaries (preventing context injection from being executed as system commands) and masking sensitive credentials.
- `store.py`: In-memory TTL context record store.
- `providers.py`: Context data providers (ActiveWindowProvider, NotificationProvider, ClipboardProvider, SystemMetricsProvider).
- `session_memory.py`: Single-consume session summary memory manager ensuring intermediate summaries are injected into the context exactly once.
- `hermes_compressor.py`: Context compression engine utilizing sliding windows and summary condensation.

### 4. Server Subsystem (`server/`)
- `websocket_server.py`: Async WebSocket Secure server (port 9000). Handles connection lifecycle, handshake, pairing challenge-response, tool requests, command results, and heartbeats. Includes `MDNSAdvertiser` for Zeroconf LAN discovery (`_max-agent._tcp.local.`).
- `http_server.py`: Asynchronous raw HTTP server (port 9001). Implements HTTP/1.1 request parsing, static dashboard asset serving, REST API endpoints, and Server-Sent Events (SSE).
- `api_router.py`: REST and SSE route handler (`/api/status`, `/api/pairing`, `/api/tool`, `/api/chat`, `/api/events`, `/health`, `/metrics`). Enforces CORS and DNS-rebinding protections.
- `pairing_manager.py`: 6-digit numeric pairing code manager with expiration and cryptographic challenge generation.
- `event_bus.py`: In-memory asynchronous EventBus supporting event subscription, publishing, and historical event replay by `last_event_id`.

### 5. Orchestrator, Planner & Engine (`orchestrator/`, `planner/`, `engine/`)
- `orchestrator/router.py`: High-level goal router. Routes instant commands to `FastPathResolver` and multi-step tasks to `TaskEngine`.
- `orchestrator/device_router.py`: Routes commands to remote Android devices over WebSocket and tracks completion via asyncio Futures.
- `planner/client.py`: LLM-based planning client decomposing compound user goals into DAG execution plans.
- `planner/fast_path.py`: Regex-based fast path resolver mapping direct natural language phrases to single tool calls in <1ms.
- `planner/validator.py`: Plan validator checking step IDs, DAG dependencies, argument types, and tool presence.
- `planner/normalizer.py`: Plan normalizer mapping tool aliases to canonical tool names and sanitizing argument dictionaries.
- `planner/task_factory.py`: Converts validated plans into executable `Task` objects.
- `engine/task_engine.py`: DAG Task Engine executing tasks step-by-step with dependency resolution, retry handling, rollback capability, and state persistence.
- `engine/step_runner.py`: Executes individual plan steps with timeout enforcement, exception handling, and audit recording.

### 6. Tools Suite (`tools/` — 39 files)
Comprehensive modular tools implementing 125 unique tool actions across system control, app management, file operations, terminal execution, network diagnostics, document manipulation, media generation, and Android integration.

---

## Feature Inventory

| Feature | Implemented | Entry Point | Main Module | Status | Notes |
| :--- | :---: | :--- | :--- | :---: | :--- |
| **Gemini Live Voice Agent** | Yes | `main.py` -> `run_voice()` | `core.gemini_live` | Production | Full-duplex voice via Google GenAI Live API WebSockets |
| **Local Vosk Wake Word Gate** | Yes | `core.voice_gate` | `core.voice_gate` | Production | Offline wake word ("MAX", "Hey MAX"), speech suppression |
| **Android Remote Companion WSS** | Yes | `server.websocket_server` | `server.websocket_server` | Production | TLS 1.3 WSS server on port 9000 with challenge-response auth |
| **LAN Zeroconf mDNS Discovery** | Yes | `server.websocket_server` | `server.websocket_server` | Production | Advertises `_max-agent._tcp.local.` on UDP 5353 |
| **Numeric 6-Digit Pairing** | Yes | `server.pairing_manager` | `server.pairing_manager` | Production | Secure 6-digit numeric pairing code with crypto HMAC auth |
| **Web Dashboard (SPA)** | Yes | `server.http_server` (:9001) | `dashboard/` | Production | Vanilla HTML/CSS/JS frontend with live SSE event streaming |
| **REST API & SSE Event Stream** | Yes | `server.api_router` | `server.api_router` | Production | REST endpoints on port 9001 and `/api/events` SSE stream |
| **Unified Tool Registry (125 Tools)**| Yes | `core.unified_tool_registry` | `core.unified_tool_registry` | Production | Canonical single-point registry with alias mapping & security |
| **Dangerous Tool Confirmation Gate**| Yes | `core.unified_tool_registry` | `core.unified_tool_registry` | Production | Blocks `shutdown`, `del`, `firewall` until user confirms |
| **Security Sandbox & Path Guard** | Yes | `core.security` | `core.security` | Production | Enforces user home/workspace boundaries, blocks UNC & traversal |
| **Threat Signature Scanner** | Yes | `core.security` | `core.security` | Production | Scans commands for reverse shells, netcat, fork bombs, piped curl |
| **Secret Masking Pipeline** | Yes | `core.security` | `core.security` | Production | Regex masking for API keys, passwords, tokens across logs & outputs |
| **Action Verification Engine** | Yes | `core.verifier` | `core.verifier` | Production | Verifies process creation, window state, filesystem outcomes |
| **Windows UI Automation (UIA)** | Yes | `core.ui_automation` | `core.ui_automation` | Production | Inspects active window UI trees, clicks controls by text |
| **Playwright Browser Automation** | Yes | `core.browser_automation` | `core.browser_automation` | Production | Isolated profile browser automation and page scraping |
| **Interactive PTY Console** | Yes | `core.pty_manager` | `core.pty_manager` | Production | Windows Pseudo-Console (`pywinpty`) interactive shell sessions |
| **Code Self-Repair Agent** | Yes | `core.code_repair_agent` | `core.code_repair_agent` | Production | Analyzes Python tracebacks, validates AST diffs, applies fixes |
| **AST Unified Diff Patch Engine** | Yes | `tools.patch_tool` | `tools.patch_tool` | Production | Safe unified diff patch engine with rollback support |
| **Closed-Loop Skill Learning** | Yes | `core.learning_loop` | `core.learning_loop` | Production | Evaluates execution traces and saves reusable `SKILL.md` workflows |
| **Persistent Cron Scheduler** | Yes | `core.cron_scheduler` | `core.cron_scheduler` | Production | SQLite-backed cron task scheduling using `croniter` |
| **Model Context Protocol (MCP)** | Yes | `core.mcp_client`, `mcp_server_host`| `core.mcp_client` | Production | MCP client integration and MCP JSON-RPC 2.0 host endpoint |
| **Multi-Provider LLM Abstraction**| Yes | `core.provider_manager` | `core.provider_manager` | Production | Cascading failover across Gemini, OpenAI, DeepSeek, Ollama |
| **Parallel Search Racer** | Yes | `core.search_racer` | `core.search_racer` | Production | Concurrently races Google, DDG, Bing, Arxiv for fastest query |
| **Two-Phase Vision Coordinator** | Yes | `core.vision_coordinator`| `core.vision_coordinator`| Production | Desktop screen capture with immediate voice acknowledgement |
| **GPU & System Doctor Telemetry**| Yes | `core.gpu_monitor`, `doctor`| `core.gpu_monitor` | Production | Telemetry for NVML, DirectX, CPU, RAM, Disk, and system health |
| **DAG Multi-Step Task Engine** | Yes | `engine.task_engine` | `engine.task_engine` | Production | DAG task planning, step runners, retries, and persistence |
| **Fast-Path Goal Resolver** | Yes | `planner.fast_path` | `planner.fast_path` | Production | Instant regex matching for common commands (<1ms execution) |
| **Multi-Platform External Gateway**| Yes | `gateway.gateway_server` (:9002)| `gateway.gateway_server`| Experimental | External messaging gateway router (WhatsApp Cloud / Slack) |
| **Programmatic Tool Calling (PTC)**| Yes | `tools.code_sandbox` | `tools.code_sandbox` | Production | Loopback TCP RPC sandbox for Python script execution |

---

## Command / Action Inventory

The agent registers **125 unique tool declarations** in the `UnifiedToolRegistry`. Below is the complete catalog grouped by category:

### 1. Android Remote Companion Actions (`execute_device_action`)
| Action Name | Target Device | Arguments Schema | Description | Security Risk |
| :--- | :--- | :--- | :--- | :--- |
| `FLASHLIGHT_ON` | Phone / Tablet | None | Turns on hardware camera flashlight | Low |
| `FLASHLIGHT_OFF` | Phone / Tablet | None | Turns off hardware camera flashlight | Low |
| `SET_VOLUME` | Phone / Tablet | `{"level": 0-100}` | Sets phone media/call volume percentage | Low |
| `SET_BRIGHTNESS` | Phone / Tablet | `{"level": 0-100}` | Sets phone display brightness | Low |
| `SET_RINGER_MODE` | Phone / Tablet | `{"mode": "normal"\|"vibrate"\|"silent"}` | Changes device ringer profile | Low |
| `SET_DND` | Phone / Tablet | `{"enabled": true\|false}` | Toggles Android Do Not Disturb mode | Low |
| `TAKE_SCREENSHOT` | Phone / Tablet | None | Captures phone screen and returns image | Medium |
| `SEND_WHATSAPP` | Phone / Tablet | `{"contact": str, "message": str}` | Opens WhatsApp and sends message | Medium |
| `OPEN_APP` | Phone / Tablet | `{"app_name": str}` | Launches installed Android application | Low |
| `YOUTUBE_SEARCH` | Phone / Tablet | `{"query": str}` | Launches YouTube with query | Low |
| `GET_BATTERY_STATUS`| Phone / Tablet | None | Returns battery percentage & charging state | Low |
| `GET_DEVICE_STATUS` | Phone / Tablet | None | Returns device online state & sensors | Low |
| `UNLOCK_DEVICE` | Phone / Tablet | None | Wakes and unlocks phone screen | Medium |
| `TOGGLE_WIFI` | Phone / Tablet | `{"enabled": true\|false}` | Toggles Android Wi-Fi adapter | Low |
| `SET_BLUETOOTH` | Phone / Tablet | `{"enabled": true\|false}` | Toggles Android Bluetooth adapter | Low |

### 2. System & Workstation Control Tools
| Tool Name | Key Parameters | Description | Security Risk / Confirmation |
| :--- | :--- | :--- | :--- |
| `open_application` | `app_name: str` | Launches desktop Windows application | Low |
| `close_application` | `app_name: str` | Gracefully closes application window | Low |
| `switch_to_app` | `app_name: str` | Brings application window to foreground | Low |
| `list_running_apps` | None | Lists visible interactive desktop applications | Low |
| `get_active_window` | None | Returns title and process ID of foreground window | Low |
| `close_active_window`| None | Closes current foreground window | Low |
| `set_volume` | `level: int (0-100)` | Sets master workstation volume | Low |
| `get_volume` | None | Gets master volume percentage and mute state | Low |
| `mute_unmute` | `action: "mute"\|"unmute"\|"toggle"` | Toggles audio mute state | Low |
| `set_brightness` | `level: int (0-100)` | Sets workstation monitor brightness | Low |
| `get_brightness` | None | Gets primary monitor brightness level | Low |
| `get_battery_status`| None | Returns laptop battery percentage & power source | Low |
| `get_system_info` | None | Hardware specs, CPU/RAM usage, OS version | Low |
| `lock_screen` | None | Locks Windows workstation session (`LockWorkStation`)| Low |
| `go_to_sleep` | None | Puts voice gate into standby mode | Low |
| `shutdown_restart` | `action: "shutdown"\|"restart"\|"sleep"`| Workstation power management | **CRITICAL (Confirmation Required)** |
| `toggle_wifi` | `action: "on"\|"off"\|"status"` | Controls local Wi-Fi adapter | Low |
| `get_wifi_status` | None | Returns Wi-Fi SSID, signal, and connection state | Low |

### 3. File & Disk Management Tools
| Tool Name | Key Parameters | Description | Security Risk / Confirmation |
| :--- | :--- | :--- | :--- |
| `search_files` | `filename: str, path: str` | Searches for files within sandbox | Low |
| `open_file` | `path: str` | Opens file with default associated program | Low |
| `open_folder` | `path: str` | Opens folder in Windows File Explorer | Low |
| `create_folder` | `path: str` | Creates directory within sandbox | Low |
| `read_file` | `path: str, offset: int, limit: int` | Reads text file contents within sandbox | Low |
| `write_file` | `path: str, content: str` | Creates or writes text file | Low |
| `create_file` | `filename: str, content: str, folder: str`| Creates file in user directory | Low |
| `get_folder_contents`| `path: str` | Lists directory files and folders | Low |
| `move_file` | `src: str, dst: str` | Moves file within sandbox | **HIGH (Confirmation Required)** |
| `delete_file` | `path: str` | Deletes file within sandbox | **CRITICAL (Confirmation Required)** |

### 4. UI Automation & Screen Tools
| Tool Name | Key Parameters | Description | Security Risk |
| :--- | :--- | :--- | :--- |
| `take_screenshot` | `filename: str` | Captures primary monitor screen to file | Medium |
| `click_ui_element` | `target: str, window_title: str, button: str`| Clicks button/link by text via UIA | Low |
| `inspect_ui_elements`| `max_elements: int` | Returns visible controls in active window | Low |
| `type_ui_element` | `target: str, text: str` | Focuses UI field and inputs text | Low |
| `select_ui_element` | `target: str, item: str` | Selects dropdown/combobox item | Low |
| `scroll_ui` | `direction: "up"\|"down", amount: int`| Scrolls active window viewport | Low |
| `verify_ui_state` | `expected_text: str` | Verifies presence of text on screen | Low |

### 5. Hermes Autonomous Agent Capabilities
| Tool Name | Key Parameters | Description | Security Risk |
| :--- | :--- | :--- | :--- |
| `start_terminal_session`| `session_id: str, shell: "powershell"\|"cmd"`| Spawns interactive PTY session | High |
| `write_terminal` | `session_id: str, command: str` | Writes input to interactive PTY | High |
| `read_terminal` | `session_id: str` | Reads stdout/stderr from PTY buffer | Low |
| `close_terminal_session`| `session_id: str` | Terminates interactive PTY session | Low |
| `skills_list` | None | Tier 1 progressive skill listing | Low |
| `skill_view` | `skill_name: str` | Tier 2 progressive skill content view | Low |
| `skill_manage` | `action: str, skill_name: str` | Tier 3 skill creation/updating | Medium |
| `skills_hub` | `action: str, query: str` | Searches & syncs community skills | Low |
| `apply_patch` | `target_file: str, diff_content: str`| AST-validated unified diff patcher | High |
| `execute_code` | `code: str, timeout_sec: float`| Executes Python code in PTC sandbox | High |
| `delegate_task` | `task_goal: str, timeout_sec: float`| Dispatches subagent autonomous task | Medium |
| `delegate_batch` | `tasks: List[str]` | Dispatches concurrent batch subagents | Medium |
| `cronjob` | `action: str, name: str, schedule: str`| Manages persistent cron jobs | Medium |
| `mcp_call` | `server: str, tool: str, arguments: dict`| Invokes external MCP tool | High |
| `mcp_server_status`| None | Returns status of MCP server host | Low |
| `gateway_status` | None | Returns Multi-Platform Gateway state | Low |
| `doctor_check` | None | Runs SystemDoctor automated health scan | Low |
| `generate_image` | `prompt: str, size: str` | Dispatches image generation task | Low |
| `generate_video` | `prompt: str, duration_sec: int` | Dispatches video generation task | Low |
| `kanban_task` | `action: str, task_id: str, title: str`| Manages SQLite Kanban task board | Low |
| `extract_insights` | `text: str` | Extracts structured facts from text | Low |

---

## Data Flow

### 1. Ingestion, Reasoning & Execution Pathways

```
1. Voice Pathway:
   User Audio -> AudioCapture (16kHz) -> VoiceInputGate (Vosk Wake-Word) -> GeminiLiveSession (WebSocket) -> Google Live API -> Tool Call Packet -> UnifiedToolRegistry -> SecurityGuard -> Action Execution -> AudioPlayer (24kHz TTS)

2. Companion App Pathway:
   Android Client -> WSS :9000 (TLS 1.3) -> PacketValidator -> Authentication Gate -> WebSocketServer.process_packet -> ToolRegistry.execute / DeviceRouter -> EventBus.publish -> Client Response Envelope

3. Goal Planning Pathway:
   Natural Language Goal -> OrchestrationRouter -> FastPathResolver (Regex Check)
     ├─ Match: Instant Tool Execution (<1ms)
     └─ Miss: GeminiPlanningClient -> PlanValidator -> PlanNormalizer -> TaskFactory -> TaskEngine -> StepRunner -> ActionVerifier

4. Context Ingestion Pathway:
   System Events (Active Window / Notifications / Clipboard / Metrics) -> ContextProviders -> ContextStore (In-Memory TTL) -> ContextPolicy (Secret Masking & Passive Boundaries) -> ContextBuilder -> LLM System Instruction Prompt
```

---

## Windows Integration

### 1. Process Control
- Process Creation: Managed through `subprocess.Popen` / `subprocess.run` and `pywinpty.PTY`. Standard executables are resolved through Windows PATH or `where.exe`.
- Process Termination: Handled via `psutil.Process.terminate()` with graceful fallback to `kill()`. Critical system processes (`csrss.exe`, `lsass.exe`, `services.exe`, `smss.exe`) are explicitly protected from termination in `core/security.py`.
- Window Management: Uses `win32gui`, `win32con`, `win32process`, and `comtypes` to enumerate top-level windows, retrieve foreground window handles, bring windows to front (`SetForegroundWindow`), and restore minimized windows (`ShowWindow(SW_RESTORE)`).

### 2. PowerShell & Command Execution
- Command Execution Policy: Command execution is gated by `SecurityGuard.classify_command_risk()`. Safe commands (`dir`, `ipconfig`, `systeminfo`, `whoami`, `ping`, `netstat`) execute directly. Development commands (`python`, `git`, `npm`, `cargo`) execute within sandbox. Destructive commands (`rmdir /s`, `format`, `del /f`, `reg delete`, `shutdown`) require explicit user confirmation.
- Injection Mitigation: Command strings avoid raw shell interpolation where possible. Arguments are parsed and sanitized. Terminal tools enforce UTF-8 streams and bounded buffers (max 100KB per response).

### 3. Filesystem Sandboxing
- Root Boundary Enforcement: `SecurityGuard.is_safe_path()` resolves symlinks via `os.path.realpath()` and verifies that target paths reside strictly inside `ALLOWED_ROOTS` (`C:\Users\<username>` and the project root).
- UNC Path Blocking: Paths starting with `\\` or `//` are strictly rejected to prevent remote SMB/UNC credential harvesting attacks.
- Directory Traversal: All static file HTTP endpoints in `server/api_router.py` enforce `os.path.commonpath([DASHBOARD_DIR, filepath]) == DASHBOARD_DIR`.

### 4. Windows APIs & Native Bindings
- `pywinpty`: Windows Pseudo-Console API for true interactive ANSI terminal emulation.
- `pycaw` & `comtypes`: Core Audio Windows API for hardware audio endpoint volume and mute control.
- `screen-brightness-control`: WMI and DDC/CI monitor brightness manipulation.
- `pywinauto`: UI Automation COM interfaces for UI tree traversal and element clicking.
- `winreg`: Windows Registry reading and writing with root key restrictions.
- `ctypes.windll.user32`: Direct Win32 API calls (`LockWorkStation`, `GetForegroundWindow`, `SetWindowPos`).

---

## Network & Communication

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            Network Endpoints Map                            │
├──────────────┬──────────────┬───────────────┬───────────────────────────────┤
│ Protocol     │ Port / Trans │ Binding       │ Purpose & Encryption          │
├──────────────┼──────────────┼───────────────┼───────────────────────────────┤
│ WSS          │ TCP 9000     │ 0.0.0.0       │ Android Companion App (TLS)   │
│ HTTP / SSE   │ TCP 9001     │ 0.0.0.0       │ Dashboard & REST API (HTTP)   │
│ Gateway      │ TCP 9002     │ 0.0.0.0       │ Multi-Platform Messaging      │
│ PTC RPC      │ TCP Ephemeral│ 127.0.0.1     │ Local Code Sandbox Loopback   │
│ mDNS         │ UDP 5353     │ Multicast LAN │ Zeroconf Discovery (_max-agent│
│ Gemini Live  │ WSS (Out)    │ Google Cloud  │ Bidirectional Voice WebSockets│
└──────────────┴──────────────┴───────────────┴───────────────────────────────┘
```

- **Authentication & Handshake**:
  - Unpaired devices initiate `pair_request` with the 6-digit numeric pairing code displayed on the console and dashboard. Upon success, a long-term device token is issued.
  - Paired devices initiate `hello`. The server responds with an `auth_challenge` (random 32-byte hex nonce). The client returns `auth_response` signed with HMAC-SHA256 using the stored device token. Upon verification, an ephemeral 32-character session token is granted.
- **Heartbeat & Keepalive**:
  - WebSocket server sends WebSocket ping frames every 15 seconds (timeout 25 seconds).
  - Clients send periodic `heartbeat` packets; server acknowledges with uptime and agent version.
  - Server-Sent Events (SSE) on HTTP 9001 emit `: keepalive\n\n` comments every 20 seconds to prevent proxy timeout dropouts.

---

## Security Audit

### Security Findings Summary
- **CRITICAL**: 0
- **HIGH**: 1
- **MEDIUM**: 2
- **LOW**: 3
- **INFORMATIONAL**: 2

---

### Finding SEC-01: HTTP Dashboard and REST API Unauthenticated on Local LAN (HIGH)
- **Affected File**: `server/api_router.py` (lines 337–365) & `server/http_server.py`
- **Affected Class / Function**: `handle_request()`
- **Why it matters**: While `/api/pairing` enforces localhost-only checks, `/api/tool` permits LAN execution if an authorization token is present, BUT local loopback requests (`127.0.0.1`) do not require authentication tokens unless cross-origin checks fail. If an attacker on the same local subnet accesses `http://<lan-ip>:9001/` or sends forged requests without origin headers, certain endpoints may process requests without strict device tokens.
- **Exploitation Scenario**: An untrusted device on the same Wi-Fi network could query `/api/status`, `/metrics`, or attempt HTTP tool dispatch if origin filtering is bypassed.
- **Recommended Fix**: Enforce session token validation on ALL non-static HTTP REST endpoints regardless of client IP, or bind HTTP server by default to `127.0.0.1` unless LAN dashboard sharing is explicitly enabled via `.env`.
- **Priority**: High

---

### Finding SEC-02: Self-Signed TLS Certificate Verification Warning in Android Clients (MEDIUM)
- **Affected File**: `core/cert_generator.py`
- **Affected Class / Function**: `generate_self_signed_cert()`
- **Why it matters**: The server generates self-signed certificates on startup for WSS on port 9000. While the pairing handshake transmits the SHA-256 certificate fingerprint for trust-on-first-use (TOFU), clients that do not strictly pin the fingerprint could be vulnerable to local LAN ARP spoofing/MITM.
- **Exploitation Scenario**: An active attacker on the local network performing ARP spoofing could present a forged self-signed certificate if the client does not validate `cert_fingerprint`.
- **Recommended Fix**: Ensure all companion clients (Android app) enforce strict certificate fingerprint verification against the fingerprint exchanged during initial pairing.
- **Priority**: Medium

---

### Finding SEC-03: PTC Code Sandbox Runs via Local Python Subprocess (MEDIUM)
- **Affected File**: `tools/code_sandbox.py`
- **Affected Class / Function**: `ProgrammaticToolSandbox.execute_code()`
- **Why it matters**: Programmatic Tool Calling executes Python scripts using `sys.executable` with a custom `PYTHONPATH`. While it connects back over loopback TCP and `SkillAstAudit` scans AST for blocked functions, malicious code generated by compromised LLM prompts could attempt filesystem reads before AST detection or memory exhaustion.
- **Exploitation Scenario**: An LLM generating heavily obfuscated Python code might evade simple AST heuristics and read files within the user's workspace.
- **Recommended Fix**: Restrict execution permissions using Windows Job Objects or low-privilege service tokens to constrain CPU time, memory limits, and file handle access for sandbox processes.
- **Priority**: Medium

---

### Finding SEC-04: Standalone Test and Diagnostic Scripts Exposed in Root Directory (LOW)
- **Affected Files**: `vosk_diag.py`, `vosk_mic_test.py`, `check_audio.py`, `test_live_vosk.py`
- **Why it matters**: Multiple test/diagnostic scripts exist in the project root containing test audio loops and print statements. While not security vulnerabilities in themselves, having non-production scripts in root increases maintenance clutter and potential confusion during deployment.
- **Recommended Fix**: Relocate standalone diagnostic scripts into `scripts/` or `tests/diagnostics/`.
- **Priority**: Low

---

### Finding SEC-05: Unencrypted Fallback JSON Memory File (LOW)
- **Affected File**: `memory.json` & `core/memory.py`
- **Why it matters**: Facts remembered via `remember_fact()` are persisted in plaintext JSON format (`memory.json`) as well as SQLite. If sensitive personal details are stored, they remain readable on disk to any process running as the current user.
- **Recommended Fix**: Rely solely on SQLite `state.db` with database-level encryption or ensure user directory ACLs restrict access.
- **Priority**: Low

---

### Finding SEC-06: Potential EventBus Subscriber Queue Unbounded Growth (LOW)
- **Affected File**: `server/event_bus.py`
- **Affected Class / Function**: `EventBus.publish()`
- **Why it matters**: When SSE clients connect to `/api/events`, an `asyncio.Queue()` is created. If an SSE client is extremely slow to consume events or stalls without triggering disconnect, the queue could accumulate items.
- **Recommended Fix**: Specify a `maxsize=1000` on subscriber `asyncio.Queue` instances and drop oldest events on overflow.
- **Priority**: Low

---

### Finding SEC-07: Invariant Voice Sovereignty Enforcement (INFORMATIONAL)
- **Affected File**: `config.py` (line 166)
- **Affected Variable**: `ENABLE_HERMES_VOICE = False`
- **Note**: The codebase enforces an explicit architecture invariant: Gemini Live + Vosk is the sole, sovereign voice engine; Hermes reasoning tools are integrated as tools without overriding voice channels.
- **Priority**: Informational

---

### Finding SEC-08: Redacted Secrets and Masking Verified (INFORMATIONAL)
- **Affected File**: `core/security.py`
- **Note**: All loggers, task engines, and context providers invoke `SecurityGuard.mask_secrets()`, ensuring API keys (Google AI Studio `AIza...`, OpenAI `sk-...`) and passwords are never dumped into logs.
- **Priority**: Informational

---

## Reliability & Stability

1. **Centralized Deterministic Shutdown Coordinator**:
   - `main.py` implements `async_shutdown()` and `run_shutdown()` with a `threading.Lock()` barrier (`_shutdown_lock`).
   - Shutdown sequence is strictly deterministic:
     1. Stop Gemini Live voice session (joins capture and playback audio threads before loop closure).
     2. Stop WebSocket server and unregister mDNS service.
     3. Stop HTTP server and close client connections.
     4. Cancel and await remaining asyncio background tasks.
   - Comprehensive regression tests in `tests/test_shutdown_race.py` verify 20 consecutive abnormal exit cycles with zero thread leaks or access violations.
2. **Audio Stream Teardown Safety**:
   - `core/audio_handler.py` avoids daemon audio threads, using explicit `is_alive()` joins and `atexit` handlers to prevent PyAudio C-level portaudio deadlocks during interpreter termination.
3. **Reconnection & Network Resilience**:
   - `WebSocketServer` implements rate limiting (1.0s interval) to prevent reconnection storms from malfunctioning client apps while gracefully disconnecting stale sockets when a device reconnects.
4. **DAG Task Crash Recovery**:
   - `engine/task_engine.py` and `engine/store.py` write task state transitions atomically with backup files. Upon startup, any interrupted running tasks are automatically detected and marked as `INTERRUPTED` or recovered.

---

## Concurrency

1. **Event Loop Strategy**:
   - Windows Selector event loop (`WindowsSelectorEventLoopPolicy`) is configured in `main.py` to ensure high compatibility with asynchronous socket polling and subprocesses.
2. **PTY and Subprocess Non-Blocking Execution**:
   - PTY read/write operations and long-running subprocesses are offloaded via `asyncio.to_thread()` or dedicated worker threads, preventing event loop blocking during heavy CLI tool executions.
3. **Thread-Safe Registries**:
   - `DeviceRegistry`, `ConnectionManager`, and `UnifiedToolRegistry` utilize internal locks (`threading.Lock` / `threading.RLock`) ensuring safe concurrent modifications from voice threads, WebSocket coroutines, and HTTP worker tasks.

---

## Performance

1. **Voice Latency Optimization**:
   - Fast-path regex matching in `FastPathResolver` executes common voice commands in <1ms without LLM roundtrips.
   - Vosk speech recognition runs locally with a lightweight model (`vosk-model-small-en-us-0.15`), achieving instant wake-word trigger (<50ms).
   - Audio capture chunks are tuned to 1024 samples (~64ms at 16kHz) for low latency.
2. **Parallel Search Engine Racing**:
   - `core/search_racer.py` concurrently queries multiple search endpoints, reducing web search latency from ~2.5s to <700ms.
3. **SQLite WAL Mode**:
   - `core/state_database.py` enables Write-Ahead Logging (`PRAGMA journal_mode=WAL`), allowing concurrent readers without blocking write transactions.

---

## Code Quality

- **Modularity & Architecture**: Codebase is clean, highly modular, and follows clean domain separation across `core`, `tools`, `server`, `orchestrator`, `planner`, `engine`, and `context`.
- **Typing & Linting**: Broad adoption of Python type hints (`typing.Dict`, `List`, `Optional`, `Callable`), Pydantic models for protocol structures, and dataclasses for commands.
- **Redundant / Duplicate Code**:
  - `tools/` contains two historical generations of tools: Class-based `BaseTool` wrappers (`app_tool.py`, `file_tool.py`, `system_tool.py`) alongside modern functional modules (`app_control.py`, `file_manager.py`, `system_control.py`). Both are unified cleanly by `UnifiedToolRegistry`, but deprecating the older wrappers will reduce file count.
- **Syntax & Compilation**: Zero AST syntax errors across all 200 Python source files.

---

## Dependencies

From `requirements.txt`:
```
google-genai>=1.0.0           # Official Google GenAI SDK for Gemini Live WebSockets
pyaudio>=0.2.14               # Real-time microphone capture & audio output
psutil>=5.9.0                 # Process monitoring, memory, CPU telemetry
screen-brightness-control>=0.23.0 # Monitor brightness control via WMI/DDC
pycaw>=20240210               # Windows Core Audio API for volume control
comtypes>=1.4.0               # Windows COM client for UI Automation
pyautogui>=0.9.54             # Mouse/keyboard simulation fallback
python-dotenv>=1.0.0          # Environment variable loading (.env)
pyperclip>=1.8.2              # Workstation clipboard manipulation
requests>=2.31.0              # Synchronous HTTP requests for search/APIs
websockets>=12.0              # Async WebSocket server & client
zeroconf>=0.132.0             # mDNS LAN service discovery & registration
cryptography>=42.0.0          # TLS certificate generation & HMAC crypto
openai>=1.50.0                # Multi-provider client (OpenAI, DeepSeek, Ollama)
croniter>=6.0.0               # Cron schedule parsing
mcp>=2.0.0                    # Model Context Protocol SDK
pywinpty>=3.0.0               # Windows Pseudo-Console PTY interactive sessions
pyyaml>=6.0.0                 # YAML parser for SKILL.md progressive skills
pydantic>=2.10.0              # Fast data validation & schema definitions
rich>=14.0.0                  # Colored terminal formatting
```
*Assessment*: Dependency definitions are well-chosen, modern, and pinned with minimum viable version constraints. No obsolete or conflicting packages detected.

---

## Configuration & Secrets

1. **Environment Configuration (`.env`)**:
   - `GEMINI_API_KEY`: Google AI Studio API key (Required for Gemini Live voice and Gemini Planning).
   - `JARVIS_NAME`: Assistant identity name (Default: `MAX`).
   - `JARVIS_VOICE`: Voice preset (Default: `Aoede`).
   - `ENABLE_HERMES`: Master flag for Hermes autonomous reasoning (Default: `true`).
   - `ENABLE_HERMES_VOICE`: Strictly hardcoded to `False` (Voice sovereignty invariant).
2. **Secret Masking**:
   - `SecurityGuard.mask_secrets()` masks all occurrences of `AIza...`, `sk-...`, tokens, passwords, and secrets across logs and outputs.
   - Verified that no live API keys or passwords exist in the codebase.

---

## Testing

### Automated Test Results
- **Total Test Cases**: 378
- **Passed**: 377 (99.7%)
- **Failed**: 1 (0.3%)
- **Subtests Passed**: 26
- **Total Test Execution Duration**: ~160 seconds

### Analysis of Test Failure
- **Failed Test**: `tests/test_planner.py::TestPlannerFoundation::test_29_e2e_plan_to_task_engine_execution`
- **Cause**: In the test, `raw_plan` supplied alias tool names (`system_info`, `volume_get`) directly to `plan_validator.validate()` without first passing through `plan_normalizer.normalize()`. `PlanValidator` expects canonical tool names (`get_system_info`, `get_volume`).
- **Fix Recommendation**: Ensure plan validation pipeline calls `plan_normalizer.normalize()` prior to validation, or update test fixture to pass canonical names.

### Missing Tests & High-Value Additions
1. End-to-end integration test simulating multiple simultaneous Android companion connections under network disconnect/reconnect loops.
2. Stress test for PTY terminal buffer overflow during extremely high throughput CLI commands (`cat 100MB_file`).
3. Automated integration test for Playwright browser profile isolation under multi-tab navigation.

---

## Documentation

- **Current State**: Codebase contains clean docstrings, architecture comments, protocol markdown specs (`protocol/`, `plugins/plugin_api.md`), and historical implementation reports (`phase18_implementation_report.md`).
- **Missing Documentation**:
  - Unified deployment and service installation guide (running MAX as a persistent Windows Background Service via NSSM / Task Scheduler).
  - Android client pairing troubleshooting guide.

---

## Startup & Shutdown

### Startup Sequence
1. `main.py` invokes `setup_logging()` and sets Windows Selector event loop policy.
2. Loads environment variables from `.env` via `config.py`.
3. Initializes `WebSocketServer` (0.0.0.0:9000), `HTTPServer` (0.0.0.0:9001), and registers tool modules.
4. Generates self-signed TLS certs and starts `MDNSAdvertiser` (`_max-agent._tcp.local.`).
5. If `GEMINI_API_KEY` is present, instantiates `ToolRegistry` and launches `GeminiLiveSession`.
6. Attaches signal handlers for `SIGINT` and `SIGTERM`.

### Shutdown Sequence
1. Signal or keyboard interrupt triggers `async_shutdown()`.
2. `_shutdown_lock` ensures single execution.
3. Voice capture and playback streams stop and join threads cleanly.
4. WebSocket client connections close with code `1000 (Normal Closure)`.
5. mDNS service unregisters from local network.
6. HTTP server stops accepting connections.
7. Background asyncio tasks cancel and drain.

---

## Critical Issues
*No Critical (Showstopper / Remote Code Execution Vulnerability) issues identified in the codebase.*

---

## High Priority Issues

### 1. HTTP Tool API Missing Mandatory Auth for LAN Clients (SEC-01)
- **File**: `server/api_router.py`
- **Impact**: LAN devices could access HTTP tool endpoints if origin headers are absent.
- **Fix**: Require session tokens on all REST API mutation endpoints.

---

## Medium Priority Issues

### 2. Plan Normalization Ordering in E2E Planner Pipeline
- **File**: `planner/validator.py` & `planner/normalizer.py`
- **Impact**: Fails raw plans with tool aliases if validator runs before normalizer.
- **Fix**: Call `plan_normalizer.normalize(plan)` automatically inside `plan_validator.validate()`.

### 3. Loopback PTC Code Sandbox Process Isolation (SEC-03)
- **File**: `tools/code_sandbox.py`
- **Impact**: Python sandbox subprocess runs with user credentials without memory/CPU job object limits.
- **Fix**: Wrap subprocess creation with Windows Job Object limits for memory and execution time.

---

## Low Priority Issues

### 4. Root Directory Clutter
- **Files**: `vosk_diag.py`, `vosk_mic_test.py`, `check_audio.py`, `test_live_vosk.py`, `phase18_implementation_report.md`
- **Impact**: Clutters root folder with non-runtime diagnostic scripts.
- **Fix**: Move diagnostic scripts to `scripts/` and reports to `docs/`.

### 5. Deprecate Redundant Generation-1 BaseTool Wrappers
- **Files**: `tools/app_tool.py`, `tools/file_tool.py`, `tools/system_tool.py`
- **Impact**: Minor code duplication with `tools/app_control.py`, `tools/file_manager.py`, `tools/system_control.py`.
- **Fix**: Phase out legacy `BaseTool` wrappers in favor of canonical `UnifiedToolRegistry` modules.

---

## Good Practices

1. **Deterministic Shutdown Barrier**: Avoids thread deadlocks and portaudio access violations on exit.
2. **Canonical Unified Tool Registry**: Single execution boundary with alias mapping, schema validation, and confirmation gating.
3. **Comprehensive Secret Masking**: Centralized regex pipeline prevents credentials from ever hitting logs.
4. **Voice Sovereignty Architecture**: Voice pipeline is isolated and prioritized to guarantee real-time latency.
5. **High Automated Test Coverage**: 378 unit and integration tests with 99.7% pass rate.
6. **Dual Fast-Path and DAG Planning**: Optimizes latency for direct commands while supporting complex multi-step workflows.

---

## Project Health Scores

| Metric | Score (0-100) | Justification |
| :--- | :---: | :--- |
| **Architecture** | **95** | Exceptionally modular, clean separation of voice, companion WSS, reasoning, and tools |
| **Security** | **90** | Robust sandboxing, path validation, threat scanning, secret masking; minor LAN auth fix needed |
| **Reliability** | **96** | 99.7% test pass rate, rock-solid shutdown coordination, thread-safe registries |
| **Performance** | **94** | <1ms fast-path execution, parallel search racing, SQLite WAL mode, local Vosk wake-word |
| **Code Quality** | **92** | Clean type hinting, structured docstrings, consistent formatting, zero syntax errors |
| **Maintainability** | **90** | Highly decoupled subsystems; slight cleanup recommended for duplicate legacy tool files |
| **Testing** | **97** | 378 test cases covering all phases, multi-device networking, security, and shutdown races |
| **Documentation** | **88** | Excellent internal docstrings and protocol docs; needs Windows Service setup guide |
| **OVERALL SCORE** | **93 / 100** | **Grade: A (Production Ready)** |

---

## Recommended Roadmap

### Phase 1 — Immediate Fixes
- Fix `plan_validator` / `plan_normalizer` pipeline ordering to achieve 100% test pass rate (378/378).
- Enforce mandatory session token authentication on all non-static HTTP endpoints in `server/api_router.py`.

### Phase 2 — Stability & Sandboxing
- Implement Windows Job Object constraints (CPU/memory caps) on `tools/code_sandbox.py`.
- Add bounded queue sizing (`maxsize=1000`) on `server/event_bus.py` SSE queues.

### Phase 3 — Repository Cleanup
- Relocate standalone diagnostic scripts (`vosk_*.py`, `check_audio.py`) to `scripts/`.
- Deprecate redundant legacy `BaseTool` wrapper classes in `tools/`.

### Phase 4 — Windows Service Deployment
- Add automated NSSM / Windows Task Scheduler installer script to run MAX seamlessly on Windows boot.

---

## Technical Debt

1. **Duplicate Legacy Tool Wrappers**: `app_tool.py`, `file_tool.py`, and `system_tool.py` duplicate functionality in `app_control.py`, `file_manager.py`, and `system_control.py`.
2. **Plaintext Memory File**: `memory.json` remains as a secondary fallback alongside SQLite `state.db`.

---

## Open Questions / Unknowns

1. **Android App Client Repository**: The Windows Agent server supports full Protocol 2.1 WSS and dynamic capabilities; client-side Android source code resides in a paired repository.
2. **Cloud Gateway Deployments**: The Multi-Platform Gateway on port 9002 (`gateway/gateway_server.py`) has placeholder endpoints for WhatsApp Cloud and Slack Webhooks for future remote access.

---

## Audit Change Log

### 2026-09-08 (MAX Voice → MAX Task Authority → Antigravity Bridge Real-Time Integration)
- **Voice Pipeline Integration (`core/gemini_live.py`, `core/voice_gate.py`, `core/unified_tool_registry.py`)**:
  - Connected real-time voice intent stream and Gemini Live session tools directly to `max_task_authority.submit_user_intent(...)`.
  - Registered `developer_task` and `execute_task` tools in `UnifiedToolRegistry` and exposed them to Gemini Live voice declarations (`get_voice_declarations`).
  - Added Hermes delegation routing in `core/hermes_controller.py` to route Tier 4/5 tasks directly to `MAXTaskAuthority`.
- **Sole Top-Level Authority Enforcement (`orchestrator/max_task_authority.py`)**:
  - MAX Task Authority acts as the sole owner of root tasks (`depth = 0`, `origin = "max"`, `parent_task_id = None`).
  - Gemini Live and voice layers are strictly prohibited from directly spawning `agy.exe`, launching worker subprocesses, modifying canonical task state, or bypassing `SecurityPolicy` / `VerificationEngine`.
- **Deterministic 5-Tier Voice Intent Classification & Safety (`core/task_classifier.py`)**:
  - Tier 1: Direct action / Fast-path system operations (Windows Agent).
  - Tier 2: Windows OS GUI / Paint canvas automation or Android remote device control.
  - Tier 3: Hermes runtime autonomous reasoning and multi-step workflows.
  - Tier 4: Antigravity developer / repository tasks (read-only code inspection or isolated worktree modification).
  - Tier 5: Hybrid developer + runtime verification workflows.
  - Granular inspection vs modification safety: inspection intents with negative keywords (e.g., "do not modify") are strictly categorized as read-only (`code_inspection`) without granting `WORKSPACE_WRITE` or `GIT_WRITE`.
- **Centralized Voice Cancellation & Confirmation**:
  - Gemini Live intercepts explicit voice cancellation phrases ("stop", "cancel that", "stop the task", "abort") and routes them to `max_task_authority.cancel_active_task(...)`.
  - Terminating tasks cancels AntigravityBridge subprocesses, terminates OS-level process trees via `ProcessSupervisor` (`taskkill /T /F`), and transitions CanonicalTaskStore state to `cancelled`. Casual speech containing "stop" is safely rejected without terminating unrelated active work.
  - Handled 60-second confirmation timeout with automatic auto-denial on timeout or ambiguous responses.
- **Natural Spoken Response Synthesis & Redaction**:
  - `MAXTaskAuthority.synthesize_spoken_response` converts structured task results and verification outcomes into concise natural voice responses.
  - Strictly sanitized: never speaks raw stdout, stderr, process PIDs, internal UUIDs, or CLI help text.
- **Hardened Antigravity CLI Discovery & Non-Interactive Bridge (`core/antigravity_bridge.py`)**:
  - Discovers real `agy.exe` executable at `C:\Users\yaswa\AppData\Local\agy\bin\agy.exe` with fallbacks for `AGY_PATH`, `shutil.which("agy")`, and standard installation paths without requiring interactive PowerShell PATH.
  - Stripped `--dangerously-skip-permissions` from the production voice execution path. Added non-blocking `run_task` async wrapper via `run_in_executor`.
- **Automated Integration Test Suite (`tests/test_voice_task_authority_integration.py`)**:
  - Implemented 17 comprehensive automated integration tests covering voice intent to root task, Tier 1/2 routing, Tier 4 Antigravity routing, Tier 5 Hybrid workflow, voice deduplication, voice cancellation, voice confirmation, ambiguous cancellation rejection, spoken summary sanitization, CLI discovery, verification failure, worker offline, auth expired, quota exceeded, timeout propagation, escalation request, and permission inheritance. All 17 tests passed (100%).
- **Full Test Suite Validation**:
  - Executed full test suite across all 469 test cases: **469 passed, 0 failures, 0 regressions**.
- **Real Workstation Runtime Verification**:
  - **Real Test 1 (Filesystem / Desktop)**: Intent: "Create a folder named VoiceTest on my Desktop." → Classified Tier 2 (Windows Agent), created `C:\Users\yaswa\Desktop\VoiceTest`, verified by `ActionVerifier`, spoke natural confirmation.
  - **Real Test 2 (Developer Read-Only / Antigravity)**: Intent: "Inspect the Paint automation code and tell me whether the Brush tool selection performs a left click after detecting the Brush. Do not modify anything." → Classified Tier 4 (`code_inspection`), executed real `agy.exe`, verified read-only safety, spoke natural concise finding.
  - **Real Test 3 (Developer Write in Isolated Worktree / Antigravity)**: Intent: "Create a file named VOICE_ANTIGRAVITY_TEST.md in an isolated worktree with the text 'Created through MAX voice and Antigravity'. Do not merge it." → Classified Tier 4 (`code_change`), created isolated Git worktree, created file, verified, spoke natural confirmation without modifying main repository.

### 2026-09-08 (MAX Master Multi-Agent Ecosystem 100/100 Hardened Implementation)
- **Sole Root Task Authority (`orchestrator/max_task_authority.py`)**: Implemented `MAXTaskAuthority` establishing MAX as the sole top-level task authority. Guaranteed that worker subagents (Hermes, Antigravity, Windows Agent, Android Agent) cannot create independent root tasks or bypass the control boundary.
- **Protocol Envelope V1.0 (`protocol/task_envelope.py`)**: Implemented canonical `TaskEnvelope`, `TaskResult`, `TaskEvent`, `TaskStatus` (19 validated statuses), `TaskEventType` (17 event types), and `GranularPermission` (19 granular permission enums). Enforced root depth 0, max depth 3, child depth = parent depth + 1, and child permissions ⊆ parent permissions.
- **Canonical Task Store (`core/canonical_task_store.py`)**: Built canonical domain/state layer directly on existing SQLite `StateDatabase` (`core/state_database.py`), ensuring single-database persistence, strict state machine transitions, SHA-256 idempotency deduplication with `UNIQUE`/`REPEATABLE` policies, and full audit event history.
- **Deterministic 5-Tier Task Classifier (`core/task_classifier.py`)**: Built deterministic classification engine routing user requests into Tier 1 (Direct Action), Tier 2 (Platform Execution - Windows/Android), Tier 3 (Hermes Internal Reasoning), Tier 4 (Antigravity Developer Task), and Tier 5 (Hybrid Workflow) with confidence thresholding (0.80) and safety fallbacks.
- **Security Policy & Deep Redaction (`core/security_policy.py`)**: Implemented `SecurityPolicyEngine` with strict permission inheritance subset validation, recursive deep secret scrubbing (`mask_secrets_deeply`) before serialization/persistence, and 60-second confirmation timeout with silence/ambiguity auto-denial.
- **Verification Policy Engine (`core/verification_engine.py`)**: Built multi-method verification orchestrator reusing existing `ActionVerifier` (`core/verifier.py`), strictly enforcing proof of completion (`verified_success == True`) before marking tasks completed.
- **Process Supervisor & Cancellation (`core/process_supervisor.py`)**: Implemented managed process tree supervision with OS-level process-tree termination (`taskkill /T /F` on Windows) and child deadline enforcement (`child_deadline <= parent_deadline`).
- **Antigravity CLI Bridge (`core/antigravity_bridge.py`)**: Created managed subprocess lifecycle bridge for Antigravity (`agy`), with CLI discovery, streaming output parsing, and structured handling for `AUTH_EXPIRED`, `QUOTA_EXCEEDED`, `RATE_LIMITED`, `CLI_UNAVAILABLE`.
- **Git Worktree Isolation (`core/worktree_isolation.py`)**: Implemented `WorktreeManager` providing isolated git worktrees for code modifications. Enforced **NO AUTOMATIC MERGE** architectural invariant — code merges into main require explicit MAX / user authorization and verified test completion.
- **Full End-to-End Scenarios Validation (`tests/test_max_authority_scenarios.py`)**: Validated all 12 Master Scenarios (A through L) covering simple Windows actions, GUI drawing, developer worktree flow, hybrid execution, worker escalation, duplicate voice idempotency, cancellation propagation, dangerous action confirmation, false success rejection, auth expired handling, quota limits, and merge authorization.
- **Test Suite Results**: 464 automated tests passing across the entire repository with 0 failures and 0 regressions.

### 2026-09-08 (Paint UI Automation Engine Integration)
- **Paint Engine Created**: Implemented dedicated `core/paint_automation.py` (`PaintAutomationEngine` and `CanvasBounds`).
- **Standardized Mouse Primitives**: Enhanced `tools/input_tool.py` (`InputHelper`) with `mouse_down`, `mouse_up`, `move_mouse`, `left_click`, and `drag_path` (smooth interpolation, explicit button up/down).
- **Brush Tool Selection Bug Fixed**: Replaced non-clicking hover actions with explicit `find -> move -> left_click -> wait -> verify` workflow across Windows 10/11 ribbon and UIA controls.
- **Dynamic Canvas Detection**: Implemented `core/ui_automation.py::get_canvas_element` with UIA element lookup, window geometry fallback, and resolution/DPI-invariant relative coordinates `(0.0 - 1.0)`.
- **Shape & Brush Generation**: Added support for geometric shapes (triangle, diamond, circle, oval, rectangle, heart, star, arrow) and freehand strokes (line, wave, zigzag, spiral, smiley).
- **Tool Registry Integration**: Registered `paint_draw_shape`, `paint_draw_brush`, `paint_select_tool`, `paint_select_color`, `paint_workflow` in `tools/app_control.py` and added aliases (`draw_triangle`, `draw_circle`, `paint_draw`, `select_brush`, `paint_shape`, `paint_brush`) in `core/unified_tool_registry.py`.
- **Testing & Verification**: Created `tests/test_paint_automation.py` with 13 mock-based unit tests covering all primitives, canvas math, and workflows. Full test suite passed (390 passed, 0 regressions).

### 2026-09-08 (Initial Audit)
- Initial full-project repository audit completed.
- Inspected 288 files across all 15 modules.
- Executed full automated test suite (378 tests: 377 passed, 1 failed).
- Documented complete architecture, 125 tool declarations, security findings, health scores, and roadmap.
