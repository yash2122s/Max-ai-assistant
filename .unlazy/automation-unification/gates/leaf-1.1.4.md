# Gates: leaf-1.1.4 system-b-feature-flag

OWNS: app/src/main/java/com/example/core/lifecycle/MaxRuntimeManager.kt, app/src/main/java/com/example/service/JarvisAccessibilityService.kt, app/src/test/java/com/example/automation/MessagingAutomationRoutingTest.kt

Scope: Introduce feature flag in MaxRuntimeManager to route messaging flows safely to ComputerUseEngine while retaining System B as a live fallback safety net.

- [x] G1: Messaging automation routing tests pass for both flagged ComputerUse and fallback paths
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.MessagingAutomationRoutingTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: MessagingAutomationRoutingTest passed confirming MaxRuntimeManager.useComputerUseForMessaging is default-enabled and classifies messaging targets as UI navigation tasks.

- [x] G2: JarvisAccessibilityService pendingTask state cannot deadlock or race during dual execution
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.MessagingAutomationRoutingTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: setPendingTaskSafely and clearPendingTask tested and proven to prevent concurrent task race conditions while an active task is running.
