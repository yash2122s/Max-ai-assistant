package com.example.license.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The only thing that matters here is `rawSignedLicenseJson` — the
 * literal file content as issued by the generator. Everything else is
 * bookkeeping (rollback detection, trial markers, a revocation cache).
 *
 * There is deliberately NO `isPremium: Boolean` column. EntitlementManager
 * re-derives Premium/Trial/Free/Invalid/Expired from rawSignedLicenseJson
 * on every check — see EntitlementManagerImpl.computeEntitlement().
 */
@Entity(tableName = "license_state")
data class LicenseStateEntity(
    @PrimaryKey val id: Int = 0, // singleton row
    val rawSignedLicenseJson: String?,
    val lastSeenWallClockEpochMillis: Long?,
    val trialStartElapsedRealtime: Long?,
    val trialStartWallClockEpochMillis: Long?,
    val cachedRevokedIdsJson: String,
)
