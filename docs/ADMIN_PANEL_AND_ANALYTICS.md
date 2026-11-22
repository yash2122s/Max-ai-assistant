# MAX AI Assistant — Admin Panel & User Activity Analytics Architecture

## 1. Executive Summary

This document describes the complete Admin Panel and User Activity Analytics system implemented for the **MAX AI Assistant** workspace. The system provides real-time operational telemetry, active session tracking, AI and tool execution diagnostics, secure role-based administration, and privacy-preserving data governance.

---

## 2. System Architecture

```
┌────────────────────────────────────────────────────────┐
│               Android Application (MAX)                 │
│  - MaxActivityTracker (Non-blocking queue & flush)      │
│  - SessionManager (Foreground session & heartbeat)     │
│  - SettingsManager (Analytics Opt-Out Toggle)          │
└──────────────────────────┬─────────────────────────────┘
                           │
             Dual Pipeline Ingestion
             (Local buffer & sanitized metadata)
                           │
       ┌───────────────────┴───────────────────┐
       ▼                                       ▼
┌─────────────────────────┐         ┌─────────────────────────┐
│    Firebase Analytics   │         │    Cloud Firestore      │
│ (Aggregate usage trends)│         │ - activityEvents        │
└─────────────────────────┘         │ - userSessions          │
                                    │ - dailyAnalytics        │
                                    │ - adminAggregates       │
                                    │ - userAnalytics         │
                                    └───────────▲─────────────┘
                                                │
                                    ┌───────────┴─────────────┐
                                    │  Firebase Functions &   │
                                    │   Security Rules        │
                                    │ (request.auth.token.admin)
                                    └───────────▲─────────────┘
                                                │
                                    ┌───────────┴─────────────┐
                                    │   MAX Admin Console     │
                                    │ (Next.js 14 + Recharts) │
                                    │ - Overview Telemetry    │
                                    │ - User Management       │
                                    │ - Live Activity Feed    │
                                    │ - Feature Analytics     │
                                    │ - Errors & Latency      │
                                    │ - Safe CSV Export       │
                                    └─────────────────────────┘
```

---

## 3. Android Telemetry Integration

### Core Classes
- `com.example.analytics.ActivityEvent`: Data models defining schema (`eventId`, `userId`, `eventType`, `feature`, `status`, `durationMs`, `sessionId`, `appVersion`, `metadata`).
- `com.example.analytics.SessionManager`: Manages foreground/background lifecycle, session duration, and periodic heartbeats (every 30 seconds). Sessions older than 5 minutes without a heartbeat are marked inactive.
- `com.example.analytics.MaxActivityTracker` queues up to 50 events, binds each event to the signed-in account, and sends batches to `ingestActivityEvents` every 10 seconds or when 10 events accumulate.
- `com.example.data.preferences.SettingsManager.isAnalyticsEnabled`: User toggle allowing full opt-out.

### Instrumented Execution Paths
1. **Application Lifecycle**: `MyApplication.kt` logs `APP_OPEN`, foreground session starts, background session ends, and screen changes via `ActivityLifecycleCallbacks`.
2. **AI Request Flow**: `AssistantEngine.kt` instruments text chat requests (`CHAT_REQUEST_START`, `CHAT_REQUEST_SUCCESS`, `CHAT_REQUEST_FAILED`) with latency timing.
3. **Voice Sessions**: `AssistantEngine.kt` instruments voice conversation start and end with millisecond duration tracking (`VOICE_SESSION_START`, `VOICE_SESSION_END`).
4. **Tool Execution Engine**: `ActionDispatcher.kt` instruments tool invocations (`TOOL_EXECUTION_START`, `TOOL_EXECUTION_SUCCESS`, `TOOL_EXECUTION_FAILED`), measuring execution latency and classifying error categories.
5. **Memory Subsystem**: `FirestoreMemoryRepository.kt` records memory operations (`MEMORY_WRITE`, `MEMORY_DELETE`) as metadata counts without storing memory content.
6. **Authentication & Licensing**: `MaxLicenseManager.kt` tracks logout events (`AUTH_LOGOUT`).

---

## 4. Firestore Schema & Security Rules

### Collections
1. `activityEvents/{eventId}`:
   - Holds sanitized telemetry records.
   - Normal users can create their own records (`request.resource.data.userId == request.auth.uid`).
   - Normal users cannot read any records in this collection.
   - Admins (`request.auth.token.admin == true`) can read all events.
2. `userSessions/{sessionId}`:
   - Tracks session start time, duration, heartbeat timestamp, app version, and active status.
   - Normal users can read/write their own session documents.
   - Admins can read all session documents.
