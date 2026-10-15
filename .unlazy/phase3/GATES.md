# Phase 3 acceptance gates

- [ ] G1: Canonical account scope, guest separation, legacy quarantine and stale A->B->A lease behavior pass real production-code tests.
  CHECK: .\gradlew.bat :app:testDebugUnitTest --tests "com.example.memory.account.*" --offline --console=plain
  EXPECT: BUILD SUCCESSFUL
- [ ] G2: Version 20->21 migration preserves encrypted payloads, IDs, chat, RAG and sync/period records and passes Room validation.
  CHECK: .\gradlew.bat :app:testDebugUnitTest --tests "com.example.data.local.*" --offline --console=plain
  EXPECT: BUILD SUCCESSFUL
- [ ] G3: All scoped memory/RAG/import/export/chat/privacy and existing authentication/licensing/execution regression checks are evaluated; every failure is classified from evidence.
  CHECK: .\gradlew.bat :app:testDebugUnitTest --offline --console=plain
  EXPECT: BUILD SUCCESSFUL
- [ ] G4: Kotlin compilation succeeds and debug APK is assembled separately.
  CHECK: .\gradlew.bat :app:assembleDebug --offline --console=plain
  EXPECT: BUILD SUCCESSFUL
- [ ] G5: Manual parent audit covers all inventory C1-C11, legacy consent UI reachability and no destructive/cloud changes. Report exact evidence, limits and changed paths.

All Gradle commands require the verified JDK launcher and PowerShell from the repo root. These are author-written oracles; no inherited root gate commands are executed. Native launch-receipt limitation is recorded in PLAN. Build/test evidence will be measured from actual command receipts/XML, not agent summaries.
