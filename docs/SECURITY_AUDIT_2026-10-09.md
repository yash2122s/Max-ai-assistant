# MAX security audit, targeted hardening and verification

**Date:** 2026-10-09 (Asia/Calcutta)  
**Verdict: PARTIALLY REMEDIATED**  
**Baseline revision:** `0f37553`, plus the substantial pre-existing dirty working tree. This is not an audit of that commit alone.

## 1. Executive summary

Confirmed dangerous entry points were hardened locally: unauthenticated Windows HTTP chat/event access, unsafe browser-origin handling, an exported Android goal-execution receiver, the native overlay WebSocket's browser exposure, dashboard event XSS, and Firestore entitlement forgery. The Next.js frontend was updated within major version 16 and its final npm audit reports zero known vulnerabilities.

This is **not a completed, exhaustive certification of the entire codebase**. Credentials remain exposed in reachable Git history, deployment controls were not accessible, dependency findings remain in Python and owner tooling, and additional source-confirmed risks remain below. The latest Android full suite has five failures, versus three in the baseline; two new security tests pass. Functionality preservation is therefore **not fully established**.

No production system was tested or changed. No credential was rotated, deleted, reproduced in this report, or submitted to an external scanner. No commits, pushes, publishing, Git history rewriting, device installations, or destructive schema changes were performed. Tests used synthetic data and a local Firestore emulator with a `demo-` project ID. A WebSocket test used a loopback ephemeral port and a no-op handler, not real automation.

## 2. Actual stack and sensitive boundaries

| Component | Evidence / stack | Entry points and sensitive operations |
|---|---|---|
| Android application | Kotlin 2.3.20, AGP 9.3.2, Jetpack Compose, compile SDK 36.1, target 36, minimum 24; `app/build.gradle.kts`, `gradle/libs.versions.toml` | `MyApplication`, `MainActivity`, voice services, AccessibilityService, notification listeners, scheduler broadcasts, companion connection and Telegram service |
| Android data | Room 2.7.0 / SQLite; encrypted shared preferences; Firebase Auth, Firestore, App Check and AI SDK | Local conversations, personal memory, contacts/notifications, device pairing credentials, trial and paid entitlements |
| Android networking | OkHttp 4.10.0, Retrofit 2.12.0, Gemini/OpenRouter paths | AI tool arguments, screenshots/audio, pairing and remote desktop commands |
| Windows agent | Python; inspected virtual environment uses Python 3.10.8; asyncio raw HTTP, `websockets`, Pydantic, SDK integrations, SQLite | `windows-agent/main.py`, `server/api_router.py`, `server/websocket_server.py`, `server/ui_bridge.py`; filesystem/process/computer-use tools |
| Browser dashboard | Plain JavaScript/HTML/CSS served by Python HTTP server | Chat/tool calls, pairing display, metrics and event stream |
| Separate frontend | Next.js 16.2.10 before / 16.4.0 after; React 19, TypeScript, Three.js, MediaPipe | `windows-agent/frontend/app/page.tsx`; build produces static root and not-found pages; server exposure is deployment-dependent |
| Native Windows overlay | C# WPF, `net8.0-windows` | `MAXOverlay` client communicating with the loopback UI bridge |
| License administration | Node.js owner CLI, Firebase Admin 12.7.0 | `tools/license-generator/generate.js`; privileged key seeding only when explicitly run with registration options; not executed in this audit |
| Deployment/configuration | `firebase.json`, `firestore.rules`, Gradle signing configuration, frontend config, Windows startup | No tracked GitHub Actions workflow returned by the scoped Git inventory; no production ingress/firewall/IAM configuration verified |
| Other folders | Shared skill schemas; assets; ignored offline license tools, backups and release artifacts; extensive existing docs/tests | Inventoried, not all source files semantically reviewed or all binaries decompiled |

The gateway module describes webhooks but the inspected implementation is a status/processing stub, not proof of a deployed internet webhook listener. No SQL server administration port, password database, server-side cookie login flow, or upload endpoint was established in the reviewed HTTP router. Do not infer those components from the original checklist.

### Threat model

Assets: desktop execution authority; Android accessibility/calling/messaging permissions; user memories, transcripts and notifications; pairing and AI credentials; license/admin authority; application signing integrity.

Attackers considered: an unpaired LAN peer, a malicious website in the user's browser, another Android app, an authenticated Firebase user modifying their own requests, and someone obtaining repository history. Registered desktop devices and native processes on the same OS account remain broadly trusted by the existing architecture. The HTTP hardening is **not** OS-user isolation or least-privilege per-tool authorization for paired devices.

