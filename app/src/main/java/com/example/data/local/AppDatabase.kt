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
import com.example.memory.data.MemorySyncState
import com.example.memory.data.MemorySyncStateDao
import com.example.memory.data.MemoryTypeConverters
import com.example.memory.data.PermanentMemory
import com.example.memory.data.PermanentMemoryDao
import com.example.rag.data.RagChunkEntity
import com.example.rag.data.RagChunkDao
import com.example.license.storage.LicenseStateEntity
import com.example.license.storage.LicenseStateDao
import com.example.firebase.cache.FirebaseLicenseCacheEntity
import com.example.firebase.cache.FirebaseLicenseCacheDao

@Database(
        entities = [
        ChatMessage::class, Reminder::class, AutoReplyRule::class, 
        ActionReward::class, InstalledApp::class, ScheduledTask::class, 
        TaskExecutionLog::class, PeriodLog::class, PermanentMemory::class,
        MemorySyncState::class, RagChunkEntity::class, LicenseStateEntity::class,
        FirebaseLicenseCacheEntity::class, AgentTaskRecord::class, AgentTaskStepRecord::class,
        AgentApprovalRecord::class, AgentTaskEventRecord::class
    ], 
    version = 20, 
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
    abstract fun memorySyncStateDao(): MemorySyncStateDao
    abstract fun ragChunkDao(): RagChunkDao
    abstract fun licenseStateDao(): LicenseStateDao
    abstract fun firebaseLicenseCacheDao(): FirebaseLicenseCacheDao
    abstract fun agentTaskDao(): AgentTaskDao

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

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN sourceRecordId TEXT")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN importChecksum TEXT")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN provider TEXT")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN extractedAt INTEGER")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN lastSyncAt INTEGER")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN contentHash TEXT")
                db.execSQL("ALTER TABLE permanent_memory ADD COLUMN metadataJson TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permanent_memory_importChecksum` ON `permanent_memory` (`importChecksum`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_permanent_memory_sourceRecordId` ON `permanent_memory` (`sourceRecordId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `memory_sync_state` (
                        `id` INTEGER PRIMARY KEY NOT NULL, 
                        `lastWrittenFileHash` TEXT, 
                        `lastSyncTimestamp` INTEGER NOT NULL DEFAULT 0, 
                        `lastSyncedMemoryCount` INTEGER NOT NULL DEFAULT 0, 
                        `syncStatus` TEXT NOT NULL DEFAULT 'IDLE', 
                        `pendingChanges` TEXT
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rag_chunks` (
                        `chunkId` TEXT NOT NULL PRIMARY KEY,
                        `memoryId` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `content` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `embedding` BLOB,
                        `embeddingModel` TEXT NOT NULL,
                        `embeddingDimension` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `provider` TEXT,
                        `sourceRecordId` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rag_chunks_memoryId` ON `rag_chunks` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rag_chunks_contentHash` ON `rag_chunks` (`contentHash`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_rag_chunks_memoryId_chunkIndex` ON `rag_chunks` (`memoryId`, `chunkIndex`)")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `license_state` (
                        `id` INTEGER PRIMARY KEY NOT NULL,
                        `rawSignedLicenseJson` TEXT,
                        `lastSeenWallClockEpochMillis` INTEGER,
                        `trialStartElapsedRealtime` INTEGER,
                        `trialStartWallClockEpochMillis` INTEGER,
                        `cachedRevokedIdsJson` TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `firebase_license_cache` (
                        `userId` TEXT NOT NULL,
                        `plan` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `trialStartedAtEpochMillis` INTEGER,
                        `trialExpiresAtEpochMillis` INTEGER,
                        `expiresAtEpochMillis` INTEGER,
                        `issuedAtEpochMillis` INTEGER NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        `cachedAtEpochMillis` INTEGER NOT NULL,
                        `integrityHash` TEXT NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_tasks` (
                        `taskId` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `currentStepIndex` INTEGER NOT NULL,
                        `totalSteps` INTEGER NOT NULL,
                        `approvalState` TEXT,
                        `failureReason` TEXT,
                        `recoveryState` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `interruptedAt` INTEGER,
                        PRIMARY KEY(`taskId`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_task_steps` (
                        `taskId` TEXT NOT NULL,
                        `stepId` TEXT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `stepIndex` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `executionId` TEXT,
                        `failureReason` TEXT,
                        `verificationResult` TEXT,
                        `attemptCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`, `stepId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add canonical identity and lifecycle fields to agent_tasks
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `requestId` TEXT")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `source` TEXT")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `requestHash` TEXT")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `currentStepId` TEXT")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `planVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `planHash` TEXT")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `deadlineAt` INTEGER")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `cancellationReason` TEXT")
                db.execSQL("ALTER TABLE `agent_tasks` ADD COLUMN `terminalReason` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_requestId` ON `agent_tasks` (`requestId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_tasks_status` ON `agent_tasks` (`status`)")

                // Add canonical execution and verification fields to agent_task_steps
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `payloadJson` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `payloadHash` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `stepVersion` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `executorResultJson` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `verificationId` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `verificationEvidence` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `approvalId` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `approvalStatus` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `deadlineAt` INTEGER")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `idempotencyKey` TEXT")
                db.execSQL("ALTER TABLE `agent_task_steps` ADD COLUMN `hasExternalSideEffects` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_steps_idempotencyKey` ON `agent_task_steps` (`idempotencyKey`)")

                // Create agent_approvals table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_approvals` (
                        `approvalId` TEXT NOT NULL PRIMARY KEY,
                        `taskId` TEXT NOT NULL,
                        `stepId` TEXT NOT NULL,
                        `executionId` TEXT,
                        `action` TEXT NOT NULL,
                        `target` TEXT,
                        `displayLabel` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `payloadHash` TEXT NOT NULL,
                        `question` TEXT NOT NULL,
                        `status` TEXT NOT NULL DEFAULT 'PENDING',
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `consumedAt` INTEGER,
                        `consumedEventId` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_approvals_taskId` ON `agent_approvals` (`taskId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_approvals_status` ON `agent_approvals` (`status`)")

                // Create agent_task_events table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `agent_task_events` (
                        `eventId` TEXT NOT NULL PRIMARY KEY,
                        `taskId` TEXT NOT NULL,
                        `stepId` TEXT,
                        `executionId` TEXT,
                        `eventType` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `idempotencyKey` TEXT NOT NULL,
                        `eventDataJson` TEXT,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_task_events_taskId` ON `agent_task_events` (`taskId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_task_events_idempotencyKey` ON `agent_task_events` (`idempotencyKey`)")
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
                .addMigrations(
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, 
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, 
                    MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20
                )
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
