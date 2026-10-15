package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "period_logs",
    indices = [Index(value = ["ownerKey", "startDate"], unique = true)]
)
data class PeriodLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val startDate: Long,
    val endDate: Long? = null,
    val durationDays: Int = 0,
    val notes: String? = null,
    val ownerKey: String = "legacy"
)