Important boundaries: browser to local HTTP/WebSocket; LAN peer to paired agent; Android caller UID to private receiver; Firebase UID to document path; client entitlements to trusted key issuance; model/tool arguments to real device actions; working tree and Git history to runtime credentials.

## 3. Ranked confirmed findings and remediation evidence

Line references marked **before** refer to the source read before this audit's patches, not necessarily Git HEAD. Function names remain stable references.

### MAX-SEC-001 — Critical: unauthenticated desktop HTTP control and event access

- **Affected:** `windows-agent/server/api_router.py`, `handle_request`; before lines 236–325 and 439–499. `main.py:359` constructs HTTPServer with the configured host.
- **Path/scenario:** an unpaired caller reaching the HTTP listener could submit chat to `agent_execution_loop.execute_goal_loop`, or subscribe to events containing chat/tool data, without the tool endpoint's separate token check. Host defaults are broad in `core/settings_manager.py:19`; actual firewall/reachability is unverified.
- **Evidence:** source control flow reaches execution/publication without authentication; new isolated tests prove unauthenticated chat/tool/status/metrics/event variants are rejected after the patch.
- **Fix:** shared boundary before routing. Remote sensitive requests require a nonempty valid pairing bearer token. Trusted local native/dashboard requests remain supported; browser origins are checked separately.
- **Compatibility:** anonymous LAN dashboard consumers now receive denial. Native remote clients must send `Authorization: Bearer ...`; token-in-body fallback is removed. Use paired WSS for Android companion traffic. HTTP transport itself is not upgraded to TLS.
- **Verification:** route tests with fake tools verify both denial-before-execution and successful trusted local/remote calls; valid chat and event-stream delivery remain tested. **Locally remediated within this boundary, not deployed.**

### MAX-SEC-002 — High: browser-origin prefix bypass and pairing CORS leakage

- **Affected:** `api_router.py`; before lines 125, 164–181, 349–374, 462.
- **Path/scenario:** origin prefix matching accepted attacker-controlled authorities extending the localhost prefix. Pairing had only a peer-IP restriction and wildcard CORS; a web page could target loopback subject to browser local-network policy.
- **Fix:** exact origin allowlist, local Host authority checks including configured port, hostile/null-origin rejection, cross-site Fetch Metadata rejection, no wildcard CORS. Native callers without Origin remain supported. JSON API responses receive no-store, nosniff and frame-denial headers.
- **Compatibility:** trusted development origins on loopback port 5173 are explicitly retained; arbitrary origins are not. No credentialed wildcard origin is used. No HSTS was added to a plain-HTTP service.
- **Verification:** invalid/prefix/null origins, DNS-rebinding Host, cross-site requests and remote pairing rejection tests, with trusted-client positive controls. **Locally remediated.**

### MAX-SEC-003 — High: Android goal receiver exposed to other apps

- **Affected:** `app/src/main/AndroidManifest.xml` TaskReceiver declaration (before line 166); `automation/scheduler/TaskReceiver.kt:20–41`.
- **Path/scenario:** an external app could broadcast execute/cancel-goal actions; attacker-provided goal text reaches `GoalPlanner.planAndExecute` under MAX's capabilities. No receiver permission separated the caller from MAX.
- **Fix:** only TaskReceiver's `android:exported` changed from true to false. Internal explicit AlarmManager delivery remains intact.
- **Compatibility:** external/ADB broadcast automation is intentionally no longer a production authority. No accessibility, calling, messaging, scheduling or engine implementation was disabled.
- **Verification:** Robolectric reads the merged manifest and checks non-exported status; a separately labeled source-level guard checks explicit immutable scheduler PendingIntents. Both pass. Actual on-device cross-UID denial and alarm delivery were not exercised.

### MAX-SEC-004 — High: runtime credentials in tracked files and reachable history

- **Affected:** TLS private key and pairing-token paths in section 5, plus an older Android preferences backup.
- **Path/scenario:** repository access can yield a desktop TLS identity and pairing tokens, or old provider credentials. Tokens may allow remote desktop authentication until revoked. Their current live validity was deliberately not tested.
- **Evidence:** local metadata-only scan over tracked text and every reachable Git blob; private-key marker and token patterns detected without printing values. This is repository exposure, not proof the remote repository is public.
- **Remediation:** revoke/regenerate and re-pair through an owner-controlled process; remove runtime files from tracking in a separately authorized change; consider coordinated history cleanup only after rotation. Ignore rules alone do not fix tracked content.
- **Compatibility/testing:** re-pairing invalidates existing connections/pins; verify new certificate pin and revoked-token rejection on staging. **Unresolved: no credential rotation or history/index changes performed.**

