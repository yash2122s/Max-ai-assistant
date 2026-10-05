# MAX License & Entitlement Forensic Audit

## 1. Observed Problem
MAX exhibits a state inconsistency between the Account/Licensing screen and the Chat/Voice execution pipeline:
- **Account & Licensing Screen UI:** Displays `LIFETIME PREMIUM ACTIVE`, `Cloud Synced` (green dot), and `Permanent Lifetime Access • Unlimited AI Messages`.
- **Chat / Voice Execution:** Fails and outputs:
  > *"Your 3-day free trial has ended. Activate MAX Premium to continue."*

---

## 2. Exact Error Message Source
The exact string `"Your 3-day free trial has ended. Activate MAX Premium to continue."` is generated in:
- **File:** `app/src/main/java/com/example/voice/assistant/AssistantEngine.kt`
- **Occurrences:**
  1. **Line 381** (Inside `handleTranscribedText` quota gate check):
     ```kotlin
     val quotaDecision = gate?.checkAndConsumeQuota() ?: com.example.firebase.manager.GateDecision.AllowLifetime
     if (!quotaDecision.isAllowed) {
         val blockMsg = when (quotaDecision) {
             is com.example.firebase.manager.GateDecision.BlockDailyLimitReached -> ...
             is com.example.firebase.manager.GateDecision.BlockTrialExpired ->
                 "Your 3-day free trial has ended. Activate MAX Premium to continue."
             ...
         }
         ...
         conversationManager.addMessage("MAX", blockMsg)
         AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", blockMsg))
         return@launch
     }
     ```
  2. **Line 548** (Inside `startListening` voice microphone gate check):
     ```kotlin
     val voiceDecision = gate?.canStartVoiceSession() ?: com.example.firebase.manager.GateDecision.AllowLifetime
     if (!voiceDecision.isAllowed) {
         val blockMsg = when (voiceDecision) {
             ...
             is com.example.firebase.manager.GateDecision.BlockTrialExpired ->
                 "Your 3-day free trial has ended. Activate MAX Premium to continue."
             ...
         }
         ...
         return
     }
     ```
  3. **Line 714** (Inside `sendTextMessageToModel` central AI dispatch):
     ```kotlin
     val decision = gate?.checkAndConsumeQuota() ?: com.example.firebase.manager.GateDecision.AllowLifetime
     if (!decision.isAllowed) {
         val blockMessage = when (decision) {
             ...
             is com.example.firebase.manager.GateDecision.BlockTrialExpired -> {
                 "Your 3-day free trial has ended. Activate MAX Premium to continue."
             }
             ...
         }
         ...
         conversationManager.addMessage("MAX", blockMessage)
         AssistantEventBus.emit(AssistantEvent.TextTranscribed("MAX", blockMessage))
         return@launch
     }
     ```

---

## 3. Account Screen License Flow
1. **Composable Entry:** `LicenseActivationScreen.kt` (lines 42–113)
2. **State Collection:**
   ```kotlin
   val cloudLicense by viewModel.cloudLicense.collectAsState() // from MaxLicenseManager.currentLicense
   val entitlement by viewModel.entitlement.collectAsState()   // from EntitlementManager.entitlementFlow
   ```
3. **Hero Card Rendering:** `CloudLicenseHeroCard(license = cloudLicense, ...)` (lines 352–361):
   ```kotlin
   val hero = when {
       license.plan == LicensePlan.LIFETIME -> {
           LicenseHeroConfig(
               title = "LIFETIME PREMIUM ACTIVE",
               subtitle = "Permanent Lifetime Access • Unlimited AI Messages",
               badgeColor = MaxRoyalGold,
               icon = Icons.Default.Verified,
               accentGradient = listOf(Color(0xFF2A2311), MaxSurfaceCard)
           )
       }
       ...
   }
   ```
