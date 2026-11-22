# MAX AI review of max-v2, 22 November 2026

**Baseline** `9a1f64246c2982bf912bed425382c37db6ac18ef`. The review followed activity records from the Android analytics model through Firebase Cloud Functions, Firestore and the admin dashboard. It also covered the Compose chat renderer’s code-block parser. The work used synthetic records and a local emulator.

## Findings and fixes

| Area | Observed behavior | Implemented change |
|---|---|---|
| Latency | Both server queries sorted by duration and selected the slowest 100 records. Session durations could enter the AI/tool metric. The dashboard fallback returned a constant 420 ms. | The server and direct-read fallback use the same sampling rule. They examine the latest 100 activity events by server timestamp and retain timed AI, chat and tool completions. A measured zero remains valid. An empty sample has a null mean. Overview and histogram use the same function. |
| Active users and sessions | The callable stopped counting sessions at 500 and the fallback at 200. The fallback gave DAU, WAU and MAU the same value as total users. | Firestore count queries measure tracked users, active sessions and the three rolling windows. Queries use one reference time per report. The dashboard labels the total as tracked users. |
| Dashboard reliability | A failed request could show populated histogram bars, zero errors or 100% reliability. Task and memory volume were estimated from tool and AI counters. Voice session starts were treated as successes. | Overview, Features and Errors have retryable load errors. Empty denominators and unmeasured voice outcomes show a dash. The feature chart displays the three categories with real volume counters. |
| License display and filtering | The browser user list assigned every fallback record a standard, active license and ignored the supplied filters. The detail modal applied the same labels to missing data. | The fallback marks unavailable entitlements unknown, applies UID search within the returned page, and reports an error if a plan filter needs the unavailable admin service. Failed server license lookups also produce unknown status. The server previously filtered only its first page. Both readers now return a cursor for the last scanned record, and the Users screen can continue through pages even when a page has no matches. |
| CSV exports | The browser fallback wrapped values in quotes without escaping embedded quotes, used different row limits and a different user header, and skipped the callable’s handling of formula-like text. | Both paths use the same cell formatter, headers, ordering and row limits. Users export up to 500 records and activity exports up to 1,000. Both outputs matched byte for byte in the emulator fixtures. |
| Firestore configuration | Dashboard reads combined filters and sorting for sessions, user detail and failures. The baseline repository had no composite-index manifest for these queries. | Added four query indexes and referenced the manifest in Firebase configuration. Production index state was outside this review. |
| Android chat | The parser toggled block state on any line starting with three backticks after trimming indentation. It lost literal fence examples, closed blocks on lines containing code after the backticks and failed to recognize tilde fences. | The parser tracks the opening delimiter and its length. Closing lines must use the same delimiter, meet the opening length, carry no trailing content and have at most three leading spaces. Streaming code retains its indentation and Unicode. |

## Evidence

| Regression fixture | Original commit | Fixed result |
|---|---:|---:|
| Average of recent timed completions of 0 ms and 3,000 ms, mixed with a one-hour session and an older event | 1,231,000 ms | 1,500 ms |
| 501 active sessions, one ended session and one stale session | 500 | 501 |
| Android parser suite with the three new cases | 3 passed, 3 failed | 6 passed |

The user-window fixture placed four users at 0, 3, 15 and 40 days since last activity. The callable returned 1 daily, 2 weekly, 3 monthly and 4 total tracked users. The web SDK fallback passed an independent fixture with the callable endpoint unavailable.

## Verification

| Check | Result |
|---|---|
| Functions TypeScript build | Passed. Rebuilt tracked JavaScript and source maps. |
| Next.js production build | Passed compilation, type checking and generation of all 11 static pages. |
| Existing Firestore rules tests | 13 passed |
| Existing analytics and admin role tests | 3 passed |
| New emulator reporting regressions | 2 passed |
| New reporting format tests | 3 passed |
| New direct-read fallback tests using the web SDK and filtered pagination | 5 passed |
| Kotlin chat parser, compiled on the JVM with Kotlin 2.3.20 and JUnit 4.13.2 | 6 passed |

**Total: 32 passing tests.** Node verification used v20.20.2, the major version declared in the Functions package. The fallback suite points the functions client at a closed loopback port and uses a synthetic admin identity against the local Firestore rules. The Android tests exercised the production parser directly. The baseline comparison compiled the same parser declarations from the original Compose file, without its UI dependencies.

### Reproduce the checks

From the repository root, install the locked dependencies and build both packages.

```sh
npm --prefix functions ci --ignore-scripts
npm --prefix admin-panel ci --ignore-scripts
npm --prefix tools/security-tests ci --ignore-scripts
npm --prefix functions run build
npm --prefix admin-panel run build
node --test functions/test/report-format.test.cjs
```

Use separate emulator runs so the fixtures start with empty collections. Run the following from `tools/security-tests` with Java on PATH.

```sh
npx --no-install firebase emulators:exec --config firebase.json --project demo-max-reporting --only firestore "node --test ../../functions/test/reporting.test.cjs"
npx --no-install firebase emulators:exec --config firebase.json --project demo-max-security --only firestore "node --test firestore.test.cjs && node --test ../../functions/test/analytics.test.cjs"
npx --no-install firebase emulators:exec --config firebase.json --project demo-max-browser --only firestore "npm --prefix ../../admin-panel run test:reporting"
```

On a configured Android development machine, the existing Gradle entry point for the parser suite is `./gradlew :app:testDebugUnitTest --tests com.example.ui.home.PremiumMessageTextTest`.

## Coverage and remaining work

- A production release still needs an Android Gradle build and device verification. This review environment lacked the Android SDK. No APK was built or installed. Voice calls, accessibility actions and other device flows were outside the executed tests.
- Firestore emulator results establish query behavior on the fixtures. The four composite indexes still need verification in the target Firebase project. No Firebase deployment, production reads or historical data migration ran.
- The latency metric describes timed completions among the latest 100 activity events. It can have few samples during periods dominated by heartbeats. Task and memory totals require actual counters before they can be displayed again.
- User search is incremental. “Load more records” advances through the filtered result set without issuing an unbounded full-collection read. Plan filtering requires the admin service.
- The dashboard imports the pure reporting helper from `functions/src/reporting.ts`. Build from the repository checkout with both package directories available.
