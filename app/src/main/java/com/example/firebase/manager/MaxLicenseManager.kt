package com.example.firebase.manager

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsManager
import com.example.firebase.appcheck.MaxAppCheckManager
import com.example.firebase.auth.FirebaseAuthManager
import com.example.firebase.auth.AuthSession
import com.example.firebase.cache.FirebaseLicenseCacheDao
import com.example.firebase.cache.FirebaseLicenseCacheEntity
import com.example.firebase.cache.LicenseIntegrityVerifier
import com.example.firebase.firestore.FirestoreLicenseRepository
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.DeviceRegistration
import com.example.firebase.model.FirebaseUserAccount
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus
import com.example.firebase.model.LicenseSyncState
import com.example.license.entitlement.Entitlement
import com.example.license.entitlement.Feature
import com.example.license.entitlement.FeatureTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

class MaxLicenseManager(
    private val context: Context,
    val authManager: FirebaseAuthManager = FirebaseAuthManager(context),
    val firestoreRepo: FirestoreLicenseRepository = FirestoreLicenseRepository(),
    private val cacheDao: FirebaseLicenseCacheDao = AppDatabase.getDatabase(context).firebaseLicenseCacheDao(),
    private val integrityVerifier: LicenseIntegrityVerifier = LicenseIntegrityVerifier(context),
    private val appCheckManager: MaxAppCheckManager = MaxAppCheckManager(context),
    private val initializeAppCheck: () -> Unit = { appCheckManager.initialize() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var authSubscription: Job? = null
    private var sessionJobs: Job? = null
    private val sessionInvalidationListener: () -> Unit = { invalidateSession() }
    private val usagePrefs by lazy { context.getSharedPreferences("max_license_usage", Context.MODE_PRIVATE) }

    private val settingsManager = SettingsManager(context)
    val deviceId: String
        get() = settingsManager.userId

    private val _currentLicense = MutableStateFlow<CloudLicense>(CloudLicense.createFree("anonymous"))
    val currentLicense: StateFlow<CloudLicense> = _currentLicense.asStateFlow()

    // Usage is keyed by authenticated UID; never bootstrap account B from the legacy device-wide value.
    private val _dailyUsageCount = MutableStateFlow<Long>(0L)
    val dailyUsageCount: StateFlow<Long> = _dailyUsageCount.asStateFlow()

    private val _syncState = MutableStateFlow<LicenseSyncState>(LicenseSyncState.Idle)
    val syncState: StateFlow<LicenseSyncState> = _syncState.asStateFlow()

    val currentUser: StateFlow<FirebaseUserAccount?> = authManager.userState

    private val _currentDevice = MutableStateFlow<DeviceRegistration?>(null)
    val currentDevice: StateFlow<DeviceRegistration?> = _currentDevice.asStateFlow()

    fun initialize() {
        if (authSubscription != null) return
        Log.i(TAG, "Initializing MaxLicenseManager...")
        // 1. Initialize Firebase App Check
        initializeAppCheck()

        // 3. React to Firebase Authentication changes
        authManager.addSessionInvalidationListener(this, sessionInvalidationListener)
        authSubscription = scope.launch {
            authManager.sessionState.collectLatest { session ->
                sessionJobs?.cancel()
                val user = authManager.userState.value
                if (user != null && session.uid == user.uid && authManager.isCurrentSession(session)) {
                    Log.d(TAG, "[License] authUser=${user.uid.take(6)}... Authenticated user detected. Starting cloud sync...")
                    sessionJobs = launch {
                        kotlinx.coroutines.supervisorScope {
                        launch { loadInitialCache(session); syncWithCloud(user.uid) }
                        launch {
                            firestoreRepo.listenToLicense(user.uid).collect { cloudLicense ->
                                if (authManager.isCurrentSession(session) && cloudLicense.userId == session.uid && cloudLicense.plan != LicensePlan.FREE) {
                                    persistLicenseToCache(cloudLicense)
                                    if (!authManager.isCurrentSession(session)) return@collect
                                    _currentLicense.value = cloudLicense
                                }
                            }
                        }
                        firestoreRepo.listenToDailyUsage(user.uid).collect { count ->
                            if (authManager.isCurrentSession(session)) {
                                val maxCount = maxOf(count, localUsage(user.uid))
                                saveLocalUsage(user.uid, maxCount)
                                _dailyUsageCount.value = maxCount
                            }
                        }
                        }
                    }
                } else {
                    Log.d(TAG, "[License] No authenticated user. Reverting to Free tier...")
                    _currentLicense.value = CloudLicense.createFree("anonymous")
                    _dailyUsageCount.value = 0L
                    _syncState.value = LicenseSyncState.Idle
                    _currentDevice.value = null
                }
            }
        }
    }

    private fun invalidateSession() {
        sessionJobs?.cancel()
        sessionJobs = null
        _currentLicense.value = CloudLicense.createFree("anonymous")
        _dailyUsageCount.value = 0L
        _syncState.value = LicenseSyncState.Idle
        _currentDevice.value = null
    }

    internal fun close() {
        authManager.removeSessionInvalidationListener(this)
        authSubscription?.cancel()
        sessionJobs?.cancel()
    }

    private suspend fun loadInitialCache(session: AuthSession) {
        try {
            val uid = authManager.currentUid
            if (uid == null || !authManager.isCurrentSession(session)) return
            val cached = withContext(Dispatchers.IO) { cacheDao.getLicense(uid) }
            if (!authManager.isCurrentSession(session)) return
            if (cached != null) {
                if (cached.userId == uid && integrityVerifier.verifyIntegrity(cached)) {
                    val isStale = integrityVerifier.isCacheStale(cached.cachedAtEpochMillis)
                    val license = cached.toCloudLicense()
                    _currentLicense.value = license
                    _syncState.value = LicenseSyncState.Synced(
                        license = license,
                        isFromCache = true,
                        cacheAgeMillis = System.currentTimeMillis() - cached.cachedAtEpochMillis,
                        isStale = isStale
                    )
                    Log.d(TAG, "Loaded valid cached license for ${cached.userId}: Plan=${license.plan}, Status=${license.status}, Stale=$isStale")
                } else {
                    Log.w(TAG, "Local license cache failed integrity check (possible tampering). Rejecting cached license.")
                    _currentLicense.value = CloudLicense.createFree("untrusted")
                    _syncState.value = LicenseSyncState.Error("Cache integrity check failed", isOffline = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load license cache on startup: ${e.message}")
        }
    }

    suspend fun signInWithGoogle(
        activity: Activity,
        customServerClientId: String? = null
    ): Result<FirebaseUserAccount> {
        return authManager.signInWithGoogle(activity, customServerClientId)
    }

    suspend fun switchAccount(activity: Activity): Result<FirebaseUserAccount> =
        authManager.switchAccount(activity)

    suspend fun signOut(): Result<Unit> {
        val result = authManager.signOut()
        if (result.isSuccess && authManager.currentUid == null) {
            _currentLicense.value = CloudLicense.createFree("anonymous")
            _dailyUsageCount.value = 0L
            _syncState.value = LicenseSyncState.Idle
            _currentDevice.value = null
        }
        return result
    }

    suspend fun syncWithCloud(userId: String): Result<CloudLicense> = withContext(Dispatchers.Main.immediate) {
        val session = authManager.sessionState.value
        if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
        _syncState.value = LicenseSyncState.Syncing

        // 1. Sync User Profile in Firestore
        val userAccount = authManager.userState.value
            ?: return@withContext Result.failure(IllegalStateException("No authenticated account"))
        val userSyncResult = firestoreRepo.syncUserProfile(userAccount, deviceId)
        if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
        if (userSyncResult.isFailure) {
            Log.w(TAG, "User profile sync warning: ${userSyncResult.exceptionOrNull()?.message}")
        }

        // 2. Register Device
        val deviceResult = firestoreRepo.registerDevice(userId, deviceId)
        if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
        if (deviceResult.isSuccess && isValidSession(userId, session)) {
            _currentDevice.value = deviceResult.getOrNull()
        }

        // 3. Fetch or Create License in Firestore
        val licenseResult = firestoreRepo.getOrCreateLicense(userId)
        if (licenseResult.isSuccess && isValidSession(userId, session)) {
            val cloudLicense = licenseResult.getOrNull()!!
            if (cloudLicense.userId != userId) return@withContext Result.failure(IllegalStateException("License response account mismatch"))
            if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
            persistLicenseToCache(cloudLicense)
            if (!isValidSession(userId, session)) return@withContext Result.failure(com.example.firebase.auth.AuthException.SessionChanged)
            _currentLicense.value = cloudLicense
            _syncState.value = LicenseSyncState.Synced(
                license = cloudLicense,
                isFromCache = false,
                cacheAgeMillis = 0L,
                isStale = false
            )

            // Also load initial daily usage count
            val usageCount = firestoreRepo.getDailyUsage(userId)
            if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
            val safeUsage = maxOf(usageCount, localUsage(userId))
            saveLocalUsage(userId, safeUsage)
            _dailyUsageCount.value = safeUsage

            Log.i(TAG, "[License] Cloud license successfully synced: Plan=${cloudLicense.plan}, Status=${cloudLicense.status}")
            Result.success(cloudLicense)
        } else {
            val error = licenseResult.exceptionOrNull()
            Log.w(TAG, "Cloud sync failed (${error?.message}). Falling back to cached license...")

            // Graceful fallback to verified Room cache
            if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
            val cached = withContext(Dispatchers.IO) { cacheDao.getLicense(userId) }
            if (!isValidSession(userId, session)) return@withContext Result.failure(com.example.firebase.auth.AuthException.SessionChanged)
            if (cached != null && cached.userId == userId && integrityVerifier.verifyIntegrity(cached)) {
                val isStale = integrityVerifier.isCacheStale(cached.cachedAtEpochMillis)
                val license = cached.toCloudLicense()
                if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
                _currentLicense.value = license
                _syncState.value = LicenseSyncState.Synced(
                    license = license,
                    isFromCache = true,
                    cacheAgeMillis = System.currentTimeMillis() - cached.cachedAtEpochMillis,
                    isStale = isStale
                )
                Result.success(license)
            } else {
                val freeFallback = CloudLicense.createFree(userId)
                if (!isValidSession(userId, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
                _currentLicense.value = freeFallback
                _syncState.value = LicenseSyncState.Error(
                    message = error?.message ?: "Network unreachable and no valid cache found",
                    cachedLicense = null,
                    isOffline = true
                )
                Result.failure(error ?: Exception("Failed to sync license with cloud."))
            }
        }
    }

    /**
     * Atomically claims a one-time license key for the authenticated user.
     */
    suspend fun claimLicenseKey(rawKey: String): Result<CloudLicense> = withContext(Dispatchers.Main.immediate) {
        val user = authManager.userState.value
            ?: return@withContext Result.failure(IllegalStateException("Please sign in with Google first."))

        val session = authManager.sessionState.value
        if (!isValidSession(user.uid, session)) return@withContext Result.failure(com.example.firebase.auth.AuthException.SessionChanged)
        val result = firestoreRepo.claimLicenseKey(user.uid, rawKey)
        if (!isValidSession(user.uid, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
        if (result.isSuccess) {
            val license = result.getOrNull()!!
            if (license.userId != user.uid) return@withContext Result.failure(IllegalStateException("License response belongs to another account"))
            if (!isValidSession(user.uid, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
            persistLicenseToCache(license)
            if (!isValidSession(user.uid, session)) return@withContext Result.failure(com.example.firebase.auth.AuthException.SessionChanged)
            _currentLicense.value = license
            _syncState.value = LicenseSyncState.Synced(license, isFromCache = false)
            Log.i(TAG, "[License] License successfully claimed and activated: Plan=${license.plan}")
        }
        result
    }

    /**
     * Consumes 1 daily message for the current user (locally persisted and synced to cloud).
     */
    suspend fun consumeDailyMessage(): Result<Pair<Long, Boolean>> = withContext(Dispatchers.Main.immediate) {
        val user = authManager.userState.value
        val session = authManager.sessionState.value
        val uid = user?.uid ?: "__guest__"
        if (!authManager.isCurrentSession(session)) return@withContext Result.failure(com.example.firebase.auth.AuthException.SessionChanged)
        val knownUsage = user == null || usagePrefs.contains(usageKey(uid))
        val currentLocal = if (user == null) settingsManager.localDailyUsageCount else localUsage(uid)
        val newLocal = currentLocal + 1L
        if (user == null) settingsManager.localDailyUsageCount = newLocal
        else if (knownUsage) saveLocalUsage(uid, newLocal)
        _dailyUsageCount.value = newLocal

        if (user == null) {
            val allowed = newLocal <= 20L
            return@withContext Result.success(Pair(newLocal, allowed))
        }

        val cloudResult = firestoreRepo.consumeDailyMessage(user.uid)
        if (!isValidSession(user.uid, session)) return@withContext Result.failure(IllegalStateException("Authenticated session changed"))
        if (cloudResult.isSuccess) {
            val (cloudCount, allowed) = cloudResult.getOrNull()!!
            val maxCount = maxOf(newLocal, cloudCount)
            saveLocalUsage(user.uid, maxCount)
            _dailyUsageCount.value = maxCount
            Result.success(Pair(maxCount, allowed))
        } else {
            // An unbound legacy device counter cannot be assigned to this UID.
            // Without a known account counter, fail closed until cloud refresh.
            val allowed = knownUsage && newLocal <= 20L
            Result.success(Pair(newLocal, allowed))
        }
    }

    private fun isValidSession(uid: String, session: AuthSession): Boolean =
        session.uid == uid && authManager.isCurrentSession(session) && authManager.currentUid == uid

    private fun usageKey(uid: String): String = "${LocalDate.now(ZoneOffset.UTC)}:$uid"
    private fun localUsage(uid: String): Long = usagePrefs.getLong(usageKey(uid), 0L)
    private fun saveLocalUsage(uid: String, value: Long) {
        usagePrefs.edit().putLong(usageKey(uid), value).apply()
    }

    private suspend fun persistLicenseToCache(cloudLicense: CloudLicense) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val hash = integrityVerifier.computeIntegrityHash(
                userId = cloudLicense.userId,
                plan = cloudLicense.plan.name,
                status = cloudLicense.status.name,
                trialExpiresAtEpochMillis = cloudLicense.trialExpiresAtEpochMillis,
                expiresAtEpochMillis = cloudLicense.expiresAtEpochMillis,
                cachedAtEpochMillis = now
            )
            val cacheEntity = FirebaseLicenseCacheEntity.fromCloudLicense(cloudLicense, now, hash)
            cacheDao.insertOrUpdate(cacheEntity)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist license cache: ${e.message}")
        }
    }

    /**
     * Map current Firebase cloud license into MAX Entitlement model.
     */
    fun toEntitlement(): Entitlement {
        val license = _currentLicense.value
        val now = System.currentTimeMillis()

        if (license.status == LicenseStatus.REVOKED) {
            return Entitlement.Revoked
        }
        if (license.isExpired(now)) {
            return Entitlement.Expired
        }

        return when (license.plan) {
            LicensePlan.FREE, LicensePlan.EXPIRED -> Entitlement.Free
            LicensePlan.TRIAL -> {
                if ((license.status == LicenseStatus.TRIAL || license.status == LicenseStatus.ACTIVE) && license.isEntitled(now)) {
                    val remainingMillis = license.remainingTrialMillis(now)
                    Entitlement.Trial(remainingMillis.milliseconds)
                } else {
                    Entitlement.Expired
                }
            }
            LicensePlan.LIFETIME, LicensePlan.PREMIUM, LicensePlan.PRO -> {
                if (license.status == LicenseStatus.ACTIVE && license.isEntitled(now)) {
                    val expiry = license.expiresAtEpochMillis?.let { Instant.ofEpochMilli(it) }
                        ?: Instant.MAX
                    Entitlement.Premium(expiry)
                } else {
                    Entitlement.Expired
                }
            }
        }
    }

    /**
     * Centralized feature check.
     */
    fun isFeatureAllowed(feature: Feature): Boolean {
        val entitlement = toEntitlement()
        return FeatureTable.isAllowed(entitlement, feature)
    }

    companion object {
        private const val TAG = "MaxLicenseManager"
    }
}
