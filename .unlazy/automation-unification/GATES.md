# Gates: automation-unification

OWNS: app/src/main/java/com/example/automation/**, app/src/main/java/com/example/service/**, app/src/main/java/com/example/core/**, app/src/test/java/com/example/automation/**

Scope: End-to-end automation unification, bilingual voice guards, contact resolution, and safe execution fallback.

- [x] G1: All unit tests under com.example.automation pass cleanly
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.IntentRoutingGuardUnitTest" --tests "com.example.automation.ActionArgumentBoundaryUnitTest" --tests "com.example.automation.ContactResolutionUnitTest" --tests "com.example.automation.MessagingAutomationRoutingTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: Full core automation test suite verified with zero failures across all 4 leaves.
