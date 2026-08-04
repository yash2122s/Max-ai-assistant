package com.example.automation.tools

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.PeriodLog
import com.example.data.local.PeriodLogDao
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PeriodTrackerTool : Tool {
    override val name: String = "period_tracker"
    override val supportedActions: Set<String> = setOf(
        "LOG_PERIOD_START",
        "LOG_PERIOD_END",
        "LOG_PERIOD_NOTE",
        "GET_PERIOD_HISTORY",
        "GET_PERIOD_PREDICTION",
        "CLEAR_ALL_PERIOD_DATA"
    )
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    private fun parseDateExpression(expr: String?): Long {
        val trimmed = expr?.trim()?.lowercase(Locale.US) ?: ""
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (trimmed == "today" || trimmed.isEmpty()) {
            return calendar.timeInMillis
        }
        if (trimmed == "yesterday") {
            calendar.add(Calendar.DAY_OF_YEAR, -1)
            return calendar.timeInMillis
        }
        
        // "X days ago"
        val daysAgoRegex = Regex("(\\d+)\\s+days?\\s+ago")
        val match = daysAgoRegex.find(trimmed)
        if (match != null) {
            val days = match.groupValues[1].toInt()
            calendar.add(Calendar.DAY_OF_YEAR, -days)
            return calendar.timeInMillis
        }

        // Try standard date formats
        val formats = listOf(
            "yyyy-MM-dd",
            "dd MMM yyyy",
            "MMM dd, yyyy",
            "MM/dd/yyyy",
            "dd/MM/yyyy",
            "d MMM yyyy",
            "MMM d, yyyy"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                val parsedDate = sdf.parse(trimmed)
                if (parsedDate != null) {
                    val cal = Calendar.getInstance()
                    cal.time = parsedDate
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (e: Exception) {
                // continue
            }
        }
        
        // Return current midnight as fallback
        return calendar.timeInMillis
    }

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult {
        val db = AppDatabase.getDatabase(context)
        val dao = db.periodLogDao()
        val jsonPayload = JSONObject(request.arguments.toString())

        return try {
            when (request.action) {
                "LOG_PERIOD_START" -> {
                    val dateExpr = jsonPayload.optString("date", "today")
                    val startDate = parseDateExpression(dateExpr)
                    val notes = if (jsonPayload.has("notes") && !jsonPayload.isNull("notes")) jsonPayload.getString("notes") else null

                    val latest = dao.getLatestPeriod()
                    if (latest != null && latest.endDate == null) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "ONGOING_CYCLE_EXISTS",
                            message = "You have an ongoing period logged that hasn't ended yet. Please log its end first."
                        )
                    }

                    val log = PeriodLog(
                        startDate = startDate,
                        endDate = null,
                        durationDays = 0,
                        notes = notes
                    )
                    dao.insertPeriod(log)

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Logged period start on ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(startDate)}.",
                        verificationRequired = true
                    )
                }

                "LOG_PERIOD_END" -> {
                    val dateExpr = jsonPayload.optString("date", "today")
                    val endDate = parseDateExpression(dateExpr)

                    val latest = dao.getLatestPeriod()
                    if (latest == null) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "NO_ACTIVE_CYCLE",
                            message = "No active cycle found to end. Please log a start date first."
                        )
                    }

                    if (endDate < latest.startDate) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "INVALID_END_DATE",
                            message = "End date cannot be earlier than the start date."
                        )
                    }

                    val durationDays = (((endDate - latest.startDate) / (24 * 3600 * 1000)) + 1).toInt()
                    dao.updatePeriodEndDate(latest.id, endDate, durationDays)

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Logged period end on ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(endDate)} (Duration: $durationDays days).",
                        verificationRequired = true
                    )
                }

                "LOG_PERIOD_NOTE" -> {
                    val notes = jsonPayload.optString("notes", "")
                    if (notes.isBlank()) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "EMPTY_NOTE",
                            message = "Note content cannot be empty."
                        )
                    }

                    val latest = dao.getLatestPeriod()
                    if (latest == null) {
                        return ToolResult(
                            success = false,
                            toolName = name,
                            errorCode = "NO_CYCLE_FOUND",
                            message = "No logged cycle found to add notes to."
                        )
                    }

                    val updatedNotes = if (latest.notes.isNullOrBlank()) notes else "${latest.notes}\n$notes"
                    dao.updatePeriodNotes(latest.id, updatedNotes)

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Notes updated: $notes",
                        verificationRequired = true
                    )
                }

                "GET_PERIOD_HISTORY" -> {
                    val periods = dao.getAllPeriods()
                    val arr = JSONArray()
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    for (p in periods) {
                        val obj = JSONObject().apply {
                            put("id", p.id)
                            put("start_date", sdf.format(p.startDate))
                            put("end_date", p.endDate?.let { sdf.format(it) } ?: "ongoing")
                            put("duration_days", p.durationDays)
                            put("notes", p.notes ?: "")
                        }
                        arr.put(obj)
                    }

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "Retrieved ${periods.size} logged period cycles.",
                        metadata = JSONObject().put("history", arr),
                        verificationRequired = true
                    )
                }

                "GET_PERIOD_PREDICTION" -> {
                    val predictions = getPredictions(dao)
                    val sdf = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.getDefault())
                    val nextStart = predictions.optLong("predictedNextStartDate", 0L)
                    val fertileStart = predictions.optLong("predictedFertileStartDate", 0L)
                    val fertileEnd = predictions.optLong("predictedFertileEndDate", 0L)

                    val message = if (nextStart > 0) {
                        val daysLeft = predictions.optInt("daysUntilNextPeriod", 0)
                        val isBleeding = predictions.optBoolean("isOnCurrentPeriod", false)
                        val duration = predictions.optInt("averagePeriodDuration", 5)
                        val cycleLen = predictions.optInt("averageCycleLength", 28)
                        val isEstimated = !predictions.optBoolean("hasEnoughData", false)
                        
                        var desc = if (isBleeding) {
                            "Currently logged as bleeding."
                        } else {
                            if (daysLeft >= 0) "Your next period is predicted to start in $daysLeft days on ${sdf.format(nextStart)}."
                            else "Your next period is overdue by ${-daysLeft} days (predicted: ${sdf.format(nextStart)})."
                        }
                        
                        if (isEstimated) {
                            desc += " Note: This is an estimated default calculation because you have logged fewer than 2 cycles."
                        } else {
                            desc += " (Based on your past cycles average: $cycleLen days cycle length, $duration days period duration)."
                        }
                        
                        desc += " Approximate fertile window: ${sdf.format(fertileStart)} to ${sdf.format(fertileEnd)}."
                        desc
                    } else {
                        "No period logs found to predict. Please log your first period to start getting predictions."
                    }

                    ToolResult(
                        success = true,
                        toolName = name,
                        message = message,
                        metadata = predictions,
                        verificationRequired = true
                    )
                }

                "CLEAR_ALL_PERIOD_DATA" -> {
                    dao.deleteAllPeriods()
                    ToolResult(
                        success = true,
                        toolName = name,
                        message = "All period logs and tracking details have been successfully deleted from your device.",
                        verificationRequired = true
                    )
                }

                else -> {
                    ToolResult(
                        success = false,
                        toolName = name,
                        errorCode = "INVALID_ACTION",
                        message = "Action not supported: ${request.action}"
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult(
                success = false,
                toolName = name,
                errorCode = "DB_ERROR",
                message = e.message ?: "Database operation failed"
            )
        }
    }

    private suspend fun getPredictions(dao: PeriodLogDao): JSONObject {
        val periods = dao.getAllPeriods()
        val result = JSONObject()

        if (periods.isEmpty()) {
            result.put("hasEnoughData", false)
            result.put("averageCycleLength", 28)
            result.put("averagePeriodDuration", 5)
            result.put("predictionSource", "default")
            result.put("isOnCurrentPeriod", false)
            result.put("daysUntilNextPeriod", 0)
            return result
        }

        val latest = periods.last()

        // 1. Calculate average period duration (only completed ones)
        val completedPeriods = periods.filter { it.endDate != null }
        val avgDuration = if (completedPeriods.isNotEmpty()) {
            completedPeriods.map { it.durationDays }.average().toInt()
        } else {
            5
        }
        result.put("averagePeriodDuration", avgDuration)

        // 2. Calculate average cycle lengths with outlier rejection (15 to 45 days)
        val cycleLengths = mutableListOf<Int>()
        for (i in 0 until periods.size - 1) {
            val start1 = periods[i].startDate
            val start2 = periods[i + 1].startDate
            val days = ((start2 - start1) / (24 * 3600 * 1000)).toInt()
            if (days in 15..45) {
                cycleLengths.add(days)
            }
        }

        // Capture last 3-6 cycles if available
        val recentCycleLengths = cycleLengths.takeLast(6)
        val hasEnoughData = recentCycleLengths.isNotEmpty()
        val avgCycle = if (hasEnoughData) {
            recentCycleLengths.average().toInt()
        } else {
            28
        }
        result.put("averageCycleLength", avgCycle)
        result.put("hasEnoughData", hasEnoughData)
        result.put("predictionSource", if (hasEnoughData) "historical" else "default")

        // 3. Predict next start date
        val nextStartDate = latest.startDate + avgCycle * 24L * 3600L * 1000L
        result.put("predictedNextStartDate", nextStartDate)

        // 4. Predict ovulation (approx 14 days before next start)
        val ovulationDate = nextStartDate - 14L * 24L * 3600L * 1000L
        result.put("predictedOvulationDate", ovulationDate)

        // 5. Fertile window: ovulation - 5 days to ovulation + 1 day
        val fertileStartDate = ovulationDate - 5L * 24L * 3600L * 1000L
        val fertileEndDate = ovulationDate + 1L * 24L * 3600L * 1000L
        result.put("predictedFertileStartDate", fertileStartDate)
        result.put("predictedFertileEndDate", fertileEndDate)

        // 6. Active bleeding check
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val isBleeding = if (latest.endDate == null) {
            today >= latest.startDate
        } else {
            today >= latest.startDate && today <= latest.endDate
        }
        result.put("isOnCurrentPeriod", isBleeding)

        // Calculate days until next period
        val daysUntil = ((nextStartDate - today) / (24 * 3600 * 1000)).toInt()
        result.put("daysUntilNextPeriod", daysUntil)

        return result
    }
}
