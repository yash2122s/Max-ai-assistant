package com.example.data.export

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.PeriodLog
import com.example.memory.account.MemoryAccountScope
import com.example.memory.account.MemorySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

class PeriodMarkdownExporter(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.periodLogDao()

    companion object {
        @Volatile
        private var INSTANCE: PeriodMarkdownExporter? = null

        fun getInstance(context: Context): PeriodMarkdownExporter {
            return INSTANCE ?: synchronized(this) {
                val instance = PeriodMarkdownExporter(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * Exports period tracking data for the authenticated real user.
     * Uses SettingsManager.userId — NEVER invents or hardcodes person IDs.
     * Stored strictly in MAX_DATA/people/<realPersonId>/period_data.md.
     * GUARANTEE: Period data remains 100% outside MAX_MEMORY.md.
     */
    suspend fun exportPersonPeriodMarkdown(
        targetPersonId: String? = null,
        session: MemorySession = MemoryAccountScope.capture()
    ): File {
        MemoryAccountScope.requireCurrent(session)
        return withContext(Dispatchers.IO) {
        MemoryAccountScope.requireCurrent(session)
        val realPersonId = MemoryAccountScope.directoryName(session)

        val baseDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "MAX_DATA")
        val personDir = File(File(baseDir, "people"), realPersonId)
        val periodFile = File(personDir, "period_data.md")
        val tempFile = File(personDir, "period_data.md.tmp")

        val logs = dao.getAllPeriods(session.ownerKey)
        // The directory is deliberately hashed for account privacy, while the
        // optional display identifier remains available inside the owner's own
        // export for backwards-compatible human-readable reports.
        val markdownContent = generatePeriodMarkdown(targetPersonId ?: realPersonId, logs)

        MemoryAccountScope.requireCurrent(session)
        personDir.mkdirs()
        tempFile.writeText(markdownContent, Charsets.UTF_8)
        if (periodFile.exists()) {
            val backupFile = File(personDir, "period_data.md.bak")
            periodFile.copyTo(backupFile, overwrite = true)
            periodFile.delete()
        }
        if (!tempFile.renameTo(periodFile)) {
            tempFile.copyTo(periodFile, overwrite = true)
            tempFile.delete()
        }

        MemoryAccountScope.requireCurrent(session)
        periodFile
        }
    }

    private fun generatePeriodMarkdown(personId: String, logs: List<PeriodLog>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = LocalDate.now()

        fun epochToLocalDate(millis: Long): LocalDate {
            return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        }

        val completedPeriods = logs.filter { it.endDate != null }
        val avgDuration = if (completedPeriods.isNotEmpty()) {
            completedPeriods.map { it.durationDays }.average().toInt()
        } else {
            5
        }

        val cycleLengths = mutableListOf<Int>()
        for (i in 0 until logs.size - 1) {
            val start1 = epochToLocalDate(logs[i].startDate)
            val start2 = epochToLocalDate(logs[i + 1].startDate)
            val days = ChronoUnit.DAYS.between(start1, start2).toInt()
            if (days in 15..45) {
                cycleLengths.add(days)
            }
        }
        val recentCycleLengths = cycleLengths.takeLast(6)
        val avgCycle = if (recentCycleLengths.isNotEmpty()) {
            recentCycleLengths.average().toInt()
        } else {
            28
        }

        val latestPeriod = logs.lastOrNull()
        val nextStartDate = latestPeriod?.let {
            epochToLocalDate(it.startDate).plusDays(avgCycle.toLong())
        }
        val daysUntilNext = nextStartDate?.let {
            ChronoUnit.DAYS.between(today, it).toInt()
        }
        val predictedOvulation = nextStartDate?.minusDays(14)
        val fertileStart = predictedOvulation?.minusDays(5)
        val fertileEnd = predictedOvulation?.plusDays(1)

        val isBleeding = latestPeriod?.let {
            if (it.endDate == null) {
                today >= epochToLocalDate(it.startDate)
            } else {
                today >= epochToLocalDate(it.startDate) && today <= epochToLocalDate(it.endDate)
            }
        } ?: false

        val isFertile = fertileStart != null && fertileEnd != null && today >= fertileStart && today <= fertileEnd

        val currentPhase = when {
            isBleeding -> "Bleeding / Menstrual Phase"
            isFertile -> "Fertile Window (Approximate)"
            daysUntilNext != null && daysUntilNext in 0..5 -> "Luteal / Pre-menstrual Phase"
            latestPeriod != null -> "Follicular Phase"
            else -> "No Data Available"
        }

        val sb = StringBuilder()
        sb.appendLine("# Period & Cycle Tracker — Health Context")
        sb.appendLine()
        sb.appendLine("> **Person ID**: `$personId`  ")
        sb.appendLine("> **Last Projected**: ${sdf.format(Date())}  ")
        sb.appendLine("> **Confidentiality**: Private On-Device Health Data")
        sb.appendLine()
        sb.appendLine("## Current Status & Summary")
        sb.appendLine("- **Active Phase**: $currentPhase")
        sb.appendLine("- **Average Cycle Length**: $avgCycle days")
        sb.appendLine("- **Average Period Duration**: $avgDuration days")
        if (nextStartDate != null) {
            sb.appendLine("- **Predicted Next Start**: $nextStartDate (${if (daysUntilNext != null) "$daysUntilNext days away" else "upcoming"})")
        }
        if (fertileStart != null && fertileEnd != null) {
            sb.appendLine("- **Estimated Fertile Window**: $fertileStart to $fertileEnd")
        }
        sb.appendLine()
        sb.appendLine("## Cycle History Logs (${logs.size} recorded)")
        if (logs.isEmpty()) {
            sb.appendLine("_No cycle records logged yet._")
        } else {
            sb.appendLine("| Start Date | End Date | Duration | Notes |")
            sb.appendLine("| :--- | :--- | :--- | :--- |")
            for (log in logs.reversed()) {
                val startStr = sdf.format(Date(log.startDate))
                val endStr = log.endDate?.let { sdf.format(Date(it)) } ?: "**Ongoing**"
                val durationStr = if (log.durationDays > 0) "${log.durationDays} days" else "In progress"
                val notesStr = (log.notes ?: "").replace("|", "-").ifEmpty { "—" }
                sb.appendLine("| $startStr | $endStr | $durationStr | $notesStr |")
            }
        }
        sb.appendLine()

        return sb.toString()
    }
}