4. **Data Source:** `LicenseActivationScreen` directly reads `license.plan == LicensePlan.LIFETIME`. Notice that `CloudLicenseHeroCard` checks **`license.plan == LicensePlan.LIFETIME` alone** without verifying `license.status == LicenseStatus.ACTIVE`.

---

## 4. Chat License Flow
1. **User input in Chat:** `ChatViewModel.sendTextMessage(text)` (lines 228–373) or `HomeViewModel.sendTextMessage(text)` (lines 233–368).
2. **Delegation:** Calls `AssistantUseCase.sendTextMessage(text)` → `AssistantEngine.sendTextMessage(userText, promptText)` (line 615).
3. **Engine Execution:** Dispatches to `AssistantEngine.sendTextMessageToModel(userText, promptText)` (line 677).
4. **Gate Invocation:**
   ```kotlin
   val app = context.applicationContext as? com.example.MyApplication
   val gate = app?.entitlementGate
   val decision = gate?.checkAndConsumeQuota() ?: GateDecision.AllowLifetime
   ```
5. **Entitlement Gate Evaluation:** `EntitlementGate.checkAndConsumeQuota()` (lines 29–76).

---

## 5. License Activation Flow
When a user activates a lifetime license key:
1. `LicenseViewModel.activateLicenseKey(rawKey)` (lines 46–57) calls `app.maxLicenseManager.claimLicenseKey(cleaned)`.
2. `MaxLicenseManager.claimLicenseKey(rawKey)` calls `firestoreRepo.claimLicenseKey(user.uid, rawKey)`:
   - Sets Firestore document `licenses/{userId}`:
     ```json
     {
       "userId": "...",
       "plan": "LIFETIME",
       "status": "ACTIVE",
       "licenseId": "<hash>",
       "activatedAt": "<timestamp>",
       "expiresAt": null,
       "updatedAt": "<timestamp>"
     }
     ```
3. Returns `CloudLicense` instance with `plan = LicensePlan.LIFETIME` and `status = LicenseStatus.ACTIVE`.
4. Saves to local Room cache (`persistLicenseToCache(license)`).
5. Updates in-memory StateFlow: `_currentLicense.value = license`.
6. Sets `_syncState.value = LicenseSyncState.Synced(license, isFromCache = false)`.
7. Calls `app.entitlementManager.refresh()`.

---

## 6. Firebase Flow
- **Firestore Collection:** `licenses/{userId}`
- **Snapshot Listener:**
  `MaxLicenseManager.initialize()` starts a snapshot listener (`firestoreRepo.listenToLicense(user.uid)`):
  ```kotlin
  firestoreRepo.listenToLicense(user.uid).collect { cloudLicense ->
      if (cloudLicense.plan != LicensePlan.FREE) {
          persistLicenseToCache(cloudLicense)
          _currentLicense.value = cloudLicense
      }
  }
  ```
- **Document Deserialization:** `CloudLicense.fromFirestoreMap(userId, data)`:
  ```kotlin
  val plan = LicensePlan.fromString(data["plan"] as? String)
  val status = LicenseStatus.fromString(data["status"] as? String)
  val trialExpires = parseTimestamp("trialExpiresAt") ?: parseTimestamp("expiresAt")
  val expiresAt = parseTimestamp("expiresAt") ?: trialExpires
  ```
  *(Crucial Detail: If Firestore has `plan: "LIFETIME"` and `status: "EXPIRED"` or if `status` is missing/deserialized as `EXPIRED`, `plan` is `LIFETIME` while `status` is `EXPIRED`)*.

---

## 7. Room Cache Flow
- **Table:** `firebase_license_cache`
- **Entity:** `FirebaseLicenseCacheEntity(userId, plan, status, trialStartedAtEpochMillis, trialExpiresAtEpochMillis, expiresAtEpochMillis, issuedAtEpochMillis, updatedAtEpochMillis, cachedAtEpochMillis, integrityHash)`
- **Startup Read:** `MaxLicenseManager.loadInitialCache()` loads the Room entity, validates HMAC-SHA256 integrity, and sets `_currentLicense.value = cached.toCloudLicense()`.
- **Integrity Validation:** If HMAC verification fails, it reverts `_currentLicense.value = CloudLicense.createFree("untrusted")`.

