package com.example.firebase.firestore

import android.os.Build
import android.util.Log
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.DeviceRegistration
import com.example.firebase.model.FirebaseUserAccount
import com.example.firebase.model.LicenseKeyHelper
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

open class FirestoreLicenseRepository(
    private val firestore: FirebaseFirestore? = try {
        FirebaseFirestore.getInstance().apply {
            try {
                firestoreSettings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                    .build()
            } catch (e: Exception) {
                // Ignore if settings already set
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "FirebaseFirestore instance deferred/unavailable: ${e.message}")
        null
    }
) {

    /**
     * Helper to get current day period key in UTC (e.g. "2026-09-24").
     */
    fun getTodayPeriodKey(date: Date = Date()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(date)
    }

    /**
     * Sync user record in Firestore.
     * Records lastLoginAt as server timestamp and links current device.
     */
    open suspend fun syncUserProfile(
        account: FirebaseUserAccount,
        currentDeviceId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.failure(Exception("Firestore is not initialized"))
        try {
            val userRef = db.collection(COLLECTION_USERS).document(account.uid)
            val snapshot = userRef.get().await()

            val updates = mutableMapOf<String, Any?>(
                "userId" to account.uid,
                "email" to account.email,
                "displayName" to account.displayName,
                "photoUrl" to account.photoUrl,
                "isAnonymous" to account.isAnonymous,
                "lastLoginAt" to FieldValue.serverTimestamp(),
                "currentDeviceId" to currentDeviceId
            )

            if (!snapshot.exists()) {
                updates["createdAt"] = FieldValue.serverTimestamp()
            }

            userRef.set(updates, SetOptions.merge()).await()
            Log.d(TAG, "[License] syncUserProfile_success user=${account.uid.take(6)}...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync user profile: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetch existing license or create an initial 3-day trial for brand new users.
     * Server timestamps are used so local clock tampering cannot forge trial duration.
     * Returning users receive their existing license and will NOT get a new trial.
     */
    open suspend fun getOrCreateLicense(userId: String): Result<CloudLicense> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.failure(Exception("Firestore is not initialized"))
        try {
            Log.d(TAG, "[License] entitlement_load_start user=${userId.take(6)}...")
            val licenseRef = db.collection(COLLECTION_LICENSES).document(userId)
            val snapshot = licenseRef.get().await()

            if (snapshot.exists() && snapshot.data != null) {
                val data = snapshot.data
                val license = CloudLicense.fromFirestoreMap(userId, data)
                Log.d(TAG, "[License] entitlement_load_success user=${userId.take(6)}... plan=${license.plan} status=${license.status}")
                return@withContext Result.success(license)
            }

            // Brand new user: Grant exactly one 7-day trial
            Log.i(TAG, "[License] trial_initialized user=${userId.take(6)}... duration=7_days")
            val nowMillis = System.currentTimeMillis()
            val sevenDaysMillis = 7L * 24L * 60L * 60L * 1000L
            val trialExpiryMillis = nowMillis + sevenDaysMillis

            val initialLicenseData = mapOf(
                "userId" to userId,
                "plan" to LicensePlan.TRIAL.name,
                "status" to LicenseStatus.TRIAL.name,
                "licenseId" to null,
                "trialStartedAt" to FieldValue.serverTimestamp(),
                "trialExpiresAt" to Timestamp(Date(trialExpiryMillis)),
                "expiresAt" to Timestamp(Date(trialExpiryMillis)),
                "issuedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            licenseRef.set(initialLicenseData).await()

            val createdSnapshot = licenseRef.get().await()
            val createdLicense = CloudLicense.fromFirestoreMap(userId, createdSnapshot.data)
            Log.i(TAG, "[License] trial_initialized_committed user=${userId.take(6)}... expiry=${createdLicense.trialExpiresAtEpochMillis}")
            Result.success(createdLicense)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching/creating license for $userId: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Atomically claim a one-time license key for the user.
     * Guaranteed single-claim invariant via Firestore transaction:
     * - Key must exist in `licenseKeys/{keyHash}`
     * - Key must be UNUSED (or already claimed by the exact same user)
     * - Binds key permanently to current userId
     * - Upgrades user's entitlement to LIFETIME ACTIVE
     */
    open suspend fun claimLicenseKey(
        userId: String,
        rawKey: String
    ): Result<CloudLicense> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.failure(Exception("Firestore is not initialized"))
        try {
            val normalizedKey = LicenseKeyHelper.normalize(rawKey)
            if (!LicenseKeyHelper.isValidFormat(normalizedKey)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid license key format. Expected: MAX-LIFE-XXXX-XXXX-XXXX"))
            }

            val keyHash = LicenseKeyHelper.sha256Hex(normalizedKey)
            Log.i(TAG, "[License] activation_started user=${userId.take(6)}... key=${LicenseKeyHelper.redact(normalizedKey)}")

            val keyRef = db.collection(COLLECTION_LICENSE_KEYS).document(keyHash)
            val licenseRef = db.collection(COLLECTION_LICENSES).document(userId)

            val updatedLicense = db.runTransaction { transaction ->
                val keyDoc = transaction.get(keyRef)
                if (!keyDoc.exists()) {
                    throw NoSuchElementException("Invalid license key. Key does not exist.")
                }

                val currentStatus = keyDoc.getString("status") ?: "UNUSED"
                val claimedBy = keyDoc.getString("claimedBy")

                if (currentStatus == "CLAIMED") {
                    if (claimedBy == userId) {
                        Log.i(TAG, "[License] activation_idempotent: already claimed by same user.")
                        val licenseDoc = transaction.get(licenseRef)
                        return@runTransaction CloudLicense.fromFirestoreMap(userId, licenseDoc.data)
                    } else {
                        Log.w(TAG, "[License] license_claim_conflict: Key already claimed by another user.")
                        throw IllegalStateException("This license key has already been claimed by another account.")
                    }
                }

                // Transition UNUSED -> CLAIMED
                transaction.update(
                    keyRef,
                    mapOf(
                        "status" to "CLAIMED",
                        "claimedBy" to userId,
                        "claimedAt" to FieldValue.serverTimestamp()
                    )
                )

                // Update user's license to LIFETIME
                val licenseUpdates = mapOf<String, Any?>(
                    "userId" to userId,
                    "plan" to LicensePlan.LIFETIME.name,
                    "status" to LicenseStatus.ACTIVE.name,
                    "licenseId" to keyHash,
                    "activatedAt" to FieldValue.serverTimestamp(),
                    "expiresAt" to null,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                transaction.set(licenseRef, licenseUpdates, SetOptions.merge())

                CloudLicense.createLifetime(
                    userId = userId,
                    licenseId = keyHash,
                    nowEpochMillis = System.currentTimeMillis()
                )
            }.await()

            Log.i(TAG, "[License] activation_success user=${userId.take(6)}... plan=LIFETIME")
            Result.success(updatedLicense)
        } catch (e: Exception) {
            Log.w(TAG, "[License] activation_failed user=${userId.take(6)}... error=${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Reads today's message count for the user.
     */
    open suspend fun getDailyUsage(
        userId: String,
        period: String = getTodayPeriodKey()
    ): Long = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext 0L
        try {
            val usageRef = db.collection(COLLECTION_LICENSES)
                .document(userId)
                .collection(COLLECTION_DAILY_USAGE)
                .document(period)

            val snapshot = usageRef.get().await()
            snapshot.getLong("messageCount") ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read daily usage: ${e.message}")
            0L
        }
    }

    /**
     * Atomically checks quota and consumes 1 message for the day.
     * Returns:
     * - Pair(newCount, true) if allowed
     * - Pair(currentCount, false) if daily limit (10) reached
     * - Throws Exception if trial expired or network/permission error
     */
    open suspend fun consumeDailyMessage(
        userId: String,
        period: String = getTodayPeriodKey(),
        maxDailyLimit: Long = 100L
    ): Result<Pair<Long, Boolean>> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.failure(Exception("Firestore is not initialized"))
        try {
            Log.d(TAG, "[License] usage_check user=${userId.take(6)}... period=$period")
            val licenseRef = db.collection(COLLECTION_LICENSES).document(userId)
            val usageRef = licenseRef.collection(COLLECTION_DAILY_USAGE).document(period)

            val result = db.runTransaction { transaction ->
                val licenseDoc = transaction.get(licenseRef)
                val now = System.currentTimeMillis()

                if (!licenseDoc.exists()) {
                    // Auto-initialize trial if doc does not exist yet
                    val sevenDaysMillis = 7L * 24L * 60L * 60L * 1000L
                    val trialExpiryMillis = now + sevenDaysMillis

                    transaction.set(licenseRef, mapOf(
                        "userId" to userId,
                        "plan" to LicensePlan.TRIAL.name,
                        "status" to LicenseStatus.TRIAL.name,
                        "licenseId" to null,
                        "trialStartedAt" to FieldValue.serverTimestamp(),
                        "trialExpiresAt" to Timestamp(Date(trialExpiryMillis)),
                        "expiresAt" to Timestamp(Date(trialExpiryMillis)),
                        "issuedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ))

                    val usageDoc = transaction.get(usageRef)
                    val count = 1L
                    if (!usageDoc.exists()) {
                        transaction.set(usageRef, mapOf(
                            "period" to period,
                            "messageCount" to count,
                            "periodStartAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedAt" to FieldValue.serverTimestamp()
                        ))
                    } else {
                        transaction.update(usageRef, mapOf(
                            "messageCount" to count,
                            "lastUpdatedAt" to FieldValue.serverTimestamp()
                        ))
                    }
                    return@runTransaction Pair(count, true)
                }

                val plan = LicensePlan.fromString(licenseDoc.getString("plan"))
                val status = LicenseStatus.fromString(licenseDoc.getString("status"))

                // 1. If Lifetime user, unlimited access
                if (plan == LicensePlan.LIFETIME && status == LicenseStatus.ACTIVE) {
                    val usageDoc = transaction.get(usageRef)
                    val count = (usageDoc.getLong("messageCount") ?: 0L) + 1L
                    if (!usageDoc.exists()) {
                        transaction.set(usageRef, mapOf(
                            "period" to period,
                            "messageCount" to count,
                            "periodStartAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedAt" to FieldValue.serverTimestamp()
                        ))
                    } else {
                        transaction.update(usageRef, mapOf(
                            "messageCount" to count,
                            "lastUpdatedAt" to FieldValue.serverTimestamp()
                        ))
                    }
                    return@runTransaction Pair(count, true)
                }

                // 2. If Trial user, verify trial has not expired
                val trialExpiry: Long = when (val raw = licenseDoc.get("trialExpiresAt") ?: licenseDoc.get("expiresAt")) {
                    is com.google.firebase.Timestamp -> raw.toDate().time
                    is Long -> raw
                    is Number -> raw.toLong()
                    is java.util.Date -> raw.time
                    else -> 0L
                }
                val isExplicitlyExpired = status == LicenseStatus.EXPIRED || plan == LicensePlan.EXPIRED
                val isTimeExpired = trialExpiry > 0L && now >= trialExpiry
                if (isExplicitlyExpired || isTimeExpired) {
                    Log.w(TAG, "[License] trial_expired user=${userId.take(6)}... expiry=$trialExpiry now=$now")
                    throw IllegalStateException("TRIAL_EXPIRED")
                }

                // 3. Check daily limit
                val usageDoc = transaction.get(usageRef)
                val currentCount = usageDoc.getLong("messageCount") ?: 0L

                if (currentCount >= maxDailyLimit) {
                    Log.w(TAG, "[License] daily_limit_reached user=${userId.take(6)}... count=$currentCount limit=$maxDailyLimit")
                    return@runTransaction Pair(currentCount, false)
                }

                val newCount = currentCount + 1L
                if (!usageDoc.exists()) {
                    transaction.set(usageRef, mapOf(
                        "period" to period,
                        "messageCount" to newCount,
                        "periodStartAt" to FieldValue.serverTimestamp(),
                        "lastUpdatedAt" to FieldValue.serverTimestamp()
                    ))
                } else {
                    transaction.update(usageRef, mapOf(
                        "messageCount" to newCount,
                        "lastUpdatedAt" to FieldValue.serverTimestamp()
                    ))
                }
                Log.i(TAG, "[License] usage_consumed user=${userId.take(6)}... newCount=$newCount remaining=${maxDailyLimit - newCount}")
                Pair(newCount, true)
            }.await()

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Real-time flow of license changes from Cloud Firestore.
     */
    open fun listenToLicense(userId: String): Flow<CloudLicense> = callbackFlow {
        val db = firestore
        if (db == null) {
            trySend(CloudLicense.createFree(userId))
            close()
            return@callbackFlow
        }
        val licenseRef = db.collection(COLLECTION_LICENSES).document(userId)
        val listener = licenseRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "License snapshot listener error: ${error.message}")
                return@addSnapshotListener
            }
            if (snapshot != null && snapshot.exists()) {
                val license = CloudLicense.fromFirestoreMap(userId, snapshot.data)
                trySend(license)
            } else {
                trySend(CloudLicense.createFree(userId))
            }
        }
        awaitClose { listener.remove() }
    }

    /**
     * Real-time flow of daily usage count.
     */
    open fun listenToDailyUsage(userId: String, period: String = getTodayPeriodKey()): Flow<Long> = callbackFlow {
        val db = firestore
        if (db == null) {
            trySend(0L)
            close()
            return@callbackFlow
        }
        val usageRef = db.collection(COLLECTION_LICENSES)
            .document(userId)
            .collection(COLLECTION_DAILY_USAGE)
            .document(period)

        val listener = usageRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Usage snapshot listener error: ${error.message}")
                return@addSnapshotListener
            }
            val count = snapshot?.getLong("messageCount") ?: 0L
            trySend(count)
        }
        awaitClose { listener.remove() }
    }

    /**
     * Register or update the app installation device.
     */
    open suspend fun registerDevice(
        userId: String,
        deviceId: String,
        appVersion: String = "1.0"
    ): Result<DeviceRegistration> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.failure(Exception("Firestore is not initialized"))
        try {
            val deviceRef = db.collection(COLLECTION_USERS)
                .document(userId)
                .collection(COLLECTION_DEVICES)
                .document(deviceId)

            val snapshot = deviceRef.get().await()
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val androidVersion = Build.VERSION.SDK_INT

            val deviceData = mutableMapOf<String, Any?>(
                "deviceId" to deviceId,
                "deviceModel" to deviceModel,
                "androidVersion" to androidVersion,
                "appVersion" to appVersion,
                "lastActiveAt" to FieldValue.serverTimestamp(),
                "isActive" to true
            )

            if (!snapshot.exists()) {
                deviceData["registeredAt"] = FieldValue.serverTimestamp()
            }

            deviceRef.set(deviceData, SetOptions.merge()).await()

            val regSnapshot = deviceRef.get().await()
            val registration = DeviceRegistration.fromFirestoreMap(deviceId, regSnapshot.data)
            Result.success(registration)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register device: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "FirestoreLicenseRepo"
        const val COLLECTION_USERS = "users"
        const val COLLECTION_LICENSES = "licenses"
        const val COLLECTION_DEVICES = "devices"
        const val COLLECTION_DAILY_USAGE = "dailyUsage"
        const val COLLECTION_LICENSE_KEYS = "licenseKeys"
    }
}