### MAX-SEC-005 — High: Firestore entitlement forgery and revocation bypass

- **Affected:** `firestore.rules`, license create/update and key claim rules; `FirestoreLicenseRepository.kt:110–128,165–205` demonstrates the legitimate client write shapes.
- **Path/scenario:** trial creation did not constrain timestamps; lifetime creation/update accepted an `UNUSED` key without requiring its atomic claim. Metadata updates could change unguarded entitlement fields. A revoked license could be rewritten ACTIVE using an already claimed key.
- **Fix:** server-relative maximum three-day initial trial, server timestamps for start/issue/update, matching expiry fields; `getAfter()` requires a LIFETIME key CLAIMED by the same UID; metadata mutation limited to updatedAt; revoked documents reject client update; key claim can change only claim fields.
- **Compatibility:** genuine atomic claim/activation remains allowed. Devices with clocks ahead of the server may fail the strict trial-expiry upper bound; authoritative trial creation on a backend is the longer-term solution. Existing forged records are not repaired. Admin SDK operations bypass rules and still require IAM controls.
- **Verification:** **9 emulator tests passed**, covering legitimate creation/claim, anonymous/cross-user denial, future/missing/malformed trial timestamps, unclaimed-key activation, cross-user reuse, expiry/key-field modification, enumeration/key creation, and revoked-license reactivation. Rules compiled and executed in the emulator. **Locally verified prototype; not deployed.** Daily usage and broader schema gaps remain in MAX-SEC-012.

I've set up prototype Security Rules to keep the data in Firestore safe. They are designed to be secure for the tested ownership, trial-duration, atomic license-claim and revocation boundaries. However, you should review and verify them before broadly sharing your app. If you'd like, I can help you harden these rules.

### MAX-SEC-006 — High: native overlay WebSocket accepted browser callers

- **Affected:** `windows-agent/server/ui_bridge.py:98–106,184–214` before the patch.
- **Path/scenario:** a hostile page could open the loopback socket, receive events and submit chat, computer-use or confirmation messages. Binding to loopback does not establish that the client is a trusted native app.
- **Fix:** `websockets.serve(..., origins=[None])` on the native-only overlay channel.
- **Compatibility:** native C#/Python clients without Origin remain accepted; browser clients, including local browser origins, are denied on this channel. The separate browser dashboard uses its HTTP API.
- **Verification:** loopback ephemeral-port handshake test accepts a no-Origin client and rejects three browser origins before a harmless handler is reached. **Locally remediated for browser attacks; local malicious processes remain a risk.**

### MAX-SEC-007 — High: event-payload XSS in local dashboard

- **Affected:** `windows-agent/dashboard/assets/js/views/activity.js`, before lines 36–56.
- **Path/scenario:** remote names, chat text or tool output entered `JSON.stringify(evt.payload)` and then `innerHTML` without HTML encoding. An active event payload could execute in the control dashboard's origin.
- **Fix:** context-specific HTML text encoding for payload, event type and rendered time. Original event content is not sanitized or altered in storage.
- **Verification:** **1 Node test passed** against the actual view-rendering path with a synthetic image-handler payload; executable markup is encoded and ordinary Unicode text preserved. Browser execution/CSP integration was not dynamically tested. **Locally remediated at this sink.**

### MAX-SEC-008 — Critical advisory severity: vulnerable frontend lockfile

- **Affected:** `windows-agent/frontend/package.json` and `package-lock.json`; Next.js 16.2.10 and transitive packages.
- **Evidence/scenario:** npm audit returned critical Next.js Windows-hosted and image-related RCE advisories, plus conditional SSRF/DoS/cache issues. Affected package versions are confirmed; each advisory's feature reachability is not proven. Static page output does not certify a running Next server against unrelated endpoints.
- **Fix:** update within major 16 to 16.4.0, raise manifest minimum, refresh compatible transitives; no forced major migration.
- **Verification:** frontend production build passed before and after; final audit **0 known vulnerabilities**. **Dependency version remediation verified; deployment version unknown.**

### MAX-SEC-009 — Medium: unbounded HTTP body reads / incomplete request limits

- **Affected:** `windows-agent/server/http_server.py`, before lines 26–55.
- **Path/scenario:** callers could announce arbitrarily large bodies or hold reads open, consuming memory/connections before route authentication.
- **Fix:** 1 MiB body, 8 KiB individual line, 32 KiB aggregate headers, a shared read deadline, rejection of duplicate headers, unsupported transfer encoding, missing HTTP/1.1 Host and incomplete requests. SSE processing is outside the read deadline.
- **Verification:** synthetic StreamReader tests reject oversized/negative/invalid/duplicate/truncated input and timeout, and dispatch a valid bounded request. **Partially remediated:** connection concurrency, request frequency, response size and SSE queue limits remain unbounded. Malformed parser requests currently use a generic 500 rather than differentiated 400/408/413 statuses.

