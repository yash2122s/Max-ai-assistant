package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeriodLogDao {
    @Query("SELECT * FROM period_logs WHERE ownerKey = :ownerKey ORDER BY startDate ASC")
    fun getAllPeriodsFlow(ownerKey: String): Flow<List<PeriodLog>>

    @Query("SELECT * FROM period_logs WHERE ownerKey = :ownerKey ORDER BY startDate ASC")
    suspend fun getAllPeriods(ownerKey: String): List<PeriodLog>

    @Query("SELECT * FROM period_logs WHERE ownerKey = :ownerKey ORDER BY startDate DESC LIMIT 1")
    suspend fun getLatestPeriod(ownerKey: String): PeriodLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriod(log: PeriodLog): Long

    @Query("UPDATE period_logs SET endDate = :endDate, durationDays = :durationDays WHERE id = :id AND ownerKey = :ownerKey")
    suspend fun updatePeriodEndDate(id: Int, endDate: Long, durationDays: Int, ownerKey: String)

    @Query("UPDATE period_logs SET notes = :notes WHERE id = :id AND ownerKey = :ownerKey")
    suspend fun updatePeriodNotes(id: Int, notes: String, ownerKey: String)

    @Query("DELETE FROM period_logs WHERE id = :id AND ownerKey = :ownerKey")
    suspend fun deletePeriod(id: Int, ownerKey: String)

    @Query("DELETE FROM period_logs WHERE ownerKey = :ownerKey")
    suspend fun deleteAllPeriods(ownerKey: String = "legacy")
}
