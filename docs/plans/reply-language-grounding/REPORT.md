# Reply language and grounded Android action safety

Date: 2026-10-18

## Scope and result

The requested language behavior is MAX **text replies and voice**, not translation of app labels. This continuation fixes concrete language-propagation and screen-action safety defects while retaining the existing native Compose app, provider/model choices and dispatch architecture.

Implementation and automated verification are complete for the focused changes below. Physical-device and live-model acceptance are **not** complete. The full JVM suite is **not green**.

## What changed

### Reply and voice language

- Canonical supported preferences are Tinglish, Telugu, English and Hindi; legacy Tenglish remains an alias. Explicit per-query language requests retain precedence.
- A lifecycle-bound preference flow synchronizes Home, Settings and an existing assistant engine across SettingsManager instances.
- Saving a language does not create or activate an otherwise inactive assistant.
- An active or connecting session gets a fresh language context rather than resuming the old context. Resumption generations reject late old-socket handles while retaining executed-call duplicate protection. Ordinary reconnects retain transport resumption.
- Old buffered playback/streaming presentation is cleared on a session language change; memory-context prompts no longer force Tinglish regardless of selection.
- Settings now offers four supported language choices instead of a free-text field. This avoids partial edits repeatedly resetting to the canonical default or triggering intermediate session changes.

### Semantic and visual actions

- Live semantic targets must match supplied signatures, be visible/enabled and be unique. Tree traversal is bounded by node count, depth and elapsed time. Anonymous canvas targeting requires live geometry/path evidence.
- Missing, changed or ambiguous semantic targets do not fall through to old snapshot coordinates. Gesture fallback reacquires the target and uses current bounds.
- The outer computer-use adapter cannot bypass a semantic rejection through the looser legacy resolver.
- Explicit text targets and focused input are validated; password fields and unknown keys fail closed. Unknown keys no longer silently become Enter.
- The vision loop reacquires screen evidence after reasoning and before screen-bound dispatch. Changed packages, geometry, focus or vision-only frames reject stale decisions before acting.
- Typing verification checks the intended editable field, not matching text elsewhere on the screen. Focus is retained in pruned observations. Geometry/focus changes are observable even without a changed visible-text summary.
- Cancellation and MAX OFF are checked at asynchronous boundaries, including final completion evidence.
- An accepted gesture with a missing/cancelled callback is uncertain, not a reason to repeat input through shell. Outer coroutine timeouts are propagated instead of swallowed by the gesture timeout handler.
- Removed the loop's ungrounded automatic Back dispatch. Modified loop/input logs no longer include raw goals, typed payloads or model action reasons.

## Measured verification

- Nine new vision-loop regressions were run before the loop fixes: all nine failed. They cover stale dispatch, input evidence and cancellation/runtime boundaries; the final expanded suite passes.
- Final focused integration run: **106 tests passed**, zero failures/skips across the suites below.
- New regression cases: **37** across four new test classes.
- Final full run: `:app:testDebugUnitTest --console=plain --rerun` — **1202 tests completed; 1200 passed, 2 failed, 0 skipped**. The same two failures were reported before this continuation and in its intermediate full run:
  - `LLMPlanningProviderUnitTest.test12_PromptBuilder_UserContent_SeparatesGoalAndContext`
  - `LLMPlanningProviderUnitTest.test9_PromptBuilder_DetailedContextForUITask`
- `:app:assembleDebug --console=plain` — **BUILD SUCCESSFUL**, run separately from the test command. APK exists at `app/build/outputs/apk/debug/app-debug.apk`.
- Targeted `git diff --check` passed. No commit, dependency/tool-registration change, Room migration or pipeline redesign was performed. Unrelated pre-existing working-tree changes were preserved.

| Focused suite | Cases | Result |
|---|---:|---|
| AssistantLanguageAndMemoryUnitTest | 13 | Pass |
| ReplyLanguageSwitchingTest | 8 | Pass |
| ReplyLanguageSelectorTest | 2 | Pass |
| GroundedActionSafetyTest | 13 | Pass |
| GenericActionExecutorTest | 7 | Pass |
| UiExecutionSafetyTest | 14 | Pass |
| UiVerificationLoopTest | 9 | Pass |
| GoogleMapsAutomationRegressionTest | 7 | Pass |
| ComputerUseReactiveLoopTest | 5 | Pass |
| KeyboardControllerUnitTest | 5 | Pass |
| CompoundNavigationComputerUseTest | 8 | Pass |
| GeminiWebSocketClientUnitTest | 15 | Pass |

