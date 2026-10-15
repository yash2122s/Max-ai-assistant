# Gates: basic execution regression

OWNS: app/src/main/java/com/example/automation/**, app/src/main/java/com/example/voice/assistant/AssistantEngine.kt, app/src/main/java/com/example/network/GeminiWebSocketClient.kt, app/src/main/java/com/example/domain/usecase/AssistantUseCase.kt, app/src/main/java/com/example/knowledge/apps/InstalledAppsRepository.kt, app/src/main/java/com/example/data/local/AgentTaskDao.kt, app/src/test/java/com/example/automation/**, app/src/test/java/com/example/network/LiveProtocolRegressionTest.kt, app/src/androidTest/java/com/example/automation/**, docs/plans/execution-regression/**, docs/max/AUTOMATION.md

Scope: restore existing verified single-action dispatch, bounded terminal lifecycle, WAIT/STOP compatibility, and honestly prove device outcomes.

- [ ] G1: forensic report identifies current and historical runtime chains and distinguishes reproduced defects from unverified device cause
  EVIDENCE: pending

- [ ] G2: current Gradle XML reports show the complete relevant execution, call, messaging, Live and WAIT/cancellation suites passing
  CHECK: node docs/plans/execution-regression/verify-results.mjs --relevant
  EXPECT: RELEVANT_EXECUTION_REGRESSIONS_PASS
  EVIDENCE: pending

- [ ] G3: application and real-device instrumentation APKs build
  EVIDENCE: pending; run :app:assembleDebug :app:assembleDebugAndroidTest using configured Windows JDK

- [ ] G4: real flashlight ON/OFF and YouTube/WhatsApp commands execute, verify, reach terminal success and clear Processing
  EVIDENCE: pending; configured ADB reports device 3bc00016 not found and no connected devices

- [ ] G5: real sequential commands and long WhatsApp/WAIT/YouTube/WAIT workflow finish with verified steps
  EVIDENCE: pending

- [ ] G6: real STOP TASK during WAIT cancels durable task, suppresses later YouTube launch and leaves no continuing execution
  EVIDENCE: pending

- [ ] G7: the full application unit suite is green
  CHECK: node docs/plans/execution-regression/verify-results.mjs --full
  EXPECT: FULL_APP_SUITE_PASS
  EVIDENCE: pending
