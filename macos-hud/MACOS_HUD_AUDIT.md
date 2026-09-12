# MAX macOS Dynamic Island HUD — Architecture & Implementation Audit

## Project Overview

- **Project Name**: MAX macOS Dynamic Island / Floating HUD
- **Location**: `gemini-live/macos-hud/`
- **Target OS**: macOS 13.0+ (Ventura, Sonoma, Sequoia)
- **Primary Stack**: Swift 5.9+, AppKit (`NSPanel`), SwiftUI
- **Architecture**: State-Driven Reactive Floating Assistant Interface

---

## 1. Executive Summary

The **MAX macOS Dynamic Island HUD** provides an always-on-top, notch-aware, glassmorphic companion interface for the MAX AI ecosystem. Operating on a non-activating `NSPanel` floating layer, it remains accessible across all Spaces and full-screen auxiliary modes without stealing keyboard or window focus from user workflows.

The HUD dynamically morphs across 6 distinct states (`IDLE`, `LISTENING`, `THINKING`, `RESPONDING`, `COMPLETED`, `ERROR`), with real-time audio amplitude reaction and particle/orbital animations.

---

## 2. Technology & Window Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                       macOS Display                         │
│   ┌─────────────────────────────────────────────────────┐   │
│   │           Notch / Top Safe Area Center              │   │
│   │   ┌─────────────────────────────────────────────┐   │   │
│   │   │           NSPanel (.nonactivating)          │   │   │
│   │   │   ┌─────────────────────────────────────┐   │   │   │
│   │   │   │       SwiftUI (MaxHUDView)          │   │   │   │
│   │   │   │     Morphing Frosted Capsule        │   │   │   │
│   │   │   └─────────────────────────────────────┘   │   │   │
│   │   └─────────────────────────────────────────────┘   │   │
│   └─────────────────────────────────────────────────────┘   │
│                                                             │
│   Normal App Windows (Safari, Xcode, VS Code, Terminal)     │
└─────────────────────────────────────────────────────────────┘
```

### Key Window Properties:
1. **Window Subclass**: `HUDPanel` (`NSPanel`)
2. **Style Mask**: `[.borderless, .nonactivatingPanel, .fullSizeContentView]`
3. **Window Level**: `.statusBar` / `.floating` (above all normal applications)
4. **Collection Behavior**: `[.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]`
5. **Backing Store**: `.buffered`, `isOpaque = false`, `backgroundColor = .clear`, `hasShadow = false`
6. **Focus Invariance**: `canBecomeKey = false`, `canBecomeMain = false` (100% focus theft prevention)
7. **Screen Adaptation**: Listens to `NSApplication.didChangeScreenParametersNotification` and centers at top safe area.

---

## 3. Authoritative State Machine

| State | Target Size | Visual Appearance | Motion / Animation | Auto Dismiss |
|---|---|---|---|---|
| **IDLE** | 130 x 34 | Compact pill, subtle radial blue glow, "MAX" text | Gentle 2.5s breathing pulse, 0% CPU | None |
| **LISTENING** | 260 x 44 | Expanded pill, glowing mic icon, cyan theme | Dynamic 8-bar audio waveform | None |
| **THINKING** | 220 x 48 | Orb core, orbiting ring + satellite particle, purple hue | 360° continuous orbital rotation | None |
| **RESPONDING** | 280 x 46 | Alive speech visualizer, emerald/cyan energy | 12-bar amplitude-linked equalizer | None |
| **COMPLETED** | 190 x 38 | Spring checkmark icon, emerald tone, "Done" | Spring pop + fade | 1.4 seconds -> IDLE |
| **ERROR** | 280 x 42 | Warning triangle icon, coral/red tone, message | Pulse | 3.0 seconds -> IDLE |

---

## 4. File Inventory

```text
gemini-live/macos-hud/
├── Package.swift                                  # Swift Package Manager manifest
├── Sources/
│   └── MaxHUD/
│       ├── App.swift                              # Application entry point & NSApplicationDelegate
│       ├── HUDPanel.swift                         # Non-activating floating NSPanel implementation
│       ├── HUDController.swift                    # Central state coordinator & frame animator
│       ├── HUDState.swift                         # Authoritative HUDState enum & dimension contracts
│       ├── AudioVisualizerModel.swift             # Audio amplitude/band processing (0% idle CPU)
│       ├── Services/
│       │   └── MAXBackendBridge.swift             # WebSocket / SSE state streamer with MAX backend
│       └── Views/
│           ├── MaxHUDView.swift                   # Root morphing glass capsule container
│           ├── IdleView.swift                     # Compact idle pill
│           ├── ListeningView.swift                # Microphone indicator & live input waveform
│           ├── ThinkingView.swift                 # Orbital reasoning core animation
│           ├── RespondingView.swift               # Assistant speech equalizer waveform
│           ├── CompletedView.swift                # Success checkmark animation
│           ├── ErrorView.swift                    # Error notice
│           └── WaveformView.swift                 # Multi-bar dynamic frequency bar renderer
└── Tests/
    └── MaxHUDTests/
        ├── HUDStateTests.swift                    # State dimension and interval test suite
        ├── AudioVisualizerTests.swift             # Amplitude and band calculation tests
        ├── BackendBridgeTests.swift               # WebSocket packet decoding and dispatch tests
        └── HUDPanelGeometryTests.swift            # Screen alignment and notch offset tests
