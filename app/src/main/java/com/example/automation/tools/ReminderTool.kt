package com.example.automation.tools

import android.content.Context
import com.example.automation.engine.ExecutionRequest
import com.example.automation.verification.RetryPolicy
import com.example.data.repository.JarvisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

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

            val now = System.currentTimeMillis()
            val expression = jsonObj.optString("time_expression").ifEmpty { jsonObj.optString("time") }
            val triggerAt = when {
                jsonObj.has("triggerAtMillis") -> jsonObj.optLong("triggerAtMillis", 0L)
                jsonObj.has("minutes_from_now") -> jsonObj.optString("minutes_from_now").toLongOrNull()
                    ?.let { relativeTime(now, it, 60_000L) }
                expression.isNotBlank() -> parseTimeExpression(expression, now)
                else -> now + 15 * 60_000L
            }
            if (triggerAt == null || triggerAt <= now) {
                return@withContext ToolResult.needsInput(name, "What future time should I use for this reminder?", targetParam = "time_expression")
            }

            val repo = JarvisRepository(context.applicationContext)
            repo.saveReminder(null, message, triggerAt)

            val sdf = SimpleDateFormat("EEE, MMM d 'at' hh:mm a", Locale.getDefault())
            val formattedTime = sdf.format(Date(triggerAt))

            return@withContext ToolResult(
                success = true,
                toolName = name,
                message = "Reminder set for '$message' at $formattedTime"
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext ToolResult(
                success = false,
                toolName = name,
                errorCode = "REMINDER_FAILED",
                message = "Failed to set reminder: ${e.message}"
            )
        }
    }

    private fun relativeTime(now: Long, amount: Long, unitMillis: Long): Long? {
        if (amount <= 0 || amount > (Long.MAX_VALUE - now) / unitMillis) return null
        return now + amount * unitMillis
    }

    internal fun parseTimeExpression(expression: String, now: Long): Long? {
        val text = expression.trim().lowercase(Locale.ROOT)
        val relative = relativePattern.matchEntire(text)
        if (relative != null) {
            val amount = relative.groupValues[1].toLongOrNull() ?: return null
            val unitMillis = when (relative.groupValues[2]) {
                "sec", "second" -> 1000L
                "min", "minute" -> 60_000L
                "hr", "hour" -> 3_600_000L
                else -> 86_400_000L
            }
            return relativeTime(now, amount, unitMillis)
        }

        val clock = clockPattern.matchEntire(text) ?: return null
        val (day, hourText, minuteText, period) = clock.destructured
        var hour = hourText.toInt()
        val minute = minuteText.toIntOrNull() ?: 0
        if (minute > 59 || hour > 23) return null
        if (period.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (period == "pm") 12 else 0
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            if (day == "tomorrow") add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now && day.isEmpty()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis.takeIf { it > now && cal.get(Calendar.HOUR_OF_DAY) == hour }
    }

    private companion object {
        val relativePattern = Regex("""(?:in\s+)?(\d+)\s*(second|sec|minute|min|hour|hr|day)s?""")
        val clockPattern = Regex("""(?:(today|tomorrow)\s+)?(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
    }
}
