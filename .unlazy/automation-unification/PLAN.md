# Plan: Automation Architecture Unification & Bilingual Routing

Scope: automation-unification
Depth: tree 2
Mode: orchestrated

## Contract

Decide before fan-out:

- Interfaces: 
  - `ActionDispatcher.kt`: Inverted routing for `TASK_INTENT`, routing all app-level & UI goals (including WhatsApp and Telegram) to `ComputerUseEngine`, bypassing only native hardware/device settings.
  - `ActionDispatcher.kt`: Full bilingual (English + Telugu) guards for voice dispatch (`CALL_PHONE` and messaging utterances).
  - `SearchContactAction.kt`: Multi-tier contact resolution (Exact/Normalized query -> Phonetic/Transliteration -> Relationship aliases -> Clarification ask-back).
  - `MaxRuntimeManager.kt` / `JarvisAccessibilityService.kt`: Feature flag `useComputerUseForMessaging` (default true) with fallback safety net to legacy System B.
- Ownership:
  - 1.1.1: `app/src/main/java/com/example/automation/engine/ActionDispatcher.kt`, `app/src/test/java/com/example/automation/IntentRoutingGuardUnitTest.kt`
  - 1.1.2: `app/src/main/java/com/example/automation/engine/ActionDispatcher.kt`, `app/src/test/java/com/example/automation/ActionArgumentBoundaryUnitTest.kt`
  - 1.1.3: `app/src/main/java/com/example/automation/actions/SearchContactAction.kt`, `app/src/main/java/com/example/automation/actions/CallPhoneAction.kt`, `app/src/test/java/com/example/automation/ContactResolutionUnitTest.kt`
  - 1.1.4: `app/src/main/java/com/example/core/lifecycle/MaxRuntimeManager.kt`, `app/src/main/java/com/example/service/JarvisAccessibilityService.kt`, `app/src/test/java/com/example/automation/MessagingAutomationRoutingTest.kt`
- Dependencies:
  - 1.1.1 (Routing Inversion) -> Wave 1 (no dependencies)
  - 1.1.2 (Bilingual Voice Guard Audit) -> Wave 1 (no dependencies)
  - 1.1.3 (Contact Alias Resolver & Voice UX) -> Wave 2 (Needs 1.1.2)
  - 1.1.4 (System B Feature Flag & Safe Migration) -> Wave 2 (Needs 1.1.1)
- Host launch mode: sequential fallback
- Wave policy: Wave 1 executes 1.1.1 & 1.1.2; Wave 2 executes 1.1.3 & 1.1.4.
- Toolchain: JDK 21, Gradle 9.5.0 (`.\gradlew.bat testDebugUnitTest`), Node 16+
- Conventions: Follow existing MAX coding invariants and kotlin idioms; do not break Room DB, do not write secrets.
- Manual review: Consequential voice UX verification on real device for WhatsApp message + sticker click and Telugu voice call.

## Current contract inventory

Contract revision: 1

| ID | Required outcome or constraint | Owner | Observing gate or manual review | Disposition | Revision |
|---|---|---|---|---|---|
| C1 | Invert routing so all UI goals (WhatsApp, Telegram, etc.) default to ComputerUseEngine | leaf-1.1.1 | leaf-1.1.1:G1 | ACTIVE | 1 |
| C2 | Existing hardware direct actions (Flashlight, WiFi, Volume, DND) remain bypassed | leaf-1.1.1 | leaf-1.1.1:G2 | ACTIVE | 1 |
| C3 | Bilingual voice utterance dispatch guards (Telugu + English) across ActionDispatcher | leaf-1.1.2 | leaf-1.1.2:G1 | ACTIVE | 1 |
| C4 | Audit all .startsWith/.contains English gates in ActionDispatcher | leaf-1.1.2 | leaf-1.1.2:G2 | ACTIVE | 1 |
| C5 | Multi-tier contact resolver with relationship aliases (నాన్న, అమ్మ, etc.) | leaf-1.1.3 | leaf-1.1.3:G1 | ACTIVE | 1 |
| C6 | Non-silent fallback / clarification response when contact is unresolved | leaf-1.1.3 | leaf-1.1.3:G2 | ACTIVE | 1 |
| C7 | Feature flag `useComputerUseForMessaging` preserving System B fallback safety net | leaf-1.1.4 | leaf-1.1.4:G1 | ACTIVE | 1 |
| C8 | End-to-end regression validation for messaging and UI action routing | node-1.1 | node-1.1:G1 | ACTIVE | 1 |

## Tree

- 1 automation-unification .............. GATES.md
  - 1.1 core-automation ................ gates/node-1.1.md
    - 1.1.1 routing-inversion .......... gates/leaf-1.1.1.md
    - 1.1.2 bilingual-voice-audit ...... gates/leaf-1.1.2.md
    - 1.1.3 contact-alias-resolver ..... gates/leaf-1.1.3.md
    - 1.1.4 system-b-feature-flag ...... gates/leaf-1.1.4.md

## Leaf dispatch table

| Leaf | Owns | Needs | Tier | Planned wave | State |
|---|---|---|---|---|---|
| 1.1.1 | app/src/main/java/com/example/automation/engine/ActionDispatcher.kt, app/src/test/java/com/example/automation/IntentRoutingGuardUnitTest.kt | - | judgment | 1 | VERIFIED |
| 1.1.2 | app/src/main/java/com/example/automation/engine/ActionDispatcher.kt, app/src/test/java/com/example/automation/ActionArgumentBoundaryUnitTest.kt | - | mechanical | 1 | VERIFIED |
| 1.1.3 | app/src/main/java/com/example/automation/actions/SearchContactAction.kt, app/src/main/java/com/example/automation/actions/CallPhoneAction.kt, app/src/test/java/com/example/automation/ContactResolutionUnitTest.kt | 1.1.2 | judgment | 2 | VERIFIED |
| 1.1.4 | app/src/main/java/com/example/core/lifecycle/MaxRuntimeManager.kt, app/src/main/java/com/example/service/JarvisAccessibilityService.kt, app/src/test/java/com/example/automation/MessagingAutomationRoutingTest.kt | 1.1.1 | judgment | 2 | VERIFIED |
