package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InstalledAppDao {
    @Query("SELECT * FROM installed_apps")
    suspend fun getAllApps(): List<InstalledApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<InstalledApp>)

    @Query("DELETE FROM installed_apps")
    suspend fun deleteAllApps()
}
