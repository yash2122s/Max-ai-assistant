# MAX Computer Use Subsystem (Blinky-Inspired v1)

## Overview
The Computer Use subsystem provides native Windows perception, element resolution, coordinate-accurate interaction, and closed-loop verification for the MAX Windows AI Agent. MAX remains the authoritative brain while Computer Use executes bounded perception and action cycles.

---

## Target Architecture

```
                    MAX
                     │
              MaxOrchestrator
                     │
                Task Planner
                     │
               Computer Use
                     │
             Screen Perception
         ┌───────────┼───────────┐
         ↓           ↓           ↓
        UIA         OCR      Screenshot
         └───────────┼───────────┘
                     ↓
               Unified UI Map
                     ↓
                   @refs
                     ↓
              Target Resolver
                     ↓
                Safety Gate
                     ↓
              Action Executor
                     ↓
                Verification
                     ↓
               Re-observation
                     ↓
                 MAX Context
```

---

## Core Modules (`windows-agent/core/computer_use/`)

| Module | Responsibility |
|---|---|
| `models.py` | Typed contracts for `UIElement`, `UIMap`, `ComputerUseAction`, `ResolutionResult`, `VerificationResult`. |
| `screen_perception.py` | Multi-modal perception orchestrator fusing UIA, OCR, and screenshot. |
| `screenshot_provider.py` | DPI-aware multi-monitor screenshot capture with multi-tier fallback. |
| `uia_provider.py` | Microsoft UIAutomation COM crawler extracting controls, bounds, and states. |
| `ocr_provider.py` | Optical character recognition provider for visual text detection. |
| `ui_map.py` | Fuses UIA + OCR, deduplicates overlapping regions, assigns ephemeral `@refs` (`@e1`, `@e2`...). |
| `ui_ref_cache.py` | Generation tracking and stale-ref validation (`StaleRefError`). |
| `target_resolver.py` | Layered target matching with ambiguity detection and confidence scoring. |
| `coordinate_transform.py` | Multi-monitor geometry, virtual coordinate offsets, and DPI scaling factors. |
| `action_executor.py` | Executes actions (click, double click, type, hotkey, scroll) with semantic UIA priority. |
| `verification.py` | Post-action verification comparing pre- and post-observation state changes. |
| `capture_exclusion.py` | Applies `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` to exclude MAX UI. |
| `computer_use_engine.py` | Master closed-loop controller: Observe → Resolve → Safety Gate → Act → Verify. |

---

## Ephemeral @ref Lifecycle
- Each screen observation produces a fresh `generation` ID.
- Elements receive deterministic `@refs` (e.g. `@e1`, `@e2`).
- Actions referencing stale `@refs` from older generations or switched windows are immediately rejected.
- Ambiguous natural language queries prompt for clarification rather than clicking blindly.

---

## Safety & Confirmation Integration
- **LOW risk**: read, observe, harmless navigation, non-destructive typing.
- **HIGH risk**: irreversible actions, deletion, critical system control → parked in `HumanConfirmationGate`.