---

## 8. MaxLicenseManager Flow
- **Singleton Instance:** Initialized in `MyApplication.onCreate()`:
  ```kotlin
  maxLicenseManager = com.example.firebase.manager.MaxLicenseManager(this)
  maxLicenseManager.initialize()
  entitlementGate = com.example.firebase.manager.EntitlementGate(this, maxLicenseManager)
  ```
- Both `ChatViewModel`, `HomeScreen`, `LicenseViewModel`, and `AssistantEngine` share the **exact same singleton `MyApplication` instances** of `MaxLicenseManager` and `EntitlementGate`.

---

## 9. Trial Logic & Expiration
In `CloudLicense.kt`:
```kotlin
fun isExpired(authoritativeNow: Long = System.currentTimeMillis()): Boolean {
    if (status == LicenseStatus.EXPIRED || plan == LicensePlan.EXPIRED) return true
    if (plan == LicensePlan.LIFETIME) return false
    ...
}
```
Notice:
- If `status == LicenseStatus.EXPIRED`, `isExpired()` returns `true` **first**, before checking `if (plan == LicensePlan.LIFETIME)`.

---

## 10. Premium Gates
The central gate is `EntitlementGate.kt`:
```kotlin
suspend fun checkAndConsumeQuota(): GateDecision {
    val user = licenseManager.currentUser.value
    val license = licenseManager.currentLicense.value
    val now = System.currentTimeMillis()

    // 1. Lifetime user: ALWAYS ALLOW
    if (license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE) {
        Log.d(TAG, "[License] gate_allowed: LIFETIME user")
        return GateDecision.AllowLifetime
    }

    // 2. Expired Trial check: only block if explicitly expired plan or active trial has elapsed
    if (license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))) {
        Log.w(TAG, "[License] gate_blocked: TRIAL EXPIRED")
        return GateDecision.BlockTrialExpired
    }

    // 3. Active Trial or First-Time Sync: Consume 1 message atomically
    if (license.plan == LicensePlan.TRIAL || license.plan == LicensePlan.FREE) {
        val result = licenseManager.consumeDailyMessage()
        if (result.isSuccess) {
            val (newCount, allowed) = result.getOrNull()!!
            return if (allowed) {
                GateDecision.AllowTrial(newCount, remaining)
            } else {
                GateDecision.BlockDailyLimitReached(newCount)
            }
        } else {
            val err = result.exceptionOrNull()
            if (err?.message == "TRIAL_EXPIRED") {
                return GateDecision.BlockTrialExpired
            }
            ...
        }
    }

    return GateDecision.BlockTrialExpired  // <--- FALLTHROUGH DEFAULT
}
```

---

## 11. Entitlement State Representations

| State | Class | Variable | Data Source | Used By | Meaning |
|---|---|---|---|---|---|
| `CloudLicense` | `MaxLicenseManager` | `currentLicense: StateFlow<CloudLicense>` | Firestore / Room | `EntitlementGate`, `HomeScreen`, `LicenseActivationScreen` | Authoritative cloud-synced license |
| `Entitlement` | `EntitlementManagerImpl` | `entitlementFlow: StateFlow<Entitlement>` | Signed file / `MaxLicenseManager` | `FeatureGate`, `LicenseViewModel` | Offline signed license / feature enablement |
| `GateDecision` | `EntitlementGate` | Return value of `checkAndConsumeQuota()` | In-memory evaluation of `currentLicense` | `AssistantEngine` | Runtime execution permission check |
| `LicensePlan` | `CloudLicense` | `plan: LicensePlan` (`LIFETIME`, `TRIAL`, `FREE`, etc.) | Firestore string | Account UI & Gate | Subscription tier |
| `LicenseStatus` | `CloudLicense` | `status: LicenseStatus` (`ACTIVE`, `TRIAL`, `EXPIRED`, `REVOKED`) | Firestore string | Account UI & Gate | Activation state of the license |

