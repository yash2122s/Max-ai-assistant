package com.example.automation.scheduler

import java.util.Calendar

interface TimeExpressionParser {
    fun canParse(expression: String): Boolean
    fun parse(expression: String, currentTime: Long): Long?
}

class AbsoluteTimeParser : TimeExpressionParser {
    override fun canParse(expression: String): Boolean {
        return expression.toLongOrNull() != null || expression.contains("T") || expression.contains("-")
    }

    override fun parse(expression: String, currentTime: Long): Long? {
        expression.toLongOrNull()?.let { return it }
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                val date = sdf.parse(expression)
                if (date != null) {
                    return date.time
                }
            } catch (e: Exception) {
                // continue
            }
        }
        return null
    }
}

class RelativeTimeParser : TimeExpressionParser {
    private val regex = Regex("(?i)(?:in\\s+)?(\\d+)\\s+(second|minute|hour|day)s?")

    override fun canParse(expression: String): Boolean {
        return regex.find(expression) != null
    }

    override fun parse(expression: String, currentTime: Long): Long? {
        val match = regex.find(expression) ?: return null
        val amount = match.groupValues[1].toLong()
        val unit = match.groupValues[2].lowercase()
        val multiplier = when (unit) {
            "second" -> 1000L
            "minute" -> 60000L
            "hour" -> 3600000L
            "day" -> 86400000L
            else -> 0L
        }
        return currentTime + amount * multiplier
    }
}

class DayExpressionParser : TimeExpressionParser {
    override fun canParse(expression: String): Boolean {
        val expr = expression.lowercase()
        return expr.contains("tomorrow") || expr.contains("evening") || expr.contains("lunch") || expr.contains("tonight") || expr.contains("morning")
    }

    override fun parse(expression: String, currentTime: Long): Long? {
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
        val expr = expression.lowercase()
        
        if (expr.contains("tomorrow")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val match = timeRegex.find(expr)
        if (match != null) {
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].let { if (it.isEmpty()) 0 else it.toInt() }
            val ampm = match.groupValues[3].lowercase()
            
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        } else {
            if (expr.contains("evening")) {
                calendar.set(Calendar.HOUR_OF_DAY, 18)
            } else if (expr.contains("tonight")) {
                calendar.set(Calendar.HOUR_OF_DAY, 21)
            } else if (expr.contains("lunch")) {
                calendar.set(Calendar.HOUR_OF_DAY, 13)
            } else if (expr.contains("morning")) {
                calendar.set(Calendar.HOUR_OF_DAY, 9)
            }
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}

class WeekdayParser : TimeExpressionParser {
    private val dayMap = mapOf(
        "sunday" to Calendar.SUNDAY,
        "monday" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY
    )

    override fun canParse(expression: String): Boolean {
        val expr = expression.lowercase()
        return dayMap.keys.any { expr.contains(it) }
    }

    override fun parse(expression: String, currentTime: Long): Long? {
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
        val expr = expression.lowercase()
        
        val targetDayName = dayMap.keys.firstOrNull { expr.contains(it) } ?: return null
        val targetDay = dayMap[targetDayName]!!
        
        val currentDay = calendar.get(Calendar.DAY_OF_WEEK)
        var daysDiff = targetDay - currentDay
        if (daysDiff <= 0) {
            daysDiff += 7
        }
        
        if (expr.contains("next")) {
            if (daysDiff < 7) daysDiff += 7
        }
        
        calendar.add(Calendar.DAY_OF_YEAR, daysDiff)
        
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val match = timeRegex.find(expr)
        if (match != null) {
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].let { if (it.isEmpty()) 0 else it.toInt() }
            val ampm = match.groupValues[3].lowercase()
            
            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
        } else {
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
        }
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        
        return calendar.timeInMillis
    }
}

object TimeParser {
    private val parsers = listOf(
        AbsoluteTimeParser(),
        RelativeTimeParser(),
        DayExpressionParser(),
        WeekdayParser()
    )

    fun parseExpression(expression: String, currentTime: Long = System.currentTimeMillis()): Long {
        val trimmed = expression.trim()
        for (parser in parsers) {
            if (parser.canParse(trimmed)) {
                val time = parser.parse(trimmed, currentTime)
                if (time != null) {
                    return time
                }
            }
        }
        return currentTime + 300000L
    }
}
