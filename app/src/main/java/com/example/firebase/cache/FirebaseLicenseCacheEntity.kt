package com.example.firebase.cache

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.firebase.model.CloudLicense
import com.example.firebase.model.LicensePlan
import com.example.firebase.model.LicenseStatus

@Entity(tableName = "firebase_license_cache")
data class FirebaseLicenseCacheEntity(
    @PrimaryKey val userId: String,
    val plan: String,
    val status: String,
    val trialStartedAtEpochMillis: Long?,
    val trialExpiresAtEpochMillis: Long?,
    val expiresAtEpochMillis: Long?,
    val issuedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val cachedAtEpochMillis: Long,
    val integrityHash: String
) {
    fun toCloudLicense(): CloudLicense {
        return CloudLicense(
            userId = userId,
            plan = LicensePlan.fromString(plan),
            status = LicenseStatus.fromString(status),
            trialStartedAtEpochMillis = trialStartedAtEpochMillis,
            trialExpiresAtEpochMillis = trialExpiresAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            issuedAtEpochMillis = issuedAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis
        )
    }

    companion object {
        fun fromCloudLicense(
            license: CloudLicense,
            cachedAtEpochMillis: Long,
            integrityHash: String
        ): FirebaseLicenseCacheEntity {
            return FirebaseLicenseCacheEntity(
                userId = license.userId,
                plan = license.plan.name,
                status = license.status.name,
                trialStartedAtEpochMillis = license.trialStartedAtEpochMillis,
                trialExpiresAtEpochMillis = license.trialExpiresAtEpochMillis,
                expiresAtEpochMillis = license.expiresAtEpochMillis,
                issuedAtEpochMillis = license.issuedAtEpochMillis,
                updatedAtEpochMillis = license.updatedAtEpochMillis,
                cachedAtEpochMillis = cachedAtEpochMillis,
                integrityHash = integrityHash
            )
        }
    }
}
