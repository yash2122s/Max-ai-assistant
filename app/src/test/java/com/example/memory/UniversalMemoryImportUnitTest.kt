package com.example.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.export.PeriodMarkdownExporter
import com.example.data.local.AppDatabase
import com.example.data.local.PeriodLog
import com.example.data.preferences.SettingsManager
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemorySyncState
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.sync.MaxBackupService
import com.example.memory.sync.MemorySyncService
import com.example.memory.universal.ImportAction
import com.example.memory.universal.ImportParsers
import com.example.memory.universal.ImportProviderType
import com.example.memory.universal.ProviderDetector
import com.example.memory.universal.UniversalMemoryImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UniversalMemoryImportUnitTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var importer: UniversalMemoryImporter
    private lateinit var syncService: MemorySyncService
    private lateinit var backupService: MaxBackupService
    private lateinit var periodExporter: PeriodMarkdownExporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(context)
        importer = UniversalMemoryImporter(context)
        syncService = MemorySyncService.getInstance(context)
        backupService = MaxBackupService.getInstance(context)
        periodExporter = PeriodMarkdownExporter.getInstance(context)

        val baseDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "MAX_DATA")
        baseDir.deleteRecursively()
        baseDir.mkdirs()

        runBlocking {
            db.permanentMemoryDao().deleteAll()
            db.periodLogDao().deleteAllPeriods()
            db.memorySyncStateDao().insertOrUpdate(
                MemorySyncState(id = 1, lastWrittenFileHash = null, lastSyncTimestamp = 0L, lastSyncedMemoryCount = 0, syncStatus = "IDLE")
            )
        }
    }

    // -------------------------------------------------------------------------
    // A. Provider Detection Tests
    // -------------------------------------------------------------------------

    @Test
    fun testProviderDetection_ChatGPT_CustomInstructions() {
        val json = """
            {
                "user_instructions": "I like dark mode",
                "custom_instructions": "Be concise"
            }
        """.trimIndent()
        val provider = ProviderDetector.detect(json, "export.json")
        assertEquals(ImportProviderType.CHATGPT, provider)
    }

    @Test
    fun testProviderDetection_Claude_Markdown() {
        val markdown = """
            # Claude Memory
            ## Profile
            - User is a mobile developer.
        """.trimIndent()
        val provider = ProviderDetector.detect(markdown, "claude_memories.md")
        assertEquals(ImportProviderType.CLAUDE, provider)
    }

    @Test
    fun testProviderDetection_Obsidian_Frontmatter() {
        val obsidian = """
            ---
            tags: [android, memory]
            category: Work
            ---
            # Kotlin Architecture
            Room provides type-safe SQLite access.
        """.trimIndent()
        val provider = ProviderDetector.detect(obsidian, "notes.md")
        assertEquals(ImportProviderType.OBSIDIAN, provider)
    }

    @Test
    fun testProviderDetection_MaxBackup_JSON() {
        val backup = """
            {
                "max_backup_version": 1,
                "export_source": "MAX",
                "memories": []
            }
        """.trimIndent()
        val provider = ProviderDetector.detect(backup, "memories.json")
        assertEquals(ImportProviderType.MAX_BACKUP, provider)
    }

    @Test
    fun testProviderDetection_GenericMarkdown() {
        val md = """
            # General Notes
            - Habit: Drink 2L of water daily.
            - Preference: Morning workout at 6 AM.
        """.trimIndent()
        val provider = ProviderDetector.detect(md, "notes.txt")
        assertEquals(ImportProviderType.MARKDOWN, provider)
    }

    // -------------------------------------------------------------------------
    // B. Immutable Checksum vs Mutable ContentHash
    // -------------------------------------------------------------------------

    @Test
    fun testChecksum_ImmutableAcrossContentEdits() {
        val rawOriginal = "User prefers tea over coffee."
        val importChecksum = PermanentMemory.computeImportChecksum(rawOriginal)

        val title = "Drink Preference"
        val contentOriginal = "Prefers green tea."
        val initialContentHash = PermanentMemory.computeContentHash(title, contentOriginal, "Preferences", "drink")

        val contentEdited = "Prefers jasmine green tea without sugar."
        val editedContentHash = PermanentMemory.computeContentHash(title, contentEdited, "Preferences", "drink")

        // contentHash must change on edit
        assertNotEquals(initialContentHash, editedContentHash)

        // importChecksum must remain strictly identical
        val recomputedChecksum = PermanentMemory.computeImportChecksum(rawOriginal)
        assertEquals(importChecksum, recomputedChecksum)
    }

    // -------------------------------------------------------------------------
    // C. Read-Only Preview Does NOT Modify Room
    // -------------------------------------------------------------------------

    @Test
    fun testPreview_NeverModifiesRoom() = runBlocking {
        val markdown = """
            # Personal Preferences
            - Music: User loves synthwave and electronic music.
            - Food: User prefers vegetarian Mediterranean food.
        """.trimIndent()

        val initialCount = db.permanentMemoryDao().getCount()
        assertEquals(0, initialCount)

        val preview = importer.preview(markdown, "preferences.md")
        assertEquals(2, preview.totalParsed)
        assertEquals(2, preview.insertCount)
        assertEquals(0, preview.duplicateCount)

        // Verify Room is completely untouched
        val postPreviewCount = db.permanentMemoryDao().getCount()
        assertEquals(0, postPreviewCount)
    }

    // -------------------------------------------------------------------------
    // D. Commit Pipeline & Idempotent Re-Import
    // -------------------------------------------------------------------------

    @Test
    fun testImport_Commit_And_Idempotent_Reimport() = runBlocking {
        val markdown = """
            # Development Context
            - IDE: Antigravity IDE on Windows.
            - Language: Kotlin for Android core, Dart for Flutter UI.
        """.trimIndent()

        // 1. Initial Preview
        val preview1 = importer.preview(markdown, "dev.md")
        assertEquals(2, preview1.insertCount)

        // 2. Commit
        val commitResult = importer.commit(preview1.items.map { it.candidate })
        assertTrue(commitResult.success)
        assertEquals(2, commitResult.insertedCount)
        assertEquals(0, commitResult.skippedCount)

        // 3. Verify Room DB
        val dbMemories = db.permanentMemoryDao().getAll()
        assertEquals(2, dbMemories.size)

        // 4. Re-Import exact same file -> Must be 100% idempotent
        val preview2 = importer.preview(markdown, "dev.md")
        assertEquals(2, preview2.totalParsed)
        assertEquals(0, preview2.insertCount)
        assertEquals(2, preview2.duplicateCount)
        assertTrue(preview2.items.all { it.action == ImportAction.SKIP_DUPLICATE })

        // 5. Commit again -> 0 inserted, 2 skipped
        val commitResult2 = importer.commit(preview2.items.map { it.candidate })
        assertEquals(0, commitResult2.insertedCount)
        assertEquals(2, commitResult2.skippedCount)
        assertEquals(2, db.permanentMemoryDao().getCount())
    }

    // -------------------------------------------------------------------------
    // E. Topic Superseding
    // -------------------------------------------------------------------------

    @Test
    fun testImport_TopicSuperseding() = runBlocking {
        val initialText = """
            # Work Preferences
            - Primary Editor: VS Code for editing.
        """.trimIndent()

        val preview1 = importer.preview(initialText)
        importer.commit(preview1.items.map { it.candidate })
        assertEquals(1, db.permanentMemoryDao().getCount())

        // Update content under same specific topic title and category
        val updatedText = """
            # Work Preferences
            - Primary Editor: Antigravity IDE with Flutter extension.
        """.trimIndent()

        val preview2 = importer.preview(updatedText)
        assertEquals(1, preview2.supersedeCount)
        assertEquals(ImportAction.UPDATE_SUPERSEDE, preview2.items[0].action)

        val commit2 = importer.commit(preview2.items.map { it.candidate })
        assertEquals(0, commit2.insertedCount)
        assertEquals(1, commit2.updatedCount)
        assertEquals(1, db.permanentMemoryDao().getCount())
    }

    // -------------------------------------------------------------------------
    // F. Markdown Projection & Atomic Write
    // -------------------------------------------------------------------------

    @Test
    fun testMarkdownProjection_AtomicGeneration() = runBlocking {
        val repo = MemoryRepository(context)
        repo.addMemory(
            PermanentMemory(
                title = "Favorite Framework",
                content = "Flutter with Impeller",
                category = "Programming",
                type = MemoryType.PREFERENCE,
                tags = "flutter,graphics"
            )
        )

        val content = syncService.exportToMarkdown()
        assertTrue(content.contains("# MAX Memory"))
        assertTrue(content.contains("Favorite Framework"))
        assertTrue(content.contains("Flutter with Impeller"))

        val file = syncService.maxMemoryFile
        assertTrue(file.exists())
        assertTrue(file.length() > 0)

        // Check sync state in Room
        val state = db.memorySyncStateDao().getSyncState()
        assertNotNull(state)
        assertNotNull(state?.lastWrittenFileHash)
        assertEquals(1, state?.lastSyncedMemoryCount)
        assertEquals("IDLE", state?.syncStatus)
    }

    // -------------------------------------------------------------------------
    // G. External Markdown Edit Detection
    // -------------------------------------------------------------------------

    @Test
    fun testExternalMarkdownChangeDetection() = runBlocking {
        val repo = MemoryRepository(context)
        repo.addMemory(
            PermanentMemory(
                title = "Theme Preference",
                content = "OLED Black",
                category = "Preferences",
                type = MemoryType.PREFERENCE
            )
        )
        syncService.exportToMarkdown()

        // 1. Initial state: In sync
        val check1 = syncService.detectExternalChanges()
        assertFalse(check1.hasChanges)

        // 2. Modify MAX_MEMORY.md externally
        val file = syncService.maxMemoryFile
        file.appendText("\n\n## External Addition\n- New Fact: Externally added note.\n")

        // 3. Detect external changes
        val check2 = syncService.detectExternalChanges()
        assertTrue(check2.hasChanges)
        assertEquals("EXTERNAL_CHANGE", db.memorySyncStateDao().getSyncState()?.syncStatus)

        // 4. Parse diff
        val diff = syncService.parseMarkdownDiff()
        assertTrue(diff.hasDifferences)
        assertTrue(diff.added.any { it.title.contains("New Fact") || it.content.contains("Externally added note") })
    }

    // -------------------------------------------------------------------------
    // H. Period Data Isolation (Never in MAX_MEMORY.md)
    // -------------------------------------------------------------------------

    @Test
    fun testPeriodData_StrictIsolation() = runBlocking {
        val repo = MemoryRepository(context)
        repo.addMemory(
            PermanentMemory(
                title = "General Fact",
                content = "User is in Bangalore",
                category = "Personal",
                type = MemoryType.FACT
            )
        )

        // Log Period Cycle data in Room
        db.periodLogDao().insertPeriod(
            PeriodLog(
                startDate = System.currentTimeMillis() - 2592000000L,
                endDate = System.currentTimeMillis() - 2160000000L,
                durationDays = 5,
                notes = "Normal cycle"
            )
        )

        // 1. Export MAX_MEMORY.md
        val maxMemoryContent = syncService.exportToMarkdown()

        // MUST contain general memory
        assertTrue("MAX_MEMORY.md must contain general fact: $maxMemoryContent", maxMemoryContent.contains("User is in Bangalore"))

        // GUARANTEE: Period data MUST NOT be in MAX_MEMORY.md
        assertFalse("MAX_MEMORY.md must not contain notes", maxMemoryContent.contains("Normal cycle"))
        assertFalse("MAX_MEMORY.md must not contain period tracker header", maxMemoryContent.contains("Period & Cycle Tracker"))
        assertFalse("MAX_MEMORY.md must not contain durationDays", maxMemoryContent.contains("durationDays"))

        // 2. Export Period markdown to isolated person file
        val realUserId = SettingsManager(context).userId
        val periodFile = periodExporter.exportPersonPeriodMarkdown(realUserId)

        assertTrue("Period markdown file must exist at ${periodFile.absolutePath}", periodFile.exists())
        val periodContent = periodFile.readText()
        assertTrue("Period content must contain userId $realUserId", periodContent.contains(realUserId))
        assertTrue("Period content must contain notes", periodContent.contains("Normal cycle"))
        assertTrue("Period content must contain Cycle History Logs", periodContent.contains("Cycle History Logs"))
    }

    // -------------------------------------------------------------------------
    // I. Portable Backup Service
    // -------------------------------------------------------------------------

    @Test
    fun testPortableBackup_Generation() = runBlocking {
        val repo = MemoryRepository(context)
        repo.addMemory(
            PermanentMemory(
                title = "Backup Test Item",
                content = "Portable json export test",
                category = "Projects",
                type = MemoryType.FACT,
                tags = "backup,test"
            )
        )

        val result = backupService.createFullBackup()
        assertEquals(true, result["success"])
        assertNotNull(result["memoriesFile"])
        assertNotNull(result["markdownFile"])

        val memoriesJsonFile = File(result["memoriesFile"] as String)
        assertTrue(memoriesJsonFile.exists())
        val jsonContent = memoriesJsonFile.readText()
        assertTrue(jsonContent.contains("max_backup_version"))
        assertTrue(jsonContent.contains("Backup Test Item"))
        assertTrue(jsonContent.contains("Portable json export test"))
    }

    // -------------------------------------------------------------------------
    // J. ContentHash Recalculation & Immutable Checksum on Edit
    // -------------------------------------------------------------------------

    @Test
    fun testContentHash_RecalculatedOnUpdate_ImportChecksumImmutable() = runBlocking {
        val repo = MemoryRepository(context)
        val initialMemory = PermanentMemory(
            title = "Morning Coffee",
            content = "Drinks espresso at 8 AM",
            category = "Preferences",
            type = MemoryType.PREFERENCE,
            tags = "coffee,morning",
            importChecksum = "checksum_immutable_12345",
            sourceRecordId = "ext_123"
        )
        repo.addMemory(initialMemory)

        val inserted = repo.getAll().first { it.title == "Morning Coffee" }
        val initialContentHash = inserted.contentHash ?: inserted.effectiveContentHash()
        val initialImportChecksum = inserted.importChecksum
        assertEquals("checksum_immutable_12345", initialImportChecksum)

        // Update content
        val updatedMemory = inserted.copy(
            content = "Drinks double espresso with oat milk at 7:30 AM"
        )
        repo.updateMemory(updatedMemory)

        val updated = repo.getAll().first { it.title == "Morning Coffee" }
        val newContentHash = updated.contentHash ?: updated.effectiveContentHash()

        // ContentHash must change
        assertNotEquals(initialContentHash, newContentHash)
        // ImportChecksum must remain completely unchanged
        assertEquals("checksum_immutable_12345", updated.importChecksum)

        // DAO getByContentHash query test
        val fetchedByHash = db.permanentMemoryDao().getByContentHash(newContentHash)
        assertNotNull(fetchedByHash)
    }

    // -------------------------------------------------------------------------
    // K. External Edit Accept and Reject Flows
    // -------------------------------------------------------------------------

    @Test
    fun testExternalMarkdownChange_AcceptAndRejectFlows() = runBlocking {
        db.permanentMemoryDao().insert(
            PermanentMemory(
                title = "Original Fact",
                content = "Original Content in Room",
                category = "Personal",
                type = MemoryType.FACT
            )
        )
        syncService.exportToMarkdown()

        // 1. User modifies MAX_MEMORY.md externally
        val file = syncService.maxMemoryFile
        file.appendText("\n\n## External Item\n- External Title: External Body Details.\n")

        // 2. Detect change & parse diff
        val extRes = syncService.detectExternalChanges()
        assertTrue(extRes.hasChanges)
        val diff = syncService.parseMarkdownDiff()
        assertTrue(diff.hasDifferences)

        // Verify pendingChanges was saved in memory_sync_state
        val stateWithPending = db.memorySyncStateDao().getSyncState()
        assertNotNull(stateWithPending?.pendingChanges)
        assertTrue(stateWithPending?.pendingChanges!!.contains("addedCount"))

        // 3. User Rejects the external changes
        val rejectRes = syncService.rejectExternalChanges()
        assertTrue(rejectRes.success)

        // Verify Room remains unchanged (still only 1 original memory)
        assertEquals(1, db.permanentMemoryDao().getCount())
        // Verify MAX_MEMORY.md was restored and pendingChanges cleared
        val stateAfterReject = db.memorySyncStateDao().getSyncState()
        assertEquals(null, stateAfterReject?.pendingChanges)
        assertEquals("IDLE", stateAfterReject?.syncStatus)
        val revertedDiskContent = file.readText()
        assertFalse(revertedDiskContent.contains("External Body Details"))

        // 4. User modifies MAX_MEMORY.md again and Accepts
        file.appendText("\n\n## External Addition 2\n- Confirmed Title: Confirmed Body Details.\n")
        syncService.detectExternalChanges()
        val diff2 = syncService.parseMarkdownDiff()
        assertTrue(diff2.hasDifferences)

        val acceptRes = syncService.applyExternalChanges(diff2.added, diff2.modified, diff2.removed.map { it.memoryId })
        assertTrue(acceptRes.success)
        assertEquals(2, db.permanentMemoryDao().getCount())

        val stateAfterAccept = db.memorySyncStateDao().getSyncState()
        assertEquals(null, stateAfterAccept?.pendingChanges)
        assertEquals("IDLE", stateAfterAccept?.syncStatus)
    }

    // -------------------------------------------------------------------------
    // L. Commit Race Safety (Re-deduplication against Room)
    // -------------------------------------------------------------------------

    @Test
    fun testCommitRaceSafety_DeduplicatesAgainAtCommit() = runBlocking {
        val markdown = """
            # Race Test
            - Item One: First item content.
            - Item Two: Second item content.
        """.trimIndent()

        // 1. Preview indicates 2 new items
        val preview = importer.preview(markdown)
        assertEquals(2, preview.insertCount)

        // 2. In between preview and commit, another background process adds Item One
        db.permanentMemoryDao().insert(
            PermanentMemory(
                title = "Item One",
                content = "First item content.",
                category = "Personal",
                type = MemoryType.FACT
            )
        )
        assertEquals(1, db.permanentMemoryDao().getCount())

        // 3. Commit candidates from preview
        val commitRes = importer.commit(preview.items.map { it.candidate })
        assertTrue(commitRes.success)
        assertEquals(1, commitRes.insertedCount)
        assertEquals(1, commitRes.skippedCount)

        // Total memories in Room must be 2, NOT 3 (zero duplicates)
        assertEquals(2, db.permanentMemoryDao().getCount())
    }

    // -------------------------------------------------------------------------
    // M. All 7 Providers Verification
    // -------------------------------------------------------------------------

    @Test
    fun testAll7Providers_ParsingAndImport() = runBlocking {
        // 1. ChatGPT JSON
        val chatgpt = """{"user_instructions": "Speaks English & Telugu"}"""
        val pChatgpt = importer.preview(chatgpt, "chatgpt.json")
        assertEquals(ImportProviderType.CHATGPT, pChatgpt.provider)
        assertTrue(pChatgpt.insertCount > 0)

        // 2. Claude Markdown
        val claude = """
            # Claude Memory
            ## Preferences
            - Prefers dark themes.
        """.trimIndent()
        val pClaude = importer.preview(claude, "claude.md")
        assertEquals(ImportProviderType.CLAUDE, pClaude.provider)

        // 3. Gemini Export
        val gemini = """[{"id":"gem_1","title":"Gemini Note","content":"Gemini remembers this"}]"""
        val pGemini = importer.preview(gemini, "gemini.json")
        assertEquals(ImportProviderType.GEMINI, pGemini.provider)

        // 4. Obsidian Frontmatter
        val obsidian = """
            ---
            tags: [obsidian, work]
            ---
            # Note
            Content here.
        """.trimIndent()
        val pObsidian = importer.preview(obsidian, "obsidian.md")
        assertEquals(ImportProviderType.OBSIDIAN, pObsidian.provider)

        // 5. MAX Backup
        val maxBackup = """{"max_backup_version":1,"memories":[{"title":"T1","content":"C1"}]}"""
        val pMax = importer.preview(maxBackup, "memories.json")
        assertEquals(ImportProviderType.MAX_BACKUP, pMax.provider)

        // 6. Generic Markdown
        val genericMd = """
            # Title
            - Bullet: Detail.
        """.trimIndent()
        val pMd = importer.preview(genericMd, "notes.md")
        assertEquals(ImportProviderType.MARKDOWN, pMd.provider)

        // 7. Generic Text
        val genericText = "Just some plain thoughts without headers."
        val pText = importer.preview(genericText, "plain.txt")
        assertEquals(ImportProviderType.GENERIC_TEXT, pText.provider)
    }

    // -------------------------------------------------------------------------
    // N. Backup Round Trip (No Credentials & Clean Restore)
    // -------------------------------------------------------------------------

    @Test
    fun testBackupRoundTrip_SafeNoCredentials() = runBlocking {
        val repo = MemoryRepository(context)
        repo.addMemory(PermanentMemory(title = "Item Alpha", content = "Alpha Content", category = "Personal", type = MemoryType.FACT))
        repo.addMemory(PermanentMemory(title = "Item Beta", content = "Beta Content", category = "Work", type = MemoryType.FACT))

        // Create backup
        val backupResult = backupService.createFullBackup()
        val jsonFile = File(backupResult["memoriesFile"] as String)
        val backupJsonText = jsonFile.readText()

        // Ensure zero credentials
        assertFalse(backupJsonText.contains("api_key"))
        assertFalse(backupJsonText.contains("password"))
        assertFalse(backupJsonText.contains("token"))

        // Wipe DB to simulate clean restore
        db.permanentMemoryDao().deleteAll()
        assertEquals(0, db.permanentMemoryDao().getCount())

        // Import backup json
        val preview = importer.preview(backupJsonText, "memories.json")
        assertEquals(2, preview.insertCount)
        val commitRes = importer.commit(preview.items.map { it.candidate })
        assertTrue(commitRes.success)
        assertEquals(2, commitRes.insertedCount)
        assertEquals(2, db.permanentMemoryDao().getCount())
    }
}
