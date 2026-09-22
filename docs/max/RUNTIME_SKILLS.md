# MAX AI — Runtime-Importable Automation Skills (Pillar 1: Adaptive Runtime)

This document describes the design and implementation of the **Runtime-Importable Automation Skills System** in MAX across Android and Windows.

---

## 1. Core Principles

1. **Separation of Concerns**:
   - `SKILL = WHAT` (declarative intent, DAG steps, dynamic slot parameters, verification assertions).
   - `PLATFORM ADAPTER = HOW` (Accessibility / Shizuku on Android; UIAutomation / Win32 on Windows).
   - `GROUNDING = WHERE` (dynamic live UI observation, negative filtering, container climbing, ordinal handling).
   - `EXECUTION = NATIVE ACTION` (reuse existing trusted dispatchers without mutating frozen `ToolRegistry`).
   - `VERIFIER = STATE TRANSITION` (bounded polling; no false success on click return).

2. **Ingress Paths**:
   - **Installed Skills**: Validated, staged atomically, persisted to filesystem under `context.filesDir/skills/installed/` (Android) or `storage/skills/installed/` (Windows) with `manifest.json`, `canonical.json`, and `source.*`.
   - **Ephemeral Skills**: Ingested directly from user prompt text (e.g. prompt-attached YAML frontmatter or Markdown) and registered in-memory for the duration of the turn without disk persistence.

3. **Safety & Invariants**:
   - Zero Room DB changes or migrations.
   - Zero modifications to core dispatchers (`ToolDispatcher`, `ActionDispatcher`, `GeminiWebSocketClient`, `max_orchestrator.py`).
   - No `eval()` or dynamic bytecode execution. All skills compile to structured data over canonical capability verbs.

---

## 2. Directory Layout & Key Modules

### Shared
- `shared/skills/fixtures/instagram_search_chat.json`
- `shared/skills/fixtures/instagram_search_chat.md`

### Android (`app/src/main/java/com/example/automation/skills/`)
| Component | Module | Description |
|-----------|--------|-------------|
| **Storage** | `SkillStorage.kt` | Sandboxed atomic staging, manifest persistence, and quarantine. |
| **Registry** | `SkillRegistry.kt` | Manages installed and ephemeral skills in memory and storage. |
| **Compiler** | `SkillInputBinder.kt` | Safe parameter interpolation (`{{param}}` / `${inputs.param}`) with type checks. |
| **Compiler** | `SkillCompiler.kt` | Kahn topological DAG sort and platform override compilation. |
| **Runtime** | `AdaptiveUiGrounder.kt` | Accessibility node matcher with negative avoid filters and container climbing. |
| **Runtime** | `SkillStepExecutor.kt` | Maps compiled steps to native Android accessibility actions. |
| **Runtime** | `SkillStateVerifier.kt` | Bounded polling verifier (`APP_FOREGROUND`, `ELEMENT_VISIBLE`, `TEXT_MATCH`). |
| **Runtime** | `SkillRecovery.kt` | Non-destructive recovery (dialog dismissal, Back navigation, re-grounding). |
| **Runtime** | `SkillResolver.kt` | Natural language intent matching (Telugu + English) and ephemeral skill extraction. |
| **Runtime** | `SkillRuntime.kt` | End-to-end execution coordinator. |

### Windows Companion Agent (`windows-agent/core/skills/`)
| Component | Module | Description |
|-----------|--------|-------------|
| **Storage** | `storage.py` | Atomic installation under `storage/skills/`, canonical serialization, and quarantine. |
| **Registry** | `registry.py` | Registry for persistent and ephemeral prompt-attached skills. |
| **Compiler** | `input_binder.py` | Type validation and safe string interpolation. |
| **Compiler** | `compiler.py` | Compiles SkillDefinitions to `BoundSkillPlan` with topological ordering. |
| **Runtime** | `adaptive_grounder.py` | UIMap grounding with avoid filtering and coordinate resolution. |
| **Runtime** | `step_executor.py` | Dispatches steps to `ActionExecutor` and PyAutoGUI / Win32. |
| **Runtime** | `state_verifier.py` | Bounded state verification with `ScreenPerceptionCoordinator`. |
| **Runtime** | `recovery.py` | Dismisses blocking dialogs and sends Escape keys upon failure. |
| **Runtime** | `resolver.py` | Matches prompt phrases, keywords, and embedded YAML frontmatter. |
| **Runtime** | `runtime.py` | End-to-end execution coordinator. |

---

## 3. Test Coverage & Verification

- **Android JVM Unit Tests**: 28 / 28 Passed (`.\gradlew.bat testDebugUnitTest`)
- **Windows PyTest Suite**: 32 / 32 Passed (`py -m pytest tests/test_skill_*.py tests/test_adaptive_grounder.py tests/test_canonical_checksum.py -v`)
