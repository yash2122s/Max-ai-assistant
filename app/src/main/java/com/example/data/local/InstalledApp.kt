package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_apps")
data class InstalledApp(
    @PrimaryKey val packageName: String,
    val appName: String
)
