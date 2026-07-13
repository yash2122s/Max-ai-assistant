package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.automation.scheduler.ScheduledTask
import com.example.automation.scheduler.TaskExecutionLog
import com.example.automation.scheduler.ScheduledTaskDao
import com.example.automation.scheduler.SchedulerTypeConverters

@Database(entities = [ChatMessage::class, Reminder::class, AutoReplyRule::class, ActionReward::class, InstalledApp::class, ScheduledTask::class, TaskExecutionLog::class], version = 9, exportSchema = false)
@TypeConverters(SchedulerTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun reminderDao(): ReminderDao
    abstract fun autoReplyRuleDao(): AutoReplyRuleDao
    abstract fun rewardDao(): RewardDao
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jarvis_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
