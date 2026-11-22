# Plan: Reply language and grounded Android action safety

Scope: reply-language-grounding-2026-10-18
Depth: tree 2
Mode: orchestrated

## Contract

- Keep the existing Android stack, provider/model selection and dispatch architecture.
- Language means assistant text replies and voice, not translation of app labels.
- Preserve the pre-existing dirty working tree; no commits, credentials, external messages or live microphone sessions.
- Toolchain: repository Gradle wrapper, Windows cmd for ledger checks, JAVA_HOME=C:\Program Files\Android\Android Studio\jbr. JVM/Robolectric checks only; driver serializes Gradle runs.
- Host launch mode: concurrent native Task calls. This host exposes task ids only with completed results, not nonblocking start receipts. Retain native tool receipts; do not invent a CLI dispatch wave or pre-return handles.
- Leaf implementations and tests have disjoint ownership. Driver owns vision-loop changes, independent test runs, integration review and final report.
- Four passes: implement, expert reread, defect hunt, focused polish. Report observed evidence and hardware/model acceptance gaps honestly.

## Current contract inventory

Contract revision: 2. Parent UI review added explicit Settings choices to avoid canonicalizing incomplete text-field edits. This is part of C1, not app-label localization.

| ID | Required outcome or constraint | Owner | Observing gate or manual review | Disposition | Revision |
|---|---|---|---|---|---|
| C1 | Home and Settings propagate supported reply/voice choices consistently, including active session and later reconnect; Settings choices remain usable without partial text edits | leaf-1.1 + driver | leaf-1.1:G1, root:R1, root:R2 | ACTIVE | 2 |
| C2 | Semantic targets are validated and missing/stale targets cannot silently fall through to old bounds | leaf-1.2 | leaf-1.2:G1, root:R2 | ACTIVE | 1 |
| C3 | Vision-loop stale decisions, typing verification and cancellation respect observation evidence | driver | root:R1, root:R2 | ACTIVE | 1 |
| C4 | Focused regression checks and debug APK build reflect combined working tree | driver | root:R1, root:R3 | ACTIVE | 1 |
| C5 | No universal reliability, live voice or device acceptance is claimed without evidence; preserve unrelated changes and safety boundaries | driver | root:R2, root:R4 | ACTIVE | 1 |

## Tree

- 1 Reply language and action safety ... GATES.md
  - 1.1 Reply language ... gates/leaf-1.1.md
  - 1.2 Semantic action safety ... gates/leaf-1.2.md

## Leaf dispatch table

| Leaf | Owns | Needs | Tier | Planned wave | State |
|---|---|---|---|---|---|
| 1.1 | app/src/main/java/com/example/data/preferences/SettingsManager.kt, app/src/main/java/com/example/domain/policy/LanguagePolicy.kt, app/src/main/java/com/example/domain/usecase/AssistantUseCase.kt, app/src/main/java/com/example/voice/assistant/AssistantEngine.kt, app/src/main/java/com/example/voice/assistant/ConnectionManager.kt, app/src/main/java/com/example/network/LiveSessionState.kt, app/src/main/java/com/example/network/GeminiWebSocketClient.kt, app/src/main/java/com/example/ui/home/HomeViewModel.kt, app/src/main/java/com/example/ui/settings/SettingsViewModel.kt, app/src/test/java/com/example/assistant/ReplyLanguageSwitchingTest.kt | - | judgment | 1 | VERIFIED |
| 1.2 | app/src/main/java/com/example/automation/engine/LiveNodeResolver.kt, app/src/main/java/com/example/automation/engine/GenericActionExecutor.kt, app/src/test/java/com/example/automation/GenericActionExecutorTest.kt, app/src/test/java/com/example/automation/GroundedActionSafetyTest.kt | - | judgment | 1 | VERIFIED |

## Integration evidence

Native task receipts: language ses_eb063f546ffezZ3U4HA5lHhMhF; semantic actions ses_eb063f537ffePMdVROc1M1kkGV. Both returned completed implementations without running Gradle; driver ran their focused checks independently and corrected integration defects.

Driver changes are limited to UiVerificationLoop, UiActionExecutor, ComputerActionExecutor's semantic fallback boundary, AccessibilityTreePruner focus metadata, SettingsScreen's language selector, regression fixtures/tests and this evidence. No desktop/device actions are required to pass synthetic checks; runtime model adherence and physical UI flows remain explicitly separate acceptance limitations.

Final local acceptance: 106 focused tests pass, debug APK builds, 37 new regressions pass. Full suite measures 1202 cases with the same two prompt-expectation failures; this is not a green full suite. Handoff and exact file list: docs/plans/reply-language-grounding/REPORT.md. Neither new APK installation nor live-model/device acceptance is claimed.
