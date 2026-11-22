package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    fun getAllRemindersFlow(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders ORDER BY triggerAt ASC")
    suspend fun getAllReminders(): List<Reminder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: Reminder): Long

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun updateReminderStatus(id: Int, status: String)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Int)

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: Int): Reminder?

    @Transaction
    suspend fun claimDueReminder(id: Int, now: Long, toleranceMs: Long = 0L): Reminder? {
        val reminder = getReminderById(id) ?: return null
        if (reminder.status != "pending" || !reminder.isEnabled || reminder.triggerAt > (now + toleranceMs)) return null
        updateReminderStatus(id, "completed")
        return reminder
    }

    @Query("SELECT * FROM reminders WHERE status = 'pending' AND isEnabled = 1 ORDER BY triggerAt ASC")
    suspend fun getPendingReminders(): List<Reminder>
}
