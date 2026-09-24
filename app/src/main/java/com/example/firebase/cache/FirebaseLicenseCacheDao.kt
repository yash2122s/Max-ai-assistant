package com.example.firebase.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FirebaseLicenseCacheDao {
    @Query("SELECT * FROM firebase_license_cache WHERE userId = :userId LIMIT 1")
    fun getLicense(userId: String): FirebaseLicenseCacheEntity?

    @Query("SELECT * FROM firebase_license_cache ORDER BY cachedAtEpochMillis DESC LIMIT 1")
    fun getLatestLicense(): FirebaseLicenseCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdate(entity: FirebaseLicenseCacheEntity)

    @Query("DELETE FROM firebase_license_cache WHERE userId = :userId")
    fun deleteLicense(userId: String)

    @Query("DELETE FROM firebase_license_cache")
    fun clearAll()
}
