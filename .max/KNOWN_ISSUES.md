# MAX Migration Known Issues & Resolutions

| Issue ID | Category | Description | Status | Resolution / Mitigation |
| :--- | :--- | :--- | :---: | :--- |
| **KI-001** | Architecture | Legacy `MainActivity.kt` forwarded intent to `MaxFlutterActivity` | RESOLVED | Refactored `MainActivity` to directly extend `ComponentActivity` and host `MaxApp()`. |
| **KI-002** | Concurrency | High-frequency Audio RMS updates could cause excessive recomposition if collected at screen root | RESOLVED | Isolated RMS state to leaf composables `JarvisNeuralOrb` and `MaxVoiceVisualizer`. Verified 60fps performance on physical device. |
| **KI-003** | Manifest | `MaxFlutterActivity` held `MAIN` and `LAUNCHER` intent-filters | RESOLVED | Moved `MAIN` / `LAUNCHER` filters to `MainActivity`. Launch verified on physical Redmi K20 Pro. |
| **KI-004** | Permissions | Android 14+ / SDK 36 requires explicit `RECORD_AUDIO` grant for microphone FGS promotion | RESOLVED | Verified microphone permission flow and grant handling on physical device. |
| **KI-005** | Flutter / Bridge Elimination | Bridge classes and Flutter engine dependencies in Gradle could conflict with pure Kotlin builds | RESOLVED | 100% removed `max_flutter/`, `com.example.flutter`, and `project(":flutter")` dependencies. Pure Kotlin build verified. |
