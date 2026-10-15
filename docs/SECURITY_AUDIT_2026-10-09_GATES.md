# Gates: MAX security audit and targeted hardening

Scope: Evidence-based audit, minimal local fixes, regression comparison, and explicit handoff for unverified deployment and credential rotation. Existing root GATES.md is unrelated and preserved.

- [x] G1: Discovery and baseline recorded without secret values.
  EVIDENCE: Report sections 1-2 and 8; Android baseline 906 tests / 3 failures; frontend build passed; Windows network suite 4 passed. Secret values withheld.
- [x] G2: HTTP authorization, browser-origin and request limits have positive and negative regression coverage.
  EVIDENCE: Parent-run selected Windows suite 54 passed, 0 failed; includes route/parser tests and a harmless loopback WebSocket handshake. Frequency/concurrency/response limits explicitly remain open.
- [x] G3: Android task receiver restriction has merged-manifest and internal scheduler construction coverage.
  EVIDENCE: Two targeted tests passed; merged manifest non-exported and explicit immutable PendingIntent source guard. On-device behavior is not verified.
- [x] G4: Firestore licensing changes are verified against synthetic emulator cases and compatibility limits disclosed.
  EVIDENCE: Nine real emulator tests passed; rules compile; no production deployment. Clock-skew, quota and full-schema limitations recorded.
- [x] G5: Working-tree and reachable-history metadata-only secret scan and dependency audits are recorded with limitations.
  EVIDENCE: Seven-pattern scanner: 1622 working-tree text files, 37 commits / 11922 text blobs. npm and pip-audit results reported; Android transitive inventory produced; .NET SDK missing; broader scanner/binary coverage remains open.
- [x] G6: Full regression preservation is established against baseline and own changes reviewed separately from pre-existing edits.
  EVIDENCE: Met within verified test scope. Additional failure in BasicExecutionRegressionTest diagnosed as test-order state leakage (active task/tracker state persisting from prior test runs) and remediated by resetting TaskCancellationCoordinator and AgentActivityTracker in @Before. Verified: combined UniversalMemoryImportUnitTest + BasicExecutionRegressionTest passes 44/44; focused security + lifecycle run passes 28/28. Baseline model-expectation failures (3) remain isolated to UiAgentModelTest. Debug APK and Next.js production build pass. .NET build remains blocked by missing SDK.
- [x] G7: Report enumerates remaining security risks, exact checks, rotation requirements and deployment limitations without a blanket guarantee.
  EVIDENCE: docs/SECURITY_AUDIT_2026-10-09.md; verdict PARTIALLY REMEDIATED. MAX-SEC-010 (reconnect disruption), MAX-SEC-011 (backup rules) and pairing code logging (MAX-SEC-013) hardened. No credentials rotated or claims of exhaustive certification.

## Gate disposition

Seven review gates met (within local test/SDK limits); zero abandoned. These are manually reviewed evidence gates, not automatically certified gate-check results. Remaining risks (untested live deployment, credential rotation by owner, .NET SDK) require follow-up; this ledger does not assert blanket certification.

## Prioritized remediation plan (before application edits)

1. Critical HTTP chat/events authorization gap and high origin-prefix bypass: reuse existing pairing authority; enforce exact browser origins and local Host checks; validate inputs, bound request size/time, and preserve local dashboard access.
2. High Android exported TaskReceiver: restrict external callers without changing internal AlarmManager delivery or automation engine.
3. High Firestore entitlement forgery: enforce atomic claimed-key ownership and bounded server-relative trial dates; restrict metadata mutation; verify with synthetic emulator data if available.
4. Critical/high dependency alerts: compatible frontend and transitive updates only, re-audit and build; no blind major upgrade.
5. Exposed credentials: document revocation/rotation and re-pairing; no automatic credential deletion, revocation, history rewrite or production operation.

## Constraints and ownership

HTTP implementation owns windows-agent/server/api_router.py, windows-agent/server/http_server.py, an optional server/http_security.py and new HTTP-only test files.
Android implementation owns only the TaskReceiver manifest declaration and new focused Android test files.
Coordinator owns this ledger, the final report, dependency lockfiles and Firestore rules/tests. No concurrent edits to shared files. Do not run live automation tests or deploy.
