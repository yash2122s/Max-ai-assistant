# Gates: App lifecycle and user-intent audit/fixes

OWNS: app/src/main/java/com/example/voice/**, app/src/main/java/com/example/core/**, app/src/main/java/com/example/data/**, app/src/main/java/com/example/ui/**, app/src/main/java/com/example/service/**, app/src/main/java/com/example/MyApplication.kt, app/src/main/AndroidManifest.xml, app/src/test/java/com/example/voice/**, app/src/test/java/com/example/core/**, app/src/test/java/com/example/data/**, app/src/test/java/com/example/ui/**

Scope: inspect and fix high-confidence voice, lifecycle, persistence, Compose/UI, service, manifest, and user-intent comprehension bugs in the owned scope.

- [x] G1: Focused voice/core/data/UI regressions pass.
  CHECK: $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests '*voice*' --tests '*core*' --tests '*data*' --tests '*ui*' --console=plain --no-daemon
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: VoiceInputProtocolUnitTest and AppDatabaseMigrationTest passed in the focused run; the complete :app:testDebugUnitTest suite passed after final integration changes.

- [x] G2: Debug compile passes for the application source after owned fixes.
  CHECK: $env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:compileDebugKotlin --console=plain --no-daemon
  EXPECT: BUILD SUCCESSFUL
  CWD: .
  EVIDENCE: Executed after final integration changes; BUILD SUCCESSFUL.

- [x] G3: Manual review confirms lifecycle/persistence changes do not bypass approval, unlock, or user-intent boundaries.
  EVIDENCE: Reviewed lifecycle generation guards, bounded voice queues, Room 23→24 migration, encrypted-settings failure behavior, approval routing, and completion handling. No live-device or external-side-effect verification was performed.
