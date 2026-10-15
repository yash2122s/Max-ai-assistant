# Gates: MAX Live lifecycle and execution verification

Scope: Integrate extended-thinking Live protocol into the existing execution pipeline and verify its truth, cancellation, and observation boundaries. This ledger is separate from the pre-existing outgoing-call ledger.

- [ ] G1: LOW/MEDIUM/HIGH setup, non-blocking declarations, final-frame ordering, missing-status handling, cancellation, duplicate calls, and private-thought suppression pass focused protocol regressions.
  CHECK: /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"; & .\gradlew.bat :app:testDebugUnitTest --tests "com.example.network.LiveProtocolRegressionTest" --tests "com.example.network.GeminiWebSocketClientUnitTest" --no-daemon --console=plain; exit $LASTEXITCODE'
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: pending
- [ ] G2: Planner advancement requires verified results; invalid dependencies and ambiguous side effects cannot advance or replay; focused workflow regressions pass.
  CHECK: /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"; & .\gradlew.bat :app:testDebugUnitTest --tests "com.example.automation.AgentPersistenceRegressionTest" --tests "com.example.automation.LongHorizonTaskExecutionTest" --tests "com.example.automation.TaskTransitionAuthorityTest" --no-daemon --console=plain; exit $LASTEXITCODE'
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: pending.
- [ ] G3: Fresh semantic observations identify state changes and stale targets; missing/incorrect foreground and unchanged key dispatch cannot verify success.
  CHECK: /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"; & .\gradlew.bat :app:testDebugUnitTest --tests "com.example.automation.AgentVerificationRegressionTest" --no-daemon --console=plain; exit $LASTEXITCODE'
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: pending.
- [ ] G4: The debug app and test APK compile using the configured Windows JDK/SDK.
  CHECK: /mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe -NoProfile -Command '$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot"; & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain; exit $LASTEXITCODE'
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: pending.
- [ ] G5: Install on 3bc00016 and verify safe navigation, observation, and app startup without external side effects.
  EVIDENCE: pending.

## Observed results (direct Gradle/ADB runs, not checker certification)
- 2026-10-04: focused suite passed **139 tests, 0 failures**, including the new protocol, observation, persistence, cancellation and call-truth regressions.
- Final standalone APK check: `:app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain` returned BUILD SUCCESSFUL (15 seconds; 75 tasks up-to-date following compilation/packaging).
- Final full-suite run: **860 tests, 857 passed, 3 failed**. All three failures are in `UiAgentModelTest`, where expectations name `gemini-2.5-flash` but the pre-existing REST UI-agent default names `gemini-3.8-live-extended-thinking`. Neither that default nor those three expectations was changed in this task.
- A memory-projection test failed in an intermediate run and passed in the final full-suite run. No memory-projection code was changed.
- Installation attempt: `adb.exe -s 3bc00016 install -r ...app-debug.apk` returned `device '3bc00016' not found`.
- Gates G5–G7 remain unverified. No real model-access, latency, device navigation, approval-continuation, or restart-resumption result is claimed.
- The runnable gates above are rerun instructions. Their automatic evidence is pending because the unlazy checker was not used for the direct Gradle runs.
- [ ] G6: Establish an authenticated extended-thinking Live session and measure connection, first response, and tool latency without exposing credentials or private reasoning.
  EVIDENCE: pending; depends on configured account/model access and device availability.
- [ ] G7: Demonstrate durable multi-step replanning, approval/input continuation, reconnect/restart recovery, and correlated persisted completion on device.
  EVIDENCE: pending; mock protocol tests do not satisfy this gate.
- [ ] G8: Reconcile the implementation report with observed evidence, changed paths, tests, build/install results, and any unmet acceptance criteria.
  EVIDENCE: pending.

## Protocol reference
Google's official `googleapis/python-genai` `google/genai/types.py` defines `LiveServerContent.interaction_status`, `ThinkingLevel`, `Behavior.NON_BLOCKING`, `FunctionResponse.will_continue`/`scheduling`, tool-call cancellation, session resumption and context-window compression. This proves protocol definitions, not this account's access to a particular model. The documentation endpoint returned HTTP 429 during this audit.

## Implementation boundaries
Use GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine and the existing LongTaskOrchestrator/AgentTaskStore. Preserve existing task-bound approval and call-controller work. Model turn/interaction completion never writes task success. Screen data and tool responses are untrusted input, not authorization.
