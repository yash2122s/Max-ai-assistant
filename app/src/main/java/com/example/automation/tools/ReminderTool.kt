package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.data.local.AppDatabase
import com.example.data.local.Reminder
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

class ReminderTool : Tool {
    override val name: String = "set_reminder"
    override val supportedActions: Set<String> = setOf("SET_REMINDER", "CREATE_REMINDER")
    override val retryPolicy: RetryPolicy = RetryPolicy.NoRetry
    override val capabilities: ToolCapabilities = ToolCapabilities(
        supportsPlanner = true,
        requiresAccessibility = false,
        requiresNetwork = false,
        cancellable = false
    )

    override fun validate(request: ExecutionRequest): Boolean = true

    override suspend fun execute(context: Context, request: ExecutionRequest): ToolResult = withContext(Dispatchers.IO) {
        try {
            val jsonStr = request.arguments.toString()
            val jsonObj = JSONObject(jsonStr)

            val message = jsonObj.optString("message")
                .ifEmpty { jsonObj.optString("title") }
                .ifEmpty { jsonObj.optString("note") }
                .ifEmpty { jsonObj.optString("text", "Reminder") }

            var triggerAt = jsonObj.optLong("triggerAtMillis", 0L)

            if (triggerAt <= 0L) {
                val minutesFromNow = jsonObj.optInt("minutes_from_now", 0)
                if (minutesFromNow > 0) {
                    triggerAt = System.currentTimeMillis() + (minutesFromNow * 60 * 1000L)
                }
            }

            if (triggerAt <= 0L) {
                val timeExpression = jsonObj.optString("time_expression")
                    .ifEmpty { jsonObj.optString("time") }
                if (timeExpression.isNotBlank()) {
                    triggerAt = parseTimeExpression(timeExpression)
                }
            }

            // Default fallback: 15 minutes from now if no time specified
            if (triggerAt <= System.currentTimeMillis()) {
                triggerAt = System.currentTimeMillis() + (15 * 60 * 1000L)
            }

            val db = AppDatabase.getDatabase(context.applicationContext)
            val reminder = Reminder(
                message = message,
                triggerAt = triggerAt,
                status = "pending"
            )

            val insertedId = db.reminderDao().insertReminder(reminder).toInt()

            val repo = JarvisRepository(context.applicationContext)
            repo.scheduleSystemAlarm(insertedId, message, triggerAt)

            val sdf = SimpleDateFormat("EEE, MMM d 'at' hh:mm a", Locale.getDefault())
            val formattedTime = sdf.format(Date(triggerAt))

            return@withContext ToolResult(
                success = true,
                toolName = name,
                message = "Reminder set for '$message' at $formattedTime"
            )
        } catch (e: Exception) {
            return@withContext ToolResult(
                success = false,
                toolName = name,
                errorCode = "REMINDER_FAILED",
                message = "Failed to set reminder: ${e.message}"
            )
        }
    }

    private fun parseTimeExpression(expr: String): Long {
        val now = System.currentTimeMillis()
        val lower = expr.lowercase().trim()

        // Match "in X minutes/hours/seconds"
        val relativePattern = Pattern.compile("in\\s+(\\d+)\\s*(second|sec|minute|min|hour|hr)s?")
        val matcher = relativePattern.matcher(lower)
        if (matcher.find()) {
            val amount = matcher.group(1)?.toLongOrNull() ?: 10L
            val unit = matcher.group(2) ?: "minute"
            val millis = when {
                unit.startsWith("sec") -> amount * 1000L
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 60 * 60 * 1000L
                else -> amount * 60 * 1000L
            }
            return now + millis
        }

        // Match specific time e.g. "at 5:30 PM", "9 AM", "tomorrow at 8:00 AM"
        val isTomorrow = lower.contains("tomorrow")
        val timePattern = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val timeMatcher = timePattern.matcher(lower)
        if (timeMatcher.find()) {
            var hour = timeMatcher.group(1)?.toIntOrNull() ?: 12
            val minute = timeMatcher.group(2)?.toIntOrNull() ?: 0
            val ampm = timeMatcher.group(3)

            if (ampm != null) {
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
            }

            val cal = Calendar.getInstance()
            if (isTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            if (cal.timeInMillis <= now && !isTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        return now + (15 * 60 * 1000L)
    }
}