### MAX-SEC-010 — High: unauthenticated companion reconnect can disrupt a paired session

- **Affected:** `windows-agent/server/websocket_server.py:206–223`, before authentication at lines 288–357.
- **Evidence/scenario:** a caller-supplied device ID previously closed the active connection before proving possession of the paired secret, allowing denial of service against active sessions.
- **Fix:** removed premature unauthenticated closure of `existing_ws`. Reconnection and socket replacement now take place strictly after successful authentication (`if authenticated:`) under either pair or auth-response success.
- **Verification:** tested against WebSocket packet routing and py_compile; existing 54-test suite passes with 0 regressions. **Locally remediated.**

### MAX-SEC-011 — Medium: Android backup exclusions omit companion credentials

- **Affected:** `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml`; `WindowsAgentClient.kt:87–94,112–126`.
- **Evidence/scenario:** companion state uses `windows_agent_prefs.xml` (storing `auth_token`, `device_id`, and `cert_fingerprint`). Backup rules excluded the main database and secure settings, but omitted this file.
- **Fix:** added `<exclude domain="sharedpref" path="windows_agent_prefs.xml"/>` to `backup_rules.xml` and to both `<cloud-backup>` and `<device-transfer>` in `data_extraction_rules.xml`.
- **Verification:** XML structure verified, build succeeds, assembleDebug passes. **Locally remediated.**

### MAX-SEC-012 — High: daily quota is not a trusted backend enforcement boundary

- **Affected:** `firestore.rules` dailyUsage rules; `FirestoreLicenseRepository.kt:225–240,251–365`.
- **Evidence/scenario:** rules allow a counter increment without a server-enforced upper bound/current-day binding; client chooses the period and can bypass making the request entirely when calling AI with its own client-held credential. The client reader also returns zero on read error. License-rule hardening alone does not enforce paid AI usage.
- **Recommendation:** if MAX pays for shared AI usage, move billed operations behind an authenticated backend with server-time quotas, per-user authorization, App Check as defense in depth, budgets and rate limits. Preserve BYOK behavior as a separate model rather than embedding a privileged provider key in an APK.
- **Compatibility/testing:** this requires an explicit product/credential-flow migration, staging cross-user tests and quota tests. **Not remediated by an architectural rewrite in this pass.** Profile/device/key document schemas also need broader type/size validation; owner-only fields are not automatically admin authority.

### MAX-SEC-013 — Medium: authentication material and personal input may enter logs

- **Affected:** `websocket_server.py:97` previously logged the actual pairing code; `TaskReceiver.kt:29,36,55` logs goals/arguments; `ui_bridge.py` logs chat text.
- **Evidence/scenario:** log readers may obtain active pairing credentials or personal input.
- **Fix applied:** removed plaintext pairing code logging from `websocket_server.py:97` (`logger.info("Initialized server with pairing manager.")`).
- **Status:** pairing code log sink remediated. Broader application logging review across all subsystems remains recommended.

## 4. Other review observations and scope boundaries

- Android privileged bindable services in the manifest generally declare their platform bind permissions (accessibility, notification listener, voice interaction, recognition, device administration, quick-settings). An exported service is not automatically vulnerable when permission-protected.
- Android cleartext traffic is false and release debuggable is false. Release minification is disabled; that is not proof of a secret leak and enabling obfuscation would not secure embedded credentials.
- No JavaScript WebView bridge or mutable PendingIntent was returned by the scoped Kotlin pattern search. This is a search result, not APK-wide proof.
- The companion TLS client checks a certificate fingerprint when pinned and fails for a paired device missing its pin. Its hostname-verifier override is therefore not automatically a trust-all finding. Initial TOFU/QR verification and fallback behavior still require device tests.
- Firebase API keys in client configuration are identifiers, not service-account secrets. Security relies on rules, Auth, applicable API restrictions and App Check enforcement; those console settings were not verified.
- Room DAO queries sampled use bound parameters. SQLite DDL and state persistence were inspected; no confirmed SQL injection was established in those sampled paths. This is not coverage of every database operation. No data/schema migration was executed against production.
- Windows tools intentionally support files, shell/processes, computer use and external requests. Those capabilities are not inherently injection/SSRF defects. Full authorization/argument tracing for every tool, imported skill, MCP server and model-generated action remains required.
- General file import size/path validation, ZIP traversal, all serialization paths, outbound network SSRF restrictions, Telegram caller authorization and all advanced Android departments were not exhaustively reviewed. No claims of safety are made for those unreviewed paths.
- Reviewed HTTP APIs do not use session cookies. Cookie flags and password hashing were not blindly added. Firebase JWT signature/issuer/audience/expiry behavior was not replaced by custom token parsing. Companion auth is an HMAC challenge protocol, not JWT.
- Internet ingress, HTTPS termination, certificate renewal, firewall rules, cloud IAM, App Check enforcement, rate/billing policies, production rules version, backups/retention and recovery procedures require owner deployment access.
- No CSP or HSTS compatibility claim is made. JSON headers were added, but a complete dashboard/browser header policy was not deployed or browser-tested.

