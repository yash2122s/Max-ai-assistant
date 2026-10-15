package com.example.memory.sync

import android.content.Context
import com.example.data.export.PeriodMarkdownExporter
import com.example.data.local.AppDatabase
import com.example.memory.security.MemoryCryptography
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MaxBackupService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val memoryDao = db.permanentMemoryDao()
    private val syncService = MemorySyncService.getInstance(context)
    private val periodExporter = PeriodMarkdownExporter.getInstance(context)

    companion object {
        @Volatile
        private var INSTANCE: MaxBackupService? = null

        fun getInstance(context: Context): MaxBackupService {
            return INSTANCE ?: synchronized(this) {
                val instance = MaxBackupService(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * Generates a complete, portable MAX backup:
     * - MAX_DATA/MAX_MEMORY.md
     * - MAX_DATA/memories.json
     * - MAX_DATA/people/<realPersonId>/period_data.md
     * GUARANTEE: Never includes API keys, tokens, or system credentials.
     */
    suspend fun createFullBackup(
        session: MemorySession = MemoryAccountScope.capture()
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = System.currentTimeMillis()

        // 1. Export MAX_MEMORY.md
        syncService.exportToMarkdown(session)
        MemoryAccountScope.requireCurrent(session)

        // 2. Export Period Data
        val periodFile = periodExporter.exportPersonPeriodMarkdown(session = session)
        MemoryAccountScope.requireCurrent(session)

        // 3. Export memories.json
        val memories = memoryDao.getAll(session.ownerKey).map { mem ->
            val decTitle = MemoryCryptography.decrypt(mem.title)
            val decContent = MemoryCryptography.decrypt(mem.content)
            val decTags = if (mem.tags.isNotBlank()) MemoryCryptography.decrypt(mem.tags) else mem.tags

            JSONObject().apply {
                put("memoryId", mem.memoryId)
                put("title", decTitle)
                put("content", decContent)
                put("category", mem.category)
                put("type", mem.type.name)
                put("tags", decTags)
                put("pinned", mem.pinned)
                put("enabled", mem.enabled)
                put("createdAt", mem.createdAt)
                put("updatedAt", mem.updatedAt)
                put("sourceRecordId", mem.sourceRecordId)
                put("importChecksum", mem.importChecksum)
                put("provider", mem.provider)
                put("confidence", mem.confidence.toDouble())
                put("contentHash", mem.contentHash ?: mem.effectiveContentHash())
            }
        }

        val backupJsonObj = JSONObject().apply {
            put("max_backup_version", 1)
            put("export_source", "MAX")
            put("backup_timestamp", now)
            put("formatted_timestamp", sdf.format(Date(now)))
            put("total_memories", memories.size)
            put("memories", JSONArray(memories))
        }

        MemoryAccountScope.requireCurrent(session)
        val directory = syncService.dataDir(session)
        val backupFile = File(directory, "memories.json")
        FileOutputStream(backupFile).use { fos ->
            fos.write(backupJsonObj.toString(2).toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        MemoryAccountScope.requireCurrent(session)
        mapOf(
            "success" to true,
            "backupPath" to directory.absolutePath,
            "memoriesFile" to backupFile.absolutePath,
            "markdownFile" to syncService.memoryFile(session).absolutePath,
            "periodFile" to periodFile.absolutePath,
            "memoryCount" to memories.size,
            "timestamp" to now
        )
    }
}