---

## 12. StateFlow/LiveData Propagation
- `MaxLicenseManager.currentLicense` emits updates to all collectors.
- `LicenseActivationScreen` collects `cloudLicense`.
- `EntitlementGate` reads `licenseManager.currentLicense.value` synchronously on each check.
- `HomeScreen` collects `cloudLicense` for `TrialQuotaBanner`.

---

## 13. Singleton / Instance Analysis
- **Account Screen:** Uses `(application as MyApplication).maxLicenseManager`.
- **Chat / AssistantEngine:** Uses `(context.applicationContext as MyApplication).entitlementGate`, which holds the same `(application as MyApplication).maxLicenseManager`.
- **Verdict:** There is **NO** duplicate instance mismatch. Both components read from the same `MaxLicenseManager` instance.

---

## 14. Disable/Enable Lifecycle
When MAX is disabled and enabled:
- `MaxRuntimeManager.setRuntimeEnabled(context, false/true)` sets SharedPreferences.
- It does **not** modify `MaxLicenseManager` or clear the Room license cache.
- However, if the app process restarts or resumes, `MaxLicenseManager.initialize()` runs:
  - `loadInitialCache()` loads from Room.
  - Snapshot listeners re-attach to Firestore.

---

## 15. Process Restart Behavior
Upon fresh launch:
1. `loadInitialCache()` loads the Room entity.
2. `authManager.userState` collects the Firebase user.
3. If user is signed in:
   - Firestore `licenses/{userId}` is fetched and listened to.
4. If Firestore document has:
   `plan = "LIFETIME"`, but `status` in Firestore is `"EXPIRED"`, `"TRIAL"`, or `null`/missing.

---

## 16. Truth Table

| Condition | Account UI (`CloudLicenseHeroCard`) | EntitlementGate (`checkAndConsumeQuota`) | Chat / Voice Output | Expected Behavior |
|---|---|---|---|---|
| `plan == LIFETIME`, `status == ACTIVE` | `LIFETIME PREMIUM ACTIVE` | `AllowLifetime` | Allowed (Unlimited) | Allowed |
| `plan == LIFETIME`, `status == EXPIRED` | **`LIFETIME PREMIUM ACTIVE`** | **`BlockTrialExpired`** (Falls through Step 1, Step 2, Step 3 to Line 75!) | **Blocked ("Your 3-day free trial has ended.")** | Bug Condition! |
| `plan == LIFETIME`, `status == TRIAL` | **`LIFETIME PREMIUM ACTIVE`** | **`BlockTrialExpired`** (Falls through Line 75) | **Blocked ("Your 3-day free trial has ended.")** | Bug Condition! |
| `plan == LIFETIME`, `status == REVOKED` | **`LIFETIME PREMIUM ACTIVE`** | **`BlockTrialExpired`** (Falls through Line 75) | **Blocked ("Your 3-day free trial has ended.")** | Inconsistent UI |
| `plan == TRIAL`, `status == TRIAL` (active) | `3-Day Free Trial Active` | `AllowTrial` | Allowed (10 msgs/day) | Allowed |
| `plan == TRIAL`, `status == EXPIRED` | `Free Trial Ended` | `BlockTrialExpired` | Blocked ("Your 3-day free trial has ended.") | Blocked |

---

## 17. Duplicate State Analysis & Discrepancies
Compare the exact condition in the Account Screen vs the Entitlement Gate:

### Account Screen UI:
`app/src/main/java/com/example/ui/license/LicenseActivationScreen.kt` Line 353:
```kotlin
val hero = when {
    license.plan == LicensePlan.LIFETIME -> {
        LicenseHeroConfig(
            title = "LIFETIME PREMIUM ACTIVE",
            subtitle = "Permanent Lifetime Access • Unlimited AI Messages",
            ...
        )
    }
```
**It checks ONLY `license.plan == LicensePlan.LIFETIME`! It completely ignores `license.status`!**