## 5. Secret exposure and Git-history scan

Established scanners (`gitleaks`, `trufflehog`, `detect-secrets`) were not available. A small local read-only detector was used instead, with seven pattern classes and synthetic positive controls. It emits only type/path/line/tracked status/blob ID; it never emits matched values or source lines.

**Measured scan:** 1,622 working-tree text files (tracked files plus selected local credential/config paths); 37 reachable commits; 11,922 distinct reachable text blobs. Re-run after changes returned the same credential findings. Text files over 8 MB and binary/NUL-containing files were skipped. Unreachable objects/reflogs, all ignored/untracked folders, compiled APKs, JKS files, large backups, packed media and arbitrary entropy-only secrets were not exhaustively scanned. This is **not equivalent to a clean Gitleaks scan**.

| Type | Path and safe location | Exposure / action |
|---|---|---|
| Windows TLS private key | `windows-agent/storage/certs/key.pem:1` | Tracked and in reachable history. Regenerate identity under owner control and re-pair pinned clients. Actual PEM body withheld. |
| Windows pairing tokens | `windows-agent/storage/paired_devices.json:5,10,15,20` | Four current matching entries; historical versions also present. Revoke old pairings and establish new tokens. Values withheld. |
| Google/Gemini API credential candidate | `MAX_DATA_BACKUP/database_original_copy/jarvis_vqzrtp/jarvis_vqzrtp_backup/shared_prefs/jarvis_settings.xml:10` in history | Historical preferences backup exposure; rotate via its provider and verify API restrictions. No validity probe performed. |
| Telegram bot token candidate | Same historical preferences path, line 19 | Revoke/reissue using the bot owner's provider workflow; update private configuration. No token tested. |
| Firebase client API key | `app/google-services.json:31`; `app/src/main/java/com/example/firebase/FirebaseInitHelper.kt:25` | Distributed client configuration. Not classified as a privileged service-account leak merely because it is public. Verify intended API/application restrictions. |
| Firebase Admin service-account private key | `tools/license-generator/serviceAccountKey.json:5` | Local and untracked; not detected in scanned reachable history. Presence alone is not proof of exposure. Keep private; review IAM/ACLs and rotate if shared elsewhere. |
| Fixture-like API tokens / PEM markers | Current security/planner tests and old removed `hermes-agent-main` test/redaction files | Pattern hits in test/redaction contexts. Do not treat each hit as a real credential; individual value validity was not tested. |

`.env`, `local.properties`, signing properties and the selected service-account path were included when present, without displaying values. Android signing files and offline licensing tools are ignored in the existing configuration, but this does not prove they were never copied elsewhere.

### Credentials requiring owner rotation or revocation

1. **MAX Windows companion:** TLS private key/certificate identity and all exposed pairing tokens; re-pair devices and verify new pins.
2. **Google/Gemini:** provider API credential found in the historical Android preferences backup; revoke old key and set restricted replacement in private configuration.
3. **Telegram:** bot token found in that backup; revoke old token and replace privately.

No recommendation to rotate the Android signing identity or service account is made solely from their local presence. Investigate access history first. Firebase client API-key restrictions need verification, not an assumption that all client identifiers must be secret.

Owner steps: rotate/revoke first; verify old credentials fail without printing them; remove runtime credential files from version tracking while preserving local replacements; separately approve any history rewrite and coordinate all clones. `.gitignore` additions do not untrack current files or purge history.

## 6. Dependency audit

