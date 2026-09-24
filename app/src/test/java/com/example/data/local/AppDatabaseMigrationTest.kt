package com.example.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.rag.data.RagChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testDatabaseVersion16InitializesWithRagChunksTable() = runBlocking {
        val db = AppDatabase.getDatabase(context)
        assertNotNull(db)

        val ragDao = db.ragChunkDao()
        assertNotNull(ragDao)

        // Verify CRUD operations on rag_chunks table in database v16
        val chunk = RagChunkEntity(
            chunkId = "chunk-test-1",
            memoryId = "mem-test-1",
            chunkIndex = 0,
            content = "Migration test content",
            contentHash = "hash123",
            embedding = floatArrayOf(0.1f, 0.2f, 0.3f),
            embeddingModel = "local-hash-v1",
            embeddingDimension = 3,
            category = "Test"
        )

        ragDao.insert(chunk)
        val fetched = ragDao.getByMemoryId("mem-test-1")
        assertEquals(1, fetched.size)
        assertEquals("Migration test content", fetched[0].content)
        assertEquals("hash123", fetched[0].contentHash)
        assertNotNull(fetched[0].embedding)

        // Cleanup
        ragDao.deleteByMemoryId("mem-test-1")
        val remaining = ragDao.getByMemoryId("mem-test-1")
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun testMigration16To17WithRealDatabase() {
        val backupFile = java.io.File("d:/max app testing/gemini-live/MAX_DATA_BACKUP/database_original_copy/jarvis_database")
        if (!backupFile.exists()) {
            println("Backup database not found at path: ${backupFile.absolutePath}, skipping real DB test")
            return
        }

        // Copy backup db to the application database directory
        val targetDbFile = context.getDatabasePath("test_jarvis_migration.db")
        targetDbFile.parentFile?.mkdirs()
        targetDbFile.delete()
        backupFile.copyTo(targetDbFile, overwrite = true)

        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            targetDbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )

        // Count tables and records before migration
        val cursorBefore = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_metadata'", null)
        val tableNamesBefore = mutableListOf<String>()
        while (cursorBefore.moveToNext()) {
            tableNamesBefore.add(cursorBefore.getString(0))
        }
        cursorBefore.close()

        val tableRowCountsBefore = mutableMapOf<String, Int>()
        for (table in tableNamesBefore) {
            val countCursor = db.rawQuery("SELECT COUNT(*) FROM `$table`", null)
            if (countCursor.moveToFirst()) {
                tableRowCountsBefore[table] = countCursor.getInt(0)
            }
            countCursor.close()
        }

        db.close()

        // Open via SupportSQLiteOpenHelper to obtain SupportSQLiteDatabase and execute Room Migration
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(targetDbFile.name)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(16) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {}
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(config)
        val supportDb = helper.writableDatabase

        AppDatabase.MIGRATION_16_17.migrate(supportDb)

        val verifiedDb = android.database.sqlite.SQLiteDatabase.openDatabase(
            targetDbFile.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )

        // Verify zero data loss on all pre-existing tables
        for ((table, countBefore) in tableRowCountsBefore) {
            val countCursor = verifiedDb.rawQuery("SELECT COUNT(*) FROM `$table`", null)
            assertTrue("Table $table must exist after migration", countCursor.moveToFirst())
            val countAfter = countCursor.getInt(0)
            assertEquals("Data in table $table must remain identical (zero data loss)", countBefore, countAfter)
            countCursor.close()
        }

        // Verify license_state table exists and is operational
        val checkTableCursor = verifiedDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='license_state'", null)
        assertTrue("license_state table must exist after MIGRATION_16_17", checkTableCursor.moveToFirst())
        checkTableCursor.close()

        // Verify schema of license_state table
        val schemaCursor = verifiedDb.rawQuery("PRAGMA table_info(license_state)", null)
        val columnNames = mutableListOf<String>()
        while (schemaCursor.moveToNext()) {
            columnNames.add(schemaCursor.getString(1)) // name
        }
        schemaCursor.close()

        assertTrue(columnNames.contains("id"))
        assertTrue(columnNames.contains("rawSignedLicenseJson"))
        assertTrue(columnNames.contains("lastSeenWallClockEpochMillis"))
        assertTrue(columnNames.contains("trialStartElapsedRealtime"))
        assertTrue(columnNames.contains("trialStartWallClockEpochMillis"))
        assertTrue(columnNames.contains("cachedRevokedIdsJson"))

        // Test insert and read
        verifiedDb.execSQL("INSERT OR REPLACE INTO license_state (id, rawSignedLicenseJson, lastSeenWallClockEpochMillis, trialStartElapsedRealtime, trialStartWallClockEpochMillis, cachedRevokedIdsJson) VALUES (0, '{\"test\":1}', 1000, 2000, 3000, '[]')")
        val testReadCursor = verifiedDb.rawQuery("SELECT rawSignedLicenseJson FROM license_state WHERE id = 0", null)
        assertTrue(testReadCursor.moveToFirst())
        assertEquals("{\"test\":1}", testReadCursor.getString(0))
        testReadCursor.close()

        verifiedDb.close()
        helper.close()
        targetDbFile.delete()
    }
}
