# MAX Security Audit

**Audit-only document. No security changes were implemented.**

## High/critical risks

1. **Cleartext traffic enabled (High):** `AndroidManifest.xml:69` sets `usesCleartextTraffic=true`. Endpoint usage is not proven, but the release default permits insecure transport.
2. **Broad privileged surface (High):** Manifest requests accessibility, overlay, microphone, calls/call log, SMS, contacts, location, camera, exact alarms, usage stats, storage, query-all-packages, notification access, device-admin and voice services. Least-privilege justification and tests are needed.
3. **PIN/unlock automation (Critical):** `onStartCommand()` accepts a PIN extra and `MaxLockAutomationEngine` types it through accessibility/coordinates. It must never enter model context, persistence, logs, or an untrusted exported path. Completion is not independently checked against keyguard state.
4. **Root/Shizuku shell control (High):** `ShellCommandPolicy` has useful allowlists and blocked tokens, but privileged command execution needs adversarial parser tests, length/encoding limits, and release authorization checks.
5. **Encrypted preference fallback (High):** `SettingsManager.kt:21-55` falls back to normal SharedPreferences after encrypted preference recovery fails. Sensitive keys include Gemini, Telegram, cloud, and UI-agent credentials.
6. **Backup/export surface (Medium/High):** Backup is enabled while chat, memory, settings, and license state exist; backup rules require explicit sensitive-data review.

## Positive controls

- Firestore rules enforce authenticated ownership and deny catch-all paths.
- License claiming uses a transaction.
- Release App Check selects Play Integrity.
- Shell commands are centrally policy checked.
- Authorization and approval components exist.
- Memory encryption and secure preferences are present, though fallback weakens guarantees.

## Required validation

Release TLS policy, backup inspection, exported-intent review, shell fuzzing, secret/log scans, PIN-flow isolation, and proof that every external/destructive action requires action-level authorization and task-bound approval.
