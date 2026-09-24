package com.example.firebase.manager

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.preferences.SettingsManager
import com.example.firebase.appcheck.MaxAppCheckManager
import com.example.firebase.auth.FirebaseAuthManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

class MaxLicenseManager(
    private val context: Context,
    val authManager: FirebaseAuthManager = FirebaseAuthManager(context),
    val firestoreRepo: FirestoreLicenseRepository = FirestoreLicenseRepository(),
    private val cacheDao: FirebaseLicenseCacheDao = AppDatabase.getDatabase(context).firebaseLicenseCacheDao(),
    private val integrityVerifier: LicenseIntegrityVerifier = LicenseIntegrityVerifier(context),
    private val appCheckManager: MaxAppCheckManager = MaxAppCheckManager(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val settingsManager = SettingsManager(context)
    val deviceId: String
        get() = settingsManager.userId

    private val _currentLicense = MutableStateFlow<CloudLicense>(CloudLicense.createFree("anonymous"))
    val currentLicense: StateFlow<CloudLicense> = _currentLicense.asStateFlow()

    private val _dailyUsageCount = MutableStateFlow<Long>(settingsManager.localDailyUsageCount)
    val dailyUsageCount: StateFlow<Long> = _dailyUsageCount.asStateFlow()

    private val _syncState = MutableStateFlow<LicenseSyncState>(LicenseSyncState.Idle)
    val syncState: StateFlow<LicenseSyncState> = _syncState.asStateFlow()

    val currentUser: StateFlow<FirebaseUserAccount?> = authManager.userState

    private val _currentDevice = MutableStateFlow<DeviceRegistration?>(null)
    val currentDevice: StateFlow<DeviceRegistration?> = _currentDevice.asStateFlow()

    fun initialize() {
        Log.i(TAG, "Initializing MaxLicenseManager...")
        // 1. Initialize Firebase App Check
        appCheckManager.initialize()

        // 2. Load cached license immediately for zero startup latency
        loadInitialCache()

        // 3. React to Firebase Authentication changes
        scope.launch {
            authManager.userState.collect { user ->
                if (user != null) {
                    Log.d(TAG, "[License] authUser=${user.uid.take(6)}... Authenticated user detected. Starting cloud sync...")
                    syncWithCloud(user.uid)

                    // Real-time daily usage listener
                    launch {
                        firestoreRepo.listenToDailyUsage(user.uid).collect { count ->
                            val maxCount = maxOf(count, settingsManager.localDailyUsageCount)
                            settingsManager.localDailyUsageCount = maxCount
                            _dailyUsageCount.value = maxCount
                        }
                    }

                    // Real-time cloud license listener
                    launch {
                        firestoreRepo.listenToLicense(user.uid).collect { cloudLicense ->
                            if (cloudLicense.plan != LicensePlan.FREE) {
                                persistLicenseToCache(cloudLicense)
                                _currentLicense.value = cloudLicense
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

    private fun loadInitialCache() {
        try {
            val cached = cacheDao.getLatestLicense()
            if (cached != null) {
                if (integrityVerifier.verifyIntegrity(cached)) {
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
        val result = authManager.signInWithGoogle(activity, customServerClientId)
        if (result.isSuccess) {
            val account = result.getOrNull()!!
            // Launch cloud sync asynchronously so Google sign-in UI returns immediately!
            scope.launch {
                syncWithCloud(account.uid)
            }
        }
        return result
    }

    suspend fun signOut(): Result<Unit> {
        val result = authManager.signOut()
        if (result.isSuccess) {
            _currentLicense.value = CloudLicense.createFree("anonymous")
            _dailyUsageCount.value = 0L
            _syncState.value = LicenseSyncState.Idle
            _currentDevice.value = null
        }
        return result
    }

    suspend fun syncWithCloud(userId: String): Result<CloudLicense> = withContext(Dispatchers.IO) {
        _syncState.value = LicenseSyncState.Syncing

        // 1. Sync User Profile in Firestore
        val userAccount = authManager.userState.value ?: FirebaseUserAccount(uid = userId)
        val userSyncResult = firestoreRepo.syncUserProfile(userAccount, deviceId)
        if (userSyncResult.isFailure) {
            Log.w(TAG, "User profile sync warning: ${userSyncResult.exceptionOrNull()?.message}")
        }

        // 2. Register Device
        val deviceResult = firestoreRepo.registerDevice(userId, deviceId)
        if (deviceResult.isSuccess) {
            _currentDevice.value = deviceResult.getOrNull()
        }

        // 3. Fetch or Create License in Firestore
        val licenseResult = firestoreRepo.getOrCreateLicense(userId)
        if (licenseResult.isSuccess) {
            val cloudLicense = licenseResult.getOrNull()!!
            persistLicenseToCache(cloudLicense)
            _currentLicense.value = cloudLicense
            _syncState.value = LicenseSyncState.Synced(
                license = cloudLicense,
                isFromCache = false,
                cacheAgeMillis = 0L,
                isStale = false
            )

            // Also load initial daily usage count
            val usageCount = firestoreRepo.getDailyUsage(userId)
            _dailyUsageCount.value = usageCount

            Log.i(TAG, "[License] Cloud license successfully synced: Plan=${cloudLicense.plan}, Status=${cloudLicense.status}")
            Result.success(cloudLicense)
        } else {
            val error = licenseResult.exceptionOrNull()
            Log.w(TAG, "Cloud sync failed (${error?.message}). Falling back to cached license...")

            // Graceful fallback to verified Room cache
            val cached = cacheDao.getLicense(userId) ?: cacheDao.getLatestLicense()
            if (cached != null && integrityVerifier.verifyIntegrity(cached)) {
                val isStale = integrityVerifier.isCacheStale(cached.cachedAtEpochMillis)
                val license = cached.toCloudLicense()
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
    suspend fun claimLicenseKey(rawKey: String): Result<CloudLicense> = withContext(Dispatchers.IO) {
        val user = authManager.userState.value
            ?: return@withContext Result.failure(IllegalStateException("Please sign in with Google first."))

        val result = firestoreRepo.claimLicenseKey(user.uid, rawKey)
        if (result.isSuccess) {
            val license = result.getOrNull()!!
            persistLicenseToCache(license)
            _currentLicense.value = license
            _syncState.value = LicenseSyncState.Synced(license, isFromCache = false)
            Log.i(TAG, "[License] License successfully claimed and activated: Plan=${license.plan}")
        }
        result
    }

    /**
     * Consumes 1 daily message for the current user (locally persisted and synced to cloud).
     */
    suspend fun consumeDailyMessage(): Result<Pair<Long, Boolean>> = withContext(Dispatchers.IO) {
        val currentLocal = settingsManager.localDailyUsageCount
        val newLocal = currentLocal + 1L
        settingsManager.localDailyUsageCount = newLocal
        _dailyUsageCount.value = newLocal

        val user = authManager.userState.value
        if (user == null) {
            val allowed = newLocal <= 10L
            return@withContext Result.success(Pair(newLocal, allowed))
        }

        val cloudResult = firestoreRepo.consumeDailyMessage(user.uid)
        if (cloudResult.isSuccess) {
            val (cloudCount, allowed) = cloudResult.getOrNull()!!
            val maxCount = maxOf(newLocal, cloudCount)
            settingsManager.localDailyUsageCount = maxCount
            _dailyUsageCount.value = maxCount
            Result.success(Pair(maxCount, allowed))
        } else {
            val allowed = newLocal <= 10L
            Result.success(Pair(newLocal, allowed))
        }
    }

    private fun persistLicenseToCache(cloudLicense: CloudLicense) {
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