### Chat Gate (`EntitlementGate.kt`):
`app/src/main/java/com/example/firebase/manager/EntitlementGate.kt` Line 35:
```kotlin
// 1. Lifetime user: ALWAYS ALLOW
if (license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE) {
    Log.d(TAG, "[License] gate_allowed: LIFETIME user")
    return GateDecision.AllowLifetime
}
```
**It requires BOTH `license.plan == LicensePlan.LIFETIME` AND `license.status == LicenseStatus.ACTIVE`!**

If `license.status` is anything other than `ACTIVE` (e.g. `EXPIRED`, `TRIAL`, `REVOKED`, or default):
1. **Account Screen:** Evaluates `license.plan == LicensePlan.LIFETIME` → **Displays "LIFETIME PREMIUM ACTIVE"**!
2. **Entitlement Gate:**
   - Step 1: `license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE` → **FALSE**!
   - Step 2: `license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))` → **FALSE**!
   - Step 3: `license.plan == LicensePlan.TRIAL || license.plan == LicensePlan.FREE` → **FALSE**!
   - Step 4 (Line 75): **`return GateDecision.BlockTrialExpired`**!
3. **AssistantEngine:** Receives `GateDecision.BlockTrialExpired` → **Outputs: *"Your 3-day free trial has ended. Activate MAX Premium to continue."***!

Furthermore:
In `app/src/main/java/com/example/firebase/manager/EntitlementGate.kt`:
Lines 85–91 (`canStartVoiceSession()`):
```kotlin
if (license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE) {
    return GateDecision.AllowLifetime
}
...
return GateDecision.AllowTrial(usage, (10L - usage).coerceAtLeast(0L))
```
And Line 75: Any plan that is not active lifetime, active trial, or free falls through directly to `GateDecision.BlockTrialExpired`.

---

## 18. Root Cause Classification

**Primary Classifications:**
- **D. WRONG BOOLEAN CONDITION / ASYMMETRIC STATE VALIDATION**
- **J. FALLTHROUGH TRIAL EXPIRED DEFAULT IN ENTITLEMENT GATE**

### Detailed Explanation:
1. **UI False Positive:**
   `LicenseActivationScreen.kt` (line 353) asserts that any `CloudLicense` with `plan == LicensePlan.LIFETIME` is `"LIFETIME PREMIUM ACTIVE"`, without verifying `license.status == LicenseStatus.ACTIVE`.
2. **Gate Fallthrough False Negative:**
   `EntitlementGate.kt` (lines 35–75) strictly checks `license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE`. When `status` is not `ACTIVE` (or if `status` in Firestore was set to `EXPIRED` while testing license revocation, or left as `TRIAL`), the code skips Step 1 (`LIFETIME`), skips Step 2 (`EXPIRED` or `TRIAL`), skips Step 3 (`TRIAL` or `FREE`), and falls through directly to line 75:
   ```kotlin
   return GateDecision.BlockTrialExpired
   ```
   This returns `BlockTrialExpired` even though the user has `plan = LIFETIME`!
3. **Database State Inconsistency:**
   When a user document in Firestore has:
   `plan: "LIFETIME"`, but `status: "EXPIRED"` (e.g., from an earlier expired trial where only `plan` was edited, or when testing revocation/restoration in Firebase Console):
   - The Account UI sees `plan: "LIFETIME"` and proudly reports: **"LIFETIME PREMIUM ACTIVE"** + **"Cloud Synced"**.
   - The EntitlementGate fails Step 1, drops through to Line 75, and reports: **"Your 3-day free trial has ended."**

---

## 19. Exact Files & Functions Responsible