| Ecosystem / scope | Actual result | Change / remaining work |
|---|---|---|
| Frontend npm lock | Before: 6 affected packages (1 critical, 4 high, 1 moderate). After: **0**; 69 dependency entries reported | Next 16.2.10 to 16.4.0 and compatible transitive updates; production build passed. No major-version jump. |
| License-generator npm lock | **9 affected packages: 2 high, 7 moderate**, before and after compatible update attempt | Firebase Admin remains 12.7.0. npm recommends major 14.5.0 for the aggregate fix. No blind major migration. Validate generator/Admin API compatibility separately. |
| Installed Windows Python environment | `pip-audit` 2.10.1 reports **24 advisory records in 3 packages**, including duplicate/alias records | `pip` 22.2.2, `setuptools` 63.2.0, `PyJWT` 2.14.0. These are not 24 independently proven exploits. PyJWT fixes reported at 2.15.0; pip fixes through 26.2; setuptools through 83.0.0. Environment/toolchain upgrades and full compatibility checks remain. |
| Android | Direct version catalog reviewed; `debugRuntimeClasspath` transitive dependency inventory generated successfully | No complete Maven vulnerability-database audit completed. Old/alpha/deprecated components are not automatically confirmed exploitable CVEs. |
| WPF/.NET | Project targets .NET 8 Windows; no explicit PackageReference in inspected project | `dotnet list ... --vulnerable --include-transitive` and build blocked: no SDK installed. Runtime/OS patch level unverified. |
| New local emulator test harness | Full npm audit: **16 affected dev packages: 12 high, 4 moderate**; `--omit=dev`: **0** | Official Firebase CLI 15.33.0, Firebase JS 12.19.0, rules-unit-testing 5.0.2. Alerts include grpc-js and CLI transitives; these tools are not shipped in Android/frontend. Keep loopback/demo-only and isolated. No forced downgrade recommended by npm was applied. This toolchain is not described as clean. |

The PyPA-maintained `pip-audit` tool was installed in an isolated temporary virtual environment, not into the application's environment. Firebase official emulator/test tooling was installed as development-only dependencies with lifecycle install scripts disabled. Registry vulnerability queries exchanged package metadata, not project secret values. No abandoned dependency was removed on filename or age alone.

## 7. Exact files changed by this audit

Existing unrelated dirty files were preserved. In particular, the substantial pre-existing Android engine, UI, build and Firestore work must not be attributed wholesale to this audit.

| File | Reason |
|---|---|
| `.gitignore` | Ignore additional local environment/private-key/release/test-emulator artifacts; does not remove tracked credentials |
| `app/src/main/AndroidManifest.xml` | Only TaskReceiver exported flag changed by this audit |
| `app/src/main/res/xml/backup_rules.xml` | Exclude `windows_agent_prefs.xml` from cloud backup to protect companion credentials |
| `app/src/main/res/xml/data_extraction_rules.xml` | Exclude `windows_agent_prefs.xml` from cloud-backup and device-transfer |
| `app/src/test/java/com/example/security/TaskReceiverManifestSecurityTest.kt` | New merged-manifest isolation test |
| `app/src/test/java/com/example/security/AlarmSchedulerInternalTargetSourceTest.kt` | New explicit/immutable scheduler source guard |
| `app/src/test/java/com/example/automation/BasicExecutionRegressionTest.kt` | State isolation fix: reset TaskCancellationCoordinator and AgentActivityTracker in @Before |
| `firestore.rules` | Trial, key-claim, metadata and revocation boundary checks |
| `windows-agent/server/api_router.py` | Shared request trust checks, payload validation, restricted CORS, safe errors and sensitive JSON headers |
| `windows-agent/server/http_security.py` | New narrow helpers for exact authorities/origins and nonempty constant-time pairing-token comparison |
| `windows-agent/server/http_server.py` | Bounded, timed, unambiguous request reads and metadata-only parser errors |
| `windows-agent/server/ui_bridge.py` | Native-only WebSocket Origin policy |
| `windows-agent/server/websocket_server.py` | Close stale sockets only after successful auth; remove plaintext pairing code logging |
| `windows-agent/dashboard/assets/js/views/activity.js` | HTML-context encoding at event rendering sinks |
| `windows-agent/tests/test_http_security.py` | Origin/Host helper regression tests |
| `windows-agent/tests/test_http_boundary_regression.py` | Synthetic route, parser, trust and positive compatibility tests |
| `windows-agent/tests/test_ui_bridge_origin_security.py` | Local handshake positive/negative regression |
| `windows-agent/frontend/package.json` | Raise Next.js minimum to audited same-major version |
| `windows-agent/frontend/package-lock.json` | Resolved compatible security updates |
| `tools/security-tests/package.json` | Reproducible local emulator test command and development-only dependencies |
| `tools/security-tests/package-lock.json` | Lock emulator/test dependencies; known dev-tool findings disclosed above |
| `tools/security-tests/firebase.json` | Loopback demo emulator configuration, no production target |
| `tools/security-tests/deny-all.rules` | Fail-closed emulator startup policy before tests load actual project rules |
| `tools/security-tests/firestore.test.cjs` | Nine synthetic rule regression cases |
| `tools/security-tests/dashboard.test.cjs` | Actual activity-view HTML encoding regression |
| `tools/security-tests/verification_summary.py` | Metadata-only XML test totals, safe diff status and package version summary |
| `docs/SECURITY_AUDIT_2026-10-09_GATES.md` | Acceptance and remediation ledger, without overwriting the pre-existing root ledger |
| `docs/SECURITY_AUDIT_2026-10-09.md` | This report |

