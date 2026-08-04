package com.example.data.local

import android.content.Context
import com.example.memory.data.MemoryCategory
import com.example.memory.data.MemoryRepository
import com.example.memory.data.MemorySource
import com.example.memory.data.MemoryType
import com.example.memory.data.PermanentMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object PeriodDataSeeder {
    suspend fun seedPeriodData(context: Context) = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val periodDao = db.periodLogDao()
        val repository = MemoryRepository(context)

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }

        val cycles = listOf(
            Triple("2025-05-28 09:00", null, "May 28, 2025 cycle start"),
            Triple("2025-07-09 09:00", null, "Bittu's period started (July 9, 2025)"),
            Triple("2025-08-08 09:00", null, "August 8, 2025 cycle start"),
            Triple("2025-09-10 09:00", null, "September 10, 2025 cycle start"),
            Triple("2025-10-16 09:00", null, "October 16, 2025 cycle start"),
            Triple("2026-01-02 09:00", null, "January 2, 2026 cycle start"),
            Triple("2026-02-07 09:00", null, "February 7, 2026 cycle start"),
            Triple("2026-03-10 09:00", null, "March 10, 2026 cycle start"),
            Triple("2026-06-08 00:00", "2026-06-08 23:59", "Started ninna night 12 - June 7 night / June 8 early")
        )

        for ((startStr, endStr, notes) in cycles) {
            val startTime = sdf.parse(startStr)?.time ?: continue
            val endTime = endStr?.let { sdf.parse(it)?.time }

            val log = PeriodLog(
                startDate = startTime,
                endDate = endTime,
                durationDays = if (endTime != null) 1 else 0,
                notes = notes
            )
            try {
                periodDao.insertPeriod(log)
            } catch (e: Exception) {
                // Ignore unique constraint index errors if already inserted
            }
        }

        // Add Permanent Memory Entry summarizing full Period tracking history with vector embedding
        val fullMemoryText = """
            Period Tracking History (Java / Bittu):
            2025 Cycles:
            - May 28, 2025
            - July 9, 2025 (Bittu's period started)
            - August 8, 2025
            - September 10, 2025
            - October 16, 2025
            
            2026 Cycles:
            - January 2, 2026
            - February 7, 2026
            - March 10, 2026
            - June 7-8, 2026 (Started June 7 night / June 8 early)
        """.trimIndent()

        val memory = PermanentMemory(
            title = "Java Period Tracking Log History",
            content = fullMemoryText,
            category = MemoryCategory.PERSONAL.displayName,
            type = MemoryType.FACT,
            source = MemorySource.MANUAL,
            pinned = true
        )

        repository.addMemory(memory)
    }
}
