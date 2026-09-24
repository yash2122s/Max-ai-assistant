package com.example.license.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LicenseStateDao {
    @Query("SELECT * FROM license_state WHERE id = 0")
    fun getState(): LicenseStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: LicenseStateEntity)
}