1. **`app/src/main/java/com/example/ui/license/LicenseActivationScreen.kt`**
   - **Function:** `CloudLicenseHeroCard(...)`
   - **Line 353:**
     ```kotlin
     license.plan == LicensePlan.LIFETIME -> {
         LicenseHeroConfig(title = "LIFETIME PREMIUM ACTIVE", ...)
     }
     ```
     *Lacks `license.status == LicenseStatus.ACTIVE` check, causing UI to show active status even when inactive/expired.*

2. **`app/src/main/java/com/example/firebase/manager/EntitlementGate.kt`**
   - **Function:** `checkAndConsumeQuota()`
   - **Lines 35 & 75:**
     ```kotlin
     if (license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE) {
         return GateDecision.AllowLifetime
     }
     ...
     return GateDecision.BlockTrialExpired // Line 75 fallthrough
     ```
     *If `plan == LIFETIME` but `status != ACTIVE`, it erroneously returns `BlockTrialExpired` instead of an appropriate license error or checking whether `isEntitled()` applies.*

3. **`app/src/main/java/com/example/firebase/model/CloudLicense.kt`**
   - **Function:** `isExpired()`
   - **Lines 94–96:**
     ```kotlin
     fun isExpired(authoritativeNow: Long = System.currentTimeMillis()): Boolean {
         if (status == LicenseStatus.EXPIRED || plan == LicensePlan.EXPIRED) return true
         if (plan == LicensePlan.LIFETIME) return false
         ...
     }
     ```
     *Notice the order: if `status == LicenseStatus.EXPIRED`, it returns `true` even when `plan == LicensePlan.LIFETIME`!*

---

## 20. Evidence

1. **Exact Error String Match:**
   `AssistantEngine.kt` lines 381, 548, 714:
   `"Your 3-day free trial has ended. Activate MAX Premium to continue."`
   Triggered ONLY when `gate.checkAndConsumeQuota()` or `gate.canStartVoiceSession()` returns `GateDecision.BlockTrialExpired`.

2. **Account Screen Hero Card String Match:**
   `LicenseActivationScreen.kt` line 355:
   `title = "LIFETIME PREMIUM ACTIVE"`
   Triggered solely by `license.plan == LicensePlan.LIFETIME`.

3. **Fallthrough Gate Logic in EntitlementGate.kt:**
   Any license where `plan == LicensePlan.LIFETIME` and `status != LicenseStatus.ACTIVE` will:
   - Fail line 35 (`license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE`)
   - Fail line 41 (`license.plan == LicensePlan.EXPIRED || (license.plan == LicensePlan.TRIAL && license.isExpired(now))`)
   - Fail line 47 (`license.plan == LicensePlan.TRIAL || license.plan == LicensePlan.FREE`)
   - Execute line 75: `return GateDecision.BlockTrialExpired`

---

## 21. Recommended Fix Location (Recommendation Only — Not Implemented)

1. **In `LicenseActivationScreen.kt` (`CloudLicenseHeroCard`):**
   Align the Hero Card conditions with actual entitlement rules:
   ```kotlin
   license.plan == LicensePlan.LIFETIME && license.status == LicenseStatus.ACTIVE -> {
       LicenseHeroConfig(title = "LIFETIME PREMIUM ACTIVE", ...)
   }
   ```
   And add explicit handling for `license.plan == LicensePlan.LIFETIME && license.status != LicenseStatus.ACTIVE` to display `"LIFETIME INACTIVE / EXPIRED"`.

2. **In `EntitlementGate.kt` (`checkAndConsumeQuota` and `canStartVoiceSession`):**
   - Check `license.isEntitled(now)` rather than relying on a brittle compound check with a fallthrough to `BlockTrialExpired`.
   - If `license.plan == LicensePlan.LIFETIME`, handle non-active statuses with a clear `GateDecision.BlockError("Lifetime license is ${license.status}")` instead of misleadingly falling through to `BlockTrialExpired`.

3. **In Firestore database `licenses/{uid}`:**
   Ensure both fields are properly synced when granting access:
   - `plan`: `"LIFETIME"`
   - `status`: `"ACTIVE"`