The license-generator update attempt produced no intended tracked changes. No signing credentials, pairing database, private key, user backup, cloud resource or production database was modified by this audit.

## 8. Tests, builds and baseline comparison

| Check | Exact result | Interpretation |
|---|---|---|
| Initial Android full unit suite | **906 total: 903 passed, 3 failed** | Baseline source compiled; combined task stopped at unit failures before the assemble task |
| Android new security tests | **2 passed, 0 failed** | Manifest runtime/package-manager check and scheduler source guard |
| Android focused lifecycle + security run | **28 total: 28 passed** (26 BasicExecutionRegressionTest + 2 security) | XML totals verified; clean execution |
| Combined memory + execution regression run | **44 total: 44 passed** | UniversalMemoryImportUnitTest (18) + BasicExecutionRegressionTest (26) run together after state isolation fix; zero failures |
| Baseline Android model-expectation failures | **3 failed** (isolated to `UiAgentModelTest`) | Pre-existing baseline failures recorded in baseline |
| Android debug APK | **assembleDebug succeeded** in targeted build invocation; later up-to-date confirmation | APK at `app/build/outputs/apk/debug/app-debug.apk`; no install or on-device feature verification |
| Windows network baseline | **4 passed** with `windows-agent/.venv` | Root `.venv` attempt had 2 passed/2 failed due missing async-test plugin; wrong environment, not a code regression |
| Final Windows security + network selection | **54 passed, 0 failed** | 50 new security cases plus 4 existing network/recovery cases; no real tool execution |
| Python changed-module compilation | **Passed** | api_router, http_server, http_security, ui_bridge, websocket_server |
| Firestore emulator | **9 passed, 0 failed**, no skipped tests | Rules compilation and authorized/unauthorized request behavior, synthetic local data only |
| Dashboard sink test | **1 passed, 0 failed** | Actual rendering path with fake DOM sink, not a full browser test |
| Frontend production build | **Passed before and after** | Next compilation, TypeScript and static page generation |
| .NET build/audit | **Blocked** | No .NET SDK; no pass claimed |
| Own tracked source diff whitespace check | **Passed** for the selected audit-owned files | Whole manifest diff also reports pre-existing whitespace in the unrelated foreground service declaration; left intact |
| Secret scan | **Findings remain** | Repeated scan does not establish remediation while credentials remain in history/current tracked files |

Baseline Android failures:

- `UiAgentModelTest.testUiAgentRouterFallbackToGemini25Flash` (`UiAgentModelTest.kt:119`)
- `UiAgentModelTest.testCentralizedModelDefaults` (`:30`)
- `UiAgentModelTest.testGeminiUiAgentProviderCompatibleModels` (`:90`)

The additional test failures observed in earlier runs have been resolved:
- `BasicExecutionRegressionTest.flashlightOffExecutesAndVerifiesCallback` was diagnosed as cross-test state leakage (residual active task or activity tracker state from prior suite runs); resolved by resetting `TaskCancellationCoordinator` and `AgentActivityTracker` in `@Before`. Verified in combined run (44/44 passed).
- `UniversalMemoryImportUnitTest.testExternalMarkdownChange_AcceptAndRejectFlows` passed cleanly.

### Reproduction commands

