package com.example.license.storage

import com.example.license.licensing.SignedLicense
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

class LicenseStorage(private val dao: LicenseStateDao) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun current(): LicenseStateEntity =
        dao.getState() ?: LicenseStateEntity(
            rawSignedLicenseJson = null,
            lastSeenWallClockEpochMillis = null,
            trialStartElapsedRealtime = null,
            trialStartWallClockEpochMillis = null,
            cachedRevokedIdsJson = "[]",
        )

    fun getStoredLicense(): SignedLicense? =
        current().rawSignedLicenseJson?.let { json.decodeFromString(SignedLicense.serializer(), it) }

    fun setStoredLicense(license: SignedLicense) {
        dao.upsert(current().copy(rawSignedLicenseJson = json.encodeToString(SignedLicense.serializer(), license)))
    }

    fun clearStoredLicense() {
        dao.upsert(current().copy(rawSignedLicenseJson = null))
    }

    fun getLastSeenWallClock(): Instant? =
        current().lastSeenWallClockEpochMillis?.let { Instant.ofEpochMilli(it) }

    fun recordLastSeenWallClock(instant: Instant) {
        dao.upsert(current().copy(lastSeenWallClockEpochMillis = instant.toEpochMilli()))
    }

    fun initTrialStart(elapsedRealtime: Long, wallClock: Instant) {
        val curr = current()
        dao.upsert(
            curr.copy(
                trialStartElapsedRealtime = curr.trialStartElapsedRealtime ?: elapsedRealtime,
                trialStartWallClockEpochMillis = curr.trialStartWallClockEpochMillis ?: wallClock.toEpochMilli(),
                lastSeenWallClockEpochMillis = curr.lastSeenWallClockEpochMillis ?: wallClock.toEpochMilli()
            )
        )
    }

    fun getTrialStartElapsedRealtime(): Long? = current().trialStartElapsedRealtime

    fun setTrialStartElapsedRealtime(value: Long) {
        dao.upsert(current().copy(trialStartElapsedRealtime = value))
    }

    fun getTrialStartWallClock(): Instant? =
        current().trialStartWallClockEpochMillis?.let { Instant.ofEpochMilli(it) }

    fun setTrialStartWallClock(instant: Instant) {
        dao.upsert(current().copy(trialStartWallClockEpochMillis = instant.toEpochMilli()))
    }

    fun getCachedRevokedIds(): Set<String> =
        json.decodeFromString<List<String>>(current().cachedRevokedIdsJson).toSet()

    fun setCachedRevokedIds(ids: Set<String>) {
        dao.upsert(current().copy(cachedRevokedIdsJson = json.encodeToString(ids.toList())))
    }
}
