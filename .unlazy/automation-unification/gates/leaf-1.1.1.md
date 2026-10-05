# Gates: leaf-1.1.1 routing-inversion

OWNS: app/src/main/java/com/example/automation/engine/ActionDispatcher.kt, app/src/test/java/com/example/automation/IntentRoutingGuardUnitTest.kt

Scope: Invert routing in ActionDispatcher so all app-level and UI navigation tasks default to ComputerUseEngine while preserving native device action bypasses.

- [x] G1: WhatsApp and Telegram tasks route to ComputerUseEngine and pass IntentRoutingGuard tests
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.IntentRoutingGuardUnitTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: IntentRoutingGuardUnitTest executed successfully with 9 passing tests verifying inverted UI navigation routing for WhatsApp, Telegram, YouTube, Instagram.

- [x] G2: Native hardware and system setting bypasses remain intact
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.ActionArgumentBoundaryUnitTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: ActionArgumentBoundaryUnitTest passed with 100% success confirming system actions and hardware bypasses.
