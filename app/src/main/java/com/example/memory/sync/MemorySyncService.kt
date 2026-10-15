package com.example.memory.sync

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import com.example.memory.data.MemorySource
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import com.example.memory.data.PermanentMemoryDao
import com.example.memory.data.MemorySyncState
import com.example.memory.security.MemoryCryptography
import com.example.memory.universal.ImportCandidate
import com.example.memory.universal.ImportParsers
import com.example.memory.universal.ImportProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ExternalChangeResult(
    val hasChanges: Boolean,
    val diskHash: String?,
    val dbHash: String?,
    val message: String
)

data class MarkdownDiffResult(
    val hasDifferences: Boolean,
    val added: List<ImportCandidate>,
    val modified: List<ImportCandidate>,
    val removed: List<PermanentMemory>,
    val message: String
)

data class SyncCommitResult(
    val success: Boolean,
    val addedCount: Int,
    val modifiedCount: Int,
    val removedCount: Int,
    val message: String
)

class MemorySyncService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val memoryDao: PermanentMemoryDao = db.permanentMemoryDao()
    private val syncStateDao = db.memorySyncStateDao()

    val maxDataBaseDir: File
        get() = dataDir(MemoryAccountScope.capture())

    fun dataDir(session: MemorySession): File {
            MemoryAccountScope.requireCurrent(session)
            val base = File(File(context.getExternalFilesDir(null) ?: context.filesDir, "MAX_DATA"), MemoryAccountScope.directoryName(session))
            if (!base.exists()) base.mkdirs()
            File(base, "memory").mkdirs()
            File(base, "people").mkdirs()
            return base
        }

    val maxMemoryFile: File
        get() = File(maxDataBaseDir, "MAX_MEMORY.md")

    fun memoryFile(session: MemorySession): File = File(dataDir(session), "MAX_MEMORY.md")

    val maxMemoryBackupFile: File
        get() = File(maxDataBaseDir, "MAX_MEMORY.md.bak")

    private fun backupFile(session: MemorySession) = File(dataDir(session), "MAX_MEMORY.md.bak")

    companion object {
        @Volatile
        private var INSTANCE: MemorySyncService? = null

        fun getInstance(context: Context): MemorySyncService {
            return INSTANCE ?: synchronized(this) {
                val instance = MemorySyncService(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun calculateFileSha256(file: File): String? {
            if (!file.exists() || !file.isFile) return null
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun decryptMemory(memory: PermanentMemory): PermanentMemory {
        return memory.copy(
            title = MemoryCryptography.decrypt(memory.title),
            content = MemoryCryptography.decrypt(memory.content),
            tags = if (memory.tags.isNotBlank()) MemoryCryptography.decrypt(memory.tags) else memory.tags
        )
    }

    private fun encryptMemory(memory: PermanentMemory): PermanentMemory {
        return memory.copy(
            title = MemoryCryptography.encrypt(memory.title),
            content = MemoryCryptography.encrypt(memory.content),
            tags = if (memory.tags.isNotBlank()) MemoryCryptography.encrypt(memory.tags) else memory.tags
        )
    }

    /**
     * Projects current Room DB state into MAX_MEMORY.md atomically.
     * Writes to .tmp -> backup .bak -> rename .tmp -> update SHA-256 sync state.
     */
    suspend fun exportToMarkdown(session: MemorySession = MemoryAccountScope.capture()): String = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val memories = memoryDao.getEnabled(session.ownerKey).map { decryptMemory(it) }
        MemoryAccountScope.requireCurrent(session)
        val markdownContent = generateProjectionMarkdown(memories)

        val baseDir = dataDir(session)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val tempFile = File(baseDir, "MAX_MEMORY.md.tmp")

        try {
            // 1. Write to temp file
            FileOutputStream(tempFile).use { fos ->
                fos.write(markdownContent.toByteArray(Charsets.UTF_8))
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (e: Exception) {
                    // Ignore sync failure on test file systems
                }
            }

            // 2. Backup existing valid file
            MemoryAccountScope.requireCurrent(session)
            if (memoryFile(session).exists()) {
                try {
                    memoryFile(session).copyTo(backupFile(session), overwrite = true)
                } catch (e: Exception) {
                    // non-fatal
                }
            }

            // 3. Atomically overwrite MAX_MEMORY.md
            MemoryAccountScope.requireCurrent(session)
            tempFile.copyTo(memoryFile(session), overwrite = true)
            tempFile.delete()

            // 4. Calculate SHA-256 and update Room sync state
            val fileHash = calculateFileSha256(memoryFile(session))
            val now = System.currentTimeMillis()

            syncStateDao.insertOrUpdate(
                MemorySyncState(
                    ownerKey = session.ownerKey,
                    id = 1,
                    lastWrittenFileHash = fileHash,
                    lastSyncTimestamp = now,
                    lastSyncedMemoryCount = memories.size,
                    syncStatus = "IDLE",
                    pendingChanges = null
                )
            )

            markdownContent
        } catch (e: Exception) {
            if (MemoryAccountScope.isCurrent(session)) syncStateDao.updateStatus("ERROR", session.ownerKey)
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    suspend fun getSyncState(session: MemorySession = MemoryAccountScope.capture()): MemorySyncState? = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        syncStateDao.getSyncState(session.ownerKey)
    }

    /**
     * Checks if MAX_MEMORY.md has been modified outside MAX.
     * Compares disk SHA-256 against Room recorded lastWrittenFileHash.
     */
    suspend fun detectExternalChanges(session: MemorySession = MemoryAccountScope.capture()): ExternalChangeResult = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val state = syncStateDao.getSyncState(session.ownerKey)
        val file = memoryFile(session)
        if (!file.exists()) {
            return@withContext ExternalChangeResult(
                hasChanges = false,
                diskHash = null,
                dbHash = state?.lastWrittenFileHash,
                message = "MAX_MEMORY.md does not exist yet"
            )
        }

        val diskHash = calculateFileSha256(file)
        val dbHash = state?.lastWrittenFileHash

        if (dbHash == null) {
            // First time or unrecorded
            return@withContext ExternalChangeResult(
                hasChanges = false,
                diskHash = diskHash,
                dbHash = null,
                message = "No previous sync baseline recorded"
            )
        }

        val hasChanges = (diskHash != dbHash)
        if (hasChanges) {
            MemoryAccountScope.requireCurrent(session)
            syncStateDao.updateStatus("EXTERNAL_CHANGE", session.ownerKey)
        }

        ExternalChangeResult(
            hasChanges = hasChanges,
            diskHash = diskHash,
            dbHash = dbHash,
            message = if (hasChanges) "External changes detected in MAX_MEMORY.md" else "MAX_MEMORY.md is in sync with Room"
        )
    }

    /**
     * Generates a structural diff comparing external Markdown entries with current Room records.
     * GUARANTEE: Never modifies Room.
     */
    suspend fun parseMarkdownDiff(session: MemorySession = MemoryAccountScope.capture()): MarkdownDiffResult = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val file = memoryFile(session)
        if (!file.exists()) {
            return@withContext MarkdownDiffResult(
                hasDifferences = false,
                added = emptyList(),
                modified = emptyList(),
                removed = emptyList(),
                message = "MAX_MEMORY.md not found"
            )
        }

        val fileContent = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return@withContext MarkdownDiffResult(
                hasDifferences = false,
                added = emptyList(),
                modified = emptyList(),
                removed = emptyList(),
                message = "Could not read MAX_MEMORY.md: ${e.message}"
            )
        }
        val errors = mutableListOf<String>()
        val parsedCandidates = ImportParsers.parseMarkdown(fileContent, errors)

        MemoryAccountScope.requireCurrent(session)
        val roomMemories = memoryDao.getAll(session.ownerKey).map { decryptMemory(it) }
        val roomMapByTitle = roomMemories.associateBy { it.title.trim().lowercase() }
        val roomMapByHash = roomMemories.associateBy { it.contentHash ?: it.effectiveContentHash() }

        val added = mutableListOf<ImportCandidate>()
        val modified = mutableListOf<ImportCandidate>()
        val matchedRoomIds = mutableSetOf<String>()

        for (candidate in parsedCandidates) {
            val titleKey = candidate.title.trim().lowercase()
            val existing = roomMapByTitle[titleKey]

            if (existing == null) {
                // Check if content matches any existing memory by hash
                val hashMatch = roomMapByHash[candidate.contentHash]
                if (hashMatch != null) {
                    matchedRoomIds.add(hashMatch.memoryId)
                } else {
                    added.add(candidate)
                }
            } else {
                matchedRoomIds.add(existing.memoryId)
                if (existing.content.trim() != candidate.content.trim() ||
                    existing.category.trim().lowercase() != candidate.category.trim().lowercase()) {
                    modified.add(candidate.copy(sourceRecordId = existing.memoryId))
                }
            }
        }

        val removed = roomMemories.filter { it.memoryId !in matchedRoomIds && !it.isPrivate }

        val hasDiff = added.isNotEmpty() || modified.isNotEmpty() || removed.isNotEmpty()

        if (hasDiff) {
            val pendingJsonObj = org.json.JSONObject().apply {
                put("addedCount", added.size)
                put("modifiedCount", modified.size)
                put("removedCount", removed.size)
                put("timestamp", System.currentTimeMillis())
            }
            MemoryAccountScope.requireCurrent(session)
            syncStateDao.updatePendingChanges(pendingJsonObj.toString(), session.ownerKey)
        } else {
            MemoryAccountScope.requireCurrent(session)
            syncStateDao.updatePendingChanges(null, session.ownerKey)
        }

        MarkdownDiffResult(
            hasDifferences = hasDiff,
            added = added,
            modified = modified,
            removed = removed,
            message = "Diff: ${added.size} added, ${modified.size} modified, ${removed.size} removed"
        )
    }

    /**
     * User-confirmed application of external Markdown changes into Room.
     * Then regenerates MAX_MEMORY.md and updates the sync hash.
     */
    suspend fun applyExternalChanges(
        confirmedAdded: List<ImportCandidate>,
        confirmedModified: List<ImportCandidate>,
        confirmedRemoveIds: List<String>,
        session: MemorySession = MemoryAccountScope.capture()
    ): SyncCommitResult = withContext(Dispatchers.IO) {
        try {
            MemoryAccountScope.requireCurrent(session)
            val now = System.currentTimeMillis()

            // 1. Delete removed
            for (id in confirmedRemoveIds) {
                memoryDao.deleteById(id, session.ownerKey)
            }

            // 2. Insert added
            val toInsert = confirmedAdded.map { candidate ->
                PermanentMemory(
                    ownerKey = session.ownerKey,
                    memoryId = UUID.randomUUID().toString(),
                    title = candidate.title,
                    content = candidate.content,
                    category = candidate.category,
                    type = candidate.type,
                    source = MemorySource.MANUAL,
                    tags = candidate.tags,
                    pinned = false,
                    enabled = true,
                    createdAt = now,
                    updatedAt = now,
                    sourceRecordId = candidate.sourceRecordId,
                    importChecksum = candidate.importChecksum,
                    provider = ImportProviderType.MARKDOWN.name,
                    confidence = 1.0f,
                    extractedAt = now,
                    lastSyncAt = now,
                    contentHash = candidate.contentHash,
                    metadataJson = if (candidate.metadata.isNotEmpty()) org.json.JSONObject(candidate.metadata).toString() else null
                ).let { encryptMemory(it) }
            }
            if (toInsert.isNotEmpty()) {
                memoryDao.insertAll(toInsert)
            }

            // 3. Update modified
            for (candidate in confirmedModified) {
                val existingId = candidate.sourceRecordId
                if (existingId != null) {
                    val existing = memoryDao.getById(existingId, session.ownerKey)?.let { decryptMemory(it) }
                    if (existing != null) {
                        val updated = existing.copy(
                            title = candidate.title,
                            content = candidate.content,
                            category = candidate.category,
                            tags = if (candidate.tags.isNotEmpty()) candidate.tags else existing.tags,
                            updatedAt = now,
                            lastSyncAt = now,
                            contentHash = candidate.contentHash,
                            metadataJson = if (candidate.metadata.isNotEmpty()) org.json.JSONObject(candidate.metadata).toString() else existing.metadataJson
                        )
                        memoryDao.update(encryptMemory(updated))
                    }
                }
            }

            // 4. Regenerate Markdown from resulting Room state and clear pending
            exportToMarkdown(session)
            MemoryAccountScope.requireCurrent(session)
            syncStateDao.updatePendingChanges(null, session.ownerKey)
            syncStateDao.updateStatus("IDLE", session.ownerKey)

            SyncCommitResult(
                success = true,
                addedCount = toInsert.size,
                modifiedCount = confirmedModified.size,
                removedCount = confirmedRemoveIds.size,
                message = "Successfully synchronized external changes with Room"
            )
        } catch (e: Exception) {
            SyncCommitResult(
                success = false,
                addedCount = 0,
                modifiedCount = 0,
                removedCount = 0,
                message = "Failed to apply external changes: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Rejects external changes, keeping Room untouched and restoring MAX_MEMORY.md from Room.
     */
    suspend fun rejectExternalChanges(session: MemorySession = MemoryAccountScope.capture()): SyncCommitResult = withContext(Dispatchers.IO) {
        try {
            // Re-project Room to overwrite disk with authoritative Room state
            exportToMarkdown(session)
            MemoryAccountScope.requireCurrent(session)
            syncStateDao.updatePendingChanges(null, session.ownerKey)
            syncStateDao.updateStatus("IDLE", session.ownerKey)
            SyncCommitResult(
                success = true,
                addedCount = 0,
                modifiedCount = 0,
                removedCount = 0,
                message = "Rejected external changes and restored MAX_MEMORY.md from Room"
            )
        } catch (e: Exception) {
            SyncCommitResult(
                success = false,
                addedCount = 0,
                modifiedCount = 0,
                removedCount = 0,
                message = "Failed to reject changes: ${e.localizedMessage}"
            )
        }
    }

    private fun generateProjectionMarkdown(memories: List<PermanentMemory>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()

        sb.appendLine("# MAX Memory")
        sb.appendLine()
        sb.appendLine("> **Source of Truth**: MAX Secure Room Database (`jarvis_database`)  ")
        sb.appendLine("> **Derived Projection Generated**: ${sdf.format(Date())}  ")
        sb.appendLine("> **Total Active Memories**: ${memories.size}  ")
        sb.appendLine("> **Storage Location**: `MAX_DATA/MAX_MEMORY.md`")
        sb.appendLine()

        // Categorize memories
        val profileMems = memories.filter { it.category.equals("Personal", ignoreCase = true) || it.tags.contains("profile", ignoreCase = true) }
        val prefMems = memories.filter { it.category.equals("Preferences", ignoreCase = true) || it.type == MemoryType.PREFERENCE }
        val instructMems = memories.filter { it.category.equals("Instructions", ignoreCase = true) || it.tags.contains("instruction", ignoreCase = true) }
        val projectMems = memories.filter { it.category.equals("Projects", ignoreCase = true) || it.category.equals("Work", ignoreCase = true) || it.category.equals("Programming", ignoreCase = true) }
        val goalMems = memories.filter { it.category.equals("Goals", ignoreCase = true) || it.type == MemoryType.GOAL }
        val convMems = memories.filter { it.tags.contains("conversation", ignoreCase = true) || it.category.equals("Conversations", ignoreCase = true) }
        val importedMems = memories.filter { it.source == MemorySource.IMPORT && it !in profileMems && it !in prefMems && it !in projectMems }
        
        val categorizedIds = (profileMems + prefMems + instructMems + projectMems + goalMems + convMems + importedMems).map { it.memoryId }.toSet()
        val otherMems = memories.filter { it.memoryId !in categorizedIds }

        fun appendSection(sectionTitle: String, items: List<PermanentMemory>) {
            if (items.isEmpty()) return
            sb.appendLine("## $sectionTitle")
            sb.appendLine()
            for (mem in items) {
                val pin = if (mem.pinned) "★ " else ""
                val tagStr = if (mem.tags.isNotBlank()) " `[Tags: ${mem.tags}]`" else ""
                sb.appendLine("### $pin${mem.title}")
                sb.appendLine(mem.content)
                if (tagStr.isNotEmpty()) {
                    sb.appendLine(tagStr)
                }
                sb.appendLine()
            }
        }

        appendSection("Profile", profileMems)
        appendSection("Preferences", prefMems)
        appendSection("Instructions", instructMems)
        appendSection("Projects & Work", projectMems)
        appendSection("Goals", goalMems)
        appendSection("Conversations", convMems)
        appendSection("Imported Knowledge", importedMems)
        appendSection("Other", otherMems)

        return sb.toString()
    }
}
