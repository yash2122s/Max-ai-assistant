# Gates: Integrated reply language and automation safety

Scope: Independently check both implementation leaves, vision-loop regressions and the debug APK, with honest runtime limitations.

- [x] R1: Combined focused language and automation regressions pass
  CHECK: set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr" && gradlew.bat :app:testDebugUnitTest --tests com.example.assistant.ReplyLanguageSwitchingTest --tests com.example.assistant.AssistantLanguageAndMemoryUnitTest --tests com.example.ui.settings.ReplyLanguageSelectorTest --tests com.example.automation.GroundedActionSafetyTest --tests com.example.automation.GenericActionExecutorTest --tests com.example.automation.agent.UiVerificationLoopTest --tests com.example.automation.agent.UiExecutionSafetyTest --tests com.example.automation.GoogleMapsAutomationRegressionTest --tests com.example.automation.ComputerUseReactiveLoopTest --tests com.example.automation.KeyboardControllerUnitTest --tests com.example.automation.CompoundNavigationComputerUseTest --tests com.example.network.GeminiWebSocketClientUnitTest --console=plain --rerun
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=9e0344c7dbca560165b1a05e4ab1caddcef18abc50920148ca67061299dbb222; exit=0; EXPECT=matched; output-sha256=eecebe16b09c3ac90d508a1736db07168d540c24b0b635dc25bdc98289e92308; output-bytes=2034; shell=C:\WINDOWS\system32\cmd.exe; cwd=D:\max-new-updated\Max-AI-Agent; path=b097f3bbfa69/39 entries

- [x] R2: Driver reviews both leaf artifacts, vision-loop negative controls and preserved architecture/unrelated work
  EVIDENCE: Both native implementations were reviewed and independently reverified. Driver corrected integration, nullable fixture handling, runtime-state fixtures, focus evidence, semantic legacy bypass and uncertain gesture fallback. Nine original vision regressions failed before repair; final focused 106-case run passes. Full suite measured 1202 cases with only the same two prompt-test failures. No pipeline/schema/provider changes, commits or device operations in this continuation.

- [x] R3: Combined working tree produces a debug APK
  CHECK: set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr" && gradlew.bat :app:assembleDebug --console=plain
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=cf9e3850e11bb26e0f24126dd0283e3177527ba97d27a5d25435313b1e0e2c16; exit=0; EXPECT=matched; output-sha256=f3291580e1cc3394bd5682f99976bbb3dd0c37606789ae364d7a3e0dc7e8bbbf; output-bytes=2172; shell=C:\WINDOWS\system32\cmd.exe; cwd=D:\max-new-updated\Max-AI-Agent; path=b097f3bbfa69/39 entries

- [x] R4: Final report distinguishes measured tests/build from physical-device and live-model acceptance, and reports exact changed paths
  EVIDENCE: docs/plans/reply-language-grounding/REPORT.md records 37 new cases, 106 focused passes, 1202 full-suite cases/2 failures, successful separate APK build, all touched source/test paths and explicit no-install/no-live-voice/no-universal-reliability limitations. APK output directory was independently read and contains app-debug.apk.