Run from repository root unless a working directory is named. Commands below are local checks, not deployment commands.

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
# Force the final full test task, including existing failures:
.\gradlew.bat :app:testDebugUnitTest --rerun :app:assembleDebug --offline --console=plain
# Focused tests + APK:
.\gradlew.bat :app:testDebugUnitTest --tests com.example.automation.BasicExecutionRegressionTest --tests com.example.security.TaskReceiverManifestSecurityTest --tests com.example.security.AlarmSchedulerInternalTargetSourceTest :app:assembleDebug --offline --console=plain
.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath --offline --console=plain
.\.venv\Scripts\python.exe tools/security-tests/verification_summary.py
```

Working directory `windows-agent`:

```powershell
$env:PYTHONIOENCODING='utf-8'
.\.venv\Scripts\python.exe -m pytest tests/test_http_security.py tests/test_http_boundary_regression.py tests/test_ui_bridge_origin_security.py tests/test_network_and_recovery.py --tb=short -q
.\.venv\Scripts\python.exe -m py_compile server/api_router.py server/http_server.py server/http_security.py server/ui_bridge.py
```

Working directory `tools/security-tests`:

```powershell
npm ci --ignore-scripts
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
npm test
node --test dashboard.test.cjs
npm audit --ignore-scripts --json
```

`npm test` explicitly uses `demo-max-security`, loopback port 18080, and no production service-account initialization. Tests refuse an unexpected emulator host. Initial emulator configuration referenced rules outside the CLI project root and was rejected; final startup uses a local deny-all file and the test SDK loads the real root rules. Emulator data clearing is limited to this synthetic demo project.

Working directory `windows-agent/frontend`: `npm audit --ignore-scripts --json`, `npm run build`. Compatible update performed: `npm update next baseline-browser-mapping nanoid source-map-js --ignore-scripts`, followed by minimum-version/lockfile synchronization. Working directory `tools/license-generator`: `npm audit --ignore-scripts --json`; compatible update attempt `npm update firebase-admin gaxios --ignore-scripts` left nine alerts.

Python vulnerability audit:

```powershell
& "C:\Users\yaswa\AppData\Local\Temp\omnirush\max-audit-env\Scripts\python.exe" -m pip_audit --path "D:\max-new-updated\Max-AI-Agent\windows-agent\.venv\Lib\site-packages" --progress-spinner off
```

The bounded local secret scanner used in this session is `C:\Users\yaswa\AppData\Local\Temp\omnirush\max_security_scan.py`; it is temporary tooling, not a portable repository scanner or secret baseline. Replace/augment it with an evaluated established local scanner before release. Its results never included actual credential values.

## 9. Required next actions and limitations

1. **Owner credential incident response:** rotate the three service groups listed in section 5, re-pair desktop clients, review access logs and revoke stale identities. Do not distribute the repository/current release artifacts as credential-clean.
2. **Resolve the two additional Android full-suite failures** and the three baseline model tests without weakening assertions. Repeat full tests and real-device scheduling, assistant, voice, calling/messaging and pairing checks in a controlled environment.
3. **Review and deploy Firestore changes only with authorization.** Export/review current deployed rules, run staging entitlement migration/clock-skew/idempotent activation tests, and apply the approved version through normal release procedures. Do not automatically rewrite existing license records.
4. **Close remaining companion session and logging risks** (MAX-SEC-010/013) with isolated lifecycle tests. Add server connection/rate/concurrency/response limits, including SSE backpressure. None of those controls was validated merely by passing request-parser tests.
5. **Protect transport and OS boundaries:** keep HTTP control off untrusted networks or introduce correctly configured TLS/authenticated ingress; verify Windows Firewall. Local native endpoints are not multi-user OS security boundaries. Confirm private-file ACLs, full-disk encryption and credential backup exclusions.
6. **Finish dependency remediation:** validate a supported Firebase Admin major migration; update the Python environment and pin/lock a reproducible compatible set; run Maven/Android advisory scanning; install the .NET 8 SDK for build/audit verification. Review the new dev-only emulator toolchain's disclosed advisories.
7. **Broaden the forensic audit:** established local secret scanner over all authorized worktree/ignored artifacts and reachable history; binary/APK analysis; complete route-to-tool permission matrix; imported skills, command execution, file uploads/imports, deserialization, SSRF and AI prompt-injection tests. Do not run live automation tests blindly: existing tests include keyboard/device/service side effects.
8. **Verify deployment-only settings:** Firebase IAM, API restrictions, Auth account-recovery settings, App Check enforcement, deployed rules, budgets/quotas, logging retention, backup restoration and public file exposure. No production access was available or used.

Full Windows/live integration, full browser UI, on-device Android cross-UID behavior, release signing/install, and .NET build were not verified. No test attacked third-party services. A passing local emulator or frontend audit is not proof that the deployed system is secure.

## 10. Final assessment

**PARTIALLY REMEDIATED.** Meaningful high-risk boundaries now have targeted local fixes and regression evidence. Exposed credentials, remaining source findings, outstanding dependency alerts, incomplete audit coverage, deployment unknowns and the non-green Android full suite prevent a stronger verdict. This report provides no guarantee that MAX cannot be compromised.