## Acceptance still requiring a device/user

- This continuation did **not** install or launch the new APK, activate a microphone, call a real model, open another app or perform real device input. Earlier installation of the preceding Maps build is not acceptance of this update.
- Verify Telugu → English → Hindi → Tinglish in Home and Settings, including returning between screens, an active voice session, reconnect and app restart. Ask the same harmless question in each mode and check both rendered response and spoken language.
- Verify a non-sensitive UI flow with a moved target, keyboard focus, type/submit, cancellation and a subsequent independent task. No WhatsApp or externally consequential flow is authorized by these smoke steps.
- Strictly ambiguous/large trees, long input truncated by the observation pruner and animated vision-only screens can return an explicit unverified/stale result rather than guessing. They require fresh planning or stronger app-specific evidence.
- Generic screen change is not proof of every arbitrary goal. The tests do not establish universal task reliability, real Google Maps searches, model-language adherence, latency or physical-device correctness.

The next concrete acceptance step is an explicitly authorized installation and non-sensitive on-device language/input smoke test, followed by the two separately scoped prompt-expectation failures if a fully green JVM suite is required.

## Files touched in this continuation

These paths identify this continuation's edits, not ownership of every change already present in their Git diffs.

Language/session/UI:

- `app/src/main/java/com/example/data/preferences/SettingsManager.kt`
- `app/src/main/java/com/example/domain/policy/LanguagePolicy.kt`
- `app/src/main/java/com/example/domain/usecase/AssistantUseCase.kt`
- `app/src/main/java/com/example/network/LiveSessionState.kt`
- `app/src/main/java/com/example/network/GeminiWebSocketClient.kt`
- `app/src/main/java/com/example/voice/assistant/ConnectionManager.kt`
- `app/src/main/java/com/example/voice/assistant/AssistantEngine.kt`
- `app/src/main/java/com/example/ui/home/HomeViewModel.kt`
- `app/src/main/java/com/example/ui/settings/SettingsViewModel.kt`
- `app/src/main/java/com/example/ui/settings/SettingsScreen.kt`

Automation:

- `app/src/main/java/com/example/automation/engine/LiveNodeResolver.kt`
- `app/src/main/java/com/example/automation/engine/GenericActionExecutor.kt`
- `app/src/main/java/com/example/automation/computeruse/ComputerActionExecutor.kt`
- `app/src/main/java/com/example/automation/agent/UiVerificationLoop.kt`
- `app/src/main/java/com/example/automation/agent/UiActionExecutor.kt`
- `app/src/main/java/com/example/service/AccessibilityTreePruner.kt`

Tests:

- `app/src/test/java/com/example/assistant/ReplyLanguageSwitchingTest.kt` (new)
- `app/src/test/java/com/example/automation/GroundedActionSafetyTest.kt` (new)
- `app/src/test/java/com/example/automation/agent/UiExecutionSafetyTest.kt` (new)
- `app/src/test/java/com/example/ui/settings/ReplyLanguageSelectorTest.kt` (new)
- `app/src/test/java/com/example/automation/GenericActionExecutorTest.kt`
- `app/src/test/java/com/example/automation/ComputerUseReactiveLoopTest.kt`

Evidence/report:

- `docs/plans/reply-language-grounding/REPORT.md`
- Local, uncommitted coordination/evidence: `.unlazy/reply-language-grounding-2026-10-18/`.

## Reproduce the checks

From the repository root in PowerShell, use the verified JBR launcher for this workspace (not Java 8 on PATH):

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.example.assistant.ReplyLanguageSwitchingTest --tests com.example.automation.GroundedActionSafetyTest --tests com.example.automation.agent.UiExecutionSafetyTest --tests com.example.ui.settings.ReplyLanguageSelectorTest --console=plain --rerun
.\gradlew.bat :app:testDebugUnitTest --console=plain --rerun
.\gradlew.bat :app:assembleDebug --console=plain
```

The first command selects the 37 new regression cases. The full 106-case integration selection and automatic execution fingerprints are recorded in the local root ledger. The full-suite command currently exits nonzero for the two prompt tests listed above; run the APK build separately.
