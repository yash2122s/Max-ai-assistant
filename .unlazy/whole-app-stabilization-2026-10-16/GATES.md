# Gates: Whole-app stabilization

Scope: whole-app-stabilization-2026-10-16

- [x] G1: Both owned implementation leaves are parent-verified and their focused checks are current.
  EVIDENCE: Both leaves returned, reviewed, and their current focused regressions plus full unit suite passed.

- [x] G2: User-intent routing and fail-closed completion behavior are reviewed across both leaves.
  EVIDENCE: execute_automation planner routing, messaging/call guards, approval/unlock gates, and verified TASK_COMPLETED handling were reviewed and regression-tested.

- [x] G3: Full unit suite passes after integration.
  CHECK: $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: Executed after final integration fixes; BUILD SUCCESSFUL.

- [x] G4: Debug APK assembles after integration.
  CHECK: $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleDebug --console=plain --no-daemon
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: Executed after final integration fixes; BUILD SUCCESSFUL. APK output: app/build/outputs/apk/debug/app-debug.apk.
