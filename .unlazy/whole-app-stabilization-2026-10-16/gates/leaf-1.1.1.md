# Gates: Automation and network audit/fixes

OWNS: app/src/main/java/com/example/automation/**, app/src/main/java/com/example/network/**, app/src/test/java/com/example/automation/**, app/src/test/java/com/example/network/**

Scope: inspect and fix high-confidence automation/network correctness bugs, especially intent routing, execution truth, UI actions, Live tool dispatch, cancellation, verification, and regressions.

- [x] G1: Focused automation/network regressions pass.
  CHECK: $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests '*automation*' --tests '*network*' --console=plain --no-daemon
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: Selected current regressions passed with BUILD SUCCESSFUL: IntentRoutingGuardUnitTest, AutonomousTaskCoordinatorTest, RemoteCommandExecutionTest, GeminiWebSocketClientUnitTest, and PromptBuilderUnitTest. The full :app:testDebugUnitTest suite also passed after the final integration fixes.

- [x] G2: Owned source has no newly introduced unresolved compilation errors.
  CHECK: $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: Executed after final integration changes; BUILD SUCCESSFUL.

- [x] G3: Manual review confirms user-intent mismatch and unverified completion paths fail closed.
  EVIDENCE: Reviewed ToolDispatcher → AssistantEngine → ActionDispatcher routing. execute_automation remains on GoalPlanner, generic planner fallback is reachable, and TASK_COMPLETED requires correlated persisted task plus verified evidence.
