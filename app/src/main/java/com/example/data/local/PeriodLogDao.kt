package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeriodLogDao {
    @Query("SELECT * FROM period_logs ORDER BY startDate ASC")
    fun getAllPeriodsFlow(): Flow<List<PeriodLog>>

    @Query("SELECT * FROM period_logs ORDER BY startDate ASC")
    suspend fun getAllPeriods(): List<PeriodLog>

    @Query("SELECT * FROM period_logs ORDER BY startDate DESC LIMIT 1")
    suspend fun getLatestPeriod(): PeriodLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeriod(log: PeriodLog): Long

    @Query("UPDATE period_logs SET endDate = :endDate, durationDays = :durationDays WHERE id = :id")
    suspend fun updatePeriodEndDate(id: Int, endDate: Long, durationDays: Int)

    @Query("UPDATE period_logs SET notes = :notes WHERE id = :id")
    suspend fun updatePeriodNotes(id: Int, notes: String)

    @Query("DELETE FROM period_logs WHERE id = :id")
    suspend fun deletePeriod(id: Int)

    @Query("DELETE FROM period_logs")
    suspend fun deleteAllPeriods()
}
