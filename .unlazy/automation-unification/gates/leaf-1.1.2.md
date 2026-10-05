# Gates: leaf-1.1.2 bilingual-voice-audit

OWNS: app/src/main/java/com/example/automation/engine/ActionDispatcher.kt, app/src/test/java/com/example/automation/ActionArgumentBoundaryUnitTest.kt

Scope: Audit and align all English-only string checks (.startsWith / .contains) across ActionDispatcher with bilingual (Telugu + English) patterns from ActionAuthorizer.

- [x] G1: Telugu phone call utterances pass bilingual CALL_PHONE guard in ActionDispatcher
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.IntentRoutingGuardUnitTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: IntentRoutingGuardUnitTest passed verifying Telugu call commands (e.g. 'నాన్నకు కాల్ చెయ్', 'డాడీకి ఫోన్ చేయి', 'కాల్ చేయండి', 'call chey') authorize call flow.

- [x] G2: Telugu messaging utterances are accurately redirected to messaging intent without collision
  CHECK: .\gradlew.bat testDebugUnitTest --tests "com.example.automation.IntentRoutingGuardUnitTest"
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: Verified: IntentRoutingGuardUnitTest passed verifying Telugu messaging commands ('వాట్సాప్‌లో మెసేజ్ పంపు', 'నాన్నకు మెసేజ్ పెట్టు', mixed message bodies) route to messaging.
