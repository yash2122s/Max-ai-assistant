package com.example.firebase.model

sealed class LicenseSyncState {
    data object Idle : LicenseSyncState()
    data object Syncing : LicenseSyncState()
    data class Synced(
        val license: CloudLicense,
        val isFromCache: Boolean,
        val cacheAgeMillis: Long = 0L,
        val isStale: Boolean = false
    ) : LicenseSyncState()
    data class Error(
        val message: String,
        val cachedLicense: CloudLicense? = null,
        val isOffline: Boolean = false
    ) : LicenseSyncState()
}
