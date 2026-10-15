# MAX repository instructions

## Read before changing a subsystem

- Read `PROJECT_MEMORY.md` (also required by `AGENT.md`), then `MAX_PROJECT_STATE.md` and `MAX_PROJECT_MAP.md`; use the relevant `docs/max/` page to locate source rather than rescanning the repository.
- Treat those documents as navigation, not executable truth. In particular, the Flutter-only instructions in `.agents/rules/max-project.md` and Flutter comments in `gradle.properties` are stale: `settings.gradle.kts` includes only `:app`, and `MainActivity` hosts Compose `MaxApp`. Do not recreate a Flutter module.
- Preserve the existing dispatch boundary: `GeminiWebSocketClient → ToolDispatcher → ActionDispatcher → ExecutionEngine`. Pipeline redesign requires approval. Tool registration changes in `MyApplication.kt` require updating `MAX_PROJECT_MAP.md`.
- Room schema/version changes require a migration and migration tests; do not use destructive migration as a repair shortcut.

## Android commands and traps

Run from the repository root in PowerShell, using the wrapper rather than an installed Gradle:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests com.example.automation.IntentRoutingGuardUnitTest --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
```

- The documented JDK above is a verified launcher; do not use the Oracle Java 8 on PATH. Gradle daemon criteria separately request Java 21 in `gradle/gradle-daemon-jvm.properties`; `gradle.properties` also names Android Studio's JBR. Changing only JAVA_HOME is not a daemon-JVM migration.
- `--offline` is useful with populated caches, not for first-time dependency setup. `:app:testDebugUnitTest --rerun` forces tests without forcing every compilation task.
- Run `assembleDebug` separately when you need an APK despite existing unit failures: a combined invocation stops at a failed test task. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Android namespace/classes are `com.example`, but the installed application ID is `com.maxaiagent.yash`. Old documentation's `com.example/.MainActivity` launch command is wrong for this application ID.
- KSP generates Room/Moshi sources; edit their source definitions, not `app/build/` output.
- The full suite is not a green baseline. Consult `docs/SECURITY_AUDIT_2026-10-09.md` for recorded model-expectation and full-suite-only failures; remeasure rather than labeling a new failure pre-existing.

## Windows and web package boundaries

- `windows-agent/main.py` is the desktop entrypoint (`startup.bat` invokes it); README's `python run.py` is stale. Starting it can activate real device/audio/automation services.
- Run Python checks from `windows-agent` with its own interpreter: `.\.venv\Scripts\python.exe -m pytest tests/test_network_and_recovery.py --tb=short -q`. The root `.venv` lacked `pytest-asyncio` during verification and produced misleading async-test failures.
- Do not blindly run `pytest tests/`: some tests perform live keyboard, desktop, audio or service operations. Inspect selected tests first. Set `$env:PYTHONIOENCODING='utf-8'` if Windows console encoding fails.
- `windows-agent/config.py` calls `load_dotenv(override=True)`; a discovered `.env` can override shell environment settings. Importing settings modules can load/write runtime storage; isolate them in security tests.
- `windows-agent/dashboard/` is the plain-JavaScript UI served by the Python HTTP router. `windows-agent/frontend/` is a separate Next.js package, not its source directory. Run `npm ci --ignore-scripts` and `npm run build` inside the frontend; its build includes TypeScript checking.
- `windows-agent/MAXOverlay/` is a separate WPF `net8.0-windows` project. A dotnet executable alone is insufficient: building requires the .NET SDK, which was absent during verification.

## Local security verification and operational boundaries

- In `tools/security-tests`, run `npm ci --ignore-scripts`, put a supported Java executable on PATH, then `npm test`; it starts Firestore on `127.0.0.1:18080` for `demo-max-security`. The test SDK loads root `firestore.rules`; local `deny-all.rules` is only the startup guard. `node --test dashboard.test.cjs` checks the dashboard HTML sink.
- `tools/license-generator/` is privileged owner tooling, not an application build step. Its `--register` option writes to Firestore with Admin authority; do not use it as a smoke test.
- Existing security findings include credentials in tracked files/history. Do not dump `.env`, signing properties, service-account JSON, `windows-agent/storage/certs/key.pem`, pairing databases or user backups into tool output. Ignoring a file does not untrack it. Rotation, deployment and history rewriting need explicit authorization.
- `TaskReceiver` is deliberately non-exported; do not re-export it to make ADB automation tests work. The native overlay WebSocket deliberately rejects browser Origins; browser dashboard access belongs on the HTTP interface.
- Existing instructions require explicit permission before opening/interacting with WhatsApp. Do not translate the old unconditional “Build → Install → Verify” guidance into unrequested live-device actions.
