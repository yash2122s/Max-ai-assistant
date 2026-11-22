# Gates: Reply and voice language switching

OWNS: app/src/main/java/com/example/data/preferences/SettingsManager.kt, app/src/main/java/com/example/domain/policy/LanguagePolicy.kt, app/src/main/java/com/example/domain/usecase/AssistantUseCase.kt, app/src/main/java/com/example/voice/assistant/AssistantEngine.kt, app/src/main/java/com/example/voice/assistant/ConnectionManager.kt, app/src/main/java/com/example/network/LiveSessionState.kt, app/src/main/java/com/example/network/GeminiWebSocketClient.kt, app/src/main/java/com/example/ui/home/HomeViewModel.kt, app/src/main/java/com/example/ui/settings/SettingsViewModel.kt, app/src/test/java/com/example/assistant/ReplyLanguageSwitchingTest.kt

Scope: Make reply-language selection observable and propagate it safely to live-session setup without changing model/provider or starting an inactive assistant.

- [x] G1: Reply-language switching regressions pass
  CHECK: set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr" && gradlew.bat :app:testDebugUnitTest --tests com.example.assistant.ReplyLanguageSwitchingTest --console=plain --rerun
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: automatic-evidence=v1; definition-sha256=af0299bf3a58847988155e060e9bba20730d9e83744355c3876f3e0b03cc423c; exit=0; EXPECT=matched; output-sha256=f7319e7b71a56ebbda67d968c28d068537e2143c72a0945400b191b9c0de5007; output-bytes=2174; shell=C:\WINDOWS\system32\cmd.exe; cwd=D:\max-new-updated\Max-AI-Agent; path=b097f3bbfa69/39 entries

- [x] G2: Independent review confirms preference synchronization, active session invalidation and no unrequested assistant activation
  EVIDENCE: Driver reviewed preference-flow registration/cleanup, Home/Settings collectors, existing-engine subscription, fresh-session generation guard and preserved call ledger. Eight ReplyLanguageSwitchingTest cases passed independently; active-setup and inactive-save controls are synthetic, not live voice acceptance. See docs/plans/reply-language-grounding/REPORT.md.