```

---

## 5. Integration with MAX Backend

The HUD connects to the MAX server via `MAXBackendBridge`:
- **Protocol**: WebSocket (`ws://localhost:8765/ws`) or HTTP SSE (`http://localhost:8765/events`)
- **Incoming Events**:
  - `state_change` -> Dispatches state transition to `HUDController`
  - `listening` -> Switches HUD to `.listening`
  - `thinking` -> Switches HUD to `.thinking`
  - `responding` -> Switches HUD to `.responding`
  - `audio_amplitude` -> Passes live normalized float `(0.0 - 1.0)` to `AudioVisualizerModel`
  - `turn_complete` -> Triggers `.completed` checkmark, collapsing back to `.idle`
  - `error` -> Displays alert before auto-dismissal
- **Resilience**: Exponential backoff auto-reconnect watchdog.

---

## 6. Verification Results

- **Package Structure & Manifest**: Verified valid Swift 5.9+ SPM package.
- **State Machine Invariants**: Verified all 6 states conform to aspect ratio and minimum height rules.
- **Display Centering Math**: Tested across MacBook Pro 16" Notch (1728x1117), Studio Display 5K (2560x1440), and External 4K (3840x2160).
- **Packet Serialization**: Verified JSON schemas for state change, thinking, responding, amplitude, and error events.
- **Tests**: 4 unit test suites covering state, audio visualizer, backend bridge, and geometry.

---

## 7. Canonical Task State & Event Flow Integration

The HUD is strictly a **presentation-only interface** that visualizes MAX canonical task states and events:
```text
Canonical Task State (CanonicalTaskStore)
            ↓
       Task Events (EventBus / WebSocket)
            ↓
  MAXBackendBridge (Protocol Adapter)
            ↓
HUDController (State Machine Coordinator)
            ↓
     MaxHUDView (SwiftUI UI)
```

| Canonical Task Event | HUD Visual State | Visual Presentation |
|---|---|---|
| `TASK_CREATED` | `.thinking` | Purple orbital core animation |
| `TASK_STARTED` / `TASK_PROGRESS` | `.executing(worker:)` | Blue animated spinner with worker badge |
| `TASK_VERIFICATION_STARTED` | `.verifying` | Golden shield indicator with "Verifying..." |
| `TASK_WAITING_CONFIRMATION` | `.waitingConfirmation(prompt:)` | Amber warning capsule with confirmation prompt |
| `TASK_WAITING_CLARIFICATION` | `.waitingClarification` | Violet question indicator |
| `TASK_CANCELLED` | `.cancelled` | Neutral grey badge auto-collapsing to idle |
| `TASK_COMPLETED` | `.completed` | Green checkmark pulse |
| `TASK_FAILED` / `TASK_PERMISSION_DENIED` | `.error(message:)` | Red warning banner with error notice |

---

## 8. Change Log

### 2026-09-08 (Master Multi-Agent Canonical Event Integration)
- Extended `HUDState` to support canonical task execution states: `.executing(worker:)`, `.verifying`, `.waitingConfirmation(prompt:)`, `.waitingClarification`, and `.cancelled`.
- Updated `MAXBackendBridge` to parse and map canonical task event packets (`task_created`, `task_started`, `task_progress`, `task_verification_started`, `task_waiting_confirmation`, `task_waiting_clarification`, `task_cancelled`, `task_completed`, `task_failed`).
- Enhanced `MaxHUDView` with custom glassmorphic badges, progress spinners, and shield icons for all newly added execution and verification states.

### 2026-09-08 (Initial Implementation)
- Designed and implemented native macOS Dynamic Island HUD in Swift + AppKit + SwiftUI.
- Created `HUDPanel` floating non-activating window with `.statusBar` level, all-Spaces support, and notch offset positioning.
- Created `HUDState` authoritative state machine with spring animations.
- Created `AudioVisualizerModel` with zero idle CPU utilization.
- Built `MAXBackendBridge` for real-time WebSocket state streaming.
- Added comprehensive unit test suites in `Tests/MaxHUDTests/`.

