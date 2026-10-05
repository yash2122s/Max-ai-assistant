# Gates: node-1.1 core-automation

OWNS: app/src/main/java/com/example/automation/**, app/src/main/java/com/example/service/JarvisAccessibilityService.kt, app/src/test/java/com/example/automation/**

Scope: Integration verification for unified automation routing, bilingual voice dispatch, resilient contact resolution, and feature-flagged accessibility execution.

- [x] G1: Full automation unit test suite passes cleanly
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.IntentRoutingGuardUnitTest" --tests "com.example.automation.ActionArgumentBoundaryUnitTest" --tests "com.example.automation.ContactResolutionUnitTest" --tests "com.example.automation.MessagingAutomationRoutingTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: All 4 test suites passed with 100% success rate under Gradle testDebugUnitTest.