3. `dailyAnalytics/{date}`:
   - Aggregated daily counters (`totalEvents`, `totalAiRequests`, `successfulAiRequests`, `failedAiRequests`, `totalToolExecutions`, `totalVoiceSessions`).
   - Read-only for admins.
4. `adminAggregates/{docId}`:
   - Global overview aggregates.
   - Read-only for admins.
5. `userAnalytics/{userId}`:
   - Aggregated user metadata (last active timestamp, app version, total requests).
   - Normal users can read their own document.
   - Admins can read and query all user analytics documents.

---

## 5. Firebase Cloud Functions

Located in `functions/`:
- `ingestActivityEvents` validates up to 50 events, sanitizes metadata, applies server timestamps, and updates counters in the same transaction that creates new event records. Repeated event IDs leave existing records and counters intact.

- `sessionHeartbeat`: Extends user active session window.
- `sessionEnd`: Calculates session duration and updates status.
- `getAdminOverview`: Computes DAU/WAU/MAU, active sessions (5m window), AI and tool execution totals, and latency averages.
- `getAdminUsers`: Paginated user list with license status and usage stats.
- `getAdminUserDetail`: Deep-dive telemetry for a selected user.
- `getAdminLiveActivity`: Near-real-time stream of latest 50 operations.
- `getAdminErrors`: Categorized operational failure list and latency distribution buckets.
- `exportAdminReport`: Generates sanitized CSV reports for users and events.
- `setAdminRole`: Grants or revokes `{ admin: true }` custom claim.

For rollout, deploy the updated ingestion function before distributing the updated Android app. Switch the Firestore aggregate write rules after clients have migrated. Older clients that write aggregates directly will lose telemetry uploads once those rules take effect. Counters already stored retain their historical values.

---

## 6. Admin Custom Claim Provisioning

To grant an initial administrator account access without exposing privileged endpoints:

1. Obtain your Firebase service account JSON key from Firebase Console -> Project Settings -> Service accounts.
2. Set the environment variable:
   ```powershell
   $env:GOOGLE_APPLICATION_CREDENTIALS="C:\path\to\service-account-key.json"
   ```
3. Run the provisioning script:
   ```bash
   node scripts/set-admin-claim.js <FIREBASE_USER_UID>
   ```
4. To revoke access:
   ```bash
   node scripts/set-admin-claim.js <FIREBASE_USER_UID> --remove
   ```

---

## 7. Admin Dashboard Frontend (`admin-panel/`)

Built with Next.js 14 (App Router), TypeScript, Tailwind CSS, Lucide icons, and Recharts.

### Key Routes
- `/login`: Secure administrator sign-in (Email/Password & Google Federated Auth). Validates `admin: true` token claim.
- `/overview`: Real-time KPI cards, active sessions count, reliability ratios, and 14-day activity trend charts.
- `/users`: Searchable and filterable user table with detailed telemetry drawer.
- `/activity`: Live event stream with pause/resume toggle, status filters, and approximate presence disclaimer.
- `/features`: Feature rankings and invocation volume for Chat, Voice, Tools, Tasks, and Memory.
- `/errors`: Latency histogram (<200ms, 200-500ms, 500ms-1s, 1s-3s, >3s) and categorized failure logs.
- `/export`: One-click CSV export of user metrics and activity logs.

### Vercel Deployment Guide
1. Push the repository to GitHub / GitLab.
2. In the Vercel dashboard, create a new project and set the **Root Directory** to `admin-panel`.
3. Add the following environment variables in Vercel Project Settings:
   - `NEXT_PUBLIC_FIREBASE_API_KEY`
   - `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN`
   - `NEXT_PUBLIC_FIREBASE_PROJECT_ID`
   - `NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET`
   - `NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID`
   - `NEXT_PUBLIC_FIREBASE_APP_ID`
4. Deploy the project. The application compiles to static and serverless edge routes with zero hardcoded secrets.

---

## 8. Data Minimization & Privacy

MAX prioritizes user privacy. The analytics pipeline enforces strict data filtering:
- **Never Logged**:
  - Raw AI prompts or generated text responses.
  - User voice audio files or spoken transcripts.
  - WhatsApp, Telegram, or SMS message bodies.
  - Captured screen images or UI hierarchy dumps.
  - Authentication tokens, API keys, passwords, or credentials.
  - Raw personal memories stored in the vector database.
- **Logged**:
  - Feature categories (e.g. `Chat`, `Voice`, `ToolExecution`).
  - Execution duration in milliseconds.
  - Status codes (`SUCCESS`, `FAILURE`, `CANCELLED`).
  - Standardized error categories (`NETWORK_ERROR`, `API_RATE_LIMIT`, `TOOL_TIMEOUT`).
  - Client application version and platform.
