# Gates: Semantic action safety

OWNS: app/src/main/java/com/example/automation/engine/LiveNodeResolver.kt, app/src/main/java/com/example/automation/engine/GenericActionExecutor.kt, app/src/test/java/com/example/automation/GenericActionExecutorTest.kt, app/src/test/java/com/example/automation/GroundedActionSafetyTest.kt

Scope: Resolve safe live semantic targets, use current bounds, and fail closed instead of acting on stale or ambiguous targets.

- [x] G1: Semantic target and action safety regressions pass
  CHECK: set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr" && gradlew.bat :app:testDebugUnitTest --tests com.example.automation.GroundedActionSafetyTest --tests com.example.automation.GenericActionExecutorTest --console=plain --rerun
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=a4eaea28db5efb84c6fdefe19f5884695b5fd4a9e90d95eaa9525da83dfd62f1; exit=0; EXPECT=matched; output-sha256=1c477aad3419805fe89b42c2940b9c1076aeee3318a50abaf088f99ddb48ece0; output-bytes=2034; shell=C:\WINDOWS\system32\cmd.exe; cwd=D:\max-new-updated\Max-AI-Agent; path=b097f3bbfa69/39 entries

- [x] G2: Independent review confirms stable target evidence, bounded traversal and safe fallback behavior
  EVIDENCE: Driver reviewed exact/unique signature matching, visible/enabled checks, node/depth/time budgets and fallback reacquisition. Thirteen GroundedActionSafetyTest and seven GenericActionExecutorTest cases passed; negative controls reject stale/duplicate/password targets, and adapter rejection cannot escape through legacy resolution. No physical-device claim.
