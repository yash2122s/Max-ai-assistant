package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.automation.scheduler.ScheduledTask
import com.example.automation.scheduler.TaskExecutionLog
import com.example.automation.scheduler.ScheduledTaskDao
import com.example.automation.scheduler.SchedulerTypeConverters
import com.example.data.local.PeriodLog
import com.example.data.local.PeriodLogDao
import com.example.memory.data.MemoryTypeConverters
import com.example.memory.data.PermanentMemory
import com.example.memory.data.PermanentMemoryDao

@Database(
    entities = [
        ChatMessage::class, Reminder::class, AutoReplyRule::class, 
        ActionReward::class, InstalledApp::class, ScheduledTask::class, 
        TaskExecutionLog::class, PeriodLog::class, PermanentMemory::class
    ], 
    version = 12, 
    exportSchema = false
)
@TypeConverters(SchedulerTypeConverters::class, MemoryTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun reminderDao(): ReminderDao
    abstract fun autoReplyRuleDao(): AutoReplyRuleDao
    abstract fun rewardDao(): RewardDao
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun periodLogDao(): PeriodLogDao
    abstract fun permanentMemoryDao(): PermanentMemoryDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `period_logs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `startDate` INTEGER NOT NULL, 
                        `endDate` INTEGER, 
                        `durationDays` INTEGER NOT NULL, 
                        `notes` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_period_logs_startDate` ON `period_logs` (`startDate`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `permanent_memory` (
                        `memoryId` TEXT NOT NULL, 
                        `title` TEXT NOT NULL, 
                        `content` TEXT NOT NULL, 
                        `category` TEXT NOT NULL, 
                        `type` TEXT NOT NULL, 
                        `source` TEXT NOT NULL, 
                        `pinned` INTEGER NOT NULL, 
                        `enabled` INTEGER NOT NULL, 
                        `usageCount` INTEGER NOT NULL, 
                        `lastUsedAt` INTEGER, 
                        `createdAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`memoryId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permanent_memory_pinned` ON `permanent_memory` (`pinned`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permanent_memory_enabled` ON `permanent_memory` (`enabled`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permanent_memory_category` ON `permanent_memory` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permanent_memory_type` ON `permanent_memory` (`type`)")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN embedding BLOB")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jarvis_database"
                )
                .addMigrations(MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
